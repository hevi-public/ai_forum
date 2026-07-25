package com.aiforum.tier0

import com.aiforum.persona.Abilities
import com.aiforum.persona.ComposerPrompts
import com.aiforum.persona.Dials
import com.aiforum.persona.PersonaSpec
import com.aiforum.persona.PriorComposition
import com.aiforum.persona.StanceProse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure persona-authoring contracts (see the bdd-tiered-testing skill). No Spring, no IO —
 * just the dial schema, ability parsing, and the meta-prompt the composer hands the LLM.
 */
@Tag("tier0")
class PersonaTraitsTest {

    // --- Dials: the fixed schema gate -------------------------------------------------------------

    @Test
    fun `normalize fills missing dials with the default and keeps canonical order`() {
        val out = Dials.normalize(mapOf("verbosity" to 9))
        assertEquals(Dials.KEYS, out.keys.toList(), "keys must be the canonical set in order")
        assertEquals(9, out["verbosity"])
        assertEquals(Dials.DEFAULT, out["agreeableness"], "missing dials default")
    }

    @Test
    fun `normalize clamps out-of-range values into the 0 to 10 range`() {
        val out = Dials.normalize(mapOf("agreeableness" to 99, "warmth" to -4))
        assertEquals(Dials.MAX, out["agreeableness"])
        assertEquals(Dials.MIN, out["warmth"])
    }

    @Test
    fun `normalize drops keys that are not part of the schema`() {
        val out = Dials.normalize(mapOf("charisma" to 7))
        assertFalse(out.containsKey("charisma"), "off-schema dials are dropped")
        assertEquals(Dials.KEYS.size, out.size)
    }

    // --- Abilities: comma-separated tags ----------------------------------------------------------

    @Test
    fun `parse trims, drops blanks, and de-duplicates while preserving order`() {
        assertEquals(listOf("kotlin", "systems"), Abilities.parse("  kotlin , systems ,, kotlin "))
    }

    // --- ComposerPrompts: dials -> instruction ----------------------------------------------------

    @Test
    fun `instruction names the abilities and every dial`() {
        val text = ComposerPrompts.instruction(
            PersonaSpec(name = "Lune", abilities = listOf("kotlin", "systems"), dials = mapOf("verbosity" to 1)),
        )
        assertTrue(text.contains("Lune"))
        assertTrue(text.contains("kotlin"))
        Dials.KEYS.forEach { key -> assertTrue(text.contains(key, ignoreCase = true), "instruction must mention $key") }
        assertTrue(text.contains("1"), "the chosen verbosity value is carried through")
    }

    @Test
    fun `the composer system role bakes the no-visible-reasoning contract into every prompt it writes`() {
        // Belt-and-suspenders: a composed persona carries the anti-leak directive itself, so it holds even
        // where PromptRenderer's per-generation steer doesn't reach (see local-model-reasoning-leak.md).
        val system = ComposerPrompts.SYSTEM.lowercase()
        assertTrue(system.contains("only its") && system.contains("finished"), "must demand only the final message")
        assertTrue(system.contains("no visible reasoning") || system.contains("thinking process"), "must forbid visible reasoning")
        assertTrue(system.contains("<think>"), "must point reasoning at <think> tags so it stays strippable")
    }

    @Test
    fun `the composer system role frames the ambient article forum, not a brainstorming room`() {
        // The room runs on its own — personas bring articles and talk to each other, with the owner as a
        // peer. Pinning the absence of "brainstorming" is the point: that word is what made every composed
        // persona read as an assistant waiting for the owner to pose a question.
        val system = ComposerPrompts.SYSTEM.lowercase()
        assertTrue(system.contains("articles"), "the room is about sharing articles")
        assertFalse(system.contains("brainstorming"), "the obsolete brainstorming framing must be gone")
    }

    @Test
    fun `instruction carries the standing relationships when the persona has them`() {
        val text = ComposerPrompts.instruction(
            PersonaSpec(name = "Vex"),
            stances = listOf(StanceProse.NamedStance("Sol", "needles him about hype")),
        )
        assertTrue(text.contains("Sol"), "the other member is named")
        assertTrue(text.contains("needles him about hype"), "the stance prose reaches the authoring model")
        // The live block injected at reply time is the source of truth; a stored prompt that also listed
        // the relations would have the persona reciting a roster instead of behaving like it.
        assertTrue(text.contains("never enumerate them"), "the composer is steered away from listing them")
    }

    @Test
    fun `instruction omits the relationships section entirely when there are none`() {
        val text = ComposerPrompts.instruction(PersonaSpec(name = "Vex"))
        assertFalse(text.contains("Standing relationships"), "no header dangling over zero relations")
    }

    @Test
    fun `an edit instruction carries the previous prompt so the model adjusts rather than regenerates`() {
        val prior = PriorComposition(
            spec = PersonaSpec(name = "Vex", dials = mapOf("agreeableness" to 1)),
            prompt = "OLD: a blunt contrarian.",
        )
        val text = ComposerPrompts.instruction(PersonaSpec(name = "Vex", dials = mapOf("agreeableness" to 8)), prior)
        assertTrue(text.contains("EDIT"), "an edit is flagged as such")
        assertTrue(text.contains("OLD: a blunt contrarian."), "the previous prompt is handed back")
    }
}
