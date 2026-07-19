package com.aiforum.service

import com.aiforum.ambient.ArticleSource
import com.aiforum.ambient.TickSource
import com.aiforum.dto.ScopeMode
import com.aiforum.repo.AmbientRunRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.ThreadRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The ambient loop's one-action tick (plan_docs/ambient-slice-1.md), mirroring [GitHubPrIngestionService]
 * — the proven non-HTTP "insert thread + summon" caller. One tick collects at most one article from the
 * [ArticleSource] port, opens it as a thread authored BY a persona (round-robin over the roster, never the
 * owner), and fires the same create-time "Whole Topic + Anyone" summon a normal new thread does — so the
 * tick itself makes NO LLM call; only the summon round (dispatcher + replies) does. Every tick — posted,
 * no-op, or failed — is recorded in `ambient_run` and surfaced on /admin/ambient.
 *
 * A failed tick is a recorded skip, not a crash: any throw is caught and recorded 'failed', never
 * propagated out (direction doc §8 — a scheduled loop must not crash-loop on one bad tick).
 */
@Service
class AmbientTickService(
    private val articleSource: ArticleSource,
    private val personas: PersonaRepository,
    private val threads: ThreadRepository,
    private val ambientRuns: AmbientRunRepository,
    private val generation: GenerationService,
) {
    private val log = LoggerFactory.getLogger(AmbientTickService::class.java)

    /**
     * Run one tick (the 5-step anatomy):
     * 1. No article to post → record a 'no-op' run and return (no LLM call — the noLlmCall scenario).
     * 2. Empty roster → record a 'no-op' run and return (no one to author as).
     * 3. Pick the author round-robin: index = prior ambient_run count % roster size, over the rowid-ordered
     *    roster. The count is read BEFORE this run's record is written, so the first tick on a fresh DB
     *    picks the first-seeded persona.
     * 4. Insert the thread authored by that persona (OP body = summary + link, no LLM call), then summon
     *    the room exactly as the owner-create path does, and record a 'posted' run.
     * 5. Any throw is caught, recorded 'failed' with the message, and swallowed — never propagated.
     */
    fun tick(source: TickSource) {
        try {
            val article = articleSource.next()
            if (article == null) {
                ambientRuns.record(source, OUTCOME_NO_OP, detail = "no articles")
                log.atInfo().setMessage("ambient tick: no article to post")
                    .addKeyValue("event", EV_NOOP).addKeyValue("source", source.name.lowercase()).log()
                return
            }
            val roster = personas.findAllByRowid()
            if (roster.isEmpty()) {
                ambientRuns.record(
                    source, OUTCOME_NO_OP, detail = "no personas",
                    articleTitle = article.title, articleUrl = article.url,
                )
                log.atInfo().setMessage("ambient tick: empty roster, nothing to author as")
                    .addKeyValue("event", EV_NOOP).addKeyValue("source", source.name.lowercase()).log()
                return
            }
            // Round-robin over the roster keyed by prior run count (deterministic for tests, varied in
            // prod; superseded by S2 relevance gating). count() is read here, before the record below.
            val persona = roster[ambientRuns.count() % roster.size]

            val threadId = UUID.randomUUID().toString()
            // OP body is the article summary + its link (no LLM call of its own — see the slice doc's
            // Out-of-scope decision). authorId attributes the thread to the persona.
            threads.insert(threadId, article.title, "${article.summary}\n\n${article.url}", authorId = persona.id)
            // Summon the room exactly as ThreadController.newThread does (async "Whole Topic + Anyone" —
            // the dispatcher reads the whole topic, incl. the persona-authored OP, and routes the reply).
            generation.summonAsync(
                threadId = threadId,
                parentId = null,
                personaIds = listOf(GenerationService.AUTO_PERSONA),
                text = "",
                scope = ScopeMode.WHOLE_THREAD,
                routingScope = ScopeMode.WHOLE_THREAD,
            )
            ambientRuns.record(
                source, OUTCOME_POSTED,
                articleTitle = article.title, articleUrl = article.url,
                personaId = persona.id, threadId = threadId,
            )
            log.atInfo().setMessage("ambient tick posted \"{}\" authored by {}")
                .addArgument(article.title).addArgument(persona.id)
                .addKeyValue("event", EV_POSTED).addKeyValue("source", source.name.lowercase())
                .addKeyValue("persona", persona.id).addKeyValue("thread", threadId).log()
        } catch (e: Throwable) {
            // A failed tick is a recorded skip, not a crash-loop (direction doc §8): record and swallow.
            ambientRuns.record(source, OUTCOME_FAILED, detail = e.message ?: e.javaClass.simpleName)
            log.atError().setMessage("ambient tick failed: {}").addArgument(e.message)
                .addKeyValue("event", EV_FAILED).addKeyValue("source", source.name.lowercase())
                .addKeyValue("reason", e.message ?: e.javaClass.simpleName).setCause(e).log()
        }
    }

    private companion object {
        // Wire outcome strings — stored verbatim and rendered as data-outcome on the drill-down.
        const val OUTCOME_POSTED = "posted"
        const val OUTCOME_NO_OP = "no-op"
        const val OUTCOME_FAILED = "failed"

        // Structured event ids for the operator log (the SqliteBackup precedent — a background job whose
        // ticks an operator watches).
        const val EV_POSTED = "ambient.posted"
        const val EV_NOOP = "ambient.noop"
        const val EV_FAILED = "ambient.failed"
    }
}
