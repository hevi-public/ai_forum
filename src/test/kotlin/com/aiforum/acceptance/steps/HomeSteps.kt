package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.acceptance.support.TestData
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Step definitions for the home page: empty state and unread-count badge (§2).
 * The "a thread {string} exists" Given is in CommonSteps; these steps extend that contract.
 */
class HomeSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val data: TestData,
) {
    @Given("there are no threads")
    fun noThreads() {
        // no-op: DatabaseResetHooks already deletes all rows before every scenario
    }

    @Given("the thread has {int} replies unread by the owner")
    fun threadHasUnreadReplies(count: Int) {
        val threadId = world.threadId ?: error("no thread in ScenarioWorld — use 'a thread ... exists' first")
        repeat(count) { i ->
            data.insertComment(threadId = threadId, authorId = "persona-a", body = "reply $i")
        }
        // deliberately NOT calling markRead → all N replies remain unread
    }

    @When("the owner opens the front page")
    fun ownerOpensFrontPage() {
        val resp = http.get("/")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the fresh-forum empty state is shown")
    fun freshForumEmptyStateShown() {
        assertTrue(
            Html.hasAttr(world.lastBody ?: "", "data-empty-state", "no-threads"),
            "expected data-empty-state=\"no-threads\" in:\n${world.lastBody}",
        )
    }

    /**
     * The badge is read off THIS scenario's own row, not off the page. A page-wide
     * `data-unread-count="3"` says only that some row somewhere carries the count — with a second
     * thread on the front page it stops distinguishing "this thread's badge" from "a neighbour's",
     * and it is the front page's only per-row number, so nothing else would catch the confusion
     * (plan_docs/ambient-slice-6.md §6).
     */
    @Then("the thread row shows a {string} badge")
    fun threadRowShowsBadge(badge: String) {
        val count = badge.removeSuffix(" new").trim()
        val title = currentThreadTitle()
        assertEquals(
            count,
            Html.threadRowAttr(world.lastBody ?: "", title, "data-unread-count"),
            "expected the \"$title\" row to carry data-unread-count=\"$count\" in:\n${world.lastBody}",
        )
    }

    /**
     * The title of the thread the scenario is about — the key its seeding Given recorded in
     * [ScenarioWorld.threadIds], which is what lets the assertion above name one row. A thread
     * arranged by some other route fails here rather than silently widening the probe back to the
     * whole page, so the widening cannot happen by omission.
     */
    private fun currentThreadTitle(): String {
        val id = world.threadId ?: error("no thread in ScenarioWorld — use 'a thread ... exists' first")
        return world.threadIds.entries.firstOrNull { it.value == id }?.key
            ?: error(
                "no title recorded for thread $id, so the badge cannot be scoped to its row — " +
                    "seed the thread through a step that records its title (world.threadIds)",
            )
    }
}
