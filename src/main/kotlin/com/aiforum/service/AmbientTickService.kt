package com.aiforum.service

import com.aiforum.ambient.AmbientGate
import com.aiforum.ambient.ArticleSource
import com.aiforum.ambient.TickSource
import com.aiforum.domain.budget.DepthBudget
import com.aiforum.dto.ReplyView
import com.aiforum.dto.ScopeMode
import com.aiforum.persona.Dials
import com.aiforum.repo.AmbientRunRepository
import com.aiforum.repo.AmbientRunRepository.Companion.ACTION_COMMENT
import com.aiforum.repo.AmbientRunRepository.Companion.ACTION_POST
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.ThreadRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The ambient loop's tick (plan_docs/ambient-slice-1.md, extended by plan_docs/ambient-slice-2.md). S1 gave
 * the tick a single action; S2 gives it a second, and lets a run-count parity choose which to PREFER while
 * either falls back to the other — so exactly ≤1 action executes per tick, but both get exercised at the
 * few-ticks-a-day cadence:
 *
 *  - **post** (S1): collect one article from the [ArticleSource] port and open it as a thread authored BY a
 *    persona (author = best relevance match; round-robin fallback), then fire the same create-time "Whole
 *    Topic + Anyone" summon a normal new thread does.
 *  - **comment** (S2): drop ONE persona comment into the most relevant live thread, gated by talkativeness ×
 *    relevance ([AmbientGate]) — a cheap backend heuristic, never an LLM call of its own. The comment carries
 *    a small non-renewing [DepthBudget.AMBIENT_GRANT], and its SETTLE automatically runs the same bounded
 *    growth round an owner grant gets (the summon's onSettled hook → autoGrow scoped to the comment's own
 *    subtree — owner-granted branches elsewhere are never touched), so the room riffs a couple of levels
 *    on its own before stalling again (§2, the fuel decision) — no owner click involved, and nothing
 *    ambient ever re-grants. A failed ambient comment surfaces the owner's retry exactly like a
 *    failed owner-summoned reply (owner-as-peer, §5) — the tick itself never retries.
 *
 * The tick makes NO LLM call itself; only the summon round (dispatcher + replies) does. Every tick — posted,
 * no-op, or failed — is recorded in `ambient_run` (with WHICH action, V22) and surfaced on /admin/ambient.
 *
 * A failed tick is a recorded skip, not a crash: any [Exception] is caught and recorded 'failed', never
 * propagated (direction doc §8 — a scheduled loop must not crash-loop on one bad tick). Narrowed from S1's
 * `Throwable` (the Assay nit) so a genuine JVM Error still propagates.
 */
@Service
class AmbientTickService(
    private val articleSource: ArticleSource,
    private val personas: PersonaRepository,
    private val threads: ThreadRepository,
    private val ambientRuns: AmbientRunRepository,
    private val generation: GenerationService,
    // S2: the comment action rules out personas who already POSTED in a thread (exclusion rule b).
    private val comments: CommentRepository,
) {
    private val log = LoggerFactory.getLogger(AmbientTickService::class.java)

    /**
     * Run one tick (the S2 5-step anatomy, plan_docs/ambient-slice-2.md §5):
     * 1. Prefer an action by prior-run-count parity: even → post, odd → comment. Parity only sets
     *    PREFERENCE (either action falls back to the other), so a no-op run never wrongly "skips a turn".
     * 2. Try the preferred action, fall back to the other, else record a 'no-op' (attributed to the
     *    PREFERRED action — whose turn it was). Still exactly ≤1 executed action per tick — no loops, so
     *    the S1 invariant holds structurally.
     * 3. Post action: author = highest relevance(abilities, article) persona, round-robin fallback when all
     *    score zero; insert the persona-authored thread + summon the room.
     * 4. Comment action: best (thread, persona) pair that clears talkativeness × relevance, excluding the
     *    thread's author and anyone who already POSTED there; summon that persona at top level with
     *    AMBIENT_GRANT budget.
     * 5. Any [Exception] is caught, recorded 'failed' — attributed to the action that was mid-attempt when
     *    it threw, never a hardcoded 'post' — and swallowed, never propagated. No fallback-on-exception:
     *    the cross-fallback of step 2 is only for a branch that cleanly yields nothing.
     */
    fun tick(source: TickSource) {
        // Which action is CURRENTLY being attempted — updated immediately before each try*, and visible to
        // the catch below, so a failed run is attributed to the branch that actually threw. Without this,
        // an odd-count tick whose comment scan hits e.g. SQLITE_BUSY would record action="post" and
        // misdirect the operator toward the ArticleSource path. Starts at 'post' (the S1 default) for a
        // fault in the pre-branch reads (roster / run count).
        var attempted = ACTION_POST
        try {
            val roster = personas.findAllByRowid()
            val preferPost = ambientRuns.count() % 2 == 0
            val preferredAction = if (preferPost) ACTION_POST else ACTION_COMMENT

            attempted = preferredAction
            var ran = if (preferPost) tryPost(source, roster) else tryComment(source, roster)
            if (!ran) {
                // Cross-fallback (§5 step 2) — for the yields-nothing case ONLY. A throw above skips
                // straight to the catch with no fallback: falling back after a fault could double-execute
                // (the failed branch may have half-dispatched before throwing).
                attempted = if (preferPost) ACTION_COMMENT else ACTION_POST
                ran = if (preferPost) tryComment(source, roster) else tryPost(source, roster)
            }
            if (!ran) {
                // A no-op records the PREFERRED action: nothing executed, so "whose turn it was" is the
                // most informative label the row can carry (not a hardcoded 'post' placeholder). S5
                // (plan_docs/ambient-slice-5.md §2 "Distinguishable no-ops"): append the source's own
                // account of WHY it yielded nothing when it offers one — "feeds returned no items" vs
                // "all N feed items already seen" — so an operator can tell the two no-op shapes apart.
                // The stub (and any source that returns null without a reason) leaves this null, so the
                // generic string is unchanged for existing setups.
                val base = "nothing to post or comment"
                val detail = articleSource.emptyReason()?.let { "$base — $it" } ?: base
                ambientRuns.record(source, OUTCOME_NO_OP, action = preferredAction, detail = detail)
                log.atInfo().setMessage("ambient tick: nothing to post or comment")
                    .addKeyValue("event", EV_NOOP).addKeyValue("source", source.name.lowercase()).log()
            }
        } catch (e: Exception) {
            // A failed tick is a recorded skip, not a crash-loop (direction doc §8): record and swallow,
            // attributed to the action that was mid-attempt when it threw.
            ambientRuns.record(source, OUTCOME_FAILED, action = attempted, detail = e.message ?: e.javaClass.simpleName)
            log.atError().setMessage("ambient tick failed: {}").addArgument(e.message)
                .addKeyValue("event", EV_FAILED).addKeyValue("source", source.name.lowercase())
                .addKeyValue("action", attempted)
                .addKeyValue("reason", e.message ?: e.javaClass.simpleName).setCause(e).log()
        }
    }

    /**
     * The post action (§5 step 3). Returns true iff it EXECUTED (an article was available). An article with
     * no roster is a recorded no-op that still counts as "handled" (there's no roster to comment with
     * either), so the tick doesn't then pointlessly try the comment action.
     */
    private fun tryPost(source: TickSource, roster: List<PersonaRepository.Persona>): Boolean {
        val article = articleSource.next() ?: return false
        if (roster.isEmpty()) {
            ambientRuns.record(
                source, OUTCOME_NO_OP, action = ACTION_POST, detail = "no personas",
                articleTitle = article.title, articleUrl = article.url,
            )
            log.atInfo().setMessage("ambient tick: empty roster, nothing to author as")
                .addKeyValue("event", EV_NOOP).addKeyValue("source", source.name.lowercase()).log()
            return true
        }
        // Relevance-ranked author pick (supersedes S1 blind round-robin): the persona whose abilities best
        // match the article. When no persona matches (all score 0), fall back to the S1 round-robin so a
        // topic no one is tagged for still gets posted (index = prior run count % size, rowid-ordered roster).
        val articleText = "${article.title}\n\n${article.summary}"
        val persona = AmbientGate.bestByRelevance(roster) { AmbientGate.relevance(it.abilities, articleText) }
            ?: roster[ambientRuns.count() % roster.size]

        val threadId = UUID.randomUUID().toString()
        // OP body is the article summary + its link (no LLM call of its own). authorId attributes the thread.
        threads.insert(threadId, article.title, "${article.summary}\n\n${article.url}", authorId = persona.id)
        // The run row is written BEFORE the dispatch (issue #15), not after. The summon's post-settle hook
        // needs a row to price, and it runs on a worker: recording afterwards is a race the tick loses
        // whenever a fake — or a fast model — settles before this thread gets back here. Ordering it this
        // way removes the race rather than papering it with a retry: `record` is a synchronous,
        // autocommitted INSERT on THIS thread, so the row is committed before summonAsync is even called,
        // and the hook cannot run before the dispatch that schedules it.
        // Accepted pathological case: if the executor REJECTS the dispatch below, this row stands as a
        // 'posted' run that produced nothing and the catch records a second, 'failed' one. Two rows
        // describing one tick is a better failure than a settled reply whose cost had nowhere to go.
        val runId = ambientRuns.record(
            source, OUTCOME_POSTED, action = ACTION_POST,
            articleTitle = article.title, articleUrl = article.url,
            personaId = persona.id, threadId = threadId,
        )
        // Summon the room exactly as ThreadController.newThread does (async "Whole Topic + Anyone").
        generation.summonAsync(
            threadId = threadId,
            parentId = null,
            personaIds = listOf(GenerationService.AUTO_PERSONA),
            text = "",
            scope = ScopeMode.WHOLE_THREAD,
            routingScope = ScopeMode.WHOLE_THREAD,
            // The post action still grows nothing on settle (a fresh article thread's first round is born
            // at budget 0 and must stall without owner engagement — depth_budget's ambient-stall scenario).
            // The hook exists here ONLY to price the run.
            onSettled = { settled -> recordRunCost(runId, settled) },
        )
        log.atInfo().setMessage("ambient tick posted \"{}\" authored by {}")
            .addArgument(article.title).addArgument(persona.id)
            .addKeyValue("event", EV_POSTED).addKeyValue("source", source.name.lowercase())
            .addKeyValue("persona", persona.id).addKeyValue("thread", threadId).log()
        return true
    }

    /**
     * The comment action (§5 step 4). Returns true iff it EXECUTED (a (thread, persona) pair cleared the
     * gate). Candidates are the active threads (findActive order) × the roster (rowid order), minus the
     * thread's author (exclusion a) and anyone who already POSTED in that thread (exclusion b), each scored
     * by relevance over the thread's title + OP body. The best CLEARING pair (talkativeness × relevance ≥
     * THRESHOLD, deterministic max-score/first-wins) gets a top-level summon carrying AMBIENT_GRANT.
     */
    private fun tryComment(source: TickSource, roster: List<PersonaRepository.Persona>): Boolean {
        if (roster.isEmpty()) return false
        val candidates = buildCommentCandidates(roster)
        val pick = AmbientGate.bestClearing(
            candidates,
            talkativenessOf = { it.persona.dials["talkativeness"] ?: Dials.DEFAULT },
            relevanceOf = { it.relevance },
        ) ?: return false
        // Top-level persona comment (parentId = null), born with the small non-renewing ambient budget so
        // auto-grow can extend it a couple of levels (§2). No routing LLM call — the persona is named.
        // onSettled consumes that fuel WITHOUT owner attention: once the comment settles, the same bounded
        // growth round an owner grant gets runs on the worker (≤2 follow-ups, the §5 cost envelope) —
        // scoped to the settled comment's OWN subtree, so an owner-granted branch elsewhere in the thread
        // that the owner deliberately left un-grown never has its fuel spent by an ambient tick. Safe on
        // failure: autoGrow only grows POSTED leaves with budget > 0, so a FAILED comment (or a growth
        // error, swallowed by the hook's own catch) leaves the thread exactly as the dispatch made it.
        // Recorded BEFORE the dispatch, for the reason spelled out in [tryPost].
        val runId = ambientRuns.record(
            source, OUTCOME_POSTED, action = ACTION_COMMENT,
            personaId = pick.persona.id, threadId = pick.threadId,
        )
        generation.summonAsync(
            threadId = pick.threadId,
            parentId = null,
            personaIds = listOf(pick.persona.id),
            text = "",
            scope = ScopeMode.WHOLE_THREAD,
            routingScope = ScopeMode.WHOLE_THREAD,
            initialBudget = DepthBudget.AMBIENT_GRANT,
            onSettled = { settled ->
                // TWO-PHASE on purpose (issue #15). The comment's own spend is committed FIRST, before
                // growth is attempted, so a growth round that throws still leaves the run priced for what
                // it definitely cost. Charging once at the end would lose the whole figure to the one
                // failure mode the hook's own catch already tolerates. Growth semantics are untouched:
                // still one branch-scoped autoGrow per settled node, so an owner-granted branch elsewhere
                // in the thread is never drained by an ambient settle.
                //
                // Each node's round is ISOLATED, and that is the second half of the same argument. A bare
                // flatMap lets the first node to throw take phase two with it, so every OTHER node's
                // growth replies — already generated, already persisted, already paid for — go unpriced.
                // Per-node runCatching narrows the loss window to exactly the throwing node's own
                // in-flight round: the replies it had not finished producing when it died, which were
                // never persisted and so were never spend anyone can attribute. Everything a sibling
                // branch actually grew is still charged below. (The hook-level catch in
                // GenerationService.summonAsync still guards whatever else in here might throw; this
                // catch exists to keep ONE node's failure from being ALL nodes' failure.)
                recordRunCost(runId, settled)
                val grown = settled.flatMap { node ->
                    runCatching { generation.autoGrow(pick.threadId, withinSubtreeOf = node.id) }
                        .onFailure { e ->
                            log.atWarn().setMessage("ambient growth round failed for node {}: {}")
                                .addArgument(node.id).addArgument(e.message)
                                .addKeyValue("event", EV_GROWTH_FAILED).addKeyValue("node", node.id)
                                .addKeyValue("thread", pick.threadId)
                                .addKeyValue("reason", e.message ?: e.javaClass.simpleName).setCause(e).log()
                        }
                        .getOrElse { emptyList() }
                }
                recordRunCost(runId, grown)
            },
        )
        log.atInfo().setMessage("ambient tick commented as {} in thread {}")
            .addArgument(pick.persona.id).addArgument(pick.threadId)
            .addKeyValue("event", EV_COMMENTED).addKeyValue("source", source.name.lowercase())
            .addKeyValue("persona", pick.persona.id).addKeyValue("thread", pick.threadId).log()
        return true
    }

    /**
     * Add what [replies] cost to run [runId] (issue #15) — the slice that finally puts a figure in
     * `ambient_run.cost_usd`, NULL since V21.
     *
     * Unpriced views write NOTHING. That is the whole rule: a provider that reports no cost (openai,
     * opencode, the stub, an older CLI) leaves the column NULL, and NULL means UNKNOWN. Summing an empty
     * list to 0.0 and writing it would turn "we have no idea what this cost" into "this tick was free",
     * which is the one wrong answer an operator watching spend must never be given. A no-op or failed
     * tick reaches here not at all, so those rows stay unpriced too — correctly: their spend, if any, is
     * unattributable to any settled node.
     *
     * KNOWN EXCLUSION, and it is a real one: the 'Anyone' dispatcher's ROUTING turn is dispatched by the
     * post action and is never charged here. [PersonaRouter.pick] returns only the chosen personas — it
     * discards the response, and with it the usage — and it settles no node, so nothing carrying that
     * spend ever reaches this function. A post run's figure is therefore its REPLIES' cost, slightly under
     * the tick's true spend by one small routing call. Issue #16's surfaces document it where the number
     * is read; it is named here because this is where the number is summed.
     *
     * ACCOUNTING NEVER ABORTS THE PRODUCT. The write is wrapped for the same reason
     * [GenerationService.recordTrace] is: this runs inside the post-settle hook, in FRONT of the growth
     * round, and a locked `ambient_run` row must not be able to cancel a round that pre-#15 accounting
     * could not touch at all. It WARNs rather than debugs — a run that silently stops being priced is
     * exactly the drift an operator watching spend needs told about (see the bdd-tiered-testing skill on
     * log levels as contract).
     */
    private fun recordRunCost(runId: Long, replies: List<ReplyView>) {
        val priced = replies.mapNotNull { it.costUsd }
        if (priced.isEmpty()) return
        try {
            ambientRuns.addCost(runId, priced.sum())
        } catch (e: Exception) {
            log.atWarn().setMessage("could not price ambient run {}: {}")
                .addArgument(runId).addArgument(e.message)
                .addKeyValue("event", EV_COST_FAILED).addKeyValue("run", runId)
                .addKeyValue("reason", e.message ?: e.javaClass.simpleName).setCause(e).log()
        }
    }

    /** One eligible (thread, persona) pairing with its precomputed relevance, for the gate to rank. */
    private data class CommentCandidate(val threadId: String, val persona: PersonaRepository.Persona, val relevance: Int)

    private fun buildCommentCandidates(roster: List<PersonaRepository.Persona>): List<CommentCandidate> {
        val candidates = mutableListOf<CommentCandidate>()
        threads.findActive(ACTIVE_THREAD_LIMIT).forEach { active ->
            val thread = threads.find(active.id) ?: return@forEach
            val text = listOf(thread.title, thread.body).filter { it.isNotBlank() }.joinToString("\n\n")
            val alreadyPosted = comments.postedAuthors(active.id)
            roster.forEach { persona ->
                if (persona.id == thread.authorId) return@forEach   // (a) never comment on your own thread
                if (persona.id in alreadyPosted) return@forEach     // (b) never comment twice in one thread
                candidates += CommentCandidate(active.id, persona, AmbientGate.relevance(persona.abilities, text))
            }
        }
        return candidates
    }

    private companion object {
        // Wire outcome strings — stored verbatim and rendered as data-outcome on the drill-down.
        const val OUTCOME_POSTED = "posted"
        const val OUTCOME_NO_OP = "no-op"
        const val OUTCOME_FAILED = "failed"

        // How many active threads the comment action considers per tick (plan_docs/ambient-slice-2.md §5).
        const val ACTIVE_THREAD_LIMIT = 10

        // Structured event ids for the operator log (the SqliteBackup precedent).
        const val EV_POSTED = "ambient.posted"
        const val EV_COMMENTED = "ambient.commented"
        const val EV_NOOP = "ambient.noop"
        const val EV_FAILED = "ambient.failed"

        // Best-effort faults inside the post-settle hook (issue #15). Both are WARN, not ERROR: the tick
        // itself succeeded and the room is unaffected — what is lost is a figure and a follow-up round.
        const val EV_COST_FAILED = "ambient.cost.failed"
        const val EV_GROWTH_FAILED = "ambient.growth.failed"
    }
}
