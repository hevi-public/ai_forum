package com.aiforum.tier1.client

import com.aiforum.dto.ReasoningLeak
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.ContextComment
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.llm.PromptContext
import com.aiforum.llm.StubLlmClient
import com.aiforum.persona.StanceJudge
import com.aiforum.persona.StanceJudgePrompts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import kotlin.concurrent.thread

/**
 * Tier-1: the stub provider's own contract — it stands in for a model in demos, so its behaviours
 * (canned markdown, roster-aware dispatcher picks, the [stub:*] failure triggers, cooperative
 * cancellation) are what a demo run actually exercises and must hold like any other adapter's.
 */
@Tag("tier1")
class StubLlmClientTest {

    // Zero delay so the suite stays fast; the pause loop still runs its cancellation check.
    private val client = StubLlmClient(delayMillis = 0)

    private fun request(body: String, persona: PersonaRef = PersonaRef("sol", "Sol")): LlmRequest {
        val comment = ContextComment(id = "c1", authorId = "owner", body = body, parentId = null, depth = 0)
        return LlmRequest(PromptContext("be helpful", listOf(comment), targetId = "c1"), persona, Duration.ofSeconds(5))
    }

    @Test
    fun `replies with markdown that quotes the target comment`() {
        val response = client.generate(request("How should I structure this?"), CancellationToken())
        assertTrue(response.text.startsWith("> How should I structure this?"), "should lead with a quote of the target")
        assertTrue(response.text.length > 100, "canned bodies are substantial")
        assertEquals(null, response.reasoningLeak)
    }

    @Test
    fun `consecutive replies to the same target differ`() {
        val first = client.generate(request("same question"), CancellationToken()).text
        val second = client.generate(request("same question"), CancellationToken()).text
        assertNotEquals(first, second, "round-robin should vary bodies for personas answering the same comment")
    }

    @Test
    fun `dispatcher requests get a pick the router can parse`() {
        val prompt = """
            You are the forum's dispatcher. Respond with ONLY their names.

            Roster:
            - Sol: backend; skills: kotlin, sql
            - Dana: design
            - Paul: QA
        """.trimIndent()
        val comment = ContextComment("c1", "owner", "Who should take this?", null, 0)
        val req = LlmRequest(PromptContext(prompt, listOf(comment), "c1"), PersonaRef("dispatcher", "Moderator"), Duration.ofSeconds(5))

        val reply = client.generate(req, CancellationToken()).text

        val names = reply.split(",").map { it.trim() }
        assertTrue(names.isNotEmpty() && names.all { it in setOf("Sol", "Dana", "Paul") }, "picked unknown names: $reply")
    }

    /**
     * S4a: a stance judgment must come back in a shape [StanceJudge] actually ACCEPTS. Asserting the
     * verdict rather than the string is the point — the failure this guards against is the stub falling
     * through to a canned essay, which the parser rejects on length, so every evolution pass in a stub
     * demo would silently do nothing and the feature would look broken to whoever was trying it.
     */
    @Test
    fun `stance judgments come back as prose the judge accepts`() {
        val req = request("Paul: that benchmark measures the wrong thing", judgeRef())

        val verdict = StanceJudge.parse(client.generate(req, CancellationToken()).text, current = "kindred pessimist")

        assertTrue(
            verdict is StanceJudge.Verdict.Changed,
            "the stub must answer a judgment with something the parser takes, got: $verdict",
        )
    }

    /** The no-numbers guardrail reaches the demo backend too: a stub answer carrying a digit would model
     *  a DISOBEDIENT model rather than a working one, and every stub pass would be a rejection. */
    @Test
    fun `no canned stance carries a digit, whichever one is drawn`() {
        repeat(24) { i ->
            val text = client.generate(request("exchange number $i", judgeRef()), CancellationToken()).text
            assertTrue(text.none { it.isDigit() }, "canned stance carried a digit: $text")
            assertTrue(
                StanceJudge.parse(text, current = "unrelated") is StanceJudge.Verdict.Changed,
                "canned stance was not acceptable prose: $text",
            )
        }
    }

    private fun judgeRef() = PersonaRef(StanceJudgePrompts.JUDGE_ID, StanceJudgePrompts.JUDGE_NAME)

    @Test
    fun `failure triggers map onto the taxonomy`() {
        assertThrows(LlmException.ProcessError::class.java) { client.generate(request("please [stub:fail] now"), CancellationToken()) }
        assertThrows(LlmException.Timeout::class.java) { client.generate(request("[stub:timeout]"), CancellationToken()) }
        assertThrows(LlmException.RateLimited::class.java) { client.generate(request("[stub:rate]"), CancellationToken()) }
        assertThrows(LlmException.EmptyOutput::class.java) { client.generate(request("[stub:empty]"), CancellationToken()) }
        assertThrows(LlmException.MalformedOutput::class.java) { client.generate(request("[stub:malformed]"), CancellationToken()) }
    }

    @Test
    fun `leak trigger tags the reply as an actual reasoning leak`() {
        val response = client.generate(request("[stub:leak] show me the badge"), CancellationToken())
        assertEquals(ReasoningLeak.ACTUAL, response.reasoningLeak)
    }

    @Test
    fun `a pre-cancelled token yields Cancelled even at zero delay`() {
        val token = CancellationToken().apply { cancel() }
        assertThrows(LlmException.Cancelled::class.java) { client.generate(request("anything"), token) }
    }

    @Test
    fun `hang trigger blocks until the token is tripped, then reports Cancelled`() {
        val token = CancellationToken()
        thread { Thread.sleep(100); token.cancel() }
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            assertThrows(LlmException.Cancelled::class.java) { client.generate(request("[stub:hang]"), token) }
        }
    }

    @Test
    fun `dispatcher pick clean-matches under the router's word-boundary parse`() {
        val roster = "Roster:\n- Sol: backend\n- Dana: design\n"
        val comment = ContextComment("c1", "owner", "routing check", null, 0)
        val req = LlmRequest(PromptContext(roster, listOf(comment), "c1"), PersonaRef("dispatcher", "Moderator"), Duration.ofSeconds(5))

        val reply = client.generate(req, CancellationToken()).text

        // The same word-boundary regex PersonaRouter.parseChosen applies per roster name: at least one
        // name must match, or routing would widen to the whole room and skew the parse-miss rate.
        val names = Regex("\\b(Sol|Dana)\\b").findAll(reply).map { it.value }.toSet()
        assertTrue(names.isNotEmpty(), "router would widen to the whole room on: $reply")
    }
}
