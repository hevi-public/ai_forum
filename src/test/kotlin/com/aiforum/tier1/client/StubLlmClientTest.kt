package com.aiforum.tier1.client

import com.aiforum.dto.ReasoningLeak
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.ContextComment
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.llm.PromptContext
import com.aiforum.llm.StubLlmClient
import com.aiforum.persona.InterestDrift
import com.aiforum.persona.InterestDriftPrompts
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

    /**
     * S4b: the interest-drift judgment has to come back as something [InterestDrift] ACCEPTS, and the
     * verdict is what is asserted rather than the string. Same failure this guards against as the stance
     * pair above — falling through to a canned essay, which the parse refuses as "not a
     * set-down-and-take-up pair", so every drift pass in a stub demo would silently do nothing. Digit-free
     * is asserted here rather than in its own test because a digit in the TAKE is not cosmetic: it is
     * refusal 4, so the same answer would fail the verdict assertion for a second reason.
     */
    @Test
    fun `interest judgments come back as a swap the parser accepts, carrying no digit`() {
        val open = listOf("typography", "small tools")
        val text = client.generate(request(driftInstruction(open), interestJudgeRef()), CancellationToken()).text

        assertTrue(text.none { it.isDigit() }, "canned interest judgment carried a digit: $text")
        assertTrue(
            InterestDrift.parse(text, open, PINNED) is InterestDrift.Verdict.Drifted,
            "the stub must answer a drift judgment with something the parser takes, got: $text",
        )
    }

    /**
     * The half a canned answer cannot fake: the DROP must name a phrase THIS member actually holds, which
     * means reading it back out of the prompt the stub was handed rather than inventing one. A stub that
     * invented a phrase would model a disobedient model — every run refused as "an interest this member
     * does not hold" — which is the same broken-looking demo the branch exists to prevent, one refusal
     * reason over. Repeated because the branch rotates its pick across the open list and across its
     * take-up candidates, so a single draw would leave most of that rotation unexercised.
     */
    @Test
    fun `every canned interest judgment sets down a phrase the member actually holds`() {
        val open = listOf("typography", "small tools", "boring technology choices")
        repeat(24) { i ->
            val prompt = driftInstruction(open, said = "the scheduler is the interesting part, take $i")

            val verdict = InterestDrift.parse(
                client.generate(request(prompt, interestJudgeRef()), CancellationToken()).text,
                open, PINNED,
            )

            assertTrue(verdict is InterestDrift.Verdict.Drifted, "canned judgment was not usable: $verdict")
            assertTrue((verdict as InterestDrift.Verdict.Drifted).dropped in open, "dropped a phrase Sol never held: $verdict")
        }
    }

    /**
     * A real judging prompt, built by the renderer the stub reads back — not a hand-written lookalike, so
     * a reworded instruction shows up here rather than only in a demo nobody is watching.
     *
     * [PINNED] deliberately names a phrase that is also one of the stub's take-up candidates: the branch
     * filters candidates already present anywhere in the prompt, and the pinned line is part of that
     * prompt, so this is what proves a stub answer can never reach for something the owner froze — which
     * the parse would refuse outright.
     */
    private fun driftInstruction(open: List<String>, said: String = "preemption cost decides this") =
        InterestDriftPrompts.instruction(
            member = "Sol",
            character = "A pragmatic backend engineer who distrusts hype.",
            pinned = PINNED,
            open = open,
            engagements = listOf(InterestDriftPrompts.Engagement("Rust in the kernel", said)),
        )

    private val PINNED = listOf("release engineering")

    private fun interestJudgeRef() = PersonaRef(InterestDriftPrompts.JUDGE_ID, InterestDriftPrompts.JUDGE_NAME)

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
