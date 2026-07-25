package com.aiforum.tier0

import com.aiforum.persona.InterestProse
import com.aiforum.persona.TopicSpread
import com.aiforum.persona.TopicSpread.SharedTopic
import com.aiforum.persona.TopicSpread.SoleTopic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the two pure renderings of what members are into (see the bdd-tiered-testing skill). No
 * Spring, no LLM — [InterestProse] turning a member's interests into the block appended to its system
 * prompt at generation time, and [TopicSpread] turning the whole room's interests into the readout on
 * `/admin/interests`.
 *
 * They sit together because they are the two ends of one guardrail
 * (`plan_docs/ambient-slice-4b.md` D7/D12): the block a MODEL reads may carry no provenance and no
 * digit, and the readout an OWNER reads may carry names but no number attached to a member. Each
 * absence is asserted structurally here, on the types and signatures, rather than trusted to a comment
 * — the [StanceProseTest] posture, extended to the type level because this slice's convergence risk is
 * what those absences buy.
 */
@Tag("tier0")
class InterestProseTest {

    // --- InterestProse: what the model reads -----------------------------------------------------

    @Test
    fun `block returns null for a member with no interests so callers append nothing`() {
        assertNull(InterestProse.block("Sol", emptyList()))
    }

    @Test
    fun `block header names the member the block is written for`() {
        val text = InterestProse.block("Sol", listOf("kernel scheduling"))
        assertTrue(text!!.contains("What you, Sol, are into at the moment:"))
    }

    @Test
    fun `block renders one line per interest in the caller's order, not sorted`() {
        val text = InterestProse.block("Sol", listOf("typography", "kernel scheduling", "boring technology choices"))!!
        val first = text.indexOf("- typography")
        val second = text.indexOf("- kernel scheduling")
        val third = text.indexOf("- boring technology choices")
        assertTrue(first in 0 until second, "typography must render before kernel scheduling, per input order")
        assertTrue(second in 0 until third, "kernel scheduling must render before boring technology choices")
    }

    @Test
    fun `block ends with a steer against reciting or listing the interests`() {
        val text = InterestProse.block("Sol", listOf("kernel scheduling"))
        assertTrue(
            text!!.contains("never recite or list them"),
            "a persona that announces what it is interested in breaks character and leaks the mechanism",
        )
    }

    @Test
    fun `block emits no digit of its own, so it can never number the list`() {
        val text = InterestProse.block("Sol", listOf("typography", "kernel scheduling"))!!
        assertTrue(
            text.none { it.isDigit() },
            "a numbered list would put digits in a prompt, which is the one thing this slice forbids",
        )
    }

    @Test
    fun `block pins the exact rendering for a two-interest example so the shape cannot drift silently`() {
        assertEquals(
            "What you, Sol, are into at the moment:\n" +
                "- typography\n" +
                "- kernel scheduling\n" +
                "Let these shape what you notice and what you bring up - never recite or list them.",
            InterestProse.block("Sol", listOf("typography", "kernel scheduling")),
        )
    }

    @Test
    fun `block's signature carries no provenance, so the model cannot learn which interests are protected`() {
        // The signature IS the enforcement (D7): a model that could see which of its interests the owner
        // pinned would know which ones are safe to perform at, which is a lever on its own drift.
        val overloads = InterestProse::class.java.methods.filter { it.name == "block" }
        assertEquals(1, overloads.size, "an overload taking sources would be exactly the leak this pins")
        assertEquals(
            listOf(String::class.java, List::class.java),
            overloads.single().parameterTypes.toList(),
            "block takes a member name and phrases - nothing that can say which of them are pinned",
        )
    }

    // --- TopicSpread: what the owner reads -------------------------------------------------------

    @Test
    fun `a phrase more than half the room holds is shared, with its holders named`() {
        val spread = TopicSpread.of(mapOf("agents" to listOf("Sol", "Paul", "Mira", "Dana")), rosterSize = 7)
        assertEquals(listOf(SharedTopic("agents", listOf("Dana", "Mira", "Paul", "Sol"))), spread.shared)
    }

    @Test
    fun `a phrase exactly half the room holds is not shared - a split room is having an argument`() {
        val spread = TopicSpread.of(mapOf("agents" to listOf("Sol", "Paul")), rosterSize = 4)
        assertTrue(
            spread.shared.isEmpty(),
            "calling a two-two split \"most of the room\" would warn the owner every time two members overlap",
        )
    }

    @Test
    fun `a phrase exactly one member holds is sole, with the holder's name`() {
        val spread = TopicSpread.of(mapOf("typography" to listOf("Sol")), rosterSize = 7)
        assertEquals(listOf(SoleTopic("typography", "Sol")), spread.sole)
        assertTrue(spread.shared.isEmpty())
    }

    @Test
    fun `the sentence reads as plain English and spells its counts out`() {
        val spread = TopicSpread.of(
            mapOf(
                "agents" to listOf("Sol", "Paul", "Mira", "Dana"),
                "typography" to listOf("Sol"),
                "kernel scheduling" to listOf("Paul"),
            ),
            rosterSize = 7,
        )
        assertEquals(
            "Most of the room is now into agents. Two members hold a topic nobody else does.",
            spread.sentence,
        )
        assertFalse(
            spread.sentence.any { it.isDigit() },
            "a digit on this page is one copy-paste from a digit in a prompt",
        )
    }

    @Test
    fun `the sentence states the reassuring reading too, or the owner stops reading the page`() {
        val spread = TopicSpread.of(mapOf("agents" to listOf("Sol", "Paul")), rosterSize = 7)
        assertEquals(
            "No topic has taken the room over. Nobody is holding a topic on their own.",
            spread.sentence,
        )
    }

    @Test
    fun `an empty roster yields empty lists and a sentence saying nothing has settled`() {
        val spread = TopicSpread.of(emptyMap(), rosterSize = 0)
        assertTrue(spread.shared.isEmpty())
        assertTrue(spread.sole.isEmpty())
        assertTrue(
            spread.sentence.contains("Nothing has settled yet"),
            "an empty room must not divide by a roster that isn't there: ${spread.sentence}",
        )
    }

    @Test
    fun `phrases render in a stable order, since a Map's iteration order is not part of its contract`() {
        val spread = TopicSpread.of(
            mapOf("typography" to listOf("Sol"), "agents" to listOf("Paul")),
            rosterSize = 7,
        )
        assertEquals(
            listOf("agents", "typography"), spread.sole.map { it.phrase },
            "an admin page whose rows shuffle between two reloads reads as breakage",
        )
    }

    @Test
    fun `the readout types carry no number keyed to a member`() {
        // D12's structural pin: the roster size is an INPUT, and nothing that comes back out of `of` can
        // be stored, compared across weeks, or attached to a member. A count is the shape an owner starts
        // thresholding on, and a threshold an owner acts on is the population metric this slice keeps
        // away from models.
        val types = listOf(
            TopicSpread.SharedTopic::class.java,
            TopicSpread.SoleTopic::class.java,
            TopicSpread.Spread::class.java,
        )
        types.forEach { type ->
            type.declaredFields.filterNot { it.isSynthetic }.forEach { field ->
                assertFalse(
                    field.type.isPrimitive || Number::class.java.isAssignableFrom(field.type),
                    "${type.simpleName}.${field.name} is a number: the readout names holders, never counts them",
                )
            }
        }
    }
}
