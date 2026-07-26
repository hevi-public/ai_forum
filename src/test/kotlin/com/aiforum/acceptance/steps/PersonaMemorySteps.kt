package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.acceptance.support.TestData
import com.aiforum.llm.LlmRequest
import com.aiforum.repo.PersonaRepository
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Persona memory (plan_docs/persona-memory.md): the private per-member memory tree, its retrieval
 * into the member's own generation prompt, the Memory Scribe pass, and the owner surfaces on the
 * persona profile and /admin/memory.
 *
 * The pass runs synchronously on the request thread (`POST /admin/memory/run`, the
 * `POST /admin/interests/drift` precedent), so no settle-poll is needed after running it; the
 * summon-driven scenarios settle through GenerationSteps as usual.
 *
 * **The seeding Givens write SQL directly** (TestData.insertMemory) rather than driving the profile
 * form — the InterestDriftSteps choice for the InterestDriftSteps reason: form-driven arranging
 * couples every scenario to the owner surface under test and, for the persona endpoints, buys LLM
 * calls a cost assertion would then see. The deliberate exceptions are the steps whose SUBJECT is
 * the write surface itself — `the owner authors the memory … for …` (scenario 20), `the owner sets
 * the root … for …` and its second-attempt sibling (scenario 25), and the over-long submission
 * (scenario 26) — each of which exists precisely to exercise the real form POST. All of them carry
 * distinct wording from the seeding Givens, because Cucumber matches on step TEXT, not on the
 * Given/When/Then keyword: `was given the root …` seeds a root by SQL, `sets the root …` authors
 * one through the form, and the two must never be readable as the same sentence.
 *
 * **Spy selection (plan doc §4, both inherited traps):** the generation-prompt steps select the spy
 * call by `persona.name == <member>`, NEVER by "the last non-dispatcher call" (`personaCall()`):
 * a MemoryScribe judgment is not the dispatcher, so it satisfies the loose selector — and the
 * scribe's own instruction contains the member's memory text, so the loose selector would let a
 * "carried the memory" assertion pass while proving nothing about a GENERATION prompt. The scribe
 * steps select by the scribe's synthetic identity (`MemoryScribePrompts.SCRIBE_NAME`) and only
 * there.
 *
 * **Rendered contracts these steps pin** (the templates are written to the spec, not the other way
 * around): each record renders ONE element carrying `data-memory="<body>"` plus
 * `data-memory-source="owner|scribe"` and — only when the record extends another —
 * `data-memory-parent="<parent body>"`; the root renders `data-memory-root="<root body>"`; the
 * audit log renders newest-first `<li data-memory-change="<change id>">` rows carrying the snapshot
 * body, a `data-memory-cited` block with permalinked snapshot lines, and
 * `data-memory-reverted="true|false"`. Endpoint contracts: `POST /personas/{slug}/memories` with a
 * `body` param authors a record; `POST /personas/{slug}/memories/{id}/delete` deletes one;
 * `POST /admin/memory/run` runs the pass; `POST /admin/memory/{id}/revert` reverts a change (the
 * grammar both sibling audit logs speak).
 *
 * **Two of those surfaces are driven by the CONTROL, not by a rebuilt path**: the revert step POSTs
 * the newest audit row's own form `action`, and the Set-root step POSTs the profile's Set-root form
 * `action`. Both were once assembled from a constant, and both then pinned nothing — a template
 * that stopped rendering the form left every scenario green while the owner lost the only lever the
 * surface offers. Where a step names a row by id instead (delete), the fixed endpoint is deliberate
 * and argued at the step.
 *
 * Persona profile URLs are SLUGS (V5) — always `PersonaRepository.slugFor(name)`, never the raw
 * name (the S4b 404-on-capitalised-name lesson).
 *
 * NOT @Component; no mutable fields — per-scenario state lives in ScenarioWorld/DB/spy only
 * (@ScenarioScope isolation, see the cucumber-spring-bdd skill).
 */
class PersonaMemorySteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val data: TestData,
    private val llm: ScriptableLlmClient,
    private val jdbc: JdbcTemplate,
) {

    // --- seeding: direct SQL, so arranging costs nothing and depends on no owner surface ----------

    /** A memory record, seeded as the owner's ("was given …" arranges; "shows …" asserts). */
    @Given("persona {string} was given the memory {string}")
    fun personaWasGivenMemory(name: String, body: String) {
        data.insertMemory(personaId(name), body)
    }

    /**
     * A record extending an earlier one — builds an associative chain without running a pass. The
     * parent is resolved by its body, the way the scenarios (and the owner) recognise records.
     */
    @Given("persona {string} was given the memory {string} extending {string}")
    fun personaWasGivenMemoryExtending(name: String, body: String, parentBody: String) {
        val id = personaId(name)
        data.insertMemory(id, body, parentId = data.memoryIdOf(id, parentBody))
    }

    /** The §6.3 root: motivation, background, identity — owner-only, and never injected this slice. */
    @Given("persona {string} was given the root {string}")
    fun personaWasGivenRoot(name: String, body: String) {
        data.insertMemory(personaId(name), body, kind = "root")
    }

    // --- drivers -----------------------------------------------------------------------------------

    /** The manual, synchronous, ungated prod button — the only acceptance seam for the pass. */
    @When("the owner runs the memory pass")
    fun runMemoryPass() {
        val resp = http.post(RUN_PATH)
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    /**
     * Revert the newest audited change by POSTing the CONTROL the newest row actually renders — its
     * form's own `action`, not a path this step builds from the row id. Reading the id and
     * hand-assembling the URL (the first cut) left the owner's one-click undo pinned by nothing:
     * deleting the `data-memory-revert` form from admin_memory.kte, or breaking its
     * `@if(!change.reverted)` condition, kept the whole suite green while the entire control surface
     * §2.12 offers for an auto-applied write stopped rendering. Now the same deletion fails here,
     * loudly, in scenarios 18 and 19 — and scenario 19's drop-after-revert branch is pinned too,
     * since a row that still offered the control after being reverted would be a form this step
     * finds where the template promises none.
     */
    @When("the owner reverts the latest memory change")
    fun revertLatestMemoryChange() {
        val row = latestRow()
        val form = Regex("<form[^>]*data-memory-revert[^>]*>").find(row)?.value
            ?: error("no revert control (data-memory-revert) on the newest memory-change row:\n$row")
        val action = Regex("action=\"([^\"]+)\"").find(form)?.groupValues?.get(1)
            ?: error("the revert control renders no action to POST:\n$form")
        val resp = http.post(action)
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    /**
     * The owner's authoring path, driven for real (a COMPOSING-free POST — nothing here touches the
     * persona edit endpoint, so no LLM call is bought). Distinct wording from the seeding Given on
     * purpose: this one must go through the controller that stamps `source='owner'`.
     */
    @When("the owner authors the memory {string} for {string}")
    fun ownerAuthorsMemory(body: String, name: String) {
        val resp = http.postForm("/personas/${slug(name)}/memories", mapOf("body" to body))
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    /**
     * The owner's OTHER authoring path, driven through the control the profile renders: the step
     * reads the Set-root form's own `action` and POSTs that, so a profile that stops offering the
     * form fails here instead of leaving the create-once endpoint driven by nothing (the
     * `data-memory-revert` lesson, applied to the surface that has no data-* hook of its own — the
     * action's `/memories/root` tail is what identifies it among the profile's other memory forms).
     */
    @When("the owner sets the root {string} for {string}")
    fun ownerSetsRoot(body: String, name: String) {
        val page = profile(name)
        val action = Regex("action=\"([^\"]*/memories/root)\"").find(page)?.groupValues?.get(1)
            ?: error("no Set-root form on $name's profile — the owner has no way to author a root:\n$page")
        val resp = http.postForm(action, mapOf("body" to body))
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    /**
     * A SECOND Set-root submission, POSTed at the endpoint directly — deliberately not through the
     * form, because there is none: the profile drops the control the moment a root stands, so a
     * second attempt can only ever arrive from a stale page or a hand-crafted POST. That is exactly
     * the create-once path under test (the V28 partial unique index is the enforcement; the
     * controller's `rootOf` pre-check is what keeps it a readable no-op instead of a 500).
     */
    @When("the owner sets a second root {string} for {string}")
    fun ownerSetsSecondRoot(body: String, name: String) {
        val resp = http.postForm("/personas/${slug(name)}/memories/root", mapOf("body" to body))
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    /**
     * An unusable authored body, SIZED rather than spelled out: a 300+ code-point sentence written
     * into the feature would drown the scenario it belongs to, and the length is the whole subject
     * (MemoryText.MAX_CODE_POINTS — the bound the feature names in words). Built from repeated
     * whole words separated by single spaces and trimmed, so it is already a fixed point of
     * MemoryText.clean: the one reason it can be refused is the one the scenario claims, never an
     * accidental second one the assertion would then be blind to.
     */
    @When("the owner authors a memory longer than {int} characters for {string}")
    fun ownerAuthorsOverLongMemory(limit: Int, name: String) {
        val body = generateSequence { FILLER_WORD }.take(limit).joinToString(" ").take(limit * 2).trimEnd()
        val resp = http.postForm("/personas/${slug(name)}/memories", mapOf("body" to body))
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    /**
     * Single-record delete — the reparent-to-grandparent path (§2.10). The record is named by its
     * prose and resolved to an id in the DB: the scenarios' subject is what happens to the CHAIN,
     * not whether a delete control renders, so a fixed endpoint contract keeps the step honest
     * without guessing at template shape.
     */
    @When("the owner deletes the memory {string} of {string}")
    fun ownerDeletesMemory(body: String, name: String) {
        val id = data.memoryIdOf(personaId(name), body)
        val resp = http.post("/personas/${slug(name)}/memories/$id/delete")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    /**
     * Scenario 21's before-half: fingerprint every surface memory must never move — tick parity
     * (ambient_run), the thread/comment tables, and the home page with both rails. Stored in
     * ScenarioWorld.counts (ints; the page fingerprint as a hash) so this class stays field-free.
     */
    @When("the owner snapshots the forum activity")
    fun snapshotForumActivity() {
        world.counts[SNAP_THREADS] = tableCount("thread")
        world.counts[SNAP_COMMENTS] = tableCount("comment")
        world.counts[SNAP_AMBIENT] = tableCount("ambient_run")
        world.counts[SNAP_HOME] = homeFingerprint().hashCode()
    }

    // --- the airtime firewall (scenario 21) ---------------------------------------------------------

    /** I2, tick-parity half: the pass writes ZERO ambient_run rows — parity and the round-robin
     *  author index read that table, so a single row would be bought airtime. */
    @Then("no ambient run was recorded")
    fun noAmbientRunRecorded() =
        assertEquals(
            0, tableCount("ambient_run"),
            "the memory pass must write zero ambient_run rows — memory never buys airtime (I2)",
        )

    @Then("the forum activity is unchanged")
    fun forumActivityUnchanged() {
        val before = world.counts[SNAP_THREADS]
            ?: error("no forum-activity snapshot — add `the owner snapshots the forum activity` before the run")
        assertEquals(before, tableCount("thread"), "the memory pass must create no threads")
        assertEquals(world.counts[SNAP_COMMENTS], tableCount("comment"), "the memory pass must create no comments")
        assertEquals(world.counts[SNAP_AMBIENT], tableCount("ambient_run"), "the memory pass must not move tick parity")
        assertEquals(
            world.counts[SNAP_HOME], homeFingerprint().hashCode(),
            "the home page and rails changed across the memory pass — now: ${homeFingerprint()}",
        )
    }

    // --- the profile, which is where the owner actually reads a member's memory ---------------------

    @Then("the profile for {string} shows the memory {string} with source {string}")
    fun profileShowsMemoryWithSource(name: String, body: String, source: String) {
        val page = profile(name)
        val tag = memoryTag(page, body)
            ?: error("no memory \"$body\" on $name's profile, got:\n${memoriesOn(page)}")
        assertTrue(
            tag.contains("data-memory-source=\"$source\""),
            "expected \"$body\" to carry source \"$source\", got: $tag",
        )
    }

    @Then("the profile for {string} shows no memory {string}")
    fun profileShowsNoMemory(name: String, body: String) {
        val page = profile(name)
        assertTrue(
            !Html.hasAttr(page, "data-memory", body),
            "expected \"$name\"'s profile NOT to show the memory \"$body\", got:\n${memoriesOn(page)}",
        )
    }

    /** Counted on the rendered hooks — a count only right in the database is not the one the owner
     *  reads. The root is not a record and carries its own hook, so it is deliberately uncounted. */
    @Then("the profile for {string} shows exactly {int} memory/memories")
    fun profileShowsMemoryCount(name: String, count: Int) {
        val page = profile(name)
        assertEquals(
            count, Regex("data-memory=\"").findAll(page).count(),
            "expected \"$name\" to hold exactly $count memory record(s), got:\n${memoriesOn(page)}",
        )
    }

    /** The associative link as the owner sees it: the child renders under its antecedent. */
    @Then("the profile for {string} shows the memory {string} beneath {string}")
    fun profileShowsMemoryBeneath(name: String, body: String, parentBody: String) {
        val page = profile(name)
        val tag = memoryTag(page, body)
            ?: error("no memory \"$body\" on $name's profile, got:\n${memoriesOn(page)}")
        val parent = Regex("data-memory-parent=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
        assertEquals(
            parentBody, parent,
            "expected \"$body\" to sit beneath \"$parentBody\", got: $tag",
        )
    }

    @Then("the profile for {string} shows the memory {string} at top level")
    fun profileShowsMemoryTopLevel(name: String, body: String) {
        val page = profile(name)
        val tag = memoryTag(page, body)
            ?: error("no memory \"$body\" on $name's profile, got:\n${memoriesOn(page)}")
        val parent = Regex("data-memory-parent=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
        assertTrue(
            parent.isNullOrEmpty(),
            "expected \"$body\" to be top-level (no antecedent), got: $tag",
        )
    }

    @Then("the profile for {string} shows the root {string}")
    fun profileShowsRoot(name: String, body: String) {
        val page = profile(name)
        assertTrue(
            Html.hasAttr(page, "data-memory-root", body),
            "expected \"$name\"'s profile to show the root \"$body\" (data-memory-root), in:\n$page",
        )
    }

    /** Create-once has to be asserted from BOTH sides: that the first root still stands is only half
     *  of it — the second submission must not have landed anywhere either. */
    @Then("the profile for {string} shows no root {string}")
    fun profileShowsNoRoot(name: String, body: String) {
        val page = profile(name)
        assertTrue(
            !Html.hasAttr(page, "data-memory-root", body),
            "expected \"$name\"'s profile NOT to show the root \"$body\" — the root is create-once, " +
                "and a second submission must change nothing:\n$page",
        )
    }

    // --- the prompt boundary: what a memory may and may not reach -----------------------------------

    /**
     * The injection assertion, on the member's own call BY NAME. Positive half reads the system
     * prompt — the fourth-block door (§2.9) is the only place a memory may enter.
     */
    @Then("{string}'s generation prompt carried the memory {string}")
    fun generationPromptCarriedMemory(name: String, body: String) {
        val req = generationCall(name)
        assertTrue(
            req.context.personaSystemPrompt.contains(body, ignoreCase = true),
            "expected the memory \"$body\" in $name's generation prompt, which was:\n" +
                req.context.personaSystemPrompt,
        )
    }

    /** The negative half scans the WHOLE request (system prompt and every context comment) — a leak
     *  through any door is still a leak. */
    @Then("{string}'s generation prompt did not carry the memory {string}")
    fun generationPromptDidNotCarryMemory(name: String, body: String) {
        val req = generationCall(name)
        assertTrue(
            !wholePrompt(req).contains(body, ignoreCase = true),
            "the memory \"$body\" reached $name's generation prompt and must not have:\n${wholePrompt(req)}",
        )
    }

    /**
     * Scenario 1's HTTP-level truth (the plan doc's own honesty note): "byte-identical" decays here
     * to frame-text absence — no memory block frame in the captured prompt. True byte-parity is
     * pinned by the Tier-2 unwired-repository test.
     */
    @Then("{string}'s generation prompt carried no memory block")
    fun generationPromptCarriedNoMemoryBlock(name: String) {
        val req = generationCall(name)
        assertTrue(
            !wholePrompt(req).contains(MEMORY_FRAME, ignoreCase = true),
            "a member with no memories must generate with no memory block — found the frame text in:\n" +
                wholePrompt(req),
        )
    }

    /** The routing half of I1: the dispatcher decides who speaks and must never know what anyone
     *  remembers. Routing runs on a worker, so wait (bounded) for its call — the OwnerControlSteps
     *  poll, mirrored. */
    @Then("the dispatcher's prompt did not carry the memory {string}")
    fun dispatcherDidNotCarryMemory(body: String) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline && llm.received.none { it.persona.name == DISPATCHER_NAME }) {
            Thread.sleep(10)
        }
        val calls = llm.received.filter { it.persona.name == DISPATCHER_NAME }
        assertTrue(calls.isNotEmpty(), "the dispatcher was never called")
        calls.forEach { call ->
            assertTrue(
                !wholePrompt(call).contains(body, ignoreCase = true),
                "the memory \"$body\" reached the dispatcher prompt and must not have:\n${wholePrompt(call)}",
            )
        }
    }

    /**
     * The scribe's blinkers, selected by the scribe's synthetic identity. The fixtures make exactly
     * one member judgeable, so the positive half may read "any scribe call"; the negative half is
     * deliberately stronger — NO scribe call in the run may carry the foreign text, which is the
     * blinkers property itself.
     */
    @Then("the scribe prompt for {string} carried {string}")
    fun scribePromptCarried(name: String, text: String) {
        val calls = scribeCalls()
        assertTrue(
            calls.any { wholePrompt(it).contains(text, ignoreCase = true) },
            "expected the scribe judging \"$name\" to be shown \"$text\"; the scribe prompts were:\n" +
                calls.joinToString("\n---\n") { wholePrompt(it) },
        )
    }

    @Then("the scribe prompt for {string} did not carry {string}")
    fun scribePromptDidNotCarry(name: String, text: String) {
        val calls = scribeCalls()
        calls.forEach { call ->
            assertTrue(
                !wholePrompt(call).contains(text, ignoreCase = true),
                "\"$text\" reached a scribe prompt while judging \"$name\" — there must be no " +
                    "cross-member channel for the room to converge through:\n${wholePrompt(call)}",
            )
        }
    }

    // --- the audit log, which is the whole of the owner's control over an auto-applied change -------

    @Then("the memory history records {string} remembering {string}")
    fun historyRecordsMemory(name: String, body: String) {
        val row = latestRow()
        assertTrue(
            Html.contains(row, name),
            "expected the newest memory-change row to belong to \"$name\", got:\n$row",
        )
        assertTrue(
            Html.contains(row, body),
            "expected the newest memory-change row to snapshot \"$body\", got:\n$row",
        )
    }

    /** The cited engagement is snapshotted prose, never a live read (`memory_change.cited`, §2.2) —
     *  a comment body edited after the judgment must not rewrite what was judged. */
    @Then("the memory history entry cites {string}")
    fun historyEntryCites(snippet: String) {
        val row = latestRow()
        assertTrue(
            Html.contains(row, "data-memory-cited"),
            "expected a data-memory-cited block on the newest memory-change row, got:\n$row",
        )
        assertTrue(
            Html.contains(row, snippet),
            "expected the newest memory-change row to cite \"$snippet\", got:\n$row",
        )
    }

    @Then("the memory history entry links to the cited comment")
    fun historyEntryLinksToComment() {
        val row = latestRow()
        assertTrue(
            Regex("href=\"/threads/[^\"]*#reply-[^\"]+\"").containsMatchIn(row),
            "expected a /threads/…#reply-… permalink to the cited comment in:\n$row",
        )
    }

    @Then("the memory history entry is marked reverted")
    fun historyEntryIsReverted() =
        assertTrue(
            Html.hasAttr(latestRow(), "data-memory-reverted", "true"),
            "expected the newest memory-change row to be marked data-memory-reverted=\"true\", got:\n${latestRow()}",
        )

    /** Scenario 19's half of the same hook: a SKIPPED (superseded) revert leaves the marker at
     *  "false" — asserted as the rendered value, not as mere absence, so a row that stopped
     *  rendering the hook fails rather than passing by omission. */
    @Then("the memory history entry is not marked reverted")
    fun historyEntryIsNotReverted() =
        assertTrue(
            Html.hasAttr(latestRow(), "data-memory-reverted", "false"),
            "expected the newest memory-change row to remain data-memory-reverted=\"false\", got:\n${latestRow()}",
        )

    /** Status-first: a 404 here must read as "no admin surface", never as "empty history". */
    @Then("the memory history is empty")
    fun historyIsEmpty() {
        val resp = http.get(HISTORY_PATH)
        assertEquals(200, resp.statusCode.value(), "expected $HISTORY_PATH to render")
        assertNull(
            Html.latestMemoryChangeRow(resp.body ?: ""),
            "expected NO memory-change rows on $HISTORY_PATH, but found one in:\n${resp.body}",
        )
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun personaId(name: String) = name

    private fun slug(name: String) = PersonaRepository.slugFor(name)

    private fun profile(name: String): String {
        val resp = http.get("/personas/${slug(name)}")
        assertEquals(200, resp.statusCode.value(), "expected \"$name\"'s profile to render")
        return resp.body ?: ""
    }

    /** The single element carrying this record's hooks (data-memory + source + parent), or null. */
    private fun memoryTag(page: String, body: String): String? =
        Regex("<[^>]*data-memory=\"${Regex.escape(body)}\"[^>]*>").find(page)?.value

    /** Just the memory hooks on a page, so a failure message is readable rather than a whole page. */
    private fun memoriesOn(page: String): String =
        Regex("data-memory=\"([^\"]*)\"").findAll(page).map { it.groupValues[1] }.toList()
            .ifEmpty { listOf("(no data-memory hooks at all)") }
            .joinToString(", ")

    /**
     * The member's own generation call, selected by NAME. Never `personaCall()` / "last call": a
     * MemoryScribe judgment satisfies both, and its instruction contains the memory text, so the
     * loose selector turns every injection assertion vacuous (plan doc §4).
     */
    private fun generationCall(name: String): LlmRequest =
        llm.received.lastOrNull { it.persona.name.equals(name, ignoreCase = true) }
            ?: error(
                "no generation call for \"$name\" reached the LLM " +
                    "(calls: ${llm.received.map { it.persona.name }})",
            )

    private fun scribeCalls(): List<LlmRequest> {
        val calls = llm.received.filter { it.persona.name == SCRIBE_NAME }
        assertTrue(
            calls.isNotEmpty(),
            "expected at least one scribe call, the spy saw: ${llm.received.map { it.persona.name }}",
        )
        return calls
    }

    /** Everything a call could leak through: the assembled system prompt and every context comment. */
    private fun wholePrompt(req: LlmRequest): String = buildString {
        append(req.context.personaSystemPrompt)
        req.context.comments.forEach { append(' ').append(it.authorId).append(' ').append(it.body) }
    }

    /** The newest audit row, freshly fetched — an audit row is read as a unit (see Html). */
    private fun latestRow(): String {
        val page = http.get(HISTORY_PATH).body ?: ""
        return Html.latestMemoryChangeRow(page)
            ?: error("no memory-change row (data-memory-change) on $HISTORY_PATH:\n$page")
    }

    private fun tableCount(table: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java) ?: 0

    /** The home page and both rails, as one comparable string: the main thread rows, the active rail
     *  and the recently-posted rail, in document order. */
    private fun homeFingerprint(): String {
        val home = http.get("/").body ?: ""
        return "threads=" + Html.attrValues(home, "data-thread-title") +
            "|active=" + Html.attrValues(home, "data-active-thread") +
            "|recent=" + Html.attrValues(home, "data-recent-comment")
    }

    private companion object {
        const val HISTORY_PATH = "/admin/memory"
        const val RUN_PATH = "/admin/memory/run"

        /** Must match `MemoryScribePrompts.SCRIBE_NAME` (plan doc §2.4) — the spy selects on it;
         *  pinned Tier 0 against the four other synthetic identities. */
        const val SCRIBE_NAME = "MemoryScribe"
        const val DISPATCHER_NAME = "Moderator"

        /** The word the over-long body is built out of — any whole word does; it is spelled with no
         *  resemblance to the fixtures so a reader never reads meaning into the filler. */
        const val FILLER_WORD = "sprawling"

        /** The MemoryProse frame opener (plan doc §2.9) — the frame text whose absence is scenario
         *  1's HTTP-level decay of "byte-identical". */
        const val MEMORY_FRAME = "Things you remember"

        // ScenarioWorld.counts keys for the scenario-21 snapshot.
        const val SNAP_THREADS = "memorySnapshot:threads"
        const val SNAP_COMMENTS = "memorySnapshot:comments"
        const val SNAP_AMBIENT = "memorySnapshot:ambientRuns"
        const val SNAP_HOME = "memorySnapshot:homeAndRails"
    }
}
