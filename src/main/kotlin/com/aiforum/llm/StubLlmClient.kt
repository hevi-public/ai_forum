package com.aiforum.llm

import com.aiforum.dto.ReasoningLeak
import com.aiforum.persona.InterestDrift
import com.aiforum.persona.InterestDriftPrompts
import com.aiforum.persona.MemoryScribePrompts
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
        if (request.persona.id == InterestDriftPrompts.JUDGE_ID) return judgeInterest(request)
        if (request.persona.id == MemoryScribePrompts.SCRIBE_ID) return judgeMemory(request)

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

    /**
     * The interest-drift judgment path (S4b, `plan_docs/ambient-slice-4b.md` D5): answer with a
     * `DROP:`/`TAKE:` pair [com.aiforum.persona.InterestDrift] accepts, or the settled sentinel.
     *
     * Exists for [judgeStance]'s reason, one slice on: without it a judgment falls through to
     * [cannedReply] and comes back as a multi-paragraph forum essay, which the parse refuses as "not a
     * set-down-and-take-up pair" — nothing is corrupted, but every drift pass in a stub demo silently does
     * nothing and the feature looks broken in the one mode somebody is most likely to try it in.
     *
     * **The DROP is read back out of the instruction rather than invented.** The parse refuses a phrase
     * the member does not hold, so a stub that made one up would model a *disobedient* model and every
     * stub run would be a rejection — the same failure the branch exists to prevent, one refusal reason
     * over. Same for the TAKE: candidates already named anywhere in the prompt are filtered out, because
     * re-taking a phrase the member holds is refused as a degenerate swap (I3), and the pinned line is
     * part of that prompt so a pinned phrase can never be reached for either.
     *
     * The one coupling this buys is to [InterestDriftPrompts.instruction]'s wording. It degrades safely:
     * a reworded open-interests line (or a phrase containing the `", "` the line joins on) simply yields
     * no parseable candidates and the answer becomes NONE — a settled member rather than a corrupt swap.
     * Deliberately digit-free for the reason [judgeStance] states, and free of the `vote` substring
     * because these phrases are one owner edit away from a generation prompt.
     */
    private fun judgeInterest(request: LlmRequest): LlmResponse {
        val instruction = targetBody(request.context)
        val openLine = OPEN_INTERESTS.find(instruction)?.groupValues?.get(1).orEmpty()
        val open = openLine.split(", ").map { it.trim() }.filter { it.isNotEmpty() && it != NO_INTERESTS }
        // Nothing droppable found: the member is either unjudgeable or the prompt shape moved under us.
        // Either way "nothing moved" is the honest answer and the only one that cannot corrupt a row.
        if (open.isEmpty()) return LlmResponse(InterestDrift.NOTHING_MOVED)
        val fresh = TAKE_UPS.filterNot { instruction.contains(it, ignoreCase = true) }
        if (fresh.isEmpty()) return LlmResponse(InterestDrift.NOTHING_MOVED)
        // The same hash + round-robin tiebreak the other canned paths use, so a demo run over several
        // members reads as a room that moved rather than one swap pasted seven times. `floorMod` rather
        // than `abs(...) % size`: `abs(Int.MIN_VALUE)` is still negative, and this index is the one place
        // in the branch that would turn a hash collision into a thrown exception instead of a judgment.
        val seed = instruction.hashCode() + calls.getAndIncrement()
        val drop = open[Math.floorMod(seed, open.size)]
        val take = fresh[Math.floorMod(seed, fresh.size)]
        return LlmResponse("DROP: $drop\nTAKE: $take")
    }

    /**
     * The memory-scribe path (plan_docs/persona-memory.md §2.13): answer with a `REMEMBER:` line
     * [com.aiforum.persona.ScribeAnswer] accepts. Exists for [judgeStance]'s reason, two slices on:
     * without it a judgment falls through to [cannedReply] and comes back as a multi-paragraph forum
     * essay, which the parse refuses — nothing is corrupted, but every scribe pass in a stub demo
     * silently does nothing and the feature looks broken in the one mode somebody is most likely to
     * try it in.
     *
     * The canned bodies are digit-free (rating shapes are refused at the parse, and skipping digits
     * wholesale is the cheapest way for a stub to model an OBEDIENT backend), free of the `vote`
     * substring (a scribe body lands in `persona_memory` and is injected into that member's next
     * generation prompt, where the firewall scans exactly that substring), and fixed points of
     * `MemoryText.clean` (single-line, single-spaced, trimmed) so the parse never refuses a stub
     * answer as un-cleaned. No `EXTENDS` line: top-level attachment is always legal, while a guessed
     * letter against an unseen list would model a disobedient model on every second draw.
     */
    private fun judgeMemory(request: LlmRequest): LlmResponse {
        val seed = abs(targetBody(request.context).hashCode())
        return LlmResponse("REMEMBER: " + MEMORIES[(seed + calls.getAndIncrement()) % MEMORIES.size])
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

        /**
         * Pulls the droppable phrases out of the rendered judging prompt — the line
         * [InterestDriftPrompts.instruction] writes as `Interests that are open to change: a, b`. Matched
         * loosely (a prefix, not the whole line) so a wording tweak on either side of the colon does not
         * silently take the stub back to canned essays; a change to the LABEL itself is what degrades this
         * to NONE, which is the safe direction.
         */
        // No trailing `$`: `.` never matches a newline, so `(.+)` already stops at the end of the line.
        private val OPEN_INTERESTS = Regex("""^Interests that are open to change: *(.+)""", RegexOption.MULTILINE)

        /** What [InterestDriftPrompts] renders for a member holding none — never a droppable phrase. */
        private const val NO_INTERESTS = "(none)"

        /**
         * Canned interests for the drift path — short prose in a member's own voice, carrying no digit
         * (the parse refuses one) and no `vote` substring, which also rules out *devoted*, *pivoted* and
         * *voting*: a taken-up phrase lands in `persona_interest` and is injected into that member's next
         * generation prompt, where `OwnerControlSteps.noVoteSignal` scans for exactly that substring.
         *
         * Kept broad and non-overlapping with the seeded phrases in `application.yml` so a stub demo run
         * over the whole roster has something to take up for everyone — a candidate already named anywhere
         * in the prompt is filtered out, because taking up a phrase the member holds is a refused swap.
         */
        private val TAKE_UPS = listOf(
            "kernel scheduling",
            "release engineering",
            "the cost of a bad abstraction",
            "what nobody benchmarks",
            "how teams decide things",
            "reading other people's code",
        )

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
        /**
         * Canned memory records for the scribe path — one sentence of first-person experiential
         * prose each, digit-free, `vote`-free (rules out *devoted*, *pivoted*, *voting* — these
         * bodies reach generation prompts the firewall scans), and already in `MemoryText.clean`'s
         * fixed-point form. Varied so a demo run over the roster reads as a room that lived through
         * different weeks rather than one sentence pasted seven times.
         */
        private val MEMORIES = listOf(
            "Watched a benchmark argument collapse the moment someone posted real measurements",
            "Learned that the quiet threads are where the actual decisions get made here",
            "Spent a week defending an unpopular position and ended up half-convinced myself",
            "Noticed that every scheduler debate in this room eventually turns into a naming debate",
            "Came away from that migration thread trusting boring rollouts more than clever ones",
            "Realised nobody in the room reads the linked article before the third reply",
        )

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
