package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Steps for the front-page side rails (the design's left/right rail boxes). Asserts on the stable
 * data-* hooks each box emits: data-rail-box="<name>" marks the box, data-<entry> carries each row's
 * id/key, and data-<name>-empty marks the empty state. Reuses the front-page fetch in HomeSteps
 * ("the owner opens the front page").
 */
class HomeRailSteps(
    private val world: ScenarioWorld,
) {
    private fun body(): String = world.lastBody ?: ""

    // Each rail's row hook and empty-state hook, keyed by the data-rail-box name used in the feature.
    private val entryAttr = mapOf(
        "members" to "data-member-entry",
        "active-threads" to "data-active-thread",
        "recent-comments" to "data-recent-comment",
        "forum-nav" to "data-nav-entry",
        "starred-comments" to "data-starred-comment",
    )
    private val emptyAttr = mapOf(
        "members" to "data-members-empty",
        "active-threads" to "data-active-threads-empty",
        "recent-comments" to "data-recent-comments-empty",
        "starred-comments" to "data-starred-comments-empty",
    )

    /**
     * The named box as a block, or a loud failure. Every entry- and text-level assertion below reads
     * through this rather than through the whole page: a rail's content is not unique to its rail —
     * the thread page renders in its tree the very comment the starred box quotes in the margin — so
     * a page-wide probe answers "is this text anywhere?" when the scenario asked "does this box show
     * it?", and would keep answering yes with the box deleted (plan_docs/ambient-slice-6.md §6).
     */
    private fun box(rail: String): String =
        Html.railBox(body(), rail)
            ?: error("no \"$rail\" rail box (data-rail-box=\"$rail\") on the page:\n${body()}")

    private fun entries(rail: String): List<String> {
        val attr = entryAttr[rail] ?: error("unknown rail \"$rail\"")
        return Html.attrValues(box(rail), attr)
    }

    @Then("the front page shows the {string} rail box")
    fun frontPageShowsRailBox(rail: String) {
        assertTrue(
            Html.hasAttr(body(), "data-rail-box", rail),
            "expected a \"$rail\" rail box (data-rail-box=\"$rail\") in:\n${body()}",
        )
    }

    // Generic alias used by thread-page and any-page assertions (both pages share world.lastBody).
    @Then("the page shows the {string} rail box")
    fun pageShowsRailBox(rail: String) = frontPageShowsRailBox(rail)

    @Then("the {string} rail lists {int} entries")
    fun railListsEntries(rail: String, count: Int) {
        assertEquals(count, entries(rail).size, "$rail rail entries in:\n${body()}")
    }

    @Then("the {string} rail has an entry for {string}")
    fun railHasEntryFor(rail: String, key: String) {
        assertTrue(
            entries(rail).contains(key),
            "expected the $rail rail to have an entry for \"$key\"; entries were ${entries(rail)} in:\n${body()}",
        )
    }

    @Then("the {string} rail shows {string}")
    fun railShows(rail: String, text: String) {
        // Read inside the box (see [box]). The page-level proxy this replaces rested on "the home page
        // renders comment bodies only inside the recent-comments box" — which said nothing about the
        // thread page, where the same step also runs and where the tree carries the very text the
        // starred box quotes.
        val box = box(rail)
        assertTrue(Html.contains(box, text), "expected the $rail rail to show \"$text\"; the box was:\n$box")
    }

    @Then("the {string} rail shows an empty state")
    fun railShowsEmptyState(rail: String) {
        val attr = emptyAttr[rail] ?: error("no empty-state hook for rail \"$rail\"")
        assertTrue(
            Html.hasAttr(body(), attr, "true"),
            "expected the $rail rail empty state ($attr=\"true\") in:\n${body()}",
        )
    }

    @Then("the front page shows the ask-the-room composer")
    fun frontPageShowsAskComposer() {
        // The composer is a <details data-ask-room> disclosure in the main column; the new-thread form
        // (data-new-thread) lives inside it, present in the DOM even while collapsed, so it still posts
        // with JS off. We assert both hooks; the reveal-on-click is a <details> behaviour the HTTP suite
        // can't drive.
        val html = body()
        assertTrue(Html.contains(html, "data-ask-room"), "expected the ask-the-room composer (data-ask-room) in:\n$html")
        assertTrue(Html.contains(html, "data-new-thread"), "expected the new-thread form (data-new-thread) inside it in:\n$html")
    }

    @Then("the ask-the-room composer is open")
    fun askComposerIsOpen() {
        assertTrue(composerIsOpen(), "expected the <details data-ask-room> to be open in:\n${body()}")
    }

    @Then("the ask-the-room composer is collapsed")
    fun askComposerIsCollapsed() {
        assertTrue(!composerIsOpen(), "expected the <details data-ask-room> to be collapsed (no open attr) in:\n${body()}")
    }

    /** True when the <details data-ask-room> opening tag carries the boolean `open` attribute. */
    private fun composerIsOpen(): Boolean {
        val tag = Regex("<details\\b[^>]*data-ask-room[^>]*>").find(body())?.value ?: return false
        return Regex("\\bopen\\b").containsMatchIn(tag)
    }
}
