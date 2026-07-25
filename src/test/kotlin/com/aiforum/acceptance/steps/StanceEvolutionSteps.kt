package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * S4a (plan_docs/ambient-slice-4a.md): the audited relation-stance evolution pass and its owner
 * surface at /admin/stances.
 *
 * The pass runs synchronously on the request thread — the `POST /admin/ambient/tick` precedent — so
 * these steps need no settle-poll: by the time the POST returns, every judgment, every stance write
 * and every recompose in that run has already happened.
 *
 * The stance TEXT assertions deliberately live elsewhere: `the profile for {string} shows a stance
 * toward {string} of {string}` (PersonaSteps) is reused verbatim, so a scenario proves the evolved
 * prose reached the same surface an owner reads rather than a private back door. What is new here is
 * only the audit trail and the revert control.
 *
 * NOT @Component: glue is instantiated by Cucumber, which injects these Spring beans (see the
 * cucumber-spring-bdd skill).
 */
class StanceEvolutionSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
) {

    @When("the owner runs the stance evolution pass")
    fun runEvolutionPass() {
        val resp = http.post(EVOLVE_PATH)
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    /**
     * Revert the newest audited change. The id is read off the page rather than guessed, so the step
     * exercises the same control the owner clicks — if the template stops rendering a revert form, this
     * fails here rather than silently reverting by a fabricated id.
     */
    @When("the owner reverts the latest stance change")
    fun revertLatestChange() {
        val page = http.get(HISTORY_PATH).body ?: ""
        val id = Regex("data-stance-change=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
            ?: error("no stance-change row to revert on $HISTORY_PATH:\n$page")
        val resp = http.post("$HISTORY_PATH/$id/revert")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the stance history records {string} toward {string} changing from {string} to {string}")
    fun historyRecordsChange(from: String, to: String, oldText: String, newText: String) {
        val row = latestRow()
        assertTrue(
            Html.hasAttr(row, "data-stance-from", from) && Html.hasAttr(row, "data-stance-to", to),
            "expected the newest stance-change row to be \"$from\" toward \"$to\", got:\n$row",
        )
        assertTrue(
            fieldText(row, "data-stance-old")?.contains(oldText, ignoreCase = true) == true,
            "expected the superseded stance \"$oldText\" on the row, got: ${fieldText(row, "data-stance-old")}",
        )
        assertTrue(
            fieldText(row, "data-stance-new")?.contains(newText, ignoreCase = true) == true,
            "expected the new stance \"$newText\" on the row, got: ${fieldText(row, "data-stance-new")}",
        )
    }

    /**
     * The cited exchange is snapshotted prose, not a live read of the comment — a body can be edited or
     * have its revision switched after the judgment, and the audit must keep showing what was actually
     * judged (the comment_quote.quoted_text precedent).
     */
    @Then("the stance history entry cites {string}")
    fun historyEntryCites(snippet: String) {
        val row = latestRow()
        assertTrue(
            Html.contains(row, snippet),
            "expected the newest stance-change row to cite \"$snippet\", got:\n$row",
        )
    }

    @Then("the stance history entry links to the cited comment")
    fun historyEntryLinksToComment() {
        val row = latestRow()
        val href = Regex("href=\"(/threads/[^\"]*#reply-[^\"]+)\"").find(row)?.groupValues?.get(1)
        assertNotNull(href, "expected a /threads/…#reply-… permalink to the cited comment in:\n$row")
    }

    @Then("the stance history is empty")
    fun historyIsEmpty() {
        val body = world.lastBody ?: ""
        assertNull(
            Html.latestStanceChangeRow(body),
            "expected NO stance-change rows on the history page, but found one in:\n$body",
        )
        assertTrue(
            Html.hasAttr(body, "data-admin-list-empty", "true"),
            "expected the shared empty-state hook on an empty history page in:\n$body",
        )
    }

    @Then("the stance history entry is marked reverted")
    fun historyEntryIsReverted() {
        val page = http.get(HISTORY_PATH).body ?: ""
        val row = Html.latestStanceChangeRow(page)
            ?: error("no stance-change row on $HISTORY_PATH:\n$page")
        assertTrue(
            Html.hasAttr(row, "data-stance-reverted", "true"),
            "expected the reverted change to be marked data-stance-reverted=\"true\", got:\n$row",
        )
    }

    /** The newest audit row, re-read from the page the last navigation loaded. */
    private fun latestRow(): String {
        val body = world.lastBody ?: ""
        return Html.latestStanceChangeRow(body)
            ?: error("no stance-change row (data-stance-change) on the page:\n$body")
    }

    /** The visible text of the element carrying [hook] inside [row], entity-decoded. */
    private fun fieldText(row: String, hook: String): String? {
        val m = Regex("<[a-z]+\\b[^>]*$hook[^>]*>(.*?)</[a-z]+>", RegexOption.DOT_MATCHES_ALL).find(row)
            ?: return null
        return unescape(m.groupValues[1].replace(Regex("<[^>]*>"), " "))
            .replace(Regex("\\s+"), " ").trim()
    }

    private fun unescape(s: String): String = s
        .replace("&#39;", "'").replace("&#x27;", "'").replace("&apos;", "'")
        .replace("&quot;", "\"").replace("&#34;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&amp;", "&")

    private companion object {
        const val HISTORY_PATH = "/admin/stances"
        const val EVOLVE_PATH = "/admin/stances/evolve"
    }
}
