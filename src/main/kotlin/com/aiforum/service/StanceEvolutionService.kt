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
import com.aiforum.repo.StanceChangeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration

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
 * wrote to each other since the last change, ask the model to judge the TONE of it, and rewrite the
 * affected `persona_stance` rows — auto-applied, never queued for approval, every change captured in
 * `stance_change` so the owner can read old→new afterwards and revert what they disagree with.
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
 * ## Ordering is load-bearing twice
 *
 * The audit row is written BEFORE the upsert. `upsert` overwrites stance, source and updated_at in one
 * statement and `persona_stance` keeps no history, so a change not captured first is unrevertable —
 * the old text would exist nowhere in the system. And the recompose fan-out happens AFTER every stance
 * write, once per distinct holder, so a member whose two relations both moved is recomposed once, from
 * a graph that has finished moving.
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
    private val props: StanceEvolutionProperties,
) {

    private val log = LoggerFactory.getLogger(StanceEvolutionService::class.java)

    /** One directed pair under judgment. Directed: `A→B` and `B→A` are separate opinions, judged apart. */
    private data class Edge(val from: String, val to: String)

    /**
     * One cited exchange, decoded from a [StanceChange][com.aiforum.repo.StanceChange]'s `cited` field —
     * enough for the audit page to render a `/threads/{threadId}#reply-{commentId}` permalink beside the
     * snapshotted prose. See [parseCited] for the wire format and why the prose is stored rather than
     * re-read.
     */
    data class CitedExchange(val commentId: String, val threadId: String, val snippet: String)

    /**
     * Run one pass and return how many edges moved.
     *
     * The whole body is wrapped in `try/catch (Exception)` — narrowed from `Throwable` per the S1 review
     * finding, so a genuine JVM Error still propagates — and never rethrows. An LLM judgment is exactly
     * the kind of thing that rate-limits at 04:00 with nobody watching, and that has to be a recorded
     * outcome rather than an unhandled scheduled-task failure. Per-pair faults are already isolated
     * below; this catch is for the reads that frame the run (roster, window, exchange query).
     */
    fun evolve(source: EvolutionSource): Int {
        var changed = 0
        log.info("event=stance.evolve.start source={}", source.name.lowercase())
        try {
            // Persona-ness is decided against the ROSTER, never by the shape of the id: "owner",
            // "system" and `gh:`-prefixed GitHub authors are excluded by simply not being on it, the
            // same call ReplyTreeAssembler makes. A string heuristic would silently admit gh: authors as
            // forum members. The map does double duty — its keys are the membership test, its values the
            // display names the judging model reads (stance rows and Comment.authorId carry ids, the
            // prompt is written in names; PersonaRouter.relationsBlock documents the same convention).
            val roster = personas.findAll().associate { it.id to it.name }
            val qualifying = qualifyingPairs(comments.exchangesSince(null), roster.keys)
            // Distinct holders whose edges moved, in first-changed order, so the fan-out below is one
            // recompose per member however many of their relations shifted.
            val movedHolders = LinkedHashSet<String>()
            qualifying.take(edgeCap()).forEach { (edge, exchanges) ->
                if (evolveEdge(edge, exchanges, roster)) {
                    movedHolders += edge.from
                    changed++
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
     * The stance write goes first and the stamp second: if the stamp fails, the owner sees an
     * un-reverted row and can click again (the upsert is idempotent), whereas the reverse order could
     * leave a row claiming to be reverted over a stance that never moved back.
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
        log.info(
            "event=stance.reverted change={} from={} to={} source={}",
            changeId, change.fromPersona, change.toPersona, change.oldSource,
        )
        return true
    }

    /**
     * The directed pairs worth judging, in a deterministic (from, to) order so a capped run always
     * spends its budget on the same edges rather than on whatever the query happened to return first.
     *
     * Both endpoints must be on the roster: an exchange with the owner is dropped here, because
     * relations are persona↔persona by §5 — the owner is a peer, not a node in the graph.
     *
     * **Each edge carries its own window.** The whole exchange history is read once and then narrowed
     * PER EDGE against [StanceChangeRepository.lastStandingChangeAt] — that method's KDoc explains why a
     * single global watermark is wrong (it would let one pair's success disinherit every pair that
     * failed, was capped out, or came back unusable in the same run). The cost of reading all exchanges
     * and filtering in memory is a SQLite read on a single-user forum; the cost of getting the boundary
     * wrong is a relationship that silently never evolves from the conversation that should have moved
     * it.
     *
     * The comparison is strict (`>`), so an exchange exactly at an edge's last change is not re-judged.
     */
    private fun qualifyingPairs(
        exchanges: List<PersonaExchange>,
        roster: Set<String>,
    ): List<Pair<Edge, List<PersonaExchange>>> {
        val minimum = maxOf(1, props.minExchanges)
        return exchanges
            .filter { it.fromAuthor in roster && it.toAuthor in roster }
            .groupBy { Edge(it.fromAuthor, it.toAuthor) }
            .mapValues { (edge, rows) ->
                val since = changes.lastStandingChangeAt(edge.from, edge.to)
                if (since == null) rows else rows.filter { it.createdAt > since }
            }
            .filterValues { it.size >= minimum }
            .toList()
            .sortedWith(compareBy({ it.first.from }, { it.first.to }))
    }

    /**
     * The exchanges actually shown to the judge: the most recent [MAX_EVIDENCE_EXCHANGES], each side
     * flattened to one line and truncated.
     *
     * Unbounded evidence is a real hazard rather than a theoretical one, because the window is per-edge
     * and starts at all-time: the FIRST run on an established forum would otherwise paste every comment
     * a pair ever exchanged, at full length, into a single prompt — and the same happens on every run for
     * any edge that never changes. Recent exchanges are also the ones that describe how the relationship
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
     */
    private fun evolveEdge(
        edge: Edge,
        exchanges: List<PersonaExchange>,
        roster: Map<String, String>,
    ): Boolean {
        val current = stances.find(edge.from, edge.to)
        if (current == null) {
            // S4a moves stances; it does not invent edges the seed never authored. Free skip, no call.
            log.info("event=stance.skipped from={} to={} reason=no-stance", edge.from, edge.to)
            return false
        }
        if (current.source == RelationStanceRepository.SOURCE_OWNER) {
            // The never-clobber contract, enforced BEFORE the judgment so it costs nothing to hold.
            log.info("event=stance.skipped from={} to={} reason=owner-authored", edge.from, edge.to)
            return false
        }
        val raw = judge(edge, roster, current.stance, exchanges) ?: return false
        return when (val verdict = StanceJudge.parse(raw, current.stance)) {
            is StanceJudge.Verdict.Changed -> {
                // Audit FIRST (old text AND old provenance AND the evidence), then the write — see the
                // class KDoc: after the upsert the old values exist nowhere and revert is impossible.
                val id = changes.record(
                    fromPersona = edge.from,
                    toPersona = edge.to,
                    oldStance = current.stance,
                    newStance = verdict.text,
                    oldSource = current.source,
                    cited = renderCited(exchanges),
                )
                stances.upsert(edge.from, edge.to, verdict.text, RelationStanceRepository.SOURCE_EVOLVED)
                log.info("event=stance.changed change={} from={} to={}", id, edge.from, edge.to)
                true
            }

            is StanceJudge.Verdict.Rejected -> {
                // Not an error: the run continues to the next pair. The raw answer is kept in the line
                // so an operator can see WHY nothing moved — the PersonaRouter split between "the seam
                // broke" and "the seam answered something we cannot use".
                log.warn(
                    "event=stance.judge.rejected from={} to={} reason={} raw={}",
                    edge.from, edge.to, verdict.reason, raw.trim(),
                )
                false
            }

            StanceJudge.Verdict.Unchanged -> {
                // A restatement must produce no upsert and no audit row, or the owner's history page
                // fills with entries recording that nothing happened.
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
     * the edge is left exactly as it was.
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
        return runCatching { llm.generate(request, CancellationToken()).text }
            .onFailure { e ->
                log.warn(
                    "event=stance.judge.failed from={} to={} reason={}",
                    edge.from, edge.to, e.message ?: e.javaClass.simpleName,
                )
            }
            .getOrNull()
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
