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
    fun `a script change is the word boundary in unspaced CJK text`() {
        // Han → Hiragana frees the edge; Han → Han glues.
        assertTrue(WholeWords.contains("日本語のスレッド", "日本語"))
        assertFalse(WholeWords.contains("日本語のスレッド", "日本"))
    }
}
