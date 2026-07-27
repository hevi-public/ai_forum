package com.aiforum.tier0

import com.aiforum.ambient.WholeWords
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: [WholeWords], the Unicode-script-aware whole-word matcher extracted from `AmbientGate`'s
 * private `containsWholeWord` (plan_docs/persona-memory.md §2.7, build step 4). The behaviour is
 * MOVED, not rewritten — these cases mirror the boundary rules `AmbientGateTest` pins through
 * `relevance`, asserted here directly on the shared object so a recall-motivated edit to the
 * matcher cannot hide behind the gate's counting layer. The extraction's other pin is
 * [AmbientGateTest] itself staying green unchanged: `relevance` now delegates here, so both suites
 * exercise one implementation.
 */
@Tag("tier0")
class WholeWordsTest {

    @Test
    fun `matches case-insensitively as a whole word`() {
        assertTrue(WholeWords.contains("Scaling SQLite", "sqlite"))
        assertTrue(WholeWords.contains("the checkpoint is what stalls everyone", "checkpoint"))
        assertFalse(WholeWords.contains("Scaling SQLite", "postgres"))
    }

    @Test
    fun `a substring inside a larger word never matches`() {
        // The reason SQL LIKE was rejected for recall (§2.7): "cat" must not hit "concatenate".
        assertFalse(WholeWords.contains("we concatenate strings", "cat"))
        assertFalse(WholeWords.contains("Scaling SQLite", "sql"))
        assertFalse(WholeWords.contains("golang is fine", "go"))
        assertTrue(WholeWords.contains("I like go", "go"))
    }

    @Test
    fun `digits and underscores glue onto word edges`() {
        // COMMON-script chars bind to everything, preserving the ASCII behaviour.
        assertFalse(WholeWords.contains("try sqlite3 today", "sqlite"))
        assertFalse(WholeWords.contains("see foo_bar here", "foo"))
    }

    @Test
    fun `punctuation frees an edge`() {
        assertTrue(WholeWords.contains("I love node.js here", "node.js"))
        assertFalse(WholeWords.contains("I love nodexjs here", "node.js"))
        assertTrue(WholeWords.contains("stalls, then recovery", "stalls"))
    }

    @Test
    fun `non-ASCII words match with Unicode-aware boundaries`() {
        // Java's \b treats every non-ASCII letter as a boundary — these are the cases it would break.
        assertTrue(WholeWords.contains("café ist gut", "café"))
        assertFalse(WholeWords.contains("cafés brew coffee", "café"))
        assertTrue(WholeWords.contains("a naïve approach", "naïve"))
    }

    @Test
    fun `combining marks free an adjacent edge and bind as the word's own edge - the asymmetry`() {
        // The half of the rules the class comment used to state backwards, pinned so a future
        // "correction" to the code moves a red test rather than gate and recall semantics.
        // Escaped, never typed: the whole test turns on which spelling is which, and an invisible
        // normalisation difference inside a source literal is not something a reviewer can see.
        val nfd = "cafe\u0301"          // c-a-f-e + COMBINING ACUTE — what NFD text arrives as
        val precomposed = "caf\u00e9"   // c-a-f-é, one code point
        // ADJACENT: Mn is neither letter nor digit, so freeEdge returns before the script test is
        // consulted — one char of lookahead cannot see the "s" hiding behind the mark.
        assertTrue(WholeWords.contains("${nfd}s brew coffee", "cafe"), "an adjacent mark frees the edge")
        // The precomposed spelling answers false for an entirely different reason: "cafe" does not
        // occur in it at all. Two falses, one rule each — not two readings of one rule.
        assertFalse(WholeWords.contains("${precomposed}s brew coffee", "cafe"))
        // AS THE WORD'S OWN EDGE CHAR: the mark is script INHERITED, which glues to anything, so
        // the trailing "s" blocks the match — the same answer the precomposed pair gives.
        assertFalse(WholeWords.contains("${nfd}s brew coffee", nfd), "a mark on the word's edge binds")
        assertTrue(WholeWords.contains("$nfd ist gut", nfd))
    }

    @Test
    fun `a script change is the word boundary in unspaced CJK text`() {
        // Han → Hiragana frees the edge; Han → Han glues.
        assertTrue(WholeWords.contains("日本語のスレッド", "日本語"))
        assertFalse(WholeWords.contains("日本語のスレッド", "日本"))
    }
}
