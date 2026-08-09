package com.aiforum.repo

import com.aiforum.llm.ToolCall
import com.aiforum.llm.ToolSummaries
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Clock

/**
 * `generation_tool_call` (V30, issue #15): the audit trail of the tools ONE generation reached for,
 * written at settle. [record] is append-only, from #15. The readers below — [countSince] (the
 * /admin/ambient usage strip's window aggregate) and [recent] (the /admin/tools trace view) — are
 * issue #16's slice, each riding one of V30's two indexes (`idx_generation_tool_call_started`,
 * `idx_generation_tool_call_comment`) that existed for exactly this purpose but shipped unread.
 *
 * The injected [Clock] is not decoration: it backfills `started_at` for a call the stream never dated,
 * so a time-window aggregate can never silently drop rows (see [record]).
 */
@Repository
class GenerationToolCallRepository(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {

    /**
     * Append [toolCalls] as the trace of generation [runId], numbered from 1 in the order observed.
     *
     * [commentId] is the settled reply when it POSTED and null otherwise — a failed run still leaves its
     * trace, which is when an operator most wants one (V30's header).
     *
     * Two deliberate defences, both cheap:
     *  - **The summaries are re-clipped here.** The parser already clipped them; this door clips again so
     *    the cap is a property of what is STORED rather than of one particular writer. A second writer
     *    added later inherits the guarantee without having read the parser.
     *  - **`started_at` falls back to the clock.** A tool the stream mentioned but never dated (a shape
     *    we did not anticipate, a stream that began mid-turn) would otherwise store NULL and vanish from
     *    every `WHERE started_at BETWEEN …` read — an audit row that exists but cannot be found is worse
     *    than one that doesn't. `ended_at` gets NO fallback: it is genuinely absent for a call whose
     *    result never came back, and inventing an end time would claim the tool returned.
     */
    fun record(runId: String, commentId: String?, toolCalls: List<ToolCall>) {
        toolCalls.forEachIndexed { i, call ->
            jdbc.update(
                "INSERT INTO generation_tool_call(run_id, comment_id, seq, tool_name, input_summary, " +
                    "output_summary, is_error, started_at, ended_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                runId,
                commentId,
                i + 1,
                call.name,
                ToolSummaries.clip(call.inputSummary, ToolSummaries.INPUT_CAP),
                ToolSummaries.clip(call.outputSummary, ToolSummaries.OUTPUT_CAP),
                if (call.isError) 1 else 0,
                (call.startedAt ?: clock.instant()).toString(),
                call.endedAt?.toString(),
            )
        }
    }

    /**
     * One tool call as the /admin/tools trace view reads it: the call's own columns plus [authorId]/
     * [threadId] joined in from its linked comment when there is one — both null for an unlinked
     * (failed-run) trace, which is the honest account of a call nobody can attribute to a persona or a
     * thread. [isError] is a real Boolean here (not the stored 0/1), so the view's `.toString()` at the
     * template boundary is a deliberate, visible step rather than an int silently standing in for it.
     */
    data class ToolCallRow(
        val id: Long,
        val runId: String,
        val commentId: String?,
        val seq: Int,
        val toolName: String,
        val inputSummary: String?,
        val outputSummary: String?,
        val isError: Boolean,
        val startedAt: String?,
        val endedAt: String?,
        val authorId: String?,
        val threadId: String?,
    )

    /**
     * Count of calls whose `started_at` is at or after [cutoffIso] (issue #16) — the /admin/ambient
     * usage strip's window aggregate, over `idx_generation_tool_call_started`. Unlike
     * `AmbientRunRepository.costSince`, this is a plain COUNT: it is NEVER null — a window with no
     * calls is a real, known zero, not an unknown (a count has no "the provider didn't say" case the
     * way a cost does).
     */
    fun countSince(cutoffIso: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM generation_tool_call WHERE started_at >= ?", Int::class.java, cutoffIso) ?: 0

    /**
     * The most recent [limit] calls, newest (highest id) first, each joined to its comment's author and
     * thread when it has one (issue #16's /admin/tools view). [commentId], when given, scopes to one
     * generation's own trace instead — over `idx_generation_tool_call_comment` — for a future per-reply
     * drill-down; there is deliberately no per-RUN filter (no run→generation join exists, by design —
     * see V30's header on why `run_id` carries no FK).
     */
    fun recent(limit: Int, commentId: String? = null): List<ToolCallRow> {
        val clause = if (commentId != null) "WHERE t.comment_id = ? " else ""
        val sql = "SELECT t.id, t.run_id, t.comment_id, t.seq, t.tool_name, t.input_summary, t.output_summary, " +
            "t.is_error, t.started_at, t.ended_at, c.author_id, c.thread_id " +
            "FROM generation_tool_call t LEFT JOIN comment c ON c.id = t.comment_id " +
            clause +
            "ORDER BY t.id DESC LIMIT ?"
        return if (commentId != null) jdbc.query(sql, ::mapRow, commentId, limit) else jdbc.query(sql, ::mapRow, limit)
    }

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = ToolCallRow(
        id = rs.getLong("id"),
        runId = rs.getString("run_id"),
        commentId = rs.getString("comment_id"),
        seq = rs.getInt("seq"),
        toolName = rs.getString("tool_name"),
        inputSummary = rs.getString("input_summary"),
        outputSummary = rs.getString("output_summary"),
        isError = rs.getInt("is_error") != 0,
        startedAt = rs.getString("started_at"),
        endedAt = rs.getString("ended_at"),
        authorId = rs.getString("author_id"),
        threadId = rs.getString("thread_id"),
    )
}
