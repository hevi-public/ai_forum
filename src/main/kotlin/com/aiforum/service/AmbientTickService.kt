package com.aiforum.service

import com.aiforum.ambient.AmbientGate
import com.aiforum.ambient.ArticleSource
import com.aiforum.ambient.TickSource
import com.aiforum.domain.budget.DepthBudget
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
                // most informative label the row can carry (not a hardcoded 'post' placeholder).
                ambientRuns.record(source, OUTCOME_NO_OP, action = preferredAction, detail = "nothing to post or comment")
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
        // Summon the room exactly as ThreadController.newThread does (async "Whole Topic + Anyone").
        generation.summonAsync(
            threadId = threadId,
            parentId = null,
            personaIds = listOf(GenerationService.AUTO_PERSONA),
            text = "",
            scope = ScopeMode.WHOLE_THREAD,
            routingScope = ScopeMode.WHOLE_THREAD,
        )
        ambientRuns.record(
            source, OUTCOME_POSTED, action = ACTION_POST,
            articleTitle = article.title, articleUrl = article.url,
            personaId = persona.id, threadId = threadId,
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
        generation.summonAsync(
            threadId = pick.threadId,
            parentId = null,
            personaIds = listOf(pick.persona.id),
            text = "",
            scope = ScopeMode.WHOLE_THREAD,
            routingScope = ScopeMode.WHOLE_THREAD,
            initialBudget = DepthBudget.AMBIENT_GRANT,
            onSettled = { settledIds ->
                settledIds.forEach { generation.autoGrow(pick.threadId, withinSubtreeOf = it) }
            },
        )
        ambientRuns.record(
            source, OUTCOME_POSTED, action = ACTION_COMMENT,
            personaId = pick.persona.id, threadId = pick.threadId,
        )
        log.atInfo().setMessage("ambient tick commented as {} in thread {}")
            .addArgument(pick.persona.id).addArgument(pick.threadId)
            .addKeyValue("event", EV_COMMENTED).addKeyValue("source", source.name.lowercase())
            .addKeyValue("persona", pick.persona.id).addKeyValue("thread", pick.threadId).log()
        return true
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
    }
}
