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

    // --- surrogate safety: the cut is at a UTF-16 index, and the text is a model's, so it has emoji ----

    /** Where the head ends for [cap] — the boundary the marker leaves room for. */
    private fun keepFor(cap: Int) = cap - ToolSummaries.MARKER.length

    /**
     * True iff [s] is well-formed UTF-16 (no unpaired surrogate). Encoding to UTF-8 and back replaces an
     * unpaired surrogate with U+FFFD, so a round trip that changes the string IS the defect — this is the
     * property that matters downstream, since the summary is bound as TEXT into SQLite.
     */
    private fun wellFormed(s: String) = s == String(s.toByteArray(Charsets.UTF_8), Charsets.UTF_8)

    @Test
    fun `an emoji straddling the cut is dropped whole, never left as half a surrogate pair`() {
        // keep = 8 for cap 20, so the emoji's HIGH surrogate lands at index 7 and its LOW surrogate at
        // index 8 — on the far side of the cut. Taking a bare prefix would persist the lone high
        // surrogate: not a character, and no longer the text the tool actually printed.
        val cap = 20
        val head = "x".repeat(keepFor(cap) - 1)
        val clipped = ToolSummaries.clip(head + "😀" + "z".repeat(30), cap)!!

        assertEquals(head + ToolSummaries.MARKER, clipped, "the split pair goes whole, leaving a shorter head")
        assertTrue(clipped.length <= cap, "≤ cap, not == cap: dropping the orphan costs one unit")
        assertTrue(wellFormed(clipped), "no unpaired surrogate may reach the database")
    }

    @Test
    fun `an emoji that fits entirely inside the head survives it`() {
        // The other side of the same boundary: both units of the pair are within `keep`, so nothing is
        // dropped and the result is exactly the cap — the guard must not shorten a clip it has no business
        // touching.
        val cap = 20
        val head = "x".repeat(keepFor(cap) - 2) + "😀"
        val clipped = ToolSummaries.clip(head + "z".repeat(30), cap)!!

        assertEquals(head + ToolSummaries.MARKER, clipped)
        assertEquals(cap, clipped.length, "a pair that fits is kept, so the head still fills the budget")
        assertTrue(wellFormed(clipped))
    }

    @Test
    fun `a megabyte of emoji clips to a well-formed string at BOTH cut parities`() {
        // The realistic shape: a tool printed emoji-laden output. `keep` for the real output cap happens to
        // be EVEN, so an unprefixed run of two-unit emoji would land the cut on a pair boundary and pass
        // whatever clip does — a test that cannot fail. The one-character prefix shifts the parity so the
        // cut falls INSIDE a pair; both are asserted so neither alignment is left uncovered.
        val emoji = "😀".repeat(500_000)
        for (prefix in listOf("", "x")) {
            val clipped = ToolSummaries.clip(prefix + emoji, ToolSummaries.OUTPUT_CAP)!!
            assertTrue(clipped.length <= ToolSummaries.OUTPUT_CAP, "prefix='$prefix'")
            assertTrue(wellFormed(clipped), "half an emoji is not a summary (prefix='$prefix')")
            assertTrue(clipped.endsWith(ToolSummaries.MARKER), "prefix='$prefix'")
        }
    }
}
