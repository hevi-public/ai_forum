package com.aiforum.tier0

import com.aiforum.persona.MemoryProse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: [MemoryProse], the fourth prompt block (plan_docs/persona-memory.md §2.9), beside
 * [InterestProseTest] and for the same reasons. The load-bearing properties: `null` on empty (a
 * member with nothing resurfaced generates with a byte-identical prompt — the S4b parity pin), a
 * frame that carries no digit and no `vote` substring (the firewall's `noVoteSignal` greps
 * substrings), the never-recite steer, and the ONE truncation site — over-long owner bodies cut in
 * CODE POINTS, so an emoji is never split into surrogate halves.
 *
 * What cannot be pinned behaviourally and is stated instead (§7's pre-budgeted list): the
 * `List<String>` signature IS the provenance/structure firewall — there is no parameter through
 * which ids, sources or parent links could reach a prompt, and no test can watch a parameter that
 * does not exist.
 */
@Tag("tier0")
class MemoryProseTest {

    @Test
    fun `null on empty so the no-memory prompt is byte-identical to today's`() {
        assertNull(MemoryProse.block(emptyList()))
    }

    @Test
    fun `renders the frame, one line per body, in the caller's order`() {
        val block = MemoryProse.block(listOf("Checkpoint stalls once ate a weekend", "Fell down a fsync rabbit hole"))
        assertEquals(
            """
            Things you remember from past discussions here:
            - Checkpoint stalls once ate a weekend
            - Fell down a fsync rabbit hole
            Let these quietly shape your reply - never recite or list them.
            """.trimIndent(),
            block,
        )
    }

    @Test
    fun `the frame opener is the one the acceptance steps grep for`() {
        // PersonaMemorySteps.MEMORY_FRAME greps "Things you remember" — scenario 1's HTTP-level
        // decay of byte-identity. If this opener drifts, that scenario goes vacuous; pin it here so
        // the drift reddens a fast test instead.
        assertTrue(MemoryProse.block(listOf("anything"))!!.startsWith("Things you remember"))
    }

    @Test
    fun `the steer tells the member to never recite`() {
        assertTrue(MemoryProse.block(listOf("a memory"))!!.contains("never recite"))
    }

    @Test
    fun `the frame text carries no digit and no vote substring`() {
        // Bodies here are digit-free and vote-free, so anything caught is the FRAME's — the same
        // hygiene the judge prompts' SYSTEM text pins (a digit would put a number in a prompt this
        // slice must keep numberless; "vote" would trip the firewall's substring grep, which rules
        // out even devoted/pivoted).
        val block = MemoryProse.block(listOf("Checkpoint stalls once ate a weekend"))!!
        assertFalse(block.any { it.isDigit() }, "the memory frame must contain no digit, in: $block")
        assertFalse(block.contains("vote", ignoreCase = true), "the memory frame must not contain 'vote', in: $block")
    }

    @Test
    fun `an over-long owner body is truncated in the rendered block, measured in code points`() {
        // Scribe rows are ≤300 by the V28 scoped CHECK; an owner row (or hand SQL) can exceed it,
        // and THIS is the one truncation site (§2.9). 301 emoji = 301 code points but 602 UTF-16
        // units: a .take(300) implementation would cut 150 emoji — or split a surrogate pair — and
        // this test reddens.
        val overlong = "🙂".repeat(301)
        val block = MemoryProse.block(listOf(overlong))!!
        assertTrue(block.contains("- " + "🙂".repeat(300) + "…\n"), "expected 300 code points plus an ellipsis")
        assertFalse(block.contains("🙂".repeat(301)), "the 301st code point must not render")
    }

    @Test
    fun `a body at exactly the bound passes through byte-identical`() {
        val exactly300 = "🙂".repeat(300)
        val block = MemoryProse.block(listOf(exactly300))!!
        assertTrue(block.contains("- $exactly300\n"))
        assertFalse(block.contains("…"), "an in-budget body is never decorated")
    }
}
