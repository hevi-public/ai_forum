package com.aiforum.service

import com.aiforum.config.StanceEvolutionProperties
import com.aiforum.domain.Comment
import com.aiforum.domain.context.ContextAssembler
import com.aiforum.dto.GenerationState
import com.aiforum.dto.Snippet
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.persona.PersonaPromptRefresher
import com.aiforum.persona.StanceJudge
import com.aiforum.persona.StanceJudgePrompts
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaExchange
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.RelationStanceRepository
import com.aiforum.repo.Stance
import com.aiforum.repo.StanceChangeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Who fired an evolution pass: the owner's hand from /admin/stances (MANUAL, always available) or the
 * gated `@Scheduled` caller (SCHEDULED). Mirrors `TickSource`, but deliberately a separate enum — the
 * two loops are independently switchable (see [StanceEvolutionProperties]) and nothing should be able
 * to pass an ambient tick's source into a stance run, or vice versa, by accident.
 *
 * Unlike `TickSource` this is never persisted: S4a runs are NOT recorded in `ambient_run`, because
 * `AmbientRunRepository.count()` drives the ambient tick's post/comment parity AND its round-robin
 * author index — an extra row per evolution run would silently change which persona posts which
 * article. The source reaches the log and nothing else.
 */
enum class EvolutionSource { MANUAL, SCHEDULED }

/**
 * The relation-stance evolution pass (plan_docs/ambient-slice-4a.md): read what the members actually
 * wrote to each other since that pair was last judged, ask the model to judge the TONE of it, and
 * rewrite the affected `persona_stance` rows — auto-applied, never queued for approval, every change
 * captured in `stance_change` so the owner can read old→new afterwards and revert what they disagree
 * with.
 *
 * ## Three things it must not do, and where each is enforced
 *
 * - **Never overwrite the owner's own words.** A stance whose `source` is
 *   [RelationStanceRepository.SOURCE_OWNER] is skipped BEFORE the judgment, so a room whose relations
 *   the owner has taken over by hand is also a room this pass stops spending money on.
 * - **Never invent an edge.** A pair with no stance row is skipped, also before the judgment. S4a
 *   *evolves* relationships; it does not introduce them.
 * - **Never smuggle in a number.** The single field in the relation model a language model writes is
 *   the stance text, and [StanceJudge.parse] refuses a digit-bearing answer outright. Nothing here
 *   counts, ranks or aggregates anything: pairs are qualified by having produced exchanges, and the
 *   audit cites those exchanges as ids plus prose, never as a tally.
 *
 * ## What one run may cost, and what holds it down
 *
 * Every qualifying edge buys one LLM judgment, the default cap is "no cap" (D4), and the scheduler runs
 * unattended — so cost control is structural rather than a knob, and three mechanisms carry it:
 *
 * - **The per-edge watermark** (`persona_stance.judged_at`, V26). An edge is judged only on exchanges
 *   newer than the last judgment that *landed* on it, and a judgment lands whether it moved the stance
 *   or left it standing. Tracking only CHANGES was the original defect: the judge is instructed to
 *   repeat a standing view when the exchanges do not move it, so "unchanged" is the steady state of a
 *   settled pair, it wrote no audit row, its window never advanced, and that pair re-bought the same
 *   judgment every night forever.
 * - **The free skips come first.** An owner-authored stance and a pair with no stance row are decided
 *   from the graph snapshot, before the cap is applied — so a skip can never eat the budget of an edge
 *   that would actually have been judged.
 * - **One pass at a time.** [evolve] is single-flight: the manual button runs the whole pass on the
 *   request thread with a 60s ceiling per judgment, so an owner who gives up waiting and clicks again
 *   would otherwise start a second pass over the same edges — every judgment paid for twice, and a
 *   second audit row whose "before" text is the first pass's "after".
 *
 * ## Ordering is load-bearing three times
 *
 * The audit row is written BEFORE the upsert, and both — with the watermark stamp — are ONE transaction.
 * `upsert` overwrites stance, source and updated_at in one statement and `persona_stance` keeps no
 * history, so a change not captured first is unrevertable: the old text would exist nowhere in the
 * system. But an audit row committed alone is the same wound from the other side — it would claim a
 * change that never happened AND become that edge's window boundary, walling off the evidence, which is
 * precisely what the per-edge window exists to prevent.
 *
 * Candidates are ordered by WINDOW AGE, oldest first, so the cap rotates. Sorting by name would give an
 * alphabetically early edge that keeps coming back unchanged, refused or rate-limited a permanent claim
 * on the budget, and the edges behind it would never be judged — not "later", never.
 *
 * And the recompose fan-out happens AFTER every stance write, once per distinct holder, so a member
 * whose two relations both moved is recomposed once, from a graph that has finished moving.
 *
 * A recompose failure deliberately costs nothing: the stance write and its audit row are already
 * committed, and [PersonaPromptRefresher.refresh] reports failure rather than throwing.
 */
@Service
class StanceEvolutionService(
    private val comments: CommentRepository,
    private val personas: PersonaRepository,
    private val stances: RelationStanceRepository,
    private val changes: StanceChangeRepository,
    private val llm: LlmClient,
    private val refresher: PersonaPromptRefresher,
    // The audit row, the stance write and the watermark stamp are one unit of work. Injected explicitly
    // rather than annotating a method `@Transactional`: the write path is a private method reached by
    // self-invocation, and a Spring proxy sees neither — the annotation would compile, read as a
    // guarantee, and do absolutely nothing.
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val props: StanceEvolutionProperties,
) {

    private val log = LoggerFactory.getLogger(StanceEvolutionService::class.java)

    /**
     * Whether a pass is in flight, for the single-flight guard in [evolve] — see the class KDoc for why
     * a double-click is the expected input rather than a hypothetical one. An [AtomicBoolean] rather
     * than a lock because a second caller must NOT queue behind the first: waiting would hand the owner
     * exactly the second pass they must not get, just later.
     */
    private val running = AtomicBoolean(false)

    /** One directed pair under judgment. Directed: `A→B` and `B→A` are separate opinions, judged apart. */
    private data class Edge(val from: String, val to: String)

    /**
     * One edge as the run sees it: its stored row plus its window, parsed once. [judgedAt] is null both
     * for an edge that has never been judged and for one whose stored stamp could not be read — the same
     * reading in both cases, "judge it over all of its history", which is the safe direction (it costs a
     * judgment; the other direction hides evidence forever).
     */
    private data class EdgeState(val stance: Stance, val judgedAt: Instant?)

    /** An edge that survived qualification: worth a judgment, with the evidence its own window admits. */
    private data class Candidate(val edge: Edge, val state: EdgeState, val evidence: List<PersonaExchange>)

    /**
     * One cited exchange, decoded from a [StanceChange][com.aiforum.repo.StanceChange]'s `cited` field —
     * enough for the audit page to render a `/threads/{threadId}#reply-{commentId}` permalink beside the
     * snapshotted prose. See [parseCited] for the wire format and why the prose is stored rather than
     * re-read.
     */
    data class CitedExchange(val commentId: String, val threadId: String, val snippet: String)

    /**
     * Run one pass and return how many edges moved. A second concurrent caller does NOTHING and returns
     * 0 — see [running] and the class KDoc: the manual trigger is a synchronous POST over a pass with no
     * cap by default, so "the browser gave up, click it again" is the normal way a second pass starts,
     * and until the first pass stamps its watermarks the second one re-judges the very same edges.
     *
     * The whole body is wrapped in `try/catch (Exception)` — narrowed from `Throwable` per the S1 review
     * finding, so a genuine JVM Error still propagates — and never rethrows. An LLM judgment is exactly
     * the kind of thing that rate-limits at 04:00 with nobody watching, and that has to be a recorded
     * outcome rather than an unhandled scheduled-task failure. This catch covers the reads that FRAME
     * the run (roster, graph, exchange query); each edge carries its own catch below, so one edge whose
     * repository write fails costs one edge rather than every edge still queued behind it.
     *
     * **The clock is read before the evidence query, never after.** That instant becomes the watermark
     * of every edge this run judges, and the ordering matters: reading it afterwards would leave a
     * comment posted during the query behind a watermark that never saw it, and this window is the only
     * thing that decides whether an exchange is ever judged. Read first, and the worst case is an
     * exchange judged twice.
     */
    fun evolve(source: EvolutionSource): Int {
        if (!running.compareAndSet(false, true)) {
            log.warn("event=stance.evolve.skipped source={} reason=already-running", source.name.lowercase())
            return 0
        }
        var changed = 0
        log.info("event=stance.evolve.start source={}", source.name.lowercase())
        try {
            val readAt = clock.instant().toString()
            // Persona-ness is decided against the ROSTER, never by the shape of the id: "owner",
            // "system" and `gh:`-prefixed GitHub authors are excluded by simply not being on it, the
            // same call ReplyTreeAssembler makes. A string heuristic would silently admit gh: authors as
            // forum members. The map does double duty — its keys are the membership test, its values the
            // display names the judging model reads (stance rows and Comment.authorId carry ids, the
            // prompt is written in names; PersonaRouter.relationsBlock documents the same convention).
            val roster = personas.findAll().associate { it.id to it.name }
            // The whole relation graph, ONCE — for ORDERING and WINDOWS only. One consistent view is
            // what stops an edge being ordered by one window and judged against another.
            //
            // It is deliberately NOT the authority on whether an edge may be written: the single-flight
            // guard above excludes another PASS, not the owner's persona form, and this pass runs long
            // enough (uncapped, synchronous, up to a minute per judgment) that an owner edit landing
            // mid-run is ordinary rather than exotic. `evolveEdge` therefore re-reads the row at the
            // judgment site and skips there — see its comment for what trusting this snapshot would
            // destroy, which is the owner's own words, unrecoverably.
            val graph = stances.findAll()
                .associate { Edge(it.fromPersona, it.toPersona) to EdgeState(it, judgedAt(it)) }
            val queue = candidates(comments.exchangesSince(coarseFloor(graph.values)), roster.keys, graph)
            // Distinct holders whose edges moved, in first-changed order, so the fan-out below is one
            // recompose per member however many of their relations shifted.
            val movedHolders = LinkedHashSet<String>()
            queue.take(edgeCap()).forEach { candidate ->
                // One bad edge costs ONE edge. `evolveEdge` writes (audit, stance, watermark), and a
                // locked DB or a constraint violation on the third pair must not abandon the fourth and
                // everything after it — the pass would look like it finished, having silently done a
                // fraction of its work. The LLM seam has its own guard inside `judge`; this one is for
                // the storage side.
                try {
                    if (evolveEdge(candidate, roster, readAt)) {
                        movedHolders += candidate.edge.from
                        changed++
                    }
                } catch (e: Exception) {
                    log.error(
                        "event=stance.edge.failed from={} to={} reason={}",
                        candidate.edge.from, candidate.edge.to, e.message ?: e.javaClass.simpleName, e,
                    )
                }
            }
            // D11: stance flavour the composer baked into a stored system_prompt goes stale the moment
            // the stance moves, so the holder is refreshed as part of the same pass rather than waiting
            // for someone to press the bulk recompose button. Isolation is the refresher's job.
            movedHolders.forEach { refresher.refresh(it) }
            log.info(
                "event=stance.evolve.finished source={} changed={} recomposed={}",
                source.name.lowercase(), changed, movedHolders.size,
            )
        } catch (e: Exception) {
            log.error(
                "event=stance.evolve.failed source={} reason={}",
                source.name.lowercase(), e.message ?: e.javaClass.simpleName, e,
            )
        } finally {
            // In a `finally`, so a fault on the framing reads cannot leave the guard latched and every
            // later pass — including the 04:00 scheduled one — returning 0 for the lifetime of the JVM.
            running.set(false)
        }
        return changed
    }

    /**
     * Undo one audited change: restore the previous stance TEXT and its previous PROVENANCE, then stamp
     * the audit row. Returns whether anything was undone — false for an unknown id or one already
     * reverted (the `reverted_at` null IS the double-revert guard, enforced again in the repository).
     * No LLM call is made or needed: every value being restored was captured at the time.
     *
     * **Restoring the source is not bookkeeping.** `upsert` overwrites provenance wholesale, so putting
     * only the text back would leave a hand-seeded row labelled `evolved`, and the next pass would read
     * a lie. Restoring `owner` likewise keeps an owner-authored row permanently out of this pass's
     * reach, which is the contract the column exists for.
     *
     * **Revert undoes; it does not freeze.** A reverted seeded row goes back to `seeded` and is free to
     * drift again — deliberately (D10). A revert that also pinned the edge forever would quietly turn
     * one disagreement into a permanent opt-out; the owner who wants a relationship fixed for good
     * edits it on the persona form, which stamps `owner` and puts it out of reach.
     *
     * **And the window is undone with it.** The rejected judgment stamped the edge's `judged_at`, so
     * leaving that stamp would keep the exchanges the owner just disagreed about permanently out of
     * reach — the edge would be free to drift in principle and blind to the conversation in practice.
     * The watermark is therefore moved back to [StanceChangeRepository.lastStandingChangeAt], the newest
     * change that STILL stands: exactly the evidence this judgment consumed is reopened, and nothing
     * older, because everything before the previous surviving change was already acted on. Null — no
     * surviving change — clears it, which reads as "judge this edge over all of its history", the same
     * state a never-judged edge is in. Read AFTER [StanceChangeRepository.markReverted] so the row being
     * undone no longer counts as standing; before it, the query would hand back this very change's stamp
     * and the revert would reopen nothing.
     *
     * The stance write goes first and the stamps after: if a stamp fails, the owner sees an un-reverted
     * row and can click again (the upsert is idempotent), whereas the reverse order could leave a row
     * claiming to be reverted over a stance that never moved back. The residual — a fault between the
     * revert stamp and the watermark reset leaves the edge closed over the evidence it should reopen,
     * and the re-click is a no-op by the double-revert guard — is the least consequential of the three
     * writes and deliberately not worth a second transaction here: the edge still evolves from anything
     * said afterwards.
     *
     * The holder's stored system prompt is deliberately NOT recomposed here — D10 says a revert costs
     * no LLM call. Nothing goes stale that matters: the authoritative relations block is re-read from
     * `persona_stance` on every generation, so only the composed prompt's flavour lags, until the next
     * evolution or the owner's bulk recompose.
     */
    fun revert(changeId: Long): Boolean {
        val change = changes.find(changeId)
        if (change == null) {
            log.warn("event=stance.revert.skipped change={} reason=unknown-change", changeId)
            return false
        }
        if (change.revertedAt != null) {
            log.info("event=stance.revert.skipped change={} reason=already-reverted", changeId)
            return false
        }
        stances.upsert(change.fromPersona, change.toPersona, change.oldStance, change.oldSource)
        changes.markReverted(changeId)
        stances.markJudged(
            change.fromPersona,
            change.toPersona,
            changes.lastStandingChangeAt(change.fromPersona, change.toPersona),
        )
        log.info(
            "event=stance.reverted change={} from={} to={} source={}",
            changeId, change.fromPersona, change.toPersona, change.oldSource,
        )
        return true
    }

    /**
     * OLDEST WINDOW FIRST — the edge nobody has looked at in longest goes to the front of the queue,
     * never-judged edges (null) ahead of everything, with (from, to) as the deterministic tiebreak so a
     * run is still reproducible.
     *
     * Sorting by name instead is what the cap turns into starvation: `take(cap)` takes the first N, and
     * an alphabetically early edge that keeps coming back unchanged, refused or rate-limited would hold
     * the same claim on the budget on every run, so the edges behind it are never judged — not "later",
     * never. Age-ordered, the cap rotates: whatever a run judges is stamped, drops to the back, and the
     * next run reaches further down.
     */
    private val byWindowAge: Comparator<Candidate> =
        compareBy<Candidate, Instant?>(nullsFirst<Instant>()) { it.state.judgedAt }
            .thenBy { it.edge.from }
            .thenBy { it.edge.to }

    /**
     * The edges worth judging this run, oldest window first (see [byWindowAge]).
     *
     * Both endpoints must be on the roster: an exchange with the owner is dropped here, because
     * relations are persona↔persona by §5 — the owner is a peer, not a node in the graph.
     *
     * **Each edge carries its own window**, its own `persona_stance.judged_at` (V26). The exchange
     * history is read once — coarsely bounded by [coarseFloor] — and narrowed PER EDGE in memory. A
     * single global watermark would let one pair's success disinherit every pair that failed, was capped
     * out, or came back unusable in the same run ([StanceChangeRepository.lastStandingChangeAt] spells
     * that out); the cost of filtering in memory is a SQLite read on a single-user forum, and the cost
     * of getting the boundary wrong is a relationship that silently never evolves from the conversation
     * that should have moved it.
     *
     * **The two free skips are decided here, not after the cap.** An owner-authored stance and a pair
     * with no stance row cost nothing to reject — the graph snapshot answers both — so letting them
     * occupy a `take(cap)` slot would spend real budget on edges that were never going to be judged. The
     * quiet-edge case is filtered before either skip is logged: an edge with nothing new to say has not
     * been skipped, it simply had no question to ask, and a log line per pair per run for that would
     * bury the two that matter.
     */
    private fun candidates(
        exchanges: List<PersonaExchange>,
        roster: Set<String>,
        graph: Map<Edge, EdgeState>,
    ): List<Candidate> {
        val minimum = maxOf(1, props.minExchanges)
        return exchanges
            .filter { it.fromAuthor in roster && it.toAuthor in roster }
            .groupBy { Edge(it.fromAuthor, it.toAuthor) }
            .mapNotNull { (edge, rows) ->
                val state = graph[edge]
                val evidence = rows.filter { isAfter(it.createdAt, state?.judgedAt) }
                when {
                    evidence.size < minimum -> null
                    state == null -> {
                        // S4a moves stances; it does not invent edges the seed never authored.
                        log.info("event=stance.skipped from={} to={} reason=no-stance", edge.from, edge.to)
                        null
                    }

                    state.stance.source == RelationStanceRepository.SOURCE_OWNER -> {
                        // The never-clobber contract, enforced before the judgment AND before the cap,
                        // so it costs nothing to hold and takes nothing from anyone else.
                        log.info("event=stance.skipped from={} to={} reason=owner-authored", edge.from, edge.to)
                        null
                    }

                    else -> Candidate(edge, state, evidence)
                }
            }
            .sortedWith(byWindowAge)
    }

    /**
     * Is this exchange newer than the edge's watermark — the EXACT per-edge test, deliberately done on
     * parsed [Instant]s rather than on the ISO strings.
     *
     * `Instant.toString()` drops trailing zeros and prints no fraction at all on a whole second, so
     * `"…:08Z"` sorts AFTER `"…:08.4Z"` lexicographically while being earlier in time. A watermark that
     * lands on a whole second — which a fixed test clock does every time, and a real one does once in a
     * while — would therefore hide every sub-second exchange in that same second, permanently: the next
     * watermark is later still, so those exchanges are never judged by anyone. Comparing instants costs
     * one parse per row and cannot go wrong.
     *
     * A null window (never judged, or an unreadable stamp) admits everything, and an exchange whose own
     * stamp cannot be parsed is KEPT rather than dropped — evidence the model might have acted on must
     * not vanish because a timestamp is malformed. Strict `isAfter`, so an exchange sitting exactly on
     * the watermark is not judged twice.
     */
    private fun isAfter(createdAt: String, window: Instant?): Boolean {
        if (window == null) return true
        val at = parsedOrNull(createdAt) ?: return true
        return at.isAfter(window)
    }

    /**
     * A coarse SQL floor for the single exchange read: the OLDEST watermark in the graph, and only when
     * every edge has one. One never-judged edge legitimately needs all-time history, and a floor above
     * it would hide exactly the evidence that edge exists to be judged on.
     *
     * This is a read-size optimisation and nothing else — [isAfter] still decides per edge what each
     * judgment actually sees, so the floor may only ever be too generous. Without it every run
     * materialises every persona-to-persona exchange the forum has ever produced, bodies and parent
     * bodies included, however narrow the actual windows are.
     *
     * **Why the floor may stay a lexicographic SQL comparison** (`c.created_at > ?`) while the per-edge
     * test may not: the anomaly above only misorders stamps INSIDE one second, when one of them prints
     * no fraction. So the floor is dropped a whole second and truncated to a second boundary, which puts
     * it strictly below every exchange it must keep in the fixed-width part of the format, where
     * lexicographic and chronological order are the same thing. A coarse filter is allowed to return a
     * few rows too many; it is not allowed to lose one.
     */
    private fun coarseFloor(graph: Collection<EdgeState>): String? {
        if (graph.isEmpty()) return null
        val oldest = graph.map { it.judgedAt ?: return null }.minOrNull() ?: return null
        return oldest.minusSeconds(FLOOR_MARGIN_SECONDS).truncatedTo(ChronoUnit.SECONDS).toString()
    }

    /**
     * The stored watermark as an [Instant], or null when the edge has never been judged — or when the
     * stored text cannot be read as an instant. A malformed stamp is a run-wide hazard if it throws
     * (one bad row would take the whole pass down from inside a framing read), and treating it as
     * "never judged" is the recoverable reading: the edge is judged over all its history once and the
     * next stamp is written by us, so the row heals itself.
     */
    private fun judgedAt(stance: Stance): Instant? = stance.judgedAt?.let { stamp ->
        parsedOrNull(stamp).also {
            if (it == null) {
                log.warn(
                    "event=stance.window.unreadable from={} to={} stamp={}",
                    stance.fromPersona, stance.toPersona, stamp,
                )
            }
        }
    }

    private fun parsedOrNull(stamp: String): Instant? =
        try {
            Instant.parse(stamp)
        } catch (e: DateTimeParseException) {
            log.debug("event=stance.stamp.unparseable stamp={} reason={}", stamp, e.message)
            null
        }

    /**
     * The exchanges actually shown to the judge: the most recent [MAX_EVIDENCE_EXCHANGES], each side
     * flattened to one line and truncated.
     *
     * Unbounded evidence is a real hazard rather than a theoretical one, because an edge's window starts
     * at all-time: the first run on an established forum would otherwise paste every comment a pair ever
     * exchanged, at full length, into a single prompt — and so would every run over an edge whose
     * judgments keep failing or coming back refused, since neither closes the window (by design; both
     * deserve another look). Recent exchanges are also the ones that describe how the relationship
     * stands NOW, which is the question being asked, so the cap costs the judgment nothing it wanted.
     */
    private fun evidenceFor(exchanges: List<PersonaExchange>): List<StanceJudge.Exchange> =
        exchanges.takeLast(MAX_EVIDENCE_EXCHANGES).map {
            StanceJudge.Exchange(
                body = Snippet.oneLine(it.body, EVIDENCE_BODY_CHARS),
                towardBody = Snippet.oneLine(it.towardBody, EVIDENCE_BODY_CHARS),
            )
        }

    /** The per-run edge budget, clamped at the use site: 0 (and anything below) means unlimited. */
    private fun edgeCap(): Int = if (props.maxEdgesPerRun > 0) props.maxEdgesPerRun else Int.MAX_VALUE

    /**
     * Judge and (maybe) rewrite one edge; returns whether the stance actually moved. Every branch that
     * declines to move it is logged with its own reason — a pass that quietly does nothing is
     * indistinguishable from a broken one at four in the morning.
     *
     * ## Which verdicts close the window, and why the split is exactly here
     *
     * [readAt] is stamped on the edge for a USABLE verdict — [StanceJudge.Verdict.Changed] and
     * [StanceJudge.Verdict.Unchanged] alike. "I read these exchanges and they did not move me" is a
     * complete answer about that evidence, and paying for the same answer again tomorrow, and every
     * night after that, is the defect V26 exists to close: the judge is *instructed* to restate a
     * standing view when nothing moved, so unchanged is the steady state of a settled pair, and it
     * writes no audit row to advance the window with.
     *
     * A REJECTED answer and a seam failure deliberately stamp nothing. In both cases we never learned
     * what the judge thought of this evidence — the model returned a digit, or the provider was busy at
     * 04:00 — so the exchanges are genuinely unjudged and get another look next run. That retry is the
     * whole reason the window is per-edge, and it is why the stamp is written here at the judgment site
     * rather than inferred from "an LLM call happened for this pair".
     *
     * ## One transaction around the change
     *
     * Audit FIRST (old text AND old provenance AND the evidence), then the write, then the stamp — and
     * all three commit together or not at all. The class KDoc explains the ordering; the transaction is
     * what keeps a half-applied change from being worse than no change. An audit row that committed
     * alone would show the owner a before→after that never happened AND would stand as this edge's
     * history entry, while a stance write that committed without its stamp would leave the pass paying
     * to re-judge evidence it has already acted on.
     *
     * [StanceChangeRepository.record] is itself `@Transactional`, and that keeps working here rather
     * than fighting this one: default propagation is REQUIRED, so it JOINS this transaction instead of
     * opening a second. Which is exactly what its own KDoc needs — its insert and its
     * `last_insert_rowid()` read stay on one connection, and the returned id is the row we just wrote
     * rather than another pooled connection's.
     */
    private fun evolveEdge(candidate: Candidate, roster: Map<String, String>, readAt: String): Boolean {
        val (edge, _, exchanges) = candidate
        // RE-READ THE ROW HERE, and do not judge or write from the snapshot. The snapshot decided this
        // edge's ORDER and its WINDOW — questions about the run as a whole, which want one consistent
        // view — but "does this edge still exist, and may the pass rewrite it" is a permission, and a
        // permission expires. The pass is synchronous, uncapped by default and spends up to a minute per
        // judgment, so the owner is watching a hung tab for as long as it runs, and the persona form in
        // another tab is where they go while they wait. Trusting the snapshot turns that wait into a
        // window where `upsert` writes the model's sentence over words the owner typed after the pass
        // started — with the audit row citing the PRE-EDIT text, so Revert restores a sentence the owner
        // never wrote and their own is gone from a table that keeps no history. Same for a retraction:
        // `upsert` is an INSERT … ON CONFLICT, so a stale snapshot resurrects a deleted edge as
        // system-authored. Both are the class KDoc's absolutes, and one re-read is what makes them true.
        //
        // Residual, stated rather than implied: an edit landing inside the judgment call itself is still
        // overwritten. That is a sixty-second race rather than a whole-pass one, it is what the pass did
        // before the graph snapshot existed, and closing it properly wants a conditional write
        // (`UPDATE … WHERE source <> 'owner'`) rather than a re-read.
        val current = stances.find(edge.from, edge.to)
        if (current == null) {
            // The owner blanked the field mid-pass. S4a moves stances; it does not author them.
            log.info("event=stance.skipped from={} to={} reason=retracted-mid-pass", edge.from, edge.to)
            return false
        }
        if (current.source == RelationStanceRepository.SOURCE_OWNER) {
            // Pinned after the queue was built. Skipped BEFORE the judgment, so it costs nothing.
            log.info("event=stance.skipped from={} to={} reason=owner-authored-mid-pass", edge.from, edge.to)
            return false
        }
        val raw = judge(edge, roster, current.stance, exchanges) ?: return false
        return when (val verdict = StanceJudge.parse(raw, current.stance)) {
            is StanceJudge.Verdict.Changed -> {
                val id = transactions.execute {
                    val recorded = changes.record(
                        fromPersona = edge.from,
                        toPersona = edge.to,
                        oldStance = current.stance,
                        newStance = verdict.text,
                        oldSource = current.source,
                        cited = renderCited(exchanges),
                    )
                    stances.upsert(edge.from, edge.to, verdict.text, RelationStanceRepository.SOURCE_EVOLVED)
                    stances.markJudged(edge.from, edge.to, readAt)
                    // The generated id travels out of the transaction because the log line — and the
                    // owner's revert link, downstream of it — needs the row that was just written.
                    recorded
                }
                log.info("event=stance.changed change={} from={} to={}", id, edge.from, edge.to)
                true
            }

            is StanceJudge.Verdict.Rejected -> {
                // Not an error: the run continues to the next pair, and the window stays OPEN so the
                // same exchanges are re-judged next time. The raw answer is kept in the line so an
                // operator can see WHY nothing moved — the PersonaRouter split between "the seam broke"
                // and "the seam answered something we cannot use".
                log.warn(
                    "event=stance.judge.rejected from={} to={} reason={} raw={}",
                    edge.from, edge.to, verdict.reason, raw.trim(),
                )
                false
            }

            StanceJudge.Verdict.Unchanged -> {
                // A restatement must produce no upsert and no audit row, or the owner's history page
                // fills with entries recording that nothing happened — but it MUST close the window, or
                // this pair re-buys this same judgment on every run for as long as the forum runs. One
                // statement, so it needs no transaction of its own.
                stances.markJudged(edge.from, edge.to, readAt)
                log.info("event=stance.unchanged from={} to={}", edge.from, edge.to)
                false
            }
        }
    }

    /**
     * One tone judgment on the single shared [LlmClient] seam — no second IO boundary — tagged with the
     * synthetic [StanceJudgePrompts] identity so a spy, or the router, can tell a judgment apart from a
     * reply (the [com.aiforum.persona.LlmPromptComposer] pattern).
     *
     * The evidence goes through [ContextAssembler.assemble] rather than a hand-built `ContextComment`,
     * so the owner-vote firewall keeps holding for this caller too: the "no vote signal reached the
     * model" guarantee is asserted by spying on what the seam received, and a caller that assembled its
     * own context would quietly sit outside it.
     *
     * Blocking `generate(request, CancellationToken())` with a fresh token and no sink: passing a sink
     * would emit AG-UI events with `runId = ""` at an SSE layer with no drafting node to route them to.
     * A seam failure returns null — logged as a SEAM failure, distinct from an unusable answer — and
     * the edge is left exactly as it was, watermark included, so the next run tries the same evidence
     * again. That is the whole point of the distinction: a rate limit is not a verdict.
     *
     * `try/catch (Exception)` rather than `runCatching`, which catches [Throwable]: a `StackOverflowError`
     * or an `OutOfMemoryError` must not be swallowed as a routine judgment failure and leave the pass
     * spending LLM calls on a JVM that is already broken. Same narrowing the S1 review forced on the
     * ambient tick and on [PersonaPromptRefresher.refresh].
     */
    private fun judge(
        edge: Edge,
        roster: Map<String, String>,
        currentStance: String,
        exchanges: List<PersonaExchange>,
    ): String? {
        val instruction = StanceJudgePrompts.instruction(
            holder = roster[edge.from] ?: edge.from,
            toward = roster[edge.to] ?: edge.to,
            currentStance = currentStance,
            exchanges = evidenceFor(exchanges),
        )
        val request = LlmRequest(
            context = ContextAssembler.assemble(StanceJudgePrompts.SYSTEM, listOf(evidenceOf(instruction))),
            persona = PersonaRef(StanceJudgePrompts.JUDGE_ID, StanceJudgePrompts.JUDGE_NAME),
            timeout = JUDGE_TIMEOUT,
        )
        return try {
            llm.generate(request, CancellationToken()).text
        } catch (e: Exception) {
            log.warn(
                "event=stance.judge.failed from={} to={} reason={}",
                edge.from, edge.to, e.message ?: e.javaClass.simpleName,
            )
            null
        }
    }

    /**
     * The judging turn as a synthetic comment, the shape [ContextAssembler] takes. Attributed to
     * `owner` for the same reason the composer's spec turn is: the prompt renderer labels the speaker,
     * and this text is instructions from the system, not a forum member's post.
     */
    private fun evidenceOf(instruction: String) = Comment(
        id = "judgment",
        threadId = "",
        parentId = null,
        authorId = "owner",
        body = instruction,
        state = GenerationState.POSTED,
        failureCategory = null,
        depth = 0,
    )

    companion object {
        /**
         * Generous but bounded: a judgment is a short answer over a handful of comments, and an
         * unattended 04:00 run must not hang a scheduler thread all night on a wedged backend.
         */
        private val JUDGE_TIMEOUT: Duration = Duration.ofSeconds(60)

        /**
         * How many exchanges one judgment may see, and how much of each — see `evidenceFor`. Enough to
         * read the shape of a running argument, bounded so a first run over a long history cannot build
         * an arbitrarily large prompt.
         */
        private const val MAX_EVIDENCE_EXCHANGES = 12
        private const val EVIDENCE_BODY_CHARS = 400

        /**
         * How far below the oldest watermark the coarse SQL floor is dropped — see [coarseFloor]. One
         * whole second, because that is the exact width of the lexicographic anomaly it has to clear:
         * a fraction-less stamp sorts after every sub-second stamp of the same second. Enlarging it
         * only widens an over-generous pre-filter; shrinking it below a second silently loses rows.
         */
        private const val FLOOR_MARGIN_SECONDS = 1L

        /**
         * How much of a cited comment is snapshotted. Long enough to recognise what was judged, short
         * enough that an audit row stays readable next to the two stance texts; the permalink is there
         * for the rest.
         */
        const val CITED_SNIPPET_CHARS = 240

        /** Field separator inside one cited record — see [parseCited] for why a tab is safe. */
        private const val CITED_SEPARATOR = "\t"

        /**
         * Snapshot the exchanges a judgment was made from, one record per line as
         * `commentId <TAB> threadId <TAB> snippet`.
         *
         * **Text, not a foreign key** (the `comment_quote.quoted_text` precedent): `comment.body` is
         * mutable in place — an owner edit, a revision switch — so citing by id alone would let the
         * evidence change under the audit record, and deleting a thread would orphan the row. Storing
         * the prose means the owner always reads what was actually judged; the ids are kept beside it
         * so the page can still offer a permalink, rendered defensively for a comment that has since
         * gone.
         *
         * Every exchange the judgment saw is cited, never a sample and never a count of them: the audit
         * IS the control here, so it has to show the evidence the model was given, not a summary of it.
         *
         * A tab is a safe separator because [Snippet.oneLine] flattens the body's markdown and collapses
         * every whitespace run to a single space, so no tab or newline survives into the third field;
         * the two ids are UUIDs. Splitting is limited to three fields anyway, so a stray separator would
         * land harmlessly inside the snippet rather than shifting the record.
         */
        fun renderCited(exchanges: List<PersonaExchange>): String =
            exchanges.joinToString("\n") {
                listOf(it.commentId, it.threadId, Snippet.oneLine(it.body, CITED_SNIPPET_CHARS))
                    .joinToString(CITED_SEPARATOR)
            }

        /**
         * Decode a stored `cited` field back into renderable records — the reader half of
         * [renderCited], kept here so the audit page and the writer cannot drift into two formats.
         *
         * Malformed records are DROPPED rather than thrown on: this parses stored data on a read path
         * whose whole job is to let the owner review what happened, and a single odd row from an older
         * format must not take the history page down with it.
         */
        fun parseCited(cited: String): List<CitedExchange> =
            cited.lineSequence()
                .filter { it.isNotBlank() }
                .map { it.split(CITED_SEPARATOR, limit = 3) }
                .filter { it.size == 3 }
                .map { CitedExchange(it[0], it[1], it[2]) }
                .toList()
    }
}
