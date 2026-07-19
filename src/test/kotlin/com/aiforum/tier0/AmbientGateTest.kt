package com.aiforum.tier0

import com.aiforum.ambient.AmbientGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure ambient comment gate (plan_docs/ambient-slice-2.md §4). No Spring, no mocks — just the
 * relevance count, the talkativeness × relevance threshold, and the deterministic best-pick helpers.
 */
@Tag("tier0")
class AmbientGateTest {

    @Test
    fun `relevance counts ability tags present as whole words, case-insensitively`() {
        assertEquals(1, AmbientGate.relevance(listOf("sqlite"), "Scaling SQLite"), "matches case-insensitively")
        assertEquals(2, AmbientGate.relevance(listOf("kotlin", "coroutines"), "Kotlin coroutines are not threads"))
        assertEquals(0, AmbientGate.relevance(listOf("java"), "Scaling SQLite"), "no tag present → 0")
    }

    @Test
    fun `relevance respects word boundaries and does not match substrings`() {
        // "sql" is a substring of "SQLite" but not a whole word there, so it must not count.
        assertEquals(0, AmbientGate.relevance(listOf("sql"), "Scaling SQLite"))
        // A hyphenated tag matches only the whole token.
        assertEquals(1, AmbientGate.relevance(listOf("write-ahead"), "the write-ahead log explained"))
        assertEquals(0, AmbientGate.relevance(listOf("head"), "the write-ahead log explained"))
    }

    @Test
    fun `a blank tag never matches`() {
        assertEquals(0, AmbientGate.relevance(listOf("", "  "), "anything at all"))
    }

    @Test
    fun `regex-special characters in a tag are matched literally`() {
        // A '.' in a tag means a literal dot — never "any character" (no pattern semantics leak in).
        assertEquals(1, AmbientGate.relevance(listOf("node.js"), "I love node.js here"))
        assertEquals(0, AmbientGate.relevance(listOf("node.js"), "I love nodexjs here"))
        assertEquals(0, AmbientGate.relevance(listOf("c.t"), "cat sat"))
    }

    @Test
    fun `relevance handles non-ASCII tags with Unicode-aware boundaries`() {
        // Java's \b is ASCII-only at the edges — these would all be stuck at 0 under a \b regex, silently
        // muting any persona whose owner-typed ability starts/ends with a non-ASCII letter.
        assertEquals(1, AmbientGate.relevance(listOf("café"), "café ist gut"))
        assertEquals(1, AmbientGate.relevance(listOf("naïve"), "a naïve approach"))
        // Unspaced CJK: a SCRIPT CHANGE is the word boundary (Han 語 → Hiragana の)…
        assertEquals(1, AmbientGate.relevance(listOf("日本語"), "日本語のスレッド"))
        // …while a same-script continuation is not: "日本" must NOT match inside "日本語" (Han → Han).
        assertEquals(0, AmbientGate.relevance(listOf("日本"), "日本語のスレッド"))
        // The ASCII substring rules still hold unchanged.
        assertEquals(0, AmbientGate.relevance(listOf("go"), "golang"))
        assertEquals(1, AmbientGate.relevance(listOf("go"), "I like go"))
        // A Latin continuation glues even after a non-ASCII edge ("café" is not in "cafés" as a word).
        assertEquals(0, AmbientGate.relevance(listOf("café"), "cafés brew coffee"))
    }

    @Test
    fun `clears requires talkativeness times relevance to reach the threshold`() {
        // Default dial 5 × one matching ability = 5 → passes (the boundary is inclusive).
        assertTrue(AmbientGate.clears(5, 1))
        assertEquals(5, AmbientGate.THRESHOLD)
        // A quiet dial with a single match stays silent.
        assertFalse(AmbientGate.clears(4, 1))
        assertFalse(AmbientGate.clears(2, 1))
        // Zero relevance never clears, however loud the dial (relevance-gated, §6.4).
        assertFalse(AmbientGate.clears(10, 0))
    }

    @Test
    fun `bestClearing picks the max score and breaks ties by input order`() {
        data class C(val id: String, val talk: Int, val rel: Int)
        val cands = listOf(C("a", 5, 1), C("b", 4, 3), C("c", 2, 3))
        // scores: a=5 (clears), b=12 (clears), c=6 (clears) → max is b.
        assertEquals("b", AmbientGate.bestClearing(cands, { it.talk }, { it.rel })?.id)
        // Two equal top scores → the FIRST in input order wins.
        val tie = listOf(C("first", 3, 3), C("second", 3, 3))
        assertEquals("first", AmbientGate.bestClearing(tie, { it.talk }, { it.rel })?.id)
        // Nothing clears → null (the caller then falls back).
        val none = listOf(C("x", 1, 1), C("y", 2, 2))
        assertNull(AmbientGate.bestClearing(none, { it.talk }, { it.rel }))
    }

    @Test
    fun `bestByRelevance picks the highest relevance over zero, first-wins on ties`() {
        data class C(val id: String, val rel: Int)
        assertEquals("b", AmbientGate.bestByRelevance(listOf(C("a", 1), C("b", 3), C("c", 2))) { it.rel }?.id)
        // A tie keeps the first (rowid order → earliest-seeded persona among equals).
        assertEquals("a", AmbientGate.bestByRelevance(listOf(C("a", 2), C("b", 2))) { it.rel }?.id)
        // All zero → null (fall back to the round-robin).
        assertNull(AmbientGate.bestByRelevance(listOf(C("a", 0), C("b", 0))) { it.rel })
    }
}
