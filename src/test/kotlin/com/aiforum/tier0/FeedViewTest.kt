package com.aiforum.tier0

import com.aiforum.web.FeedView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the front page's two-constant vocabulary (plan_docs/ambient-slice-6.md §2.1).
 *
 * Small, but it is the FIRST of the three layers guarding the stored preference — the one that gives the
 * endpoint something to refuse with — and [FeedView.DEFAULT] is what nine untouched front-page scenarios
 * silently rely on. The last test is the one that could not be written anywhere else: every slug this
 * enum can produce has to appear in V29's CHECK list, or the enum can hand the repository a value the
 * database refuses, and neither layer would notice until a click.
 */
@Tag("tier0")
class FeedViewTest {

    @Test
    fun `a slug round-trips to its view`() {
        assertEquals(FeedView.THREADS, FeedView.of("threads"))
        assertEquals(FeedView.ACTIVITY, FeedView.of("activity"))
        FeedView.entries.forEach { assertEquals(it, FeedView.of(it.slug), "${it.name} must round-trip its own slug") }
    }

    @Test
    fun `a slug that names no view answers null rather than a fallback`() {
        // Null and not DEFAULT: the endpoint needs to tell "the owner picked threads" from "this is not a
        // view at all", or an unknown name would be stored as the default instead of refused.
        assertNull(FeedView.of("chronological"))
        assertNull(FeedView.of(""))
        assertNull(FeedView.of("THREADS"), "the slug is the wire form, and the wire form is lower case")
    }

    @Test
    fun `an absent slug answers null`() {
        assertNull(FeedView.of(null))
    }

    @Test
    fun `the default view is the thread cards`() {
        // The out-of-the-box front page, and the guard the nine untouched front-page scenarios lean on:
        // a fresh DB holds no owner_pref row, so this constant IS what they render against.
        assertEquals(FeedView.THREADS, FeedView.DEFAULT)
    }

    @Test
    fun `every view's slug is one V29's CHECK constraint accepts`() {
        val v29 = javaClass.getResource("/db/migration/V29__front_page_feed.sql")?.readText()
            ?: error("V29__front_page_feed.sql is not on the test classpath")
        val allowed = Regex("CHECK \\(feed_view IN \\(([^)]*)\\)\\)").find(v29)?.groupValues?.get(1)
            ?: error("V29 no longer constrains feed_view; the enum is then the only guard:\n$v29")
        FeedView.entries.forEach {
            assertTrue(
                allowed.contains("'${it.slug}'"),
                "FeedView.${it.name} stores \"${it.slug}\", which V29 refuses — the CHECK list is: $allowed",
            )
        }
    }

    @Test
    fun `the two views name themselves and their empty states apart`() {
        // "no threads yet" is not "nothing has happened yet": one shared empty-state key would make the
        // stream's empty page claim something about threads. And the stream is named Activity, never
        // Ambient — the schema cannot express provenance (D1/I6).
        assertEquals("Activity", FeedView.ACTIVITY.title)
        assertEquals(
            FeedView.entries.size, FeedView.entries.map { it.emptyStateKey }.distinct().size,
            "each view needs its own empty-state key",
        )
        assertEquals(
            FeedView.entries.size, FeedView.entries.map { it.slug }.distinct().size,
            "two views sharing a slug would make the stored preference ambiguous",
        )
    }
}
