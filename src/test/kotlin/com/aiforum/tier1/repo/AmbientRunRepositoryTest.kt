package com.aiforum.tier1.repo

import com.aiforum.ambient.TickSource
import com.aiforum.repo.AmbientRunRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Tier-1: [AmbientRunRepository] against the real test SQLite DB (see the bdd-tiered-testing skill).
 *
 * The first test is the load-bearing one for issue #15: `record` now has to hand back the row's
 * AUTOINCREMENT id, because the tick passes that id to a post-settle hook running on another thread.
 * It does so with `INSERT … RETURNING id` through `queryForObject`, which is a shape worth PROVING on
 * the driver we actually ship (xerial 3.53.2) rather than assuming — the alternative is a
 * PreparedStatementCreator + GeneratedKeyHolder, and `last_insert_rowid()` is not an alternative at all
 * (per-connection state, wrong under a pool).
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class AmbientRunRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var runs: AmbientRunRepository

    @BeforeEach
    fun clean() {
        jdbc.update("DELETE FROM ambient_run")
    }

    private fun record(outcome: String = "posted") =
        runs.record(TickSource.MANUAL, outcome, action = AmbientRunRepository.ACTION_POST)

    @Test
    fun `record returns the row's own autoincrement id`() {
        val first = record()
        val second = record()

        assertNotEquals(first, second, "two runs must not share an id")
        assertTrue(first > 0 && second > first, "ids come back ascending from the AUTOINCREMENT column")
        // Independently authored expectation: the ids the repository claims must be the ids in the table.
        val stored = jdbc.query("SELECT id FROM ambient_run ORDER BY id") { rs, _ -> rs.getLong("id") }
        assertEquals(listOf(first, second), stored)
    }

    @Test
    fun `a freshly recorded run is unpriced`() {
        val id = record()
        assertNull(costOf(id), "cost is unknown until the generations this run dispatched settle")
    }

    @Test
    fun `addCost turns a NULL cost into a figure and then accumulates`() {
        // The two-phase write the ambient comment action makes: its own fan-out, then the growth round its
        // settle triggered. COALESCE is what makes the FIRST call an initialisation rather than a no-op.
        val id = record()

        runs.addCost(id, 0.05)
        assertEquals(0.05, costOf(id)!!, 1e-9)

        runs.addCost(id, 0.06)
        assertEquals(0.11, costOf(id)!!, 1e-9)
    }

    @Test
    fun `addCost touches only the run it names`() {
        val priced = record()
        val other = record()

        runs.addCost(priced, 0.42)

        assertEquals(0.42, costOf(priced)!!, 1e-9)
        assertNull(costOf(other), "a sibling run stays unpriced — cost is per run, not per tick batch")
    }

    private fun costOf(id: Long): Double? =
        jdbc.query("SELECT cost_usd FROM ambient_run WHERE id = ?", { rs, _ -> rs.getObject("cost_usd") as? Double }, id)
            .single()

    // --- costSince (issue #16) --------------------------------------------------------------------

    private fun stampAndPrice(tickTime: String, costUsd: Double?) {
        val id = record()
        jdbc.update("UPDATE ambient_run SET tick_time = ? WHERE id = ?", tickTime, id)
        if (costUsd != null) runs.addCost(id, costUsd)
    }

    @Test
    fun `costSince sums runs at or after the cutoff, excluding an older one`() {
        val cutoff = "2026-01-01T00:00:00Z"
        stampAndPrice("2025-12-31T23:59:59Z", 99.0)   // one tick before the cutoff — must be excluded
        stampAndPrice(cutoff, 0.10)                    // exactly AT the cutoff — inclusive, must count
        stampAndPrice("2026-01-01T00:00:01Z", 0.20)    // after the cutoff — must count

        assertEquals(
            0.30, runs.costSince(cutoff)!!, 1e-9,
            "the row exactly at the cutoff counts (>=); the older row must not be summed in",
        )
    }

    @Test
    fun `costSince ignores a NULL-cost row inside the window rather than treating it as zero`() {
        val cutoff = "2026-01-01T00:00:00Z"
        stampAndPrice(cutoff, 0.10)
        stampAndPrice(cutoff, null)   // unpriced run in the same window — SQL SUM must skip it, not zero it

        assertEquals(0.10, runs.costSince(cutoff)!!, 1e-9, "SUM ignores SQL NULL; an unpriced row must not zero out the total")
    }

    @Test
    fun `costSince is null, not zero, when nothing in the window is priced`() {
        assertNull(runs.costSince("2026-01-01T00:00:00Z"), "no runs at all in the window -> unknown, never a claimed 0.0")

        stampAndPrice("2026-01-01T00:00:00Z", null)
        assertNull(runs.costSince("2026-01-01T00:00:00Z"), "an all-NULL window must render as unknown, never a claimed 0.0")
    }
}
