package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.FailingRepositoryToggle
import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.config.ScriptableLlmClient.Behavior
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.acceptance.support.TestData
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Shared Given (seed preconditions into the real test DB) and generic Then (assert on the data-*
 * hooks of the last response). Step classes inject Spring beans by constructor (cucumber-spring).
 */
class CommonSteps(
    private val world: ScenarioWorld,
    private val data: TestData,
    private val llm: ScriptableLlmClient,
    private val http: HttpClient,
    private val failingRepo: FailingRepositoryToggle,
) {
    @Given("a thread {string} exists")
    fun threadExists(title: String) {
        world.threadId = data.insertThread(title)
        world.threadIds[title] = world.threadId!!
    }

    @Given("a persona {string} exists")
    fun personaExists(name: String) {
        // use the name as the id to keep summon payloads readable in features
        data.insertPersona(id = name, name = name)
    }

    @Given("a persona {string} skilled in {string} exists")
    fun personaSkilledExists(name: String, ability: String) {
        // The structured abilities tag (V10) the "Anyone" dispatcher now reads to match topic -> persona.
        data.insertPersona(id = name, name = name, abilities = listOf(ability))
    }

    // S2 (plan_docs/ambient-slice-2.md §3/§4): the ambient comment gate needs BOTH a matching ability tag
    // (relevance) and the talkativeness dial (P(comment)) — this sibling of personaSkilledExists sets
    // both in one seed so ambient_commenting/depth_budget scenarios can vary talkativeness independently
    // of the ability match.
    @Given("a persona {string} exists with ability {string} and talkativeness {int}")
    fun personaWithAbilityAndTalkativeness(name: String, ability: String, talkativeness: Int) {
        data.insertPersona(id = name, name = name, abilities = listOf(ability), dials = mapOf("talkativeness" to talkativeness))
    }

    @Given("a posted reply from {string} saying {string}")
    fun postedReply(persona: String, body: String) {
        val id = data.insertComment(world.threadId!!, authorId = persona, body = body)
        world.rememberReply("$persona's reply", id)
    }

    @Given("a posted reply from {string} saying {string} under {string}'s reply")
    fun postedReplyUnder(persona: String, body: String, parent: String) {
        val parentId = world.replyIds["$parent's reply"] ?: error("no remembered reply for \"$parent's reply\"")
        val id = data.insertComment(world.threadId!!, authorId = persona, body = body, parentId = parentId)
        world.rememberReply("$persona's reply", id)
    }

    @Given("the LLM will respond with {string}")
    fun llmWillRespond(text: String) = llm.enqueue(Behavior.Respond(text))

    // Docstring variant: a reply body spanning multiple lines (e.g. a fenced code block needs real
    // newlines, which a one-line {string} can't carry).
    @Given("the LLM will respond with the markdown:")
    fun llmWillRespondMarkdown(markdown: String) = llm.enqueue(Behavior.Respond(markdown))

    /**
     * Docstring variant for an answer whose SHAPE is multi-line. S4b's interest judgment is a
     * `DROP:`/`TAKE:` pair on two lines, and a one-line `{string}` cannot carry the newline that
     * separates them: Gherkin does not interpret `\n` inside a quoted string, so the enqueued text
     * would hold a literal backslash-n and the parse would refuse an answer the model got right.
     *
     * Distinct from the markdown variant above only in what it says it is for — a reply body versus a
     * structured answer — so a reader of either feature knows which shape is being scripted.
     */
    @Given("the LLM will respond with the answer:")
    fun llmWillRespondAnswer(answer: String) = llm.enqueue(Behavior.Respond(answer))

    @Given("the next save will fail")
    fun nextSaveWillFail() {
        failingRepo.failNextWrite = true
    }

    @Then("the reply is {string}")
    fun replyIs(state: String) {
        assertTrue(
            Html.hasAttr(body(), "data-state", state.lowercase()),
            "expected a reply with data-state=\"$state\" in:\n${body()}",
        )
    }

    @Then("the reply is not {string}")
    fun replyIsNot(state: String) {
        assertFalse(
            Html.hasAttr(body(), "data-state", state.lowercase()),
            "expected NO reply with data-state=\"$state\" in:\n${body()}",
        )
    }

    @Then("the reply body contains {string}")
    fun replyBodyContains(text: String) {
        assertTrue(Html.contains(body(), text), "expected body to contain \"$text\" in:\n${body()}")
    }

    @Then("the reply body does not contain {string}")
    fun replyBodyDoesNotContain(text: String) {
        assertFalse(Html.contains(body(), text), "expected body NOT to contain \"$text\" in:\n${body()}")
    }

    @Then("the reply author is {string}")
    fun replyAuthor(author: String) {
        assertTrue(
            Html.hasAttr(body(), "data-author", author),
            "expected a reply with data-author=\"$author\" in:\n${body()}",
        )
    }

    @Then("the reply failureCategory is {string}")
    fun replyFailureCategory(category: String) {
        assertTrue(
            Html.hasAttr(body(), "data-failure-category", category),
            "expected data-failure-category=\"$category\" in:\n${body()}",
        )
    }

    @Then("the reply retryable is {string}")
    fun replyRetryable(retryable: String) {
        assertTrue(
            Html.hasAttr(body(), "data-retryable", retryable),
            "expected data-retryable=\"$retryable\" in:\n${body()}",
        )
    }

    @Then("the reply reasoning-leak is {string}")
    fun replyReasoningLeak(leak: String) {
        assertTrue(
            Html.hasAttr(body(), "data-reasoning-leak", leak),
            "expected data-reasoning-leak=\"$leak\" in:\n${body()}",
        )
    }

    @Then("the response status is {int}")
    fun responseStatus(status: Int) = assertEquals(status, world.lastStatus)

    private fun body(): String = world.lastBody ?: ""
}
