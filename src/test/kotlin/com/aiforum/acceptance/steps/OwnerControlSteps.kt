package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.llm.LlmRequest
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Owner-control steps, including the anti-sycophancy firewall (§7): assert the +1 is recorded and
 * shown to the owner, yet never appears in the PromptContext handed to the model (via the spy).
 */
class OwnerControlSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val llm: ScriptableLlmClient,
) {
    @When("the owner gives a +1 to {string}'s reply")
    fun plusOne(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        val resp = http.post("/replies/$id/plus-one")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the owner sees a vote count of {int} on {string}'s reply")
    fun seesVoteCount(count: Int, persona: String) {
        val id = world.replyIds["$persona's reply"]!!
        assertEquals(count.toString(), Html.replyAttr(world.lastBody ?: "", id, "data-vote-count"))
    }

    @Then("the +1 button is present on {string}'s reply")
    fun plusOneButtonPresent(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertNotNull(
            Html.replyAttr(world.lastBody ?: "", id, "data-reply-id"),
            "expected reply node for $persona in page",
        )
        assertTrue(
            (world.lastBody ?: "").contains("hx-post=\"/replies/$id/plus-one\""),
            "expected +1 button (hx-post=/replies/$id/plus-one) in page:\n${world.lastBody}",
        )
    }

    @When("the owner deletes {string}'s reply")
    fun deleteReply(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        val resp = http.post("/replies/$id/delete")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the delete button is present on {string}'s reply")
    fun deleteButtonPresent(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertNotNull(
            Html.replyAttr(world.lastBody ?: "", id, "data-reply-id"),
            "expected reply node for $persona in page",
        )
        assertTrue(
            (world.lastBody ?: "").contains("hx-post=\"/replies/$id/delete\""),
            "expected delete button (hx-post=/replies/$id/delete) in page:\n${world.lastBody}",
        )
    }

    @Then("the thread no longer shows {string}'s reply")
    fun threadNoLongerShows(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertNull(
            Html.replyAttr(world.lastBody ?: "", id, "data-reply-id"),
            "expected $persona's reply ($id) to be gone from the page:\n${world.lastBody}",
        )
    }

    @Then("the thread still shows {string}'s reply")
    fun threadStillShows(persona: String) {
        val id = world.replyIds["$persona's reply"] ?: error("no remembered reply for $persona")
        assertNotNull(
            Html.replyAttr(world.lastBody ?: "", id, "data-reply-id"),
            "expected $persona's reply ($id) to still be on the page:\n${world.lastBody}",
        )
    }

    @Then("the model's context included {string}'s words {string}")
    fun contextIncluded(persona: String, words: String) {
        val req = llm.received.lastOrNull() ?: error("the LLM was never called")
        assertTrue(
            req.context.comments.any { it.body.contains(words) },
            "expected the model context to include \"$words\"",
        )
    }

    @Then("the model's context contained no vote signal")
    fun noVoteSignal() {
        val req = llm.received.lastOrNull() ?: error("the LLM was never called")
        val everything = buildString {
            append(req.context.personaSystemPrompt)
            req.context.comments.forEach { append(' ').append(it.authorId).append(' ').append(it.body) }
        }.lowercase()
        assertTrue(
            !everything.contains("+1") && !everything.contains("vote"),
            "the firewall leaked a vote signal into the model context: $everything",
        )
    }

    /**
     * The other side of the same boundary: a relation stance is prose the owner authored on purpose, so
     * it is meant to be IN the persona's system prompt. Asserted on the last non-dispatcher call — the
     * dispatcher gets its own relations block (a different shape, see the routing feature), and asserting
     * blindly on `received.last()` would let a routing prompt satisfy a generation-prompt claim.
     */
    @Then("the model's system prompt carried the stance {string}")
    fun systemPromptCarriedStance(stance: String) {
        val req = personaCall()
        assertTrue(
            req.context.personaSystemPrompt.contains(stance),
            "expected the stance \"$stance\" in ${req.persona.name}'s system prompt, which was:\n" +
                req.context.personaSystemPrompt,
        )
    }

    /**
     * S4b's half of the same boundary: a DRIFTED interest is meant to be inside the persona's system
     * prompt, and it gets there by generation-time injection rather than by anything being composed.
     *
     * **Selected by the member's own name, never by `personaCall()`.** An `InterestJudge` call is not
     * the dispatcher, so it satisfies "the last non-dispatcher call" — and the judge's own instruction
     * contains the interest phrase, so the loose selector would let this assertion pass while proving
     * nothing about injection into a GENERATION prompt.
     */
    @Then("{string}'s system prompt carried the interest {string}")
    fun systemPromptCarriedInterest(persona: String, interest: String) {
        val req = llm.received.lastOrNull { it.persona.name.equals(persona, ignoreCase = true) }
            ?: error(
                "no generation call for \"$persona\" reached the LLM " +
                    "(calls: ${llm.received.map { it.persona.name }})",
            )
        assertTrue(
            req.context.personaSystemPrompt.contains(interest, ignoreCase = true),
            "expected the interest \"$interest\" in $persona's system prompt, which was:\n" +
                req.context.personaSystemPrompt,
        )
    }

    /**
     * Negative twin of GenerationSteps' `the dispatcher's roster lists ...`, and a sibling of the
     * no-vote-signal assertion above: both pin something that must NOT reach a model. With no persona in
     * the discussion yet there is no edge worth showing, so the dispatcher prompt must omit the relations
     * section entirely rather than render an empty header.
     */
    @Then("the dispatcher's prompt carries no relations section")
    fun dispatcherHasNoRelations() {
        val prompt = dispatcherCall().context.personaSystemPrompt
        assertTrue(
            !prompt.contains(RELATIONS_HEADER),
            "expected NO \"$RELATIONS_HEADER\" section in the dispatcher's prompt, which was:\n$prompt",
        )
    }

    /** The persona's own generation call — anything that is not the "Moderator" routing call. */
    private fun personaCall(): LlmRequest =
        llm.received.lastOrNull { it.persona.name != DISPATCHER_NAME }
            ?: error("no persona generation call reached the LLM (calls: ${llm.received.map { it.persona.name }})")

    /**
     * The dispatcher's call carries its synthetic "Moderator" ref. Routing runs on a worker, so wait
     * (bounded) for it to land in the spy before asserting on it — mirrors GenerationSteps' poll.
     */
    private fun dispatcherCall(): LlmRequest {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline && llm.received.none { it.persona.name == DISPATCHER_NAME }) {
            Thread.sleep(10)
        }
        return llm.received.firstOrNull { it.persona.name == DISPATCHER_NAME }
            ?: error("the dispatcher was never called")
    }

    private companion object {
        const val DISPATCHER_NAME = "Moderator"
        const val RELATIONS_HEADER = "Relations between participants:"
    }
}
