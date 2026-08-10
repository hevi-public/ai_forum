package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.config.ScriptableLlmClient.Behavior
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.llm.LlmUsage
import com.aiforum.llm.ToolCall
import com.aiforum.llm.ToolSummaries
import io.cucumber.datatable.DataTable
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Duration
import java.util.Locale

/**
 * Issue #15's assertions (what a turn COST and what it REACHED FOR), extended by issue #16 with the
 * surfaces that made both actually visible: the /admin/ambient usage strip (rolling 24h/7d aggregates)
 * and the /admin/tools trace view.
 *
 * Three different probes, on purpose. **Per-run cost** is asserted through the page, on the
 * `data-cost-usd` hook of the /admin/ambient run row — the house data-* convention, and the honest
 * absent-vs-empty probe (JTE omits a null-valued attribute, so "no cost" renders as no attribute at all
 * rather than an empty string that could be mistaken for a zero). **The aggregates strip** is the same
 * idiom one section up (`data-usage-aggregates`), reconciled against the SAME rows the run list shows
 * (no separate ledger — see [runRowCostsSumToStrip7d]). **Tool calls**, pre-#16, were asserted straight
 * off the DB via JdbcTemplate; #16 adds a rendered /admin/tools view, so the NEW assertions below read
 * THAT (the page is now the surface an operator actually looks at), while the pre-existing DB-level
 * Then methods stay as the persistence-layer pin #15 already established.
 *
 * The cost assertions POLL. Both cost writes happen on the summon worker, in the post-settle hook —
 * strictly AFTER the node the trigger step waited for is persisted — so a single read races the write
 * it is checking. The deadline is a stuck-worker guard, never a sampling interval: the value normally
 * lands in milliseconds behind fake seams. Tool-call rows do NOT need this: `recordTrace` runs INLINE
 * inside `settleOne`, the same synchronous call that persists the reply, so by the time the reply is
 * observably POSTED the trace is already written — no separate hook, no race (mirrors the pre-existing
 * DB-level `toolCallsAreRecorded`, which has never needed to poll).
 *
 * NOT @Component: glue classes are instantiated by Cucumber, which injects their constructor
 * dependencies from the Spring context (see DatabaseResetHooks' comment).
 */
class UsageSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val jdbc: JdbcTemplate,
    private val llm: ScriptableLlmClient,
    private val clock: Clock,
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
     * row explainable — the run id is the settled comment's id, so a POSTED reply links and a failed run
     * leaves the trace with a NULL comment_id rather than no trace at all.
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

    // --- issue #16: usage-observability surfaces --------------------------------------------------

    /**
     * A reply the provider both PRICED and reached for tools to write — the one combined shape the
     * reconcile scenario needs (two ticks, each costed AND tooled) that neither of #15's two separate
     * scripting steps (`… costing {double} USD` / `… using tools:`) can express alone. Lives here, not
     * in CommonSteps, because CommonSteps is off-limits (see the class doc) — the underlying seam
     * (`ScriptableLlmClient.Behavior.Respond`) already accepts both `usage` and `toolCalls` together,
     * this just exposes the combination as its own step text.
     */
    @Given("the LLM will respond with {string} costing {double} USD using tools:")
    fun llmWillRespondCostingUsingTools(text: String, costUsd: Double, table: DataTable) {
        val calls = table.asMaps().mapIndexed { i, row ->
            ToolCall(
                id = row["id"] ?: "toolu_${i + 1}",
                name = row["tool"] ?: error("the tools table needs a `tool` column"),
                inputSummary = row["input"],
                outputSummary = row["output"],
                isError = row["error"]?.toBoolean() ?: false,
            )
        }
        llm.enqueue(Behavior.Respond(text, usage = LlmUsage(costUsd = costUsd), toolCalls = calls))
    }

    /**
     * Backdates the MOST RECENTLY recorded ambient run's `tick_time` — the sanctioned direct-SQL
     * mutation for a window-boundary fixture (mirrors [com.aiforum.acceptance.support.TestData]'s own
     * `agoSeconds` idiom, one level down since `ambient_run` has no seeding helper of its own). Computed
     * off the injected [clock] (fixed under `test`), not off `Instant.now()`, so the "N days ago" is
     * exactly N days behind whatever the suite's fixed clock reads — deterministic regardless of when
     * the build actually runs.
     */
    @Given("the latest ambient run happened {int} days ago")
    fun latestAmbientRunHappenedDaysAgo(days: Int) {
        val backdated = clock.instant().minus(Duration.ofDays(days.toLong())).toString()
        jdbc.update("UPDATE ambient_run SET tick_time = ? WHERE id = (SELECT MAX(id) FROM ambient_run)", backdated)
    }

    /**
     * Backdates EVERY `generation_tool_call.started_at` row — a deliberate simplification, not a join
     * back to the run [latestAmbientRunHappenedDaysAgo] just backdated. `generation_tool_call.run_id` is
     * the GENERATION's id (the in-flight node / settled comment id), never the tick's — there is no
     * column to join through to "the latest ambient_run" by design (V30's header) — so scoping this to
     * "that run's" calls would need a much fiddlier query than the fixture is worth. Correct as long as
     * the calling scenario has exactly ONE tooled tick recorded so far (true of every scenario that uses
     * it today): with a single tick's worth of rows in the table, "all of them" and "that run's" name
     * the same set.
     */
    @Given("that run's tool calls started {int} days ago")
    fun thatRunsToolCallsStartedDaysAgo(days: Int) {
        val backdated = clock.instant().minus(Duration.ofDays(days.toLong())).toString()
        jdbc.update("UPDATE generation_tool_call SET started_at = ?", backdated)
    }

    /**
     * Pins the human-visible half of #15's cost figure — the attribute was already pinned by #15's own
     * scenarios; this proves an operator can actually SEE it, not just grep the markup for it.
     *
     * Scoped to the LATEST RUN ROW's own block, not a page-wide [Html.contains]: the /admin/ambient usage
     * strip renders the SAME dollar figure in its own prose whenever the window holds exactly one priced
     * tick (as this scenario's does), so a page-wide probe is satisfiable by the strip alone with the
     * row's own cost span deleted entirely — a vacuous scenario that measurably went green with no
     * per-row cost markup at all. [Html.latestAmbientRunRow] excludes the strip (a `<section>` above the
     * `<ul>`, outside any `<li>`), so only the row itself can satisfy this.
     */
    @Then("the ambient run list shows the cost {string}")
    fun ambientRunListShowsCost(cost: String) {
        val body = awaitAmbientPage { Html.latestAmbientRunRow(it)?.contains(cost) == true }
        val row = Html.latestAmbientRunRow(body)
        assertTrue(
            row != null && row.contains(cost),
            "expected the /admin/ambient latest run ROW to show \"$cost\" visibly (after ${POLL_TIMEOUT_MS}ms) in:\n" +
                (row ?: "<no ambient-run row found>\n$body"),
        )
    }

    /**
     * The usage strip's own four numbers — the 24h/7d cost + tool-call-count aggregates
     * (`AmbientController.usageAggregates`). NOT one population: `cost24h`/`cost7d`
     * (`AmbientRunRepository.costSince`) cover ONLY the ambient-run rows the run list below shows, but
     * `toolCalls24h`/`toolCalls7d` (`GenerationToolCallRepository.countSince`) count EVERY
     * `generation_tool_call` row with no ambient-vs-owner filter — a prior version of this comment
     * conflated the two as "the same rows"; that was the false claim [usageStripCountsOwnerSummonToolCallsButNotCost]
     * below now pins against (see plan_docs/usage-observability.md §3).
     */
    @Then("the usage strip shows a 24h cost of {double} with {int} tool calls, and a 7d cost of {double} with {int} tool calls")
    fun usageStripShows(cost24h: Double, calls24h: Int, cost7d: Double, calls7d: Int) {
        val fmt24 = String.format(Locale.ROOT, "%.4f", cost24h)
        val fmt7 = String.format(Locale.ROOT, "%.4f", cost7d)
        val body = awaitAmbientPage { Html.usageAggregatesAttr(it, "data-cost-7d") == fmt7 }
        assertEquals(fmt24, Html.usageAggregatesAttr(body, "data-cost-24h"), "24h cost on the usage strip after ${POLL_TIMEOUT_MS}ms:\n$body")
        assertEquals(fmt7, Html.usageAggregatesAttr(body, "data-cost-7d"), "7d cost on the usage strip:\n$body")
        assertEquals(calls24h.toString(), Html.usageAggregatesAttr(body, "data-tool-calls-24h"), "24h tool-call count:\n$body")
        assertEquals(calls7d.toString(), Html.usageAggregatesAttr(body, "data-tool-calls-7d"), "7d tool-call count:\n$body")
    }

    /**
     * The vetted pin for the review's population-claim fix (issue #16): an owner summon is not an
     * ambient tick, so it never touches `ambient_run.cost_usd` — the strip's 24h cost stays absent, same
     * as [usageStripShowsHonestUnknown]'s all-unpriced-window case. But
     * `GenerationToolCallRepository.countSince` counts every `generation_tool_call` row with no
     * ambient-vs-owner filter (`run_id` carries no origin marker — V30's header), and `settleOne` records
     * a trace for EVERY generation it settles, owner summons included — so the owner's own tool call DOES
     * land in `toolCalls24h`. Written adversarially first: an assertion that trusted the plan doc's old
     * (now-corrected) "ambient-tick generations only" claim — that this summon would leave the count at 0
     * — reddened against the real count of 1 (`expected: <0> but was: <1>`), which is the empirical proof
     * the claim was wrong, not just a doc nit. See plan_docs/usage-observability.md §3.
     */
    @Then("the usage strip's 24h tool-call count includes the owner's summon, and its 24h cost stays absent")
    fun usageStripCountsOwnerSummonToolCallsButNotCost() {
        val body = awaitAmbientPage { Html.usageAggregatesAttr(it, "data-tool-calls-24h") == "1" }
        assertEquals(
            "1", Html.usageAggregatesAttr(body, "data-tool-calls-24h"),
            "expected the owner summon's own tool call to land in the strip's 24h count (countSince has " +
                "no ambient-vs-owner filter) after ${POLL_TIMEOUT_MS}ms, in:\n$body",
        )
        assertNull(
            Html.usageAggregatesAttr(body, "data-cost-24h"),
            "an owner summon never writes ambient_run.cost_usd (no tick behind it, so no row to charge), " +
                "so the strip's 24h cost must stay absent, but it rendered in:\n$body",
        )
    }

    /**
     * THE RECONCILE. Independently sums every run row's own `data-cost-usd` and checks it equals the
     * strip's `data-cost-7d` — proving the aggregate isn't just a plausible-looking number but is
     * actually the SAME total the individual rows show, computed the honest way (fresh SQL over the
     * same table), not merely "asserted by construction" (see the bdd-tiered-testing skill on tests
     * that cannot fail — restating the implementation would be exactly that trap).
     */
    @Then("the run rows' own costs sum to the usage strip's 7d cost")
    fun runRowCostsSumToStrip7d() {
        val body = http.get("/admin/ambient").body ?: ""
        val strip7d = Html.usageAggregatesAttr(body, "data-cost-7d")?.toDouble()
            ?: error("the usage strip has no data-cost-7d attribute to reconcile against in:\n$body")
        val rowCosts = Html.attrValues(body, "data-cost-usd").map { it.toDouble() }
        assertEquals(
            strip7d, rowCosts.sum(), 1e-9,
            "the run rows' own costs $rowCosts must sum to the strip's 7d figure $strip7d",
        )
    }

    /** The no-usage-provider case: the strip must show the honest unknown, never a claimed price, and
     *  the tool-call counts must be real zeros (never absent — a COUNT is never "unknown"). */
    @Then("the usage strip shows the honest unknown")
    fun usageStripShowsHonestUnknown() {
        val body = awaitAmbientPage { Html.usageAggregatesAttr(it, "data-cost-24h") != null }
        assertNull(
            Html.usageAggregatesAttr(body, "data-cost-24h"),
            "an all-unpriced 24h window must render NO data-cost-24h attribute (after ${POLL_TIMEOUT_MS}ms) in:\n$body",
        )
        assertEquals(
            "0", Html.usageAggregatesAttr(body, "data-tool-calls-24h"),
            "a provider with no tool loop must show a real 0 tool-call count, never absent, in:\n$body",
        )
        assertTrue(
            Html.contains(body, "24h: —"),
            "expected the strip's prose to render the honest \"—\" placeholder for an unknown cost in:\n$body",
        )
    }

    /**
     * The rendered /admin/tools trace for the generation this scenario just ran (`world.lastReplyId`,
     * which IS the generation's `run_id` — see V30's header), read off the page rather than the DB: #16
     * ships the view #15 deliberately didn't, so THIS is the assertion that proves an operator can
     * actually see the trace, not just that it persisted (that half is #15's `toolCallsAreRecorded`).
     * `linked` mirrors that DB-level step's own column — "yes" iff the row's `data-tool-comment` equals
     * the generation id, i.e. the reply POSTED and the trace is explained by it.
     */
    @Then("the tool-call list shows this generation's calls:")
    fun toolCallListShowsCalls(table: DataTable) {
        val runId = world.lastReplyId ?: error("no generation ran in this scenario — nothing to look up in the tool-call list")
        val body = http.get("/admin/tools").body ?: ""
        val rows = Html.toolCallRowsForRun(body, runId)
        // /admin/tools renders newest-id-first; reverse so document order becomes seq order (1, 2, …).
        val actual = rows.asReversed().map { row ->
            listOf(
                Regex("data-tool-seq=\"([^\"]*)\"").find(row)?.groupValues?.get(1),
                Regex("data-tool-name=\"([^\"]*)\"").find(row)?.groupValues?.get(1),
                Regex("data-tool-error=\"([^\"]*)\"").find(row)?.groupValues?.get(1),
                if (Regex("data-tool-comment=\"${Regex.escape(runId)}\"").containsMatchIn(row)) "yes" else "no",
            )
        }
        val expected = table.asMaps().map { listOf(it["seq"], it["tool"], it["error"] ?: "false", it["linked"]) }
        assertEquals(expected, actual, "the /admin/tools rows for generation $runId in:\n$body")
    }

    @Then("the tool-call list contains the text {string}")
    fun toolCallListContainsText(text: String) {
        val body = http.get("/admin/tools").body ?: ""
        assertTrue(Html.contains(body, text), "expected /admin/tools to contain \"$text\" in:\n$body")
    }

    /** The oversized-output boundary, at the RENDERING layer (#15 already pinned it at persistence): the
     *  marker proves the clip happened, and the sentinel's absence proves the clip cut the tail rather
     *  than merely growing the marker past an already-short string. */
    @Then("the tool-call list shows the truncation marker and not the sentinel {string}")
    fun toolCallListShowsTruncationMarker(sentinel: String) {
        val body = http.get("/admin/tools").body ?: ""
        assertTrue(
            Html.contains(body, ToolSummaries.MARKER),
            "expected the truncation marker \"${ToolSummaries.MARKER}\" on /admin/tools in:\n$body",
        )
        assertFalse(
            Html.contains(body, sentinel),
            "the sentinel \"$sentinel\" must never reach the rendered page — its absence is what proves the clip, not just the marker, in:\n$body",
        )
    }

    /**
     * Poll GET /admin/ambient until [done] is satisfied (or the deadline passes), returning whatever was
     * last read either way. Generalises [awaitLatestRunCost]'s reasoning to the usage strip: its cost
     * figures derive from the SAME `ambient_run.cost_usd` column, written in the same post-settle hook,
     * strictly after the reply the trigger step already waited for — so a single read races that write
     * exactly as the per-run probe does.
     */
    private fun awaitAmbientPage(done: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var body = http.get("/admin/ambient").body ?: ""
        while (!done(body) && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            body = http.get("/admin/ambient").body ?: ""
        }
        return body
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
