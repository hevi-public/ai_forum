package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.support.TestData
import com.aiforum.persona.Dials
import com.aiforum.repo.PersonaInterestRepository
import com.aiforum.repo.PersonaRepository
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * S4b (plan_docs/ambient-slice-4b.md): the interest drift pass, its owner surface at /admin/interests,
 * and the room map.
 *
 * The pass runs synchronously on the request thread — the `POST /admin/ambient/tick` and
 * `POST /admin/stances/evolve` precedent — so these steps need no settle-poll: by the time the POST
 * returns, every judgment and every interest write in that run has already happened.
 *
 * **The authoring Givens write SQL directly** rather than driving the persona edit form. That is not
 * shortcut-taking: `POST /personas/{slug}/edit` composes a prompt, so a form-driven Given would buy an
 * LLM call and every `no LLM call was made` scenario in this feature would be asserting against a spy
 * that had already seen one. `a persona {string} exists` (CommonSteps) makes the same choice for the
 * same reason.
 *
 * NOT @Component: glue is instantiated by Cucumber, which injects these Spring beans (see the
 * cucumber-spring-bdd skill).
 */
class InterestDriftSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val data: TestData,
    private val personas: PersonaRepository,
    private val interests: PersonaInterestRepository,
    private val llm: ScriptableLlmClient,
) {

    /** An open interest — one the pass may set down. */
    @Given("persona {string} is into {string}")
    fun personaIsInto(name: String, interest: String) =
        data.insertInterest(personaId(name), interest, SOURCE_SEEDED)

    /**
     * A PINNED interest: `source = 'owner'`, which is what makes the immutable core per-persona. The
     * pass must skip it before spending anything, and a judgment that names it must be refused.
     */
    @Given("the owner has pinned {string} as an interest of {string}")
    fun ownerPinnedInterest(interest: String, name: String) =
        data.insertInterest(personaId(name), interest, SOURCE_OWNER)

    /**
     * Give an already-seeded member the expertise and the stored prompt this slice must never touch.
     *
     * These exist because their obvious counterparts — `the persona {string} has abilities {string}` and
     * `... has system prompt {string}` (PersonaSteps) — are `@Then` ASSERTIONS, and Cucumber matches on
     * step TEXT rather than on the Given/When/Then keyword: writing one under a `Given` silently runs the
     * assertion, against a member nobody ever configured. The immutable-core scenario needs both halves
     * — a value authored before the pass, and the same value asserted after it — so the authoring half
     * gets its own wording rather than a keyword the runner ignores.
     */
    @Given("the persona {string} was authored with abilities {string}")
    fun personaAuthoredWithAbilities(name: String, abilities: String) {
        val persona = personas.find(personaId(name)) ?: error("no persona \"$name\" — seed it first")
        personas.update(
            persona.id, persona.name, persona.descriptor, persona.model, persona.systemPrompt,
            abilities.split(",").map { it.trim() }.filter { it.isNotEmpty() }, persona.dials,
        )
    }

    @Given("the persona {string} was authored with the system prompt {string}")
    fun personaAuthoredWithSystemPrompt(name: String, prompt: String) {
        val persona = personas.find(personaId(name)) ?: error("no persona \"$name\" — seed it first")
        personas.update(
            persona.id, persona.name, persona.descriptor, persona.model, prompt,
            persona.abilities, persona.dials,
        )
    }

    /**
     * Model the state a drift leaves behind: the phrase is gone from the member, and nothing records
     * that it was ever there except the audit row. The seeding phase must read that as "this member has
     * been seeded and is done", not as "a configured phrase is missing" — which is the whole point of
     * the scenario this serves.
     */
    @Given("persona {string} has set down the seeded interest {string}")
    fun personaHasSetDownInterest(name: String, interest: String) =
        interests.delete(personaId(name), interest)

    @When("the owner runs the interest drift pass")
    fun runDriftPass() {
        val resp = http.post(DRIFT_PATH)
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    /**
     * Revert the newest audited drift. The id is read off the page rather than guessed, so the step
     * exercises the same control the owner clicks — if the template stops rendering a revert form, this
     * fails here rather than silently reverting by a fabricated id (the S4a step's reasoning).
     */
    @When("the owner reverts the latest interest change")
    fun revertLatestInterestChange() {
        val page = http.get(HISTORY_PATH).body ?: ""
        val id = Regex("data-interest-change=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
            ?: error("no interest-change row to revert on $HISTORY_PATH:\n$page")
        val resp = http.post("$HISTORY_PATH/$id/revert")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    /**
     * Drive the persona EDIT FORM's interest fieldset, the way the owner does — the only path that
     * stamps `owner` provenance, retracts a phrase by blanking it, and enforces the ceiling.
     *
     * Deliberately the real form POST rather than direct SQL: every other Given here writes SQL to keep
     * the zero-cost scenarios honest, which left `applyInterestEdits` — the pin mechanism, the
     * reconciliation, all three guards — reachable by no test at any tier. Replacing that method's body
     * with `return` left the whole suite green.
     *
     * The full field list is replayed (descriptor, model, abilities, systemPrompt, every dial), because
     * the edit endpoint binds absent params to blank and a partial POST would wipe what it omits — the
     * same reason `PersonaSteps.saveStanceOnly` replays a fixed list.
     */
    @When("the owner saves {string}'s interests as {string}")
    fun ownerSavesInterests(name: String, phrasesCsv: String) {
        val existing = personas.find(personaId(name)) ?: error("no persona \"$name\"")
        val phrases = phrasesCsv.split("|").map { it.trim() }
        val form = mutableMapOf<String, Any?>(
            "descriptor" to existing.descriptor,
            "model" to existing.model,
            "abilities" to existing.abilities.joinToString(", "),
            "systemPrompt" to existing.systemPrompt,
        )
        Dials.KEYS.forEach { key -> form["dial_$key"] = existing.dials[key] ?: Dials.DEFAULT }
        phrases.forEachIndexed { i, phrase -> form["interest_$i"] = phrase }
        val resp = http.postForm("/personas/${PersonaRepository.slugFor(existing.name)}/edit", form)
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the profile for {string} shows the interest {string} as the owner's")
    fun profileShowsOwnerInterest(name: String, interest: String) {
        val page = profile(name)
        val tag = Regex("<span[^>]*data-interest=\"${Regex.escape(interest)}\"[^>]*>").find(page)?.value
            ?: error("no interest tag for \"$interest\" on $name's profile:\n${interestsOn(page)}")
        assertTrue(
            tag.contains("data-interest-source=\"owner\""),
            "expected \"$interest\" to be marked as the owner's, got: $tag",
        )
    }

    // --- the profile, which is where an owner actually reads what a member is into ------------------

    @Then("the profile for {string} shows the interest {string}")
    fun profileShowsInterest(name: String, interest: String) {
        val page = profile(name)
        assertTrue(
            Html.hasAttr(page, "data-interest", interest),
            "expected \"$name\"'s profile to show the interest \"$interest\", got:\n${interestsOn(page)}",
        )
    }

    @Then("the profile for {string} shows no interest {string}")
    fun profileShowsNoInterest(name: String, interest: String) {
        val page = profile(name)
        assertTrue(
            !Html.hasAttr(page, "data-interest", interest),
            "expected \"$name\"'s profile NOT to show the interest \"$interest\", got:\n${interestsOn(page)}",
        )
    }

    /**
     * The count invariant (I3) as the owner sees it: a swap leaves the member holding exactly as many
     * interests as before. Asserted on the rendered hooks rather than on a repository, because a
     * count that is only right in the database is not the one the owner reads.
     */
    @Then("the profile for {string} shows {int} interests")
    fun profileShowsInterestCount(name: String, count: Int) {
        val page = profile(name)
        assertEquals(
            count, Regex("data-interest=\"").findAll(page).count(),
            "expected \"$name\" to hold exactly $count interests, got:\n${interestsOn(page)}",
        )
    }

    /**
     * The immutable core, from the other side: the drift pass has no write path to `descriptor`. The
     * seeded descriptor is "<name> descriptor" (TestData.insertPersona), so the scenario names the
     * member and this step asserts the value that member booted with.
     */
    @Then("the persona {string} still has the descriptor {string}")
    fun personaStillHasDescriptor(name: String, expected: String) {
        // Read off the PAGE, not the repository: this suite asserts at HTTP level so a scenario proves
        // what an owner can actually see. `persona.kte` already renders `data-persona-descriptor`, so
        // going through the repository was a private back door for no gain.
        val page = profile(name)
        assertTrue(
            Html.hasAttr(page, "data-persona-descriptor", "$expected descriptor"),
            "the drift pass must not touch a member's character — expected the seeded descriptor on the profile",
        )
    }

    // --- the audit log, which is the whole of the owner's control over an auto-applied change -------

    @Then("the interest history records {string} setting down {string} and taking up {string}")
    fun historyRecordsDrift(name: String, dropped: String, takenUp: String) {
        val row = latestRow()
        assertTrue(
            Html.hasAttr(row, "data-interest-persona", personaId(name)),
            "expected the newest interest-change row to belong to \"$name\", got:\n$row",
        )
        assertTrue(
            Html.hasAttr(row, "data-interest-dropped", dropped),
            "expected the row to record \"$dropped\" being set down, got:\n$row",
        )
        assertTrue(
            Html.hasAttr(row, "data-interest-taken", takenUp),
            "expected the row to record \"$takenUp\" being taken up, got:\n$row",
        )
    }

    /**
     * The cited engagement is snapshotted prose, not a live read of the comment — a body can be edited
     * or have its revision switched after the judgment, and the audit must keep showing what was
     * actually judged (the `comment_quote.quoted_text` precedent).
     */
    @Then("the interest history entry cites {string}")
    fun historyEntryCites(snippet: String) {
        val row = latestRow()
        assertTrue(
            Html.contains(row, snippet),
            "expected the newest interest-change row to cite \"$snippet\", got:\n$row",
        )
    }

    @Then("the interest history entry links to the cited comment")
    fun historyEntryLinksToComment() {
        val row = latestRow()
        val href = Regex("href=\"(/threads/[^\"]*#reply-[^\"]+)\"").find(row)?.groupValues?.get(1)
        assertNotNull(href, "expected a /threads/…#reply-… permalink to the cited comment in:\n$row")
    }

    @Then("the interest history is empty")
    fun historyIsEmpty() {
        val body = pageOrHistory()
        assertNull(
            Html.latestInterestChangeRow(body),
            "expected NO interest-change rows on the history page, but found one in:\n$body",
        )
        assertTrue(
            Html.hasAttr(body, "data-admin-list-empty", "true"),
            "expected the shared empty-state hook on an empty history page in:\n$body",
        )
    }

    @Then("the interest history entry is marked reverted")
    fun historyEntryIsReverted() {
        val page = http.get(HISTORY_PATH).body ?: ""
        val row = Html.latestInterestChangeRow(page)
            ?: error("no interest-change row on $HISTORY_PATH:\n$page")
        assertTrue(
            Html.hasAttr(row, "data-interest-reverted", "true"),
            "expected the reverted drift to be marked data-interest-reverted=\"true\", got:\n$row",
        )
    }

    // --- the room map: a phrase and the members holding it, by NAME ---------------------------------

    /**
     * The convergence readout, and the assertion is deliberately about NAMES. A count ("held by 2 of
     * 7") is the shape an owner starts thresholding on, and a threshold an owner acts on is the
     * population metric this slice keeps away from models (D12) — so the step would have to change
     * shape for that to ship, rather than passing quietly.
     */
    @Then("the room map shows {string} held by {string}")
    fun roomMapShows(interest: String, holders: String) {
        val body = pageOrHistory()
        val row = Html.roomMapRow(body, interest)
            ?: error("no room-map row for \"$interest\" on the page:\n$body")
        holders.split(",").map { it.trim() }.forEach { holder ->
            assertTrue(
                Html.contains(row, holder),
                "expected the room map's \"$interest\" row to name \"$holder\", got:\n$row",
            )
        }
        assertTrue(
            !Regex("\\d").containsMatchIn(Html.textOf(row)),
            "the room map names holders, it never counts them — found a digit in:\n$row",
        )
    }

    // --- the judge's blinkers, which are the convergence guardrail ----------------------------------

    /**
     * The judging model sees ONE member's own material. Selected off the spy by the judge's synthetic
     * persona name, never by "the last non-dispatcher call": a judge call satisfies that description
     * too, so the loose selector would make this assertion read the wrong prompt and pass vacuously.
     */
    @Then("the judging model was shown only {string}'s own interests")
    fun judgeSawOnlyOwnInterests(name: String) {
        val judged = llm.received.filter { it.persona.name == JUDGE_NAME }
        assertTrue(judged.isNotEmpty(), "expected at least one judging call, the spy saw: ${llm.received.map { it.persona.name }}")
        val ownInterests = data.interestsOf(personaId(name))
        val others = personas.findAll().filter { it.id != personaId(name) }
            .flatMap { data.interestsOf(it.id) }
            .filter { it !in ownInterests }
        judged.forEach { call ->
            val prompt = buildString {
                append(call.context.personaSystemPrompt)
                call.context.comments.forEach { append(' ').append(it.body) }
            }.lowercase()
            others.forEach { foreign ->
                assertTrue(
                    !prompt.contains(foreign.lowercase()),
                    "another member's interest \"$foreign\" reached the judging model — there must be no " +
                        "cross-member channel for the room to converge through",
                )
            }
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun personaId(name: String) = name

    private fun profile(name: String): String {
        val slug = PersonaRepository.slugFor(name)
        val resp = http.get("/personas/$slug")
        assertEquals(200, resp.statusCode.value(), "expected \"$name\"'s profile to render")
        return resp.body ?: ""
    }

    /** Just the interest hooks on a page, so a failure message is readable rather than a whole page. */
    private fun interestsOn(page: String): String =
        Regex("data-interest=\"([^\"]*)\"").findAll(page).map { it.groupValues[1] }.toList()
            .ifEmpty { listOf("(no data-interest hooks at all)") }
            .joinToString(", ")

    /** The newest audit row, re-read from the page the last navigation loaded. */
    private fun latestRow(): String {
        val body = pageOrHistory()
        return Html.latestInterestChangeRow(body)
            ?: error("no interest-change row (data-interest-change) on the page:\n$body")
    }

    /**
     * The page the scenario last navigated to, or the history page when it navigated nowhere. The
     * empty-history and room-map scenarios navigate explicitly; the ones that assert straight after a
     * pass have `world.lastBody` holding the 303's body, so they need the fetch.
     */
    private fun pageOrHistory(): String {
        val last = world.lastBody
        if (last != null && last.contains("data-admin-list=\"interests\"")) return last
        return http.get(HISTORY_PATH).body ?: ""
    }

    private companion object {
        const val HISTORY_PATH = "/admin/interests"
        const val DRIFT_PATH = "/admin/interests/drift"
        const val SOURCE_SEEDED = "seeded"
        const val SOURCE_OWNER = "owner"

        /** Must match `InterestDriftPrompts.JUDGE_NAME` — the spy selects on it. */
        const val JUDGE_NAME = "InterestJudge"
    }
}
