package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.GenerationSettle
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

/**
 * Step definitions for thread-level operations (create, view). The When step POSTs to /threads; the Then
 * steps assert against the rendered thread page. Creating a thread now auto-summons the room (§2), so the
 * create step settles the drafted reply/replies (mirroring the browser's htmx poll) before the Then steps
 * read the spy / page.
 */
class ThreadSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val settle: GenerationSettle,
) {
    @When("the owner creates a thread {string} asking {string} of {string}")
    fun createThread(title: String, text: String, personaList: String) {
        val personaIds = personaList.split(",").map { it.trim() }
        val resp = http.postJson(
            "/threads",
            mapOf("title" to title, "text" to text, "personaIds" to personaIds),
        )
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        world.threadId = resp.body?.let {
            Regex("""data-thread-id="([^"]+)"""").find(it)?.groupValues?.get(1)
        }
        // Creating a thread auto-summons the room (Whole Topic + Anyone) on a worker — routing included
        // (summonAsync), so the create response carries no drafts yet. Poll the room endpoint until the
        // dispatcher has picked and the drafts are registered, then settle them so the dispatcher + persona
        // calls land in the LlmClient spy and the thread page shows the posted replies the Then steps read.
        settle.awaitAllSettled(settle.awaitRoomReplies(world.threadId ?: ""))
    }

    @When("the owner starts a thread titled {string} from the browser")
    fun startThreadFromBrowser(title: String) {
        // The home page's form posts form-urlencoded (the browser default), then PRG-redirects onto the
        // thread. We locate the freshly-created thread on the home page by title — robust whether or not
        // the HTTP client auto-follows the redirect.
        http.postForm("/threads", mapOf("title" to title))
        world.threadId = createdThreadId(title)
    }

    @When("the owner starts a thread titled {string} with body {string} from the browser")
    fun startThreadWithBodyFromBrowser(title: String, body: String) {
        // Same browser form path as the title-only step, but the new-thread form now carries a body
        // (name="text") alongside the title — the opening post's content.
        http.postForm("/threads", mapOf("title" to title, "text" to body))
        world.threadId = createdThreadId(title)
    }

    /**
     * The id of the thread just created, read off the home page's row for [title] through the row's
     * own hooks. The pair regex this replaces (`data-thread-id="…"\s+data-thread-title="…"`) pinned
     * more than it meant to: it required the two attributes to be ADJACENT and in that order, so
     * inserting a third hook between them — a restyle, not a regression — would fail here as
     * "expected thread … on the home page after create" and send the reader hunting a creation bug
     * that does not exist (plan_docs/ambient-slice-6.md §6).
     */
    private fun createdThreadId(title: String): String {
        val home = http.get("/").body ?: ""
        return Html.threadRowAttr(home, title, "data-thread-id")
            ?: fail("expected thread \"$title\" on the home page after create:\n$home")
    }

    @Then("the thread page shows the post body {string}")
    fun threadPageShowsBody(body: String) {
        val resp = http.get("/threads/${world.threadId}")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        val html = world.lastBody ?: ""
        assertTrue(html.contains("data-op-body"), "expected an OP body element in:\n$html")
        assertTrue(Html.contains(html, body), "expected body \"$body\" in thread page:\n$html")
    }

    @Then("the thread exists with title {string}")
    fun threadExistsWithTitle(title: String) {
        val resp = http.get("/threads/${world.threadId}")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        assertTrue(
            Html.contains(world.lastBody ?: "", title),
            "expected title \"$title\" in thread page:\n${world.lastBody}",
        )
    }

    @When("the owner views the thread page")
    fun viewThreadPage() {
        val resp = http.get("/threads/${world.threadId}")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the thread shows the opening post {string}")
    fun threadShowsOpeningPost(text: String) {
        // The opening question renders on the post node itself (data-op-body), under the title — not as a
        // persona reply, not dropped. Re-fetch so this asserts the persisted+rendered page, not the create
        // response.
        val body = http.get("/threads/${world.threadId}").body ?: ""
        assertTrue(
            Html.contains(body, "data-op-body"),
            "expected an opening-post body element (data-op-body) in:\n$body",
        )
        assertTrue(
            Html.contains(body, text),
            "expected the opening post \"$text\" on the thread page:\n$body",
        )
    }

    /**
     * The browser's room poll, read the way the browser reads it: the fragment body PLUS the htmx swap
     * headers it carries. The create step has already settled the room, so this is the poll that lands
     * AFTER every draft left the in-flight registry — the case the endpoint used to answer with nothing.
     */
    @When("the owner's page polls the room")
    fun pollTheRoom() {
        val resp = http.get("/threads/${world.threadId}/room")
        world.lastStatus = resp.statusCode.value()
        world.lastFragment = resp.body
        world.lastHxRetarget = resp.headers.getFirst("HX-Retarget")
    }

    @Then("the room fragment shows the reply {string}")
    fun roomFragmentShowsReply(body: String) {
        val html = world.lastFragment ?: ""
        assertTrue(
            Html.contains(html, body),
            "expected the room poll to carry the settled reply \"$body\":\n$html",
        )
    }

    @Then("the room fragment's reply is {string}")
    fun roomFragmentReplyState(state: String) {
        val html = world.lastFragment ?: ""
        assertTrue(
            Html.hasAttr(html, "data-state", state),
            "expected a reply in state \"$state\" in the room fragment:\n$html",
        )
    }

    @Then("the room fragment still offers the summoning poller")
    fun roomFragmentStillPolls() {
        val html = world.lastFragment ?: ""
        assertTrue(
            Html.hasAttr(html, "data-empty-state", "summoning"),
            "expected the room poll to re-emit the summoning poller while routing is in flight:\n$html",
        )
    }

    @Then("the room fragment does not retarget the reply list")
    fun roomFragmentDoesNotRetarget() {
        assertNull(
            world.lastHxRetarget,
            "a poller re-emitted mid-routing must replace only itself — retargeting the whole reply list " +
                "would swap the poller away (and take any mid-wait note with it)",
        )
    }

    @Then("the thread carries the reply {string}")
    fun threadCarriesTheReply(text: String) {
        // Settle first: the released routing has to run, pick, and let the persona's reply land. This reads
        // the page the browser would hold once the poller that survived did its job.
        val body = settle.awaitThreadSettled(world.threadId ?: "")
        assertTrue(Html.contains(body, text), "expected the room's reply \"$text\" on the thread page:\n$body")
    }

    @Then("the thread still shows the note {string}")
    fun threadStillShowsNote(text: String) {
        val body = http.get("/threads/${world.threadId}").body ?: ""
        assertTrue(Html.contains(body, text), "expected the owner's note \"$text\" still on the page:\n$body")
    }

    @Then("the room fragment retargets the reply list")
    fun roomFragmentRetargetsReplyList() {
        assertEquals(
            ".reply-list",
            world.lastHxRetarget,
            "expected the room fragment to retarget the whole reply list (HX-Retarget), not just the poller",
        )
    }

    @Then("the thread shows the waiting-on-the-room empty state")
    fun threadShowsWaitingState() {
        assertTrue(
            Html.hasAttr(world.lastBody ?: "", "data-empty-state", "waiting"),
            "expected data-empty-state=\"waiting\" in:\n${world.lastBody}",
        )
    }
}
