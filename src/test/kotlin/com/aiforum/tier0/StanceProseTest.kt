package com.aiforum.tier0

import com.aiforum.persona.StanceProse
import com.aiforum.persona.StanceProse.NamedStance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure stances→prose translation (see the bdd-tiered-testing skill). No Spring, no LLM —
 * just [StanceProse] turning a persona's directed stances toward the other participants of the
 * current discussion into the block appended to its system prompt.
 */
@Tag("tier0")
class StanceProseTest {

    @Test
    fun `block returns null for an empty stance list so callers append nothing`() {
        assertNull(StanceProse.block("Sol", emptyList()))
    }

    @Test
    fun `block header names the persona the block is written for`() {
        val text = StanceProse.block("Sol", listOf(NamedStance("Paul", "respects his rigor")))
        assertTrue(text!!.contains("How you, Sol, relate to the others here:"))
    }

    @Test
    fun `block renders a single stance as one dashed line naming the other persona`() {
        val text = StanceProse.block("Sol", listOf(NamedStance("Paul", "respects his rigor")))
        assertTrue(text!!.contains("- Paul: respects his rigor"))
    }

    @Test
    fun `block renders one line per stance in input order, not sorted`() {
        val text = StanceProse.block(
            "Sol",
            listOf(
                NamedStance("Mira", "wary of her certainty"),
                NamedStance("Dana", "enjoys sparring with her"),
                NamedStance("Paul", "respects his rigor"),
            ),
        )!!
        val miraIdx = text.indexOf("- Mira: wary of her certainty")
        val danaIdx = text.indexOf("- Dana: enjoys sparring with her")
        val paulIdx = text.indexOf("- Paul: respects his rigor")
        assertTrue(miraIdx in 0 until danaIdx, "Mira must render before Dana, per input order")
        assertTrue(danaIdx in 0 until paulIdx, "Dana must render before Paul, per input order")
    }

    @Test
    fun `block ends with a steer against reciting or mentioning the stances`() {
        val text = StanceProse.block("Sol", listOf(NamedStance("Paul", "respects his rigor")))
        assertTrue(
            text!!.contains("never recite or mention them"),
            "a persona that recites its own relationship notes breaks character and leaks the mechanism",
        )
    }

    @Test
    fun `block never contains a digit - stances are free text only, never a score`() {
        val text = StanceProse.block(
            "Sol",
            listOf(NamedStance("Paul", "respects his rigor"), NamedStance("Mira", "wary of her certainty")),
        )!!
        assertTrue(text.none { it.isDigit() }, "the relation model must carry no numbers by construction")
    }

    @Test
    fun `block pins the exact rendering for a two-stance example so the shape cannot drift silently`() {
        val text = StanceProse.block(
            "Sol",
            listOf(
                NamedStance("Paul", "pushed back on you twice this week - it's earned a little wariness"),
                NamedStance("Mira", "respects her rigor even when it slows things down"),
            ),
        )
        assertEquals(
            "How you, Sol, relate to the others here:\n" +
                "- Paul: pushed back on you twice this week - it's earned a little wariness\n" +
                "- Mira: respects her rigor even when it slows things down\n" +
                "Let these attitudes colour your tone toward each of them naturally - never recite or mention them.",
            text,
        )
    }
}
