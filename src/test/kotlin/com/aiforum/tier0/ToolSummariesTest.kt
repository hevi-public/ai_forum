package com.aiforum.tier0

import com.aiforum.llm.ToolSummaries
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the truncation the persisted tool-call summaries depend on (issue #15). Pure — a string and a
 * cap in, a string out. The property that matters downstream is that a clipped result is EXACTLY the cap
 * long, so "no stored summary exceeds its cap" holds by construction rather than by convention.
 */
@Tag("tier0")
class ToolSummariesTest {

    @Test
    fun `null passes through, because absent is not the same as empty`() {
        assertNull(ToolSummaries.clip(null, 10))
    }

    @Test
    fun `a string under the cap is returned untouched`() {
        assertEquals("short", ToolSummaries.clip("short", 10))
    }

    @Test
    fun `a string exactly at the cap is untouched — the boundary is inclusive`() {
        val exact = "x".repeat(10)
        assertEquals(exact, ToolSummaries.clip(exact, 10), "at the cap is within the cap, not over it")
    }

    @Test
    fun `one character over the cap is clipped to exactly the cap and marked`() {
        // The off-by-one boundary: 20 is untouched (above), 21 is cut — and cut to 20, not to 20 plus a
        // marker, because the marker lives INSIDE the budget.
        val clipped = ToolSummaries.clip("x".repeat(21), 20)!!
        assertEquals(20, clipped.length)
        assertTrue(clipped.endsWith(ToolSummaries.MARKER))
        assertEquals("x".repeat(20 - ToolSummaries.MARKER.length), clipped.removeSuffix(ToolSummaries.MARKER))
    }

    @Test
    fun `a string exactly at a larger cap is untouched — the other side of the same boundary`() {
        val exact = "x".repeat(20)
        assertEquals(exact, ToolSummaries.clip(exact, 20))
    }

    @Test
    fun `a far-oversized string keeps a prefix and ends in the full marker`() {
        val clipped = ToolSummaries.clip("abcdefghij".repeat(500), 100)!!
        assertEquals(100, clipped.length)
        assertTrue(clipped.startsWith("abcdefghij"), "the head is what identifies the call")
        assertTrue(clipped.endsWith(ToolSummaries.MARKER), "and the tail says it was cut")
    }

    @Test
    fun `the real caps clip a megabyte of tool output down to their own length`() {
        val huge = "y".repeat(1_000_000)
        assertEquals(ToolSummaries.INPUT_CAP, ToolSummaries.clip(huge, ToolSummaries.INPUT_CAP)!!.length)
        assertEquals(ToolSummaries.OUTPUT_CAP, ToolSummaries.clip(huge, ToolSummaries.OUTPUT_CAP)!!.length)
    }

    @Test
    fun `a cap below the marker's own length still respects the cap`() {
        // Defensive only — no caller passes anything this small. It must degrade to a short string, never
        // throw and never overshoot, because a clip that throws would take a whole settle down with it.
        val clipped = ToolSummaries.clip("abcdefghij", 3)!!
        assertEquals(3, clipped.length)
    }
}
