package com.aiforum.repo

import com.aiforum.llm.ToolCall
import com.aiforum.llm.ToolSummaries
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Clock

/**
 * The write side of `generation_tool_call` (V30, issue #15): the audit trail of the tools ONE generation
 * reached for, written at settle. Append-only and write-only in this slice — the readers (the per-reply
 * trace and the time-window aggregate the two V30 indexes exist for) land with issue #16's surfaces, and
 * adding them here would ship untested query shapes the next slice has to redesign.
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
     * [commentId] is the settled reply when it POSTED and null otherwise. "Otherwise" is narrower than it
     * sounds and V30's header spells it out: the null case is the turn whose model call SUCCEEDED but
     * whose reply could not be persisted (COULDNT_SAVE), which is exactly the trace an operator would
     * otherwise have nowhere to read. A turn that died AT THE SEAM never reaches this method at all — its
     * calls were never returned — so this is not "a failed run still leaves its trace"; it is "a
     * generation that finished and could not be stored still leaves its trace".
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
}
