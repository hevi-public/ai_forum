package com.aiforum.llm

import com.aiforum.dto.ReasoningLeak
import com.aiforum.persona.StanceJudgePrompts
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * The `stub` provider: a deterministic, in-process [LlmClient] for demos and manual UX work — no model,
 * no network, no quota. It is NOT the test double (that's the `test` profile's ScriptableLlmClient,
 * programmed per scenario); this one self-drives: markdown-rich canned replies, a roster-aware dispatcher
 * answer so "Anyone" routing stays on the clean-match path, and comment-body triggers that let you walk
 * the whole failure taxonomy from the UI:
 *
 *   [stub:fail]      → ProcessError(1)          [stub:timeout]   → Timeout
 *   [stub:rate]      → RateLimited(30s)         [stub:empty]     → EmptyOutput
 *   [stub:malformed] → MalformedOutput          [stub:hang]      → blocks until cancelled
 *   [stub:slow]      → ~10s delay, then replies [stub:leak]      → reply tagged as a reasoning leak
 *
 * A trigger anywhere in the TARGET comment's body fires on every persona summoned to it. The reply delay
 * (default 1.5s) keeps the DRAFTING placeholder observable; it checks the cancellation token every 50ms
 * so Stop stays snappy, mirroring the real clients' cooperative-cancellation contract.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.llm", name = ["provider"], havingValue = "stub")
class StubLlmClient(
    @Value("\${aiforum.llm.stub.delay-millis:1500}") private val delayMillis: Long,
) : LlmClient {

    private val log = LoggerFactory.getLogger(StubLlmClient::class.java)

    // Round-robin tiebreaker so two personas answering the SAME comment still get different canned
    // bodies (the deterministic hash alone would collide for them).
    private val calls = AtomicInteger()

    override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
        if (request.persona.id == "dispatcher") return route(request)
        if (request.persona.id == StanceJudgePrompts.JUDGE_ID) return judgeStance(request)

        val target = targetBody(request.context)
        trigger(target)?.let { return applyTrigger(it, request, cancellation) }

        pause(delayMillis, cancellation)
        return LlmResponse(cannedReply(request))
    }

    /** The dispatcher path: pick 1–2 real names off the prompt's roster so routing clean-matches. */
    private fun route(request: LlmRequest): LlmResponse {
        val roster = ROSTER_LINE.findAll(request.context.personaSystemPrompt).map { it.groupValues[1].trim() }.toList()
        if (roster.isEmpty()) return LlmResponse("no roster found")
        val seed = abs(targetBody(request.context).hashCode())
        val picks = listOf(roster[seed % roster.size], roster[(seed / 7 + 1) % roster.size]).distinct()
        return LlmResponse(picks.joinToString(", "))
    }

    /**
     * The stance-judgment path (S4a, `plan_docs/ambient-slice-4a.md` D5): return ONE short digit-free
     * sentence, the shape [com.aiforum.persona.StanceJudge] accepts.
     *
     * Without this branch a judgment would fall through to [cannedReply] and come back as a
     * multi-paragraph forum essay — which the parser rejects on length, so nothing would be corrupted,
     * but every pass in a stub demo would silently do nothing and the feature would look broken to the
     * one person most likely to be trying it. Deliberately digit-free: the parser refuses any answer
     * carrying a number, so a stub that emitted one would model a *disobedient* backend rather than a
     * working one.
     */
    private fun judgeStance(request: LlmRequest): LlmResponse {
        val seed = abs(targetBody(request.context).hashCode())
        return LlmResponse(STANCES[(seed + calls.getAndIncrement()) % STANCES.size])
    }

    private fun applyTrigger(trigger: String, request: LlmRequest, cancellation: CancellationToken): LlmResponse {
        log.info("stub trigger [{}] firing for persona {}", trigger, request.persona.name)
        return when (trigger) {
            "fail" -> throw LlmException.ProcessError(1)
            "timeout" -> throw LlmException.Timeout()
            "rate" -> throw LlmException.RateLimited(Duration.ofSeconds(30))
            "empty" -> throw LlmException.EmptyOutput()
            "malformed" -> throw LlmException.MalformedOutput("stub: reply truncated mid-")
            "hang" -> {
                while (!cancellation.isCancelled) Thread.sleep(50)
                throw LlmException.Cancelled()
            }
            "slow" -> {
                pause(SLOW_MILLIS, cancellation)
                LlmResponse(cannedReply(request))
            }
            "leak" -> {
                pause(delayMillis, cancellation)
                LlmResponse(cannedReply(request), ReasoningLeak.ACTUAL)
            }
            else -> {
                pause(delayMillis, cancellation)
                LlmResponse(cannedReply(request))
            }
        }
    }

    /** Sleep in slices, honouring the cancellation token like the real clients' wait loops. */
    private fun pause(totalMillis: Long, cancellation: CancellationToken) {
        var remaining = totalMillis
        while (remaining > 0) {
            if (cancellation.isCancelled) throw LlmException.Cancelled()
            val slice = minOf(50L, remaining)
            Thread.sleep(slice)
            remaining -= slice
        }
        if (cancellation.isCancelled) throw LlmException.Cancelled()
    }

    private fun cannedReply(request: LlmRequest): String {
        val seed = abs((request.persona.id + targetBody(request.context)).hashCode())
        val body = BODIES[(seed + calls.getAndIncrement()) % BODIES.size]
        val quoted = quoteLine(targetBody(request.context))
        return if (quoted == null) body else "> $quoted\n\n$body"
    }

    /** The body the persona is answering: the marked target, else the most recent comment, else "". */
    private fun targetBody(context: PromptContext): String {
        val target = context.targetId?.let { id -> context.comments.firstOrNull { it.id == id } }
        return (target ?: context.comments.lastOrNull())?.body ?: ""
    }

    /** First non-blank, non-markdown-syntax line of the target, trimmed for a lead-in quote. */
    private fun quoteLine(body: String): String? =
        body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(">") && !it.startsWith("```") }
            ?.take(120)

    companion object {
        /** Matches the router prompt's roster lines: `- Name` or `- Name: descriptor…`. */
        private val ROSTER_LINE = Regex("""^- ([^:\n]+?)(?::|$)""", RegexOption.MULTILINE)

        private const val SLOW_MILLIS = 10_000L

        private fun trigger(body: String): String? =
            Regex("""\[stub:([a-z]+)]""").find(body)?.groupValues?.get(1)

        // Varied GFM so the rendering pipeline (headings, fences + highlighting, tables, task lists,
        // links, blockquotes) all get exercised by simply talking to the forum. Paragraphs are single
        // source lines on purpose: the renderer honours soft breaks GitHub-style (newline → <br>), so a
        // hard-wrapped string would show mid-sentence line breaks in the rendered reply.
        /**
         * Canned stance prose for the judgment path — short, in the holder's voice, and carrying no
         * digits so the parser accepts them (see [judgeStance]). Kept varied enough that a demo run
         * over several edges reads as a room whose relationships moved, rather than one sentence
         * pasted seven times.
         */
        private val STANCES = listOf(
            "has started treating their posts as claims to be checked rather than news to read",
            "warmed to them after that exchange, and would rather not admit it",
            "finds their certainty harder to take at face value than it used to be",
            "keeps meaning to push back properly and keeps deciding it is not worth the afternoon",
            "reads them now with an eyebrow already half raised",
            "has quietly started waiting for their reply before forming an opinion",
        )

        private val BODIES = listOf(
            """
            Good question — two things stand out immediately.

            First, the constraint you're describing is **structural**, not incidental: it falls out of how the pieces are wired, so patching around it will just move the seam. Second, the fix is smaller than it looks. I'd start here:

            ```kotlin
            fun reconcile(items: List<Item>): Plan =
                items.groupBy { it.owner }
                    .mapValues { (_, owned) -> owned.sortedBy { it.priority } }
                    .let(::Plan)
            ```

            The grouping step is the whole trick — once ownership is explicit, the ordering question answers itself.
            """,
            """
            I'd frame the trade-off as a table before deciding anything:

            | Option | Cost | Wins when |
            |--------|------|-----------|
            | Do it inline | low | the caller list is short |
            | Extract a service | medium | ≥3 call sites need it |
            | Full seam + fake | higher | you need it testable in isolation |

            My vote is the middle row. Extracting early is cheap insurance, and the seam can come later *if* the IO ever becomes real — [YAGNI](https://martinfowler.com/bliki/Yagni.html) cuts both ways here.
            """,
            """
            ## Short version

            It works, but it's doing two jobs at once.

            - The *decision* (what to change) and the *effect* (writing it out) are interleaved
            - That's why the error path feels awkward — you're unwinding half-applied state
            - Split them: decide fully, then apply atomically

            The pattern goes by "functional core, imperative shell". Once the core is pure, the tests stop needing mocks entirely — you assert on the returned plan, not on side effects.
            """,
            """
            Careful — there's an edge case hiding in the happy path.

            > What happens when the input is *valid but empty*?

            Empty is not an error: it should produce an empty result, not a failure. I'd pin that with a test first, because it's exactly the kind of behaviour that silently changes during a refactor:

            ```python
            def test_empty_input_yields_empty_plan():
                assert plan([]) == Plan(steps=[])
            ```

            Everything else in the approach looks sound to me. Ship the test, then the change.
            """,
            """
            Agreed with the direction, one refinement: name the intermediate state.

            Right now the value flows through three transformations anonymously, and every reader has to mentally re-derive what it *is* between steps. A single well-named `val` in the middle — something like `eligibleCandidates` — costs one line and saves every future reading of it.

            Naming is cheap. Re-deriving is not. That's the whole review, honestly — the logic itself is right.
            """,
            """
            Three checks before this ships:

            1. **Idempotence** — run it twice; the second run must be a no-op, not a duplicate
            2. **Ordering** — the results look order-dependent; either sort explicitly or assert the invariant
            3. **The `null` row** — the legacy table has them, and `firstOrNull()?.let { }` will skip silently

            The third one is the sleeper. Silent skips pass every test you'll think to write and then eat one record a week in production. Log loudly on that branch even if you keep the skip.
            """,
        ).map { it.trimIndent().trim() }
    }
}
