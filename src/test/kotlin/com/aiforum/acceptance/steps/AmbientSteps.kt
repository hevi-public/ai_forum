package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableArticleSource
import com.aiforum.acceptance.support.GenerationSettle
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.ambient.Article
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Steps for the ambient tick (plan_docs/ambient-slice-1.md): the manual admin trigger that collects
 * one article from the ArticleSource port and opens it as a persona-authored thread, then lets the
 * existing thread-create auto-summon produce the discussion round. Mirrors ThreadSteps' create-thread
 * step: the summon is async (summonAsync), so after triggering we look for a freshly-inserted thread
 * row (by rowid — thread.id is a TEXT PRIMARY KEY, not a rowid alias, so SQLite's implicit rowid still
 * orders inserts) and settle its room the same way `GenerationSettle` does for owner-created threads.
 *
 * RED: until `POST /admin/ambient/tick` exists, the trigger 404s and no thread row appears — the When
 * step must NOT throw in that case, so the Then assertions fail for a clear, informative reason
 * instead of an opaque step error.
 */
class AmbientSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val settle: GenerationSettle,
    private val articleSource: ScriptableArticleSource,
    private val jdbc: JdbcTemplate,
) {
    @Given("the ArticleSource has the article {string} at {string} summarised {string}")
    fun articleScripted(title: String, url: String, summary: String) {
        articleSource.add(Article(title, url, summary))
    }

    @When("the owner triggers an ambient tick")
    fun triggerTick() {
        val before = newestThreadId()
        val resp = http.post("/admin/ambient/tick")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        val after = newestThreadId()
        if (after != null && after != before) {
            // A new thread appeared — this is the "posted" outcome. Settle its auto-summoned room
            // (dispatcher pick + persona reply) the same way ThreadSteps.createThread does, so the
            // spy + rendered page reflect the finished round for the Then steps below.
            world.threadId = after
            settle.awaitAllSettled(settle.awaitRoomDrafts(after))
        }
        // Else: no new thread (a genuine no-op tick, or — under RED — the endpoint 404ing). Leave
        // world.threadId untouched and let the Then steps fail with their own clear message.
    }

    @Then("the thread author is {string}")
    fun threadAuthorIs(name: String) {
        val id = world.threadId ?: error("no thread was created by the ambient tick — nothing to check the author of")
        val body = http.get("/threads/$id").body ?: ""
        assertTrue(
            Html.hasAttr(body, "data-thread-author", name),
            "expected data-thread-author=\"$name\" on the thread page:\n$body",
        )
    }

    @Then("the home rail shows thread {string} authored by {string}")
    fun homeRailShowsAuthoredBy(title: String, name: String) {
        val home = http.get("/").body ?: ""
        val author = Html.threadRowAttr(home, title, "data-thread-author")
        assertTrue(
            author == name,
            "expected data-thread-author=\"$name\" on the home rail row for \"$title\" (was \"$author\") in:\n$home",
        )
    }

    @Then("the ambient run is recorded with outcome {string}")
    fun ambientRunRecorded(outcome: String) {
        val body = http.get("/admin/ambient").body ?: ""
        assertTrue(body.contains("data-ambient-run"), "expected a data-ambient-run row in:\n$body")
        assertTrue(
            Html.hasAttr(body, "data-outcome", outcome),
            "expected a run with data-outcome=\"$outcome\" in:\n$body",
        )
    }

    /** The most recently inserted thread's id, or null if there are none yet. */
    private fun newestThreadId(): String? =
        jdbc.query("SELECT id FROM thread ORDER BY rowid DESC LIMIT 1") { rs, _ -> rs.getString("id") }
            .firstOrNull()
}
