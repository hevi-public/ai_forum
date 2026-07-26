package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.support.GenerationSettle
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Trigger modes (§4): sequential fan-out / roomful in M1, where one persona failing does NOT abort the
 * room (partial-roomful). The LLM behaviours are enqueued in persona order by preceding steps; a single
 * worker settles the personas in that order, preserving the deque-scripted mapping.
 */
class TriggerModeSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val settle: GenerationSettle,
    private val llm: ScriptableLlmClient,
) {
    @When("the owner fans out to {string}")
    fun fanOut(personasCsv: String) {
        val personaIds = personasCsv.split(",").map { it.trim() }
        val resp = http.postJson(
            "/threads/${world.threadId}/generate",
            mapOf("personaIds" to personaIds, "text" to "what do you think?", "triggerMode" to "FANOUT"),
        )
        world.lastStatus = resp.statusCode.value()
        // Async: the POST returns N DRAFTING nodes; settle them all, then reassemble the room so the
        // posted/failed counts below see every node.
        val ids = Html.allReplyIds(resp.body ?: "")
        world.lastBody = settle.awaitAllSettled(ids)
    }

    @Then("exactly {int} replies are posted")
    fun postedCount(count: Int) =
        assertEquals(count, Html.countAttr(world.lastBody ?: "", "data-state", "posted"), ::roomReport)

    @Then("exactly {int} reply is failed")
    fun failedCount(count: Int) =
        assertEquals(count, Html.countAttr(world.lastBody ?: "", "data-state", "failed"), ::roomReport)

    /**
     * What the room actually looked like when a count came up short — every node with its state, plus
     * whether anything was still in flight. A bare `expected 2 but was 1` says a fan-out went wrong and
     * nothing about HOW, which is what made the ambient variant of this scenario an unreadable
     * intermittent failure for three sessions running.
     */
    private fun roomReport(): String {
        val body = world.lastBody ?: ""
        val nodes = Regex("data-reply-id=\"([^\"]+)\"[^>]*data-state=\"([^\"]+)\"")
            .findAll(body).map { "${it.groupValues[1].take(8)}=${it.groupValues[2]}" }.toList()
        val states = Regex("data-state=\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }
            .groupingBy { it }.eachCount()
        val seen = llm.received.map { it.persona.name }
        return "room: nodes=$nodes stateCounts=$states " +
            "summoning=${Html.hasAttr(body, "data-empty-state", "summoning")} " +
            "bodyChars=${body.length} llmCalls=$seen"
    }
}
