package com.aiforum.repo

import com.aiforum.ambient.TickSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Clock

/**
 * The run log for the ambient loop (plan_docs/ambient-slice-1.md), mirroring [RoutingEventRepository]'s
 * shape: [record] lands one row per tick, [recent] feeds the /admin/ambient drill-down, and [count]
 * backs both the /admin stat tile AND the round-robin author pick (index = count % roster size). The
 * injected [Clock] stamps `tick_time` (no `Instant.now()`), so a fixed test clock keeps timestamps
 * deterministic — the same seam discipline as the rest of persistence.
 *
 * "Append-only" now has one exception, [addCost] (issue #15): a run's SPEND is not knowable when the row
 * is written, because the generations it dispatched settle afterwards on a worker. So the row is
 * inserted unpriced and updated once the replies come back. Nothing else about a run is ever rewritten.
 */
@Repository
class AmbientRunRepository(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {

    /** One recorded tick as the drill-down reads it: the full row, newest-first via [recent]. */
    data class AmbientRun(
        val id: Long,
        val tickTime: String,
        val source: String,
        val outcome: String,
        // WHICH action this run dispatched (V22): 'post' (open an article thread) | 'comment' (drop a
        // persona comment into a live thread). S1 rows read 'post' via the column DEFAULT.
        val action: String,
        val detail: String?,
        val articleTitle: String?,
        val articleUrl: String?,
        val personaId: String?,
        val threadId: String?,
        val costUsd: Double?,
    )

    /**
     * Append one tick outcome and RETURN ITS ID. [outcome] is the wire string the drill-down renders
     * verbatim ('posted' / 'no-op' / 'failed'); [action] is which action it dispatched ('post' |
     * 'comment', V22). The article/persona/thread fields are populated only on a 'posted' run, [detail]
     * only on a skip or failure.
     *
     * `cost_usd` is still NULL at insert — the spend isn't known yet, because the generations this run
     * dispatches settle asynchronously afterwards. That is what the returned id is FOR (issue #15): the
     * tick holds it and the summon's post-settle hook calls [addCost] with what the replies actually
     * cost. V21's header said cost awaited an LlmClient contract change; that change has landed, and
     * this is the slice that spends it.
     *
     * The id comes back via `INSERT … RETURNING id` — one statement, one round trip, and correct under a
     * connection pool. `last_insert_rowid()` would NOT be: it is per-connection state, so a second
     * statement can land on a different pooled connection and read someone else's insert.
     */
    fun record(
        source: TickSource,
        outcome: String,
        action: String = ACTION_POST,
        detail: String? = null,
        articleTitle: String? = null,
        articleUrl: String? = null,
        personaId: String? = null,
        threadId: String? = null,
        costUsd: Double? = null,
    ): Long =
        jdbc.queryForObject(
            "INSERT INTO ambient_run(tick_time, source, outcome, action, detail, article_title, article_url, persona_id, thread_id, cost_usd) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            Long::class.java,
            clock.instant().toString(), source.name.lowercase(), outcome, action, detail,
            articleTitle, articleUrl, personaId, threadId, costUsd,
        ) ?: 0L

    /**
     * Add [deltaUsd] to run [runId]'s cost, treating NULL as the starting point rather than as zero-so-far
     * (`COALESCE`), so the FIRST addition turns an unknown into a known figure and later ones accumulate.
     *
     * Additive rather than set-once because one run's spend arrives in instalments: the ambient comment
     * action pays for its own fan-out, then again for the growth round its settle triggers, and those are
     * two separate moments on the worker (see AmbientTickService.recordRunCost). A caller with nothing to
     * add must not call this at all — writing 0 would turn "we don't know" into "it was free".
     */
    fun addCost(runId: Long, deltaUsd: Double) {
        jdbc.update("UPDATE ambient_run SET cost_usd = COALESCE(cost_usd, 0) + ? WHERE id = ?", deltaUsd, runId)
    }

    /** Total recorded ticks — the /admin stat tile figure and the round-robin key (count BEFORE this run). */
    fun count(): Int = jdbc.queryForObject("SELECT COUNT(*) FROM ambient_run", Int::class.java) ?: 0

    /**
     * Summed cost of every run whose `tick_time` is at or after [cutoffIso] (issue #16) — the
     * /admin/ambient usage strip's 24h/7d windows. The boundary is INCLUSIVE (`>=`): the strip computes
     * [cutoffIso] as `now.minus(Duration...)`, so "24h ago" is itself the oldest instant IN the window,
     * and a run stamped exactly there must count.
     *
     * `SUM` over zero matching rows, or over rows that are ALL NULL, is SQL NULL — and `queryForObject`
     * hands that back as Kotlin `null` rather than a misleading `0.0`, the same absent-means-unknown
     * distinction [addCost]'s `COALESCE` preserves on the write side. A NULL-cost row *inside* the
     * window never breaks the sum either: SQL `SUM` silently skips NULLs, summing only what the priced
     * siblings actually cost.
     */
    fun costSince(cutoffIso: String): Double? =
        jdbc.queryForObject("SELECT SUM(cost_usd) FROM ambient_run WHERE tick_time >= ?", Double::class.java, cutoffIso)

    /** The most recent ticks, newest first — the /admin/ambient run list. */
    fun recent(limit: Int): List<AmbientRun> =
        jdbc.query(
            "SELECT id, tick_time, source, outcome, action, detail, article_title, article_url, persona_id, thread_id, cost_usd " +
                "FROM ambient_run ORDER BY tick_time DESC, id DESC LIMIT ?",
            ::mapRun,
            limit,
        )

    private fun mapRun(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = AmbientRun(
        id = rs.getLong("id"),
        tickTime = rs.getString("tick_time"),
        source = rs.getString("source"),
        outcome = rs.getString("outcome"),
        action = rs.getString("action"),
        detail = rs.getString("detail"),
        articleTitle = rs.getString("article_title"),
        articleUrl = rs.getString("article_url"),
        personaId = rs.getString("persona_id"),
        threadId = rs.getString("thread_id"),
        costUsd = rs.getObject("cost_usd") as? Double,
    )

    companion object {
        // Wire strings for the V22 `action` column, rendered as data-action on the drill-down.
        const val ACTION_POST = "post"
        const val ACTION_COMMENT = "comment"
    }
}
