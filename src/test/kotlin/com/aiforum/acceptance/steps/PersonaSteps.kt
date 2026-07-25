package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.acceptance.support.TestData
import com.aiforum.llm.LlmRequest
import com.aiforum.persona.Dials
import com.aiforum.repo.PersonaRepository
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class PersonaSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val data: TestData,
    private val llm: ScriptableLlmClient,
    // Read-only, and only ever for ARRANGE: the stance-save step has to replay a persona's stored
    // descriptor/abilities/dials/prompt verbatim so that the one field it changes really is the stance.
    private val personas: PersonaRepository,
) {
    // The composition call rides the single LlmClient seam tagged with this synthetic persona, so the
    // spy can tell a prompt-authoring call apart from a normal generation call (see PromptComposer).
    private val COMPOSER_NAME = "PromptComposer"

    private fun composerCalls(): List<LlmRequest> = llm.received.filter { it.persona.name == COMPOSER_NAME }

    /** Everything the composer was handed: its system role plus the spec/instruction turn(s). */
    private fun LlmRequest.allText(): String =
        context.personaSystemPrompt + " " + context.comments.joinToString(" ") { it.body }
    @When("the owner opens the profile for {string}")
    fun openProfile(name: String) {
        val resp = http.get("/personas/$name")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the profile shows the persona's name and descriptor")
    fun profileShowsNameAndDescriptor() {
        val body = world.lastBody ?: ""
        assertTrue(
            Regex("""data-persona-name="[^"]+"""").containsMatchIn(body),
            "expected data-persona-name attribute in:\n$body",
        )
        assertTrue(
            Regex("""data-persona-descriptor="[^"]+"""").containsMatchIn(body),
            "expected data-persona-descriptor attribute in:\n$body",
        )
    }

    @When("the owner adds a persona {string} described as {string}")
    fun addPersona(name: String, descriptor: String) {
        val resp = http.postForm("/personas", mapOf("name" to name, "descriptor" to descriptor))
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @When("the owner opens the members list")
    fun openMembersList() {
        val resp = http.get("/personas")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the members page offers a name and descriptor field")
    fun membersPageOffersCreateForm() {
        val body = world.lastBody ?: ""
        assertTrue(Html.contains(body, "name=\"name\""), "expected a name=\"name\" field in:\n$body")
        assertTrue(Html.contains(body, "name=\"descriptor\""), "expected a name=\"descriptor\" field in:\n$body")
    }

    @Then("the persona {string} exists")
    fun personaExists(name: String) {
        val resp = http.get("/personas/$name")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        assertTrue(resp.statusCode.is2xxSuccessful, "expected 200 for /personas/$name, got ${resp.statusCode}")
    }

    @Then("{string} appears in the members list")
    fun appearsInMembersList(name: String) {
        val resp = http.get("/personas")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        assertTrue(
            Html.hasAttr(resp.body ?: "", "data-persona-id", name),
            "expected data-persona-id=\"$name\" in:\n${resp.body}",
        )
    }

    @When("the owner adds a persona {string} with abilities {string} and dials agreeableness {int}, verbosity {int}")
    fun addPersonaWithTraits(name: String, abilities: String, agreeableness: Int, verbosity: Int) {
        val resp = http.postForm(
            "/personas",
            mapOf(
                "name" to name,
                "abilities" to abilities,
                "dial_agreeableness" to agreeableness,
                "dial_verbosity" to verbosity,
            ),
        )
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Given("a persona {string} exists with system prompt {string} and dials agreeableness {int}, verbosity {int}")
    fun seedPersonaWithPromptAndDials(name: String, systemPrompt: String, agreeableness: Int, verbosity: Int) {
        data.insertPersona(
            id = name,
            name = name,
            systemPrompt = systemPrompt,
            dials = mapOf("agreeableness" to agreeableness, "verbosity" to verbosity),
        )
    }

    @When("the owner edits {string} setting dials agreeableness {int}, verbosity {int}")
    fun editPersonaDials(name: String, agreeableness: Int, verbosity: Int) {
        val resp = http.postForm(
            "/personas/$name/edit",
            mapOf("dial_agreeableness" to agreeableness, "dial_verbosity" to verbosity),
        )
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the persona {string} has abilities {string}")
    fun personaHasAbilities(name: String, abilities: String) {
        val body = http.get("/personas/$name").body ?: ""
        assertTrue(
            Html.hasAttr(body, "data-persona-abilities", abilities),
            "expected data-persona-abilities=\"$abilities\" in:\n$body",
        )
    }

    @Then("the persona {string} has system prompt {string}")
    fun personaHasSystemPrompt(name: String, prompt: String) {
        val body = http.get("/personas/$name").body ?: ""
        assertTrue(
            Html.hasAttr(body, "data-system-prompt", prompt),
            "expected data-system-prompt=\"$prompt\" in:\n$body",
        )
    }

    @Then("the composer was asked to honour the dials")
    fun composerHonouredDials() {
        val calls = composerCalls()
        assertTrue(calls.isNotEmpty(), "expected a composition call to the LLM, got: ${llm.received.map { it.persona.name }}")
        assertTrue(
            calls.any { it.allText().contains("agreeableness", ignoreCase = true) },
            "expected the composer instruction to mention the dials, got:\n${calls.joinToString("\n") { it.allText() }}",
        )
    }

    @Then("the composer was given the previous prompt {string}")
    fun composerGivenPreviousPrompt(previous: String) {
        val calls = composerCalls()
        assertTrue(calls.isNotEmpty(), "expected a composition call to the LLM, got: ${llm.received.map { it.persona.name }}")
        assertTrue(
            calls.any { it.allText().contains(previous) },
            "expected the composer to be handed the previous prompt \"$previous\", got:\n${calls.joinToString("\n") { it.allText() }}",
        )
    }

    @Then("the members page offers an abilities field and dial controls")
    fun membersPageOffersTraitFields() {
        val body = world.lastBody ?: ""
        assertTrue(Html.contains(body, "name=\"abilities\""), "expected a name=\"abilities\" field in:\n$body")
        assertTrue(Html.contains(body, "name=\"dial_agreeableness\""), "expected a name=\"dial_agreeableness\" control in:\n$body")
    }

    // S2 (plan_docs/ambient-slice-2.md §3): talkativeness is a fifth dial (spec §6.4). The create/edit
    // forms and the profile page all iterate Dials.KEYS generically, so this is the one seam that proves
    // the form renders it once it's added — no template change needed beyond the KEYS list itself.
    @Then("the members page offers a talkativeness dial control")
    fun membersPageOffersTalkativenessDial() {
        val body = world.lastBody ?: ""
        assertTrue(
            Html.contains(body, "name=\"dial_talkativeness\""),
            "expected a name=\"dial_talkativeness\" control in:\n$body",
        )
    }

    @When("the owner adds a persona {string} with abilities {string} and dials agreeableness {int}, verbosity {int}, talkativeness {int}")
    fun addPersonaWithTalkativeness(name: String, abilities: String, agreeableness: Int, verbosity: Int, talkativeness: Int) {
        val resp = http.postForm(
            "/personas",
            mapOf(
                "name" to name,
                "abilities" to abilities,
                "dial_agreeableness" to agreeableness,
                "dial_verbosity" to verbosity,
                "dial_talkativeness" to talkativeness,
            ),
        )
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the persona {string} has dial {string} value {int}")
    fun personaHasDialValue(name: String, key: String, value: Int) {
        val body = http.get("/personas/$name").body ?: ""
        val text = Html.dialText(body, key)
        assertTrue(
            text != null && text.contains("$value/10"),
            "expected dial \"$key\" = $value/10 for \"$name\", got \"$text\" in:\n$body",
        )
    }

    @When("the owner previews a new persona {string} with abilities {string} and dials agreeableness {int}, verbosity {int}")
    fun previewNewPersona(name: String, abilities: String, agreeableness: Int, verbosity: Int) {
        val resp = http.postForm(
            "/personas/compose",
            mapOf(
                "name" to name,
                "abilities" to abilities,
                "dial_agreeableness" to agreeableness,
                "dial_verbosity" to verbosity,
            ),
        )
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the preview shows {string}")
    fun previewShows(text: String) {
        assertTrue(Html.contains(world.lastBody ?: "", text), "expected the preview to contain \"$text\" in:\n${world.lastBody}")
    }

    @Then("the persona {string} does not exist")
    fun personaDoesNotExist(name: String) {
        val body = http.get("/personas").body ?: ""
        assertTrue(
            !Html.hasAttr(body, "data-persona-id", name),
            "expected \"$name\" to be absent from the members list, but found it in:\n$body",
        )
    }

    @When("the owner adds a persona {string} with prompt {string} and abilities {string}")
    fun addPersonaWithPrompt(name: String, systemPrompt: String, abilities: String) {
        val resp = http.postForm(
            "/personas",
            mapOf("name" to name, "systemPrompt" to systemPrompt, "abilities" to abilities),
        )
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("no composition call was made")
    fun noCompositionCall() {
        assertTrue(
            composerCalls().isEmpty(),
            "expected NO composition call, but the composer was invoked: ${composerCalls().map { it.allText() }}",
        )
    }

    @Then("the members page offers a preview control and a prompt field")
    fun membersPageOffersPreview() {
        val body = world.lastBody ?: ""
        assertTrue(Html.contains(body, "/personas/compose"), "expected a preview control posting to /personas/compose in:\n$body")
        assertTrue(Html.contains(body, "name=\"systemPrompt\""), "expected a name=\"systemPrompt\" field in:\n$body")
    }

    @When("the owner opens the edit form for {string}")
    fun openEditForm(name: String) {
        val resp = http.get("/personas/$name/edit")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the edit form offers a cancel link back to {string}'s profile")
    fun editFormOffersCancel(name: String) {
        val body = world.lastBody ?: ""
        assertTrue(
            Regex("""data-cancel-edit[^>]*href="/personas/$name"""").containsMatchIn(body),
            "expected a cancel link (data-cancel-edit → /personas/$name) in:\n$body",
        )
    }

    @When("the owner saves {string} with the unchanged prompt {string} and dials agreeableness {int}, verbosity {int}")
    fun saveUnchangedPrompt(name: String, prompt: String, agreeableness: Int, verbosity: Int) =
        saveEdit(name, prompt, agreeableness, verbosity)

    @When("the owner saves {string} with the edited prompt {string} and dials agreeableness {int}, verbosity {int}")
    fun saveEditedPrompt(name: String, prompt: String, agreeableness: Int, verbosity: Int) =
        saveEdit(name, prompt, agreeableness, verbosity)

    private fun saveEdit(name: String, prompt: String, agreeableness: Int, verbosity: Int) {
        val resp = http.postForm(
            "/personas/$name/edit",
            mapOf("systemPrompt" to prompt, "dial_agreeableness" to agreeableness, "dial_verbosity" to verbosity),
        )
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    // ---------------------------------------------------------------------------------------------
    // Qualitative relations (plan_docs/ambient-slice-3.md): directed, FREE-TEXT persona→persona
    // stances. These steps are the SHARED vocabulary — persona_seeding, persona_routing and
    // owner_controls_firewall use them and must not redefine them (Cucumber glue is global, so a
    // second definition of any of these step texts fails the whole suite).
    // ---------------------------------------------------------------------------------------------

    /** Seed one directed edge straight into persona_stance — [from] and [to] are persona IDs, i.e. the
     *  strings the "a persona {string} exists" step inserts as both id and name. */
    @Given("persona {string} has a stance toward {string} of {string}")
    fun personaHasStanceToward(from: String, to: String, stance: String) = data.insertStance(from, to, stance)

    @Then("the profile for {string} shows a stance toward {string} of {string}")
    fun profileShowsStance(from: String, to: String, stance: String) {
        val body = profileBody(from)
        val text = stanceEntryText(body, to)
        assertNotNull(text, "expected a stance entry (data-stance-to=\"$to\") on \"$from\"'s profile:\n$body")
        assertTrue(
            text!!.contains(stance, ignoreCase = true),
            "expected \"$from\"'s stance toward \"$to\" to read \"$stance\", got \"$text\"",
        )
    }

    @Then("the profile for {string} shows no stance toward {string}")
    fun profileShowsNoStance(from: String, to: String) {
        val body = profileBody(from)
        assertNull(
            stanceEntryText(body, to),
            "expected NO stance toward \"$to\" on \"$from\"'s profile, but found one in:\n$body",
        )
    }

    /**
     * A stance-only save: every other field is replayed exactly as stored, so the single thing this
     * submit changes is the one `stance_<toId>` param. That is what makes the "editing only a stance
     * costs nothing" scenario meaningful — with any other field drifting, the server's `inputsChanged`
     * backstop would recompose and the free-save claim would be untestable. Dials are submitted for
     * every [Dials.KEYS] entry, exactly as the rendered edit form does, so the persona under test must
     * be seeded with a complete dial set (see "a persona … exists with every dial at …").
     */
    @When("the owner saves {string} with a stance toward {string} of {string}")
    fun saveStanceOnly(name: String, to: String, stance: String) {
        val existing = personas.find(name) ?: error("no persona \"$name\" — seed it before saving a stance")
        val form = mutableMapOf<String, Any?>(
            "descriptor" to existing.descriptor,
            "model" to existing.model,
            "abilities" to existing.abilities.joinToString(", "),
            "systemPrompt" to existing.systemPrompt,
            "stance_$to" to stance,
        )
        Dials.KEYS.forEach { key -> form["dial_$key"] = existing.dials[key] ?: Dials.DEFAULT }
        val resp = http.postForm("/personas/${existing.slug}/edit", form)
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    /** Seeds the full fixed dial schema so an "unchanged" resubmit really is unchanged (the sibling
     *  Given that seeds only two dials leaves the other three absent, which the edit form would then
     *  fill with defaults — a difference the server reads as a changed input). */
    @Given("a persona {string} exists with every dial at {int}")
    fun personaWithEveryDialAt(name: String, value: Int) {
        data.insertPersona(id = name, name = name, dials = Dials.KEYS.associateWith { value })
    }

    @Then("the edit form offers a stance field toward {string}")
    fun editFormOffersStanceField(to: String) {
        val body = world.lastBody ?: ""
        assertTrue(
            Html.contains(body, "name=\"stance_$to\""),
            "expected a name=\"stance_$to\" field on the edit form in:\n$body",
        )
        assertTrue(
            Html.hasAttr(body, "data-stance-field", to),
            "expected a data-stance-field=\"$to\" hook on the edit form in:\n$body",
        )
    }

    @Then("the stance field toward {string} is prefilled with {string}")
    fun stanceFieldPrefilled(to: String, stance: String) {
        val body = world.lastBody ?: ""
        val field = Regex(
            "<textarea\\b[^>]*data-stance-field=\"${Regex.escape(to)}\"[^>]*>(.*?)</textarea>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(body)?.groupValues?.get(1)?.let { unescape(it) }
        assertNotNull(field, "expected a stance textarea (data-stance-field=\"$to\") in:\n$body")
        assertTrue(
            field!!.contains(stance, ignoreCase = true),
            "expected the stance field toward \"$to\" to be prefilled with \"$stance\", got \"${field.trim()}\"",
        )
    }

    @Then("the composer was handed the stance {string}")
    fun composerHandedStance(stance: String) {
        val calls = composerCalls()
        assertTrue(calls.isNotEmpty(), "expected a composition call to the LLM, got: ${llm.received.map { it.persona.name }}")
        assertTrue(
            calls.any { it.allText().contains(stance, ignoreCase = true) },
            "expected the composer to be handed the stance \"$stance\", got:\n${calls.joinToString("\n") { it.allText() }}",
        )
    }

    @When("the owner recomposes every persona's prompt")
    fun recomposeEveryPrompt() {
        val resp = http.post("/personas/recompose")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the members page offers a recompose-all control")
    fun membersPageOffersRecomposeAll() {
        val body = world.lastBody ?: ""
        val tag = Regex("<[^>]*data-recompose-all[^>]*>").find(body)?.value
        assertNotNull(tag, "expected a data-recompose-all form on the members page in:\n$body")
        assertTrue(
            tag!!.contains("action=\"/personas/recompose\""),
            "expected the recompose-all form to post to /personas/recompose, got: $tag",
        )
    }

    @Then("the recompose-all control warns what it costs")
    fun recomposeAllWarnsCost() {
        val body = world.lastBody ?: ""
        assertTrue(
            Html.contains(body, "per member"),
            "expected copy warning it is one LLM call PER MEMBER in:\n$body",
        )
        assertTrue(
            Html.contains(body, "overwrit"),
            "expected copy warning that hand-edited prompts are OVERWRITTEN in:\n$body",
        )
    }

    /** The persona profile page, resolved the way the app resolves it — by slug, not by id. */
    private fun profileBody(personaId: String): String {
        val slug = personas.find(personaId)?.slug ?: PersonaRepository.slugFor(personaId)
        val resp = http.get("/personas/$slug")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        return resp.body ?: ""
    }

    /** The visible text of the Relations entry pointing at [to] (`<li data-stance-to="…">`), or null
     *  when the profile carries no stance toward that persona at all. Entities are decoded so a stance
     *  the owner wrote with an apostrophe or an ampersand still compares as the prose they typed. */
    private fun stanceEntryText(html: String, to: String): String? {
        val li = Regex(
            "<li\\b[^>]*data-stance-to=\"${Regex.escape(to)}\"[^>]*>(.*?)</li>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(html) ?: return null
        return unescape(li.groupValues[1].replace(Regex("<[^>]*>"), " ")).replace(Regex("\\s+"), " ").trim()
    }

    private fun unescape(s: String): String = s
        .replace("&#39;", "'").replace("&#x27;", "'").replace("&apos;", "'")
        .replace("&quot;", "\"").replace("&#34;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&amp;", "&")
}
