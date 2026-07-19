package com.aiforum.repo

import com.aiforum.ambient.TickSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Clock

/**
 * The append-only run log for the ambient loop (plan_docs/ambient-slice-1.md), mirroring
 * [RoutingEventRepository]'s shape: [record] lands one row per tick, [recent] feeds the /admin/ambient
 * drill-down, and [count] backs both the /admin stat tile AND the round-robin author pick (index =
 * count % roster size). The injected [Clock] stamps `tick_time` (no `Instant.now()`), so a fixed test
 * clock keeps timestamps deterministic — the same seam discipline as the rest of persistence.
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
     * Append one tick outcome. [outcome] is the wire string the drill-down renders verbatim ('posted' /
     * 'no-op' / 'failed'); [action] is which action it dispatched ('post' | 'comment', V22). The
     * article/persona/thread fields are populated only on a 'posted' run, [detail] only on a skip or
     * failure. `cost_usd` stays NULL until per-run cost capture lands.
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
    ) {
        jdbc.update(
            "INSERT INTO ambient_run(tick_time, source, outcome, action, detail, article_title, article_url, persona_id, thread_id, cost_usd) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            clock.instant().toString(), source.name.lowercase(), outcome, action, detail,
            articleTitle, articleUrl, personaId, threadId, costUsd,
        )
    }

    /** Total recorded ticks — the /admin stat tile figure and the round-robin key (count BEFORE this run). */
    fun count(): Int = jdbc.queryForObject("SELECT COUNT(*) FROM ambient_run", Int::class.java) ?: 0

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
