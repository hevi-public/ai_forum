package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.llm.ToolSummaries
import io.cucumber.datatable.DataTable
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Issue #15's assertions: what a turn COST (read over HTTP off the run row) and what it REACHED FOR
 * (read out of `generation_tool_call`).
 *
 * Two different probes on purpose. **Cost** is asserted through the page, on the
 * `data-cost-usd` hook of the /admin/ambient run row — the house data-* convention, and the honest
 * absent-vs-empty probe (JTE omits a null-valued attribute, so "no cost" renders as no attribute at all
 * rather than an empty string that could be mistaken for a zero). **Tool calls** are asserted straight
 * off the DB via JdbcTemplate, the same house-sanctioned shortcut AmbientSteps uses for descendantCount:
 * #15 deliberately ships no tool-call UI (that is issue #16), so there is no rendered surface to read,
 * and inventing one here would pin a contract the next slice has to redesign.
 *
 * The cost assertions POLL. Both cost writes happen on the summon worker, in the post-settle hook —
 * strictly AFTER the node the trigger step waited for is persisted — so a single read races the write
 * it is checking. The deadline is a stuck-worker guard, never a sampling interval: the value normally
 * lands in milliseconds behind fake seams.
 *
 * NOT @Component: glue classes are instantiated by Cucumber, which injects their constructor
 * dependencies from the Spring context (see DatabaseResetHooks' comment).
 */
class UsageSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val jdbc: JdbcTemplate,
) {

    @Then("the latest ambient run's cost is {string}")
    fun latestRunCostIs(expected: String) {
        val actual = awaitLatestRunCost { it == expected }
        assertEquals(
            expected, actual,
            "expected the latest ambient run's data-cost-usd to be \"$expected\" after ${POLL_TIMEOUT_MS}ms " +
                "(the cost is summed on the summon worker's post-settle hook), but it was " +
                (actual?.let { "\"$it\"" } ?: "absent (no data-cost-usd attribute on the run row)"),
        )
    }

    /**
     * The unpriced case: a provider that reports no usage must leave the column NULL, so the attribute is
     * absent entirely. NULL is UNKNOWN — never a rendered "0.0000", which would claim the run was free.
     * Polls the same window as the positive assertion, so a cost that lands LATE fails this rather than
     * sneaking past a single early read.
     */
    @Then("the latest ambient run has no recorded cost")
    fun latestRunHasNoCost() {
        val actual = awaitLatestRunCost { it != null }
        assertNull(
            actual,
            "an unpriced generation must leave the run's cost NULL (absent attribute), never a claimed " +
                "zero — but data-cost-usd rendered as \"$actual\"",
        )
    }

    /**
     * The whole trace of the generation the scenario just ran, in `seq` order:
     *
     *     | seq | tool | linked |
     *
     * `linked` is yes/no for "comment_id points at the posted reply", which is the column that makes a
     * row explainable — the run id is the settled comment's id, so a POSTED reply links, while a turn
     * that generated fine and then could not be SAVED leaves the trace with a NULL comment_id rather than
     * no trace at all. (A turn that died at the LLM seam leaves no rows here at all; V30's header says so.)
     */
    @Then("the generation's tool calls are recorded:")
    fun toolCallsAreRecorded(table: DataTable) {
        val runId = world.lastReplyId ?: error("no generation ran in this scenario — nothing to read a trace of")
        val rows = jdbc.query(
            "SELECT seq, tool_name, comment_id FROM generation_tool_call WHERE run_id = ? ORDER BY seq",
            { rs, _ ->
                listOf(
                    rs.getInt("seq").toString(),
                    rs.getString("tool_name"),
                    if (rs.getString("comment_id") == runId) "yes" else "no",
                )
            },
            runId,
        )
        val expected = table.asMaps().map { listOf(it["seq"], it["tool"], it["linked"]) }
        assertEquals(expected, rows, "the persisted tool-call trace for generation $runId")
    }

    @Then("the recorded tool output is at most {int} characters and does not contain {string}")
    fun recordedOutputIsClipped(cap: Int, sentinel: String) {
        val runId = world.lastReplyId ?: error("no generation ran in this scenario — nothing to read a trace of")
        val outputs = jdbc.query(
            "SELECT output_summary FROM generation_tool_call WHERE run_id = ? ORDER BY seq",
            { rs, _ -> rs.getString("output_summary") },
            runId,
        )
        assertTrue(outputs.isNotEmpty(), "expected a persisted tool call for generation $runId, found none")
        outputs.filterNotNull().forEach { out ->
            assertTrue(
                out.length <= cap,
                "a persisted tool output must be clipped to $cap characters, but one is ${out.length}",
            )
            assertTrue(
                !out.contains(sentinel),
                "the clip must cut the tail off: the stored summary still contains the sentinel \"$sentinel\"",
            )
            assertTrue(
                out.endsWith(ToolSummaries.MARKER),
                "a clipped summary must say so — expected it to end in \"${ToolSummaries.MARKER}\", " +
                    "but it ends \"${out.takeLast(20)}\"",
            )
        }
    }

    @Then("no tool calls were recorded")
    fun noToolCallsRecorded() {
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM generation_tool_call", Int::class.java) ?: 0
        assertEquals(0, count, "a provider that reports no tool calls must leave the trace table empty")
    }

    /**
     * Poll /admin/ambient until the latest run's `data-cost-usd` satisfies [done] (or the deadline
     * passes), then return whatever it was on the last read — so the caller's assertion reports the
     * observed value rather than a bare timeout.
     */
    private fun awaitLatestRunCost(done: (String?) -> Boolean): String? {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var seen = latestRunCost()
        while (!done(seen) && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            seen = latestRunCost()
        }
        return seen
    }

    private fun latestRunCost(): String? =
        Html.latestAmbientRunAttr(http.get("/admin/ambient").body ?: "", "data-cost-usd")

    private companion object {
        // Stuck-worker guard, not a sampling interval (mirrors AmbientSteps' poll budget).
        const val POLL_TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
