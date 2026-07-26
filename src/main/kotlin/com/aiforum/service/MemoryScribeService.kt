package com.aiforum.service

import com.aiforum.config.MemoryProperties
import com.aiforum.domain.Comment
import com.aiforum.domain.context.ContextAssembler
import com.aiforum.dto.GenerationState
import com.aiforum.dto.Snippet
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.persona.MemoryRecall
import com.aiforum.persona.MemoryScribePrompts
import com.aiforum.persona.MemoryText
import com.aiforum.persona.ScribeAnswer
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.MemoryChangeRepository
import com.aiforum.repo.PersonaExchange
import com.aiforum.repo.PersonaMemory
import com.aiforum.repo.PersonaMemoryRepository
import com.aiforum.repo.PersonaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Who fired a scribe pass: the owner's hand from /admin/memory (MANUAL, always available) or the
 * gated `@Scheduled` caller (SCHEDULED). Its own enum rather than a shared one, for the reason
 * [MemoryProperties] has its own prefix: the four loops are independently switchable, and nothing
 * should be able to pass one loop's source into another's by accident.
 *
 * **Never persisted, and specifically never as a row in `ambient_run`** (I2, pinned behaviourally
 * by scenario 21): `AmbientRunRepository.count()` drives the ambient tick's post/comment parity AND
 * its round-robin author index, so a single extra row would silently buy this pass airtime it must
 * never hold. The source reaches the log and nothing else.
 */
enum class ScribeSource { MANUAL, SCHEDULED }

/**
 * The Memory Scribe (plan_docs/persona-memory.md §2.4) — the third instance of the S4a/S4b
 * evolution-pass anatomy, copied joint by joint from [InterestDriftService]: read what one member
 * lived through since the pass last LOOKED, ask the model whether ONE experience is worth keeping,
 * and append at most one prose memory record — auto-applied, never queued, every write captured in
 * `memory_change` with its cited evidence so the owner can read and revert it on /admin/memory.
 *
 * Four promises, each held by construction rather than by rule:
 *
 * - **No write path to identity (I3).** This class holds no [PersonaRepository.update] call and no
 *   [PersonaMemoryRepository.insertRoot] call — there is no method shape here to say either with,
 *   and the Tier-2 failing fakes pin BOTH (every identity-adjacent write path, not just one).
 * - **Blinkers (convergence guardrail).** One member's judgment sees that member's own evidence and
 *   its own record bodies, nothing else — enforced by what [MemoryScribePrompts.instruction] has no
 *   parameter for, and pinned Tier 2 by a byte-identical instruction over a memory-rich vs
 *   memory-empty room.
 * - **Memory never buys airtime (I2).** The pass writes zero `ambient_run` rows and never touches
 *   `AmbientGate`, `PersonaRouter` or `AmbientTickService` — pinned behaviourally (scenario 21:
 *   tick parity, home page and both rails byte-unchanged across a run that wrote a record).
 * - **No number persists (I4).** V28 has no numeric column; [ScribeAnswer] refuses rating-shaped
 *   lines at the parse; nothing here counts, ranks or aggregates anything a model could feed.
 *
 * ## Cost, and what holds it down
 *
 * One LLM call per member the pass actually judges, and nothing else — no recompose, no retrieval
 * call, ever. Three free skips are decided in [candidates] BEFORE the cap ([memory.skip.no_exchanges],
 * below the engagement floor, at the 24-record capacity), so a skip can never eat the budget of a
 * member that would have been judged. The watermark closes on any USABLE answer — a record, NOTHING
 * and the duplicate refusal alike (§2.5's five-posture table) — which is the V26 cost lesson built
 * in on day one: NOTHING is the designed steady state and writes no audit row to advance a window
 * with, so anything less than stamping it re-buys the same judgment weekly, forever.
 *
 * ## The 90-day horizon (D6b) — the dead-coarseFloor class, killed by construction
 *
 * Both prior slices shipped a coarse SQL floor dead under config: one never-stamped member keeps it
 * NULL forever, so every run materialises all-time history. Here the evidence read is bounded by
 * `max(coarseFloor, readAt − max-lookback-days)` — a hard horizon a null window cannot defeat —
 * and each member's own narrowing uses `max(watermark, horizon)`. A member does not consolidate
 * evidence older than the lookback, regardless of window state.
 */
@Service
class MemoryScribeService(
    private val comments: CommentRepository,
    private val personas: PersonaRepository,
    private val memories: PersonaMemoryRepository,
    private val changes: MemoryChangeRepository,
    private val llm: LlmClient,
    // The audit row, the record insert and the watermark stamp are one unit of work. Injected
    // explicitly rather than annotating a method `@Transactional`: the write path is a private
    // method reached by self-invocation, and a Spring proxy sees neither — the annotation would
    // compile, read as a guarantee, and do absolutely nothing (the InterestDriftService argument).
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val props: MemoryProperties,
) {

    // Never `javaClass`: the class-literal form is what LogCapture binds to, and a subclassed or
    // proxied instance would otherwise log under a name no test is listening on (§2.16).
    private val log = LoggerFactory.getLogger(MemoryScribeService::class.java)

    /**
     * Whether a pass is in flight, for the single-flight guard in [consolidate]. An [AtomicBoolean]
     * rather than a lock because a second caller must NOT queue behind the first: waiting would
     * hand the owner exactly the second pass they must not get, just later.
     */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /** One member that survived qualification: worth a judgment, with the engagements its own
     *  window-bounded horizon admits. [judgedAt] is null for a never-judged member (or an
     *  unreadable stamp — same reading, and the horizon bounds it either way, §2.6). */
    private data class Candidate(
        val persona: PersonaRepository.Persona,
        val judgedAt: Instant?,
        val evidence: List<PersonaExchange>,
    )

    /**
     * Run one pass and return how many records were written. A second concurrent caller does
     * NOTHING and returns 0 — the manual trigger is a synchronous POST over a pass with no cap by
     * default, so "the browser gave up, click it again" is the normal way a second pass starts.
     *
     * The whole body is `try/catch (Exception)` — never `runCatching`, which catches `Throwable`
     * and would keep the run spending LLM calls on a JVM that is already broken — and it never
     * rethrows: a rate limit at 05:00 on a Sunday is a recorded outcome, not an unhandled scheduled
     * failure. Each member carries its own catch below, so one bad member costs one member.
     *
     * **The clock is read once, BEFORE the evidence query** (§2.6, the bed019fe read-instant rule).
     * That instant becomes the watermark of every member this run stamps AND the `read_at` on every
     * audit row: read-first's worst case is judging one engagement twice; read-after loses one
     * permanently.
     */
    fun consolidate(source: ScribeSource): Int {
        if (!running.compareAndSet(false, true)) {
            log.warn("event=memory.run.skipped source={} reason=already-running", source.name.lowercase())
            return 0
        }
        var written = 0
        log.info("event=memory.run.start source={}", source.name.lowercase())
        try {
            val readAt = clock.instant()
            val readAtStamp = readAt.toString()
            // The hard evidence horizon (D6b): exact per-member bound; the SQL floor gets the same
            // instant coarsened below. Clamped at the use site — a zero or negative override must
            // not turn "consolidate nothing, ever" or "read all time" into a configuration.
            val horizon = readAt.minus(maxOf(1, props.maxLookbackDays).toLong(), ChronoUnit.DAYS)
            val roster = personas.findAll()
            // Every member's window, read once so ordering and narrowing share one consistent view.
            // Read through PersonaMemoryRepository — the column's one reader and writer (§2.2), so
            // PersonaRepository.update can never be tidied into stamping it.
            val windows = roster.associate { it.id to memories.judgedAt(it.id) }
            val queue = candidates(comments.exchangesSince(since(roster, windows, horizon)), roster, windows, horizon)
            queue.take(memberCap()).forEach { candidate ->
                // One bad member costs ONE member: a locked DB on the third member must not abandon
                // the fourth. The LLM seam has its own guard inside [judge]; this one is storage's.
                try {
                    if (scribeMember(candidate, readAtStamp)) written++
                } catch (e: Exception) {
                    log.error(
                        "event=memory.member.failed persona={} reason={}",
                        candidate.persona.id, e.message ?: e.javaClass.simpleName, e,
                    )
                }
            }
            log.info(
                "event=memory.run.finished source={} candidates={} written={}",
                source.name.lowercase(), minOf(queue.size, memberCap()), written,
            )
        } catch (e: Exception) {
            log.error(
                "event=memory.run.failed source={} reason={}",
                source.name.lowercase(), e.message ?: e.javaClass.simpleName, e,
            )
        } finally {
            // In a `finally`, so a fault on the framing reads cannot leave the guard latched and
            // every later pass returning 0 for the lifetime of the JVM.
            running.set(false)
        }
        return written
    }

    /**
     * Undo one audited write: delete the scribe-written record (reparenting its children to the
     * grandparent, §2.10) and stamp the audit row, one transaction. Returns whether anything was
     * undone.
     *
     * **Re-read at the action site** (the bed019fe rule's second application in this slice): the
     * audit row records what was true when the pass ran; the owner has had every minute since to
     * delete the record from the profile. A row that no longer exists — or whose body no longer
     * equals the audit snapshot (impossible today, records are never edited in place, but the guard
     * is one comparison) — is SKIPPED, `reason=superseded`, and the audit row survives un-reverted,
     * which is the honest state. `reverted_at IS NULL` in the repository's SQL is the double-revert
     * guard.
     *
     * **Revert does NOT roll the watermark back** — the argued D10 departure from S4a/S4b. There,
     * rollback re-derives lost prior state; here revert is pure deletion, and rollback would
     * GUARANTEE the next run re-reads the same evidence and re-manufactures the row the owner just
     * killed: an owner-fight loop. The evidence stays consumed; a genuinely new memory inside the
     * consumed window is also lost, accepted at one record per member per week.
     */
    fun revert(changeId: Long): Boolean {
        val change = changes.find(changeId)
        if (change == null) {
            log.warn("event=memory.revert.skipped change={} reason=unknown-change", changeId)
            return false
        }
        if (change.revertedAt != null) {
            log.info("event=memory.revert.skipped change={} reason=already-reverted", changeId)
            return false
        }
        val current = memories.find(change.memoryId)
        if (current == null || current.body != change.body) {
            log.warn(
                "event=memory.revert.skipped change={} persona={} reason=superseded",
                changeId, change.personaId,
            )
            return false
        }
        transactions.execute {
            // deleteRecord is @Transactional REQUIRED, so it JOINS this transaction rather than
            // opening a second — the reparent, the delete and the revert stamp land as one unit.
            memories.deleteRecord(change.memoryId)
            changes.markReverted(changeId)
        }
        log.info("event=memory.reverted change={} persona={}", changeId, change.personaId)
        return true
    }

    /**
     * The members worth judging this run, oldest window first ([BY_WINDOW_AGE] — never-judged (null)
     * ahead of everything, persona id as the deterministic tiebreak), so a biting cap ROTATES:
     * whatever a run judges is stamped, drops to the back, and the next run reaches further down.
     *
     * Evidence is what the member LIVED THROUGH: engagements *involving* the member, either
     * direction (§2.4 — memory is about experience, not only outgoing speech), narrowed per member
     * to `max(watermark, horizon)` in memory on parsed instants. Membership is decided against the
     * ROSTER — this iterates it, so `owner`, `system` and `gh:` authors can never become candidates.
     *
     * **The three free skips are decided here, not after the cap** (§2.4/§2.11), each with its own
     * §2.16 event: a member with nothing new inside its horizon, one below the engagement floor,
     * and one already holding [MAX_SCRIBE_MEMORIES] scribe rows all cost zero seam calls — and none
     * of them may occupy a `take(cap)` slot a judgeable member needed.
     */
    private fun candidates(
        engagements: List<PersonaExchange>,
        roster: List<PersonaRepository.Persona>,
        windows: Map<String, Instant?>,
        horizon: Instant,
    ): List<Candidate> {
        val minimum = maxOf(1, props.minEngagements)
        return roster.mapNotNull { member ->
            val window = windows[member.id]
            val floor = if (window == null || window.isBefore(horizon)) horizon else window
            val evidence = engagements.filter {
                (it.fromAuthor == member.id || it.toAuthor == member.id) && isAfter(it.createdAt, floor)
            }
            when {
                evidence.isEmpty() -> {
                    log.info("event=memory.skip.no_exchanges persona={}", member.id)
                    null
                }

                evidence.size < minimum -> {
                    log.info(
                        "event=memory.skip.below_floor persona={} engagements={}",
                        member.id, evidence.size,
                    )
                    null
                }

                atCapacity(member.id) -> {
                    // §2.11: the ceiling bounds the MODEL, so it is checked before any spend and
                    // owner rows are uncounted. The owner deletes to make room.
                    log.info("event=memory.skip.at_capacity persona={}", member.id)
                    null
                }

                else -> Candidate(member, window, evidence)
            }
        }.sortedWith(compareBy(BY_WINDOW_AGE) { it.judgedAt to it.persona.id })
    }

    /**
     * Judge one member and (maybe) write one record; returns whether a record landed. The five
     * postures of §2.5, three stamp behaviours:
     *
     * - a well-formed record → audit row + insert + stamp in ONE `TransactionTemplate.execute`;
     * - `NOTHING` → no row, **stamped** (the designed steady state — the V26 cost lesson);
     * - a duplicate → refused as a row, **stamped** (the model did its job; re-buying the identical
     *   judgment weekly is the exact V26 defect shape);
     * - a malformed / rating-shaped answer → rejected, **not stamped** — re-judged next run (the
     *   persistently-refused member holding its rotation slot is S4a's characterised limitation);
     * - a seam failure → nothing, **not stamped** (a rate limit is not a verdict).
     *
     * [readAt] is the run's pre-query read instant — stamped here at the judgment site, never
     * inferred from "a call happened", and carried onto the audit row so the contract is auditable
     * per row rather than trusted (§2.6).
     */
    private fun scribeMember(candidate: Candidate, readAt: String): Boolean {
        val member = candidate.persona
        // The snapshot the model is SHOWN: records newest-first, capped to the letter alphabet.
        // Re-sorted on PARSED instants before the cut rather than trusted from the SQL `ORDER BY`,
        // which is lexicographic and only NEAR-chronological — the rule PersonaMemoryRepository's
        // KDoc states for exactly this case ("any caller whose CUT depends on the order parses
        // instants instead"). It binds once a member holds more rows than there are letters, which
        // is reachable: a judged member holds up to 23 scribe rows (24 is a free skip) plus owner
        // rows the ceiling does not count at all, and past the alphabet a same-second boundary in
        // the string order drops a genuinely newer record off the end of it. The letter
        // resolves against THIS map — what the model actually saw — and the resolved id is
        // re-verified against a fresh read at write time (the bed019fe third application).
        val offered = memories.recordsOf(member.id)
            .sortedWith(MemoryRecall.NEWEST_FIRST)
            .take(MemoryScribePrompts.MAX_PARENT_LETTERS)
        val lettered = offered.mapIndexed { i, row -> ('A' + i).toString() to row }.toMap()
        // ONE list feeds both the prompt and the citation, so the audit shows the owner exactly the
        // evidence the model was given rather than a superset of it. Sorted for the same reason the
        // letter list is: `takeLast` keeps the NEWEST tail, and over the raw SQL order a same-second
        // pair hands that tail the fractionally OLDER engagement while dropping its newer sibling —
        // the opposite of what [MAX_EVIDENCE_ENGAGEMENTS] promises.
        val shown = candidate.evidence.sortedWith(BY_STAMP).takeLast(MAX_EVIDENCE_ENGAGEMENTS)
        val raw = judge(member, offered.map { it.body }, shown) ?: return false
        return when (val verdict = ScribeAnswer.parse(raw)) {
            is ScribeAnswer.Verdict.NothingToRemember -> {
                // No row, but the window MUST close, or this member re-buys this same judgment on
                // every run for as long as the forum runs. One statement, no transaction needed.
                memories.markJudged(member.id, readAt)
                log.info("event=memory.nothing persona={}", member.id)
                false
            }

            is ScribeAnswer.Verdict.Rejected -> {
                // Not an error: the run continues, and the window stays OPEN so the same evidence
                // is re-judged next time. The raw answer is kept so an operator can see WHY.
                log.warn(
                    "event=memory.rejected persona={} reason={} raw={}",
                    member.id, verdict.reason, raw.trim(),
                )
                false
            }

            is ScribeAnswer.Verdict.Remember -> writeRecord(member, verdict, lettered, shown, readAt)
        }
    }

    /**
     * The paid write, decided against a FRESH read of the member's current rows — the pass is
     * synchronous and spends up to a minute inside the seam call, and the profile in another tab is
     * where the owner goes while they wait (bed019fe: never act on a pre-call snapshot when the
     * world can move under a paid call).
     *
     * Duplicate first (§2.5): the cleaned, case-folded body — [MemoryText.fold], the ONE canonical
     * fold; SQLite's ASCII-only NOCASE never participates — is compared against ALL held rows,
     * owner records and the root included. A duplicate refuses the ROW, never the STAMP.
     *
     * Then the parent: an out-of-set letter degrades to top-level (`memory.parent.unknown` — a
     * broken decoration never costs a paid, well-formed record), and a letter whose record vanished
     * mid-pass degrades likewise (`memory.parent.vanished`). Then ONE transaction: audit row FIRST
     * (the snapshot is what makes the write revertable), then the insert, then the stamp.
     */
    private fun writeRecord(
        member: PersonaRepository.Persona,
        verdict: ScribeAnswer.Verdict.Remember,
        lettered: Map<String, PersonaMemory>,
        shown: List<PersonaExchange>,
        readAt: String,
    ): Boolean {
        val fresh = memories.recordsOf(member.id)
        val fold = MemoryText.fold(verdict.body)
        val held = fresh + listOfNotNull(memories.rootOf(member.id))
        if (held.any { MemoryText.fold(it.body) == fold }) {
            memories.markJudged(member.id, readAt)
            log.info("event=memory.duplicate_refused persona={}", member.id)
            return false
        }
        val parent = resolveParent(member.id, verdict.extends, lettered, fresh)
        val memoryId = UUID.randomUUID().toString()
        val recorded = transactions.execute {
            val id = changes.record(
                personaId = member.id,
                memoryId = memoryId,
                body = verdict.body,
                parentBody = parent?.body,
                cited = renderCited(shown),
                readAt = readAt,
            )
            memories.insertScribeRecord(member.id, verdict.body, parent?.id, memoryId)
            memories.markJudged(member.id, readAt)
            id
        }
        log.info(
            "event=memory.written change={} persona={} memory={} parent={}",
            recorded, member.id, memoryId, parent?.id ?: "(top-level)",
        )
        return true
    }

    /**
     * The letter → parent-id resolution, then the judgment-site re-read. The letter resolves
     * against the snapshot map the model was actually shown ([lettered]); the resolved id is then
     * re-checked against [fresh], the member's current rows read at write time — if the chosen
     * parent vanished mid-pass (the owner deleted it during the sixty-second call), the record
     * degrades to top-level rather than failing a paid, well-formed answer.
     */
    private fun resolveParent(
        personaId: String,
        selector: String?,
        lettered: Map<String, PersonaMemory>,
        fresh: List<PersonaMemory>,
    ): PersonaMemory? {
        if (selector == null) return null
        val snapshot = lettered[selector]
        if (snapshot == null) {
            log.info("event=memory.parent.unknown persona={} selector={}", personaId, selector)
            return null
        }
        val current = fresh.firstOrNull { it.id == snapshot.id }
        if (current == null) {
            log.warn("event=memory.parent.vanished persona={} parent={}", personaId, snapshot.id)
            return null
        }
        return current
    }

    /**
     * One scribe judgment on the single shared [LlmClient] seam — no second IO port — tagged with
     * the synthetic [MemoryScribePrompts] identity so a spy can tell a judgment apart from a reply.
     * The instruction goes through [ContextAssembler.assemble] rather than a hand-built context, so
     * the owner-vote firewall keeps holding for this caller for free.
     *
     * Blocking `generate(request, CancellationToken())` with a fresh token and no sink. A seam
     * failure returns null — logged as a SEAM failure, distinct from an unusable answer — and the
     * member is left exactly as it was, watermark included, so the next run tries the same evidence
     * again: a rate limit is not a verdict.
     */
    private fun judge(
        member: PersonaRepository.Persona,
        ownRecords: List<String>,
        evidence: List<PersonaExchange>,
    ): String? {
        val instruction = MemoryScribePrompts.instruction(
            member = member.name,
            engagements = engagementsOf(evidence),
            ownRecords = ownRecords,
        )
        val request = LlmRequest(
            context = ContextAssembler.assemble(MemoryScribePrompts.SYSTEM, listOf(judgmentTurn(instruction))),
            persona = PersonaRef(MemoryScribePrompts.SCRIBE_ID, MemoryScribePrompts.SCRIBE_NAME),
            timeout = JUDGE_TIMEOUT,
        )
        return try {
            llm.generate(request, CancellationToken()).text
        } catch (e: Exception) {
            log.warn(
                "event=memory.judge.failed persona={} reason={}",
                member.id, e.message ?: e.javaClass.simpleName,
            )
            null
        }
    }

    /**
     * The engagements as the scribe reads them: the room, and the words, one line each and
     * truncated at the SAME lengths the citation stores, so the audit carries byte-for-byte what
     * the model read. `towardBody` is deliberately dropped — on the top-level branch it is the
     * fetched article SUMMARY, and that is the web text kept out of the judging prompt (the S4a
     * posture, which never claimed more than the summary).
     *
     * What is NOT kept out, said plainly because the short version of this sentence read as though
     * it were: `room` is the thread title, and on an ambient article thread the title is fetched
     * text too. It enters, bounded at [EVIDENCE_ROOM_CHARS] one-lined characters and never trusted
     * — §4's injection residual, with [MemoryScribePrompts.SYSTEM]'s read-it-as-evidence clause
     * standing in front of it. Nobody should relax the [ScribeAnswer] refusals or this bound
     * believing the judging prompt is already clean of web text.
     */
    private fun engagementsOf(evidence: List<PersonaExchange>): List<MemoryScribePrompts.Engagement> =
        evidence.map {
            MemoryScribePrompts.Engagement(
                room = Snippet.oneLine(it.threadTitle, EVIDENCE_ROOM_CHARS),
                body = Snippet.oneLine(it.body, EVIDENCE_BODY_CHARS),
            )
        }

    /** The judging turn as a synthetic comment, the shape [ContextAssembler] takes — attributed to
     *  `owner` because this text is instructions from the system, not a member's post. */
    private fun judgmentTurn(instruction: String) = Comment(
        id = "judgment",
        threadId = "",
        parentId = null,
        authorId = "owner",
        body = instruction,
        state = GenerationState.POSTED,
        failureCategory = null,
        depth = 0,
    )

    /** At the scribe-row ceiling? Owner rows are UNCOUNTED — the ceiling bounds the model, not the
     *  owner (§2.11); the owner's own authoring ceiling lives on the controller. */
    private fun atCapacity(personaId: String): Boolean =
        memories.recordsOf(personaId).count { it.source == PersonaMemoryRepository.SOURCE_SCRIBE } >=
            MAX_SCRIBE_MEMORIES

    /** The per-run member budget, clamped at the use site: 0 (and anything below) means unlimited. */
    private fun memberCap(): Int = if (props.maxPersonasPerRun > 0) props.maxPersonasPerRun else Int.MAX_VALUE

    /**
     * Is this engagement newer than the member's effective floor — the exact per-member test, on
     * parsed [Instant]s rather than ISO strings (`Instant.toString()` prints no fraction on a whole
     * second, so a fraction-less stamp sorts lexicographically AFTER every sub-second stamp of the
     * same second — the S4b anomaly). An engagement whose own stamp cannot be parsed is KEPT:
     * evidence must not vanish because a timestamp is malformed. Strict `isAfter`, so an engagement
     * sitting exactly on the watermark is not judged twice.
     */
    private fun isAfter(createdAt: String, floor: Instant): Boolean {
        val at = try {
            Instant.parse(createdAt)
        } catch (e: java.time.format.DateTimeParseException) {
            log.debug("event=memory.stamp.unparseable stamp={} reason={}", createdAt, e.message)
            return true
        }
        return at.isAfter(floor)
    }

    /**
     * The coarse SQL floor for the single evidence read: `max(coarseFloor, horizon)` (§2.6, D6b).
     * The roster half is S4b's construction — the oldest watermark, minus the one-second margin
     * that clears the lexicographic anomaly, truncated to a second boundary — but unlike S4b a
     * null window can no longer drag the floor to NULL: the HORIZON is the floor's floor, always,
     * which is what kills the dead-coarseFloor class by construction rather than by patch. A coarse
     * filter may return a few rows too many ([isAfter] still decides per member); it may never
     * lose one — hence the same margin on the horizon side.
     */
    private fun since(
        roster: List<PersonaRepository.Persona>,
        windows: Map<String, Instant?>,
        horizon: Instant,
    ): String {
        val horizonFloor = horizon.minusSeconds(FLOOR_MARGIN_SECONDS).truncatedTo(ChronoUnit.SECONDS)
        val oldest = roster.map { windows[it.id] ?: return horizonFloor.toString() }.minOrNull()
            ?: return horizonFloor.toString()
        val coarse = oldest.minusSeconds(FLOOR_MARGIN_SECONDS).truncatedTo(ChronoUnit.SECONDS)
        return maxOf(coarse, horizonFloor).toString()
    }

    companion object {
        /**
         * The per-member ceiling on SCRIBE-written rows (§2.11): at ≤1 record/week that is about
         * six months of accumulation, and 24 × 300 chars keeps the full per-member store a trivial
         * in-memory scan. A code constant, not a property — it is half of the letter-protocol
         * arithmetic (24 < [MemoryScribePrompts.MAX_PARENT_LETTERS]), and a knob that could be
         * turned past the alphabet would put the labelling scheme's correctness in yml. At ceiling
         * the skip is FREE, decided before any spend; the owner deletes to make room.
         */
        const val MAX_SCRIBE_MEMORIES = 24

        /**
         * OLDEST WINDOW FIRST over (judgedAt, personaId): never-judged (null) ahead of everything,
         * id as the deterministic tiebreak — so a biting cap rotates instead of starving the tail
         * (the S4a/S4b comparator, pinned Tier 0 INCLUDING the null-vs-stamped case, the S4b §10.4
         * gap closed here rather than repeated).
         */
        val BY_WINDOW_AGE: Comparator<Pair<Instant?, String>> =
            compareBy<Pair<Instant?, String>, Instant?>(nullsFirst()) { it.first }
                .thenBy { it.second }

        /**
         * OLDEST FIRST over evidence, on PARSED stamps with a comment-id tiebreak — the ordering the
         * `takeLast` cut in [scribeMember] leans on, and the one `exchangesSince`'s SQL `ORDER BY
         * c.created_at` cannot supply (string order, the whole-second anomaly).
         *
         * NOT a reuse of [MemoryRecall.NEWEST_FIRST], and the reason is the type, not the rule: that
         * comparator sorts [PersonaMemory] and this one [PersonaExchange], and unifying them would
         * mean a selector-taking factory whose entire client list is these two lines. What IS shared
         * is the rule — parse, never string-compare — and the degrade: `nullsLast` on an ASCENDING
         * compare puts an unparseable stamp at the newest end, where `takeLast` keeps it, exactly as
         * [isAfter] keeps it and as recall's cut does. Ascending rather than descending because the
         * prompt renders evidence oldest-first; the cut takes the tail, so the two must agree.
         */
        private val BY_STAMP: Comparator<PersonaExchange> =
            compareBy<PersonaExchange, Instant?>(nullsLast()) { parsedOrNull(it.createdAt) }
                .thenBy { it.commentId }

        /** The stamp as an [Instant], or null when malformed — the comparator's half of [isAfter]'s
         *  decision, kept beside it so both express the same degrade. */
        private fun parsedOrNull(createdAt: String): Instant? = try {
            Instant.parse(createdAt)
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }

        /** Generous but bounded: a judgment is a two-line answer, and an unattended Sunday run must
         *  not hang a scheduler thread all night on a wedged backend. */
        private val JUDGE_TIMEOUT: Duration = Duration.ofSeconds(60)

        /**
         * How many engagements one judgment may see, and how much of each (§2.4): the twelve most
         * recent, 400-char one-lined bodies, 120-char titles. The horizon bounds the read; this cap
         * bounds the prompt — a member's first-ever judgment on a busy forum must not paste a
         * season of comments into one call.
         */
        private const val MAX_EVIDENCE_ENGAGEMENTS = 12
        private const val EVIDENCE_BODY_CHARS = 400

        /** A thread title is fetched text on an ambient article thread — bounded, never trusted. */
        private const val EVIDENCE_ROOM_CHARS = 120

        /** How far below a watermark/horizon the coarse SQL floor drops — exactly the width of the
         *  lexicographic anomaly it must clear (a fraction-less stamp sorts after every sub-second
         *  stamp of its own second). */
        private const val FLOOR_MARGIN_SECONDS = 1L

        /** Field separator inside one cited record — safe because [Snippet.oneLine] collapses every
         *  whitespace run, so no tab survives into the third field; the ids are UUIDs. */
        private const val CITED_SEPARATOR = "\t"

        /**
         * Snapshot the engagements a judgment was made from, one line per engagement as
         * `commentId <TAB> threadId <TAB> snippet` — text, not a foreign key (`comment.body` is
         * mutable in place, so citing by id alone would let the evidence change under the audit
         * record), with the ids kept beside the prose so /admin/memory can offer a permalink. The
         * snippet is cut at the SAME length the prompt used, so the audit carries byte-for-byte
         * what the model read. Its own codec rather than a reuse of [InterestDriftService]'s: the
         * two logs snapshot the same shape by coincidence today, and sharing would couple S4b's row
         * format to this slice's the moment either changes what it snapshots.
         */
        fun renderCited(engagements: List<PersonaExchange>): String =
            engagements.joinToString("\n") {
                listOf(it.commentId, it.threadId, Snippet.oneLine(it.body, EVIDENCE_BODY_CHARS))
                    .joinToString(CITED_SEPARATOR)
            }
    }
}
