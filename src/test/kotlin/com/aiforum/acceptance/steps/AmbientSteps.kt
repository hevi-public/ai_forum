package com.aiforum.acceptance.steps

import com.aiforum.acceptance.config.ScriptableArticleSource
import com.aiforum.acceptance.support.GenerationSettle
import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.ambient.Article
import com.aiforum.repo.CommentRepository
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Steps for the ambient tick (plan_docs/ambient-slice-1.md, extended by plan_docs/ambient-slice-2.md):
 * the manual admin trigger that runs one tick. S1 gave the tick a single action — collect an article
 * from the ArticleSource port and open it as a persona-authored thread, then let the existing
 * thread-create auto-summon produce the discussion round. S2 adds a second action — drop a persona
 * comment into an existing thread, gated by talkativeness × relevance (`AmbientGate`) — so [triggerTick]
 * now also looks for a freshly-inserted TOP-LEVEL COMMENT (no new thread) and settles that instead.
 * Mirrors ThreadSteps' create-thread step either way: the summon/comment settles asynchronously
 * (summonAsync), so after triggering we look for a fresh row (by rowid — both thread.id and comment.id
 * are TEXT PRIMARY KEYs, not rowid aliases, so SQLite's implicit rowid still orders inserts) and settle
 * it the same way `GenerationSettle` does for owner-created threads/replies.
 *
 * RED (S1): until `POST /admin/ambient/tick` exists, the trigger 404s and no thread row appears.
 * RED (S2): until the comment action exists, an empty ArticleSource always take the "no article" no-op
 * exit before ever considering a comment — no new thread AND no new comment ever appear. Either way the
 * When step must NOT throw, so the Then assertions fail for a clear, informative reason instead of an
 * opaque step error.
 */
class AmbientSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val settle: GenerationSettle,
    private val articleSource: ScriptableArticleSource,
    private val jdbc: JdbcTemplate,
    // The mini-discussion growth assertions read the persisted tree directly (descendantCount), the same
    // layer DepthBudgetSteps asserts branch claims against.
    private val comments: CommentRepository,
) {
    @Given("the ArticleSource has the article {string} at {string} summarised {string}")
    fun articleScripted(title: String, url: String, summary: String) {
        articleSource.add(Article(title, url, summary))
    }

    // S2 (plan_docs/ambient-slice-2.md §6, scenario 5): the acceptance-level pin of the tick's outer
    // failure handling — a broken feed must record a 'failed' run, never crash the tick.
    @Given("the ArticleSource fails with {string}")
    fun articleSourceFails(message: String) {
        articleSource.failWith(message)
    }

    // S5 (plan_docs/ambient-slice-5.md §2 "Distinguishable no-ops", §4 article_source.feature scenarios
    // 2/3): programs the source's own account of why it yielded nothing — "feeds returned no items" vs
    // "all N feed items already seen" — leaving [articles] empty so `next()` still returns null exactly
    // like the plain no-scripting no-op does today. Only the field is wired here (see the doc comment on
    // ScriptableArticleSource.emptyReason in TestBeans.kt); nothing in production reads it yet, so this
    // step alone can never turn a scenario green.
    @Given("the ArticleSource is empty because {string}")
    fun articleSourceEmptyBecause(reason: String) {
        articleSource.emptyReason = reason
    }

    // S2 (plan_docs/ambient-slice-2.md §5 step 4, exclusion rule a): the comment action must never let a
    // persona comment on the thread it authored itself. Background threads are owner-authored (author_id
    // NULL); this flips a thread to persona-authored without going through a real ambient post tick.
    @Given("the thread was authored by {string}")
    fun theThreadWasAuthoredBy(persona: String) {
        jdbc.update("UPDATE thread SET author_id = ? WHERE id = ?", persona, world.threadId)
    }

    @When("the owner triggers an ambient tick")
    fun triggerTick() {
        val beforeThread = newestThreadId()
        val beforeComment = newestTopLevelCommentId()
        val resp = http.post("/admin/ambient/tick")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        val afterThread = newestThreadId()
        if (afterThread != null && afterThread != beforeThread) {
            // A new thread appeared — the "post" outcome. First wait (the room poll) for the round to have
            // started, then settle the WHOLE thread page rather than just the ids that poll happened to
            // catch: a multi-persona dispatcher fan-out (S2's ambient trigger_modes variant) settles
            // unevenly fast, and the first non-empty poll is only whoever landed first. awaitThreadSettled
            // waits out every persona's outcome.
            world.threadId = afterThread
            settle.awaitRoomReplies(afterThread)
            world.lastBody = settle.awaitThreadSettled(afterThread)
            return
        }
        // No new thread. A "comment" tick's reply is inserted ASYNCHRONOUSLY by the summon worker
        // (summonAsync), so it may not exist the instant the POST returns — a single read here would race
        // it. The tick records its run row SYNCHRONOUSLY before returning, so we consult that first: only a
        // dispatched comment run ('posted' + action 'comment', §5 step 4) has a reply on the way — then we
        // poll (bounded) for it. A genuine no-op / failed tick (or, under RED, the endpoint 404ing / the S2
        // comment action not existing yet) records no such run, so we skip the poll entirely, leave world
        // state untouched, and let the Then steps fail with their own clear message.
        if (latestRunIsDispatchedComment()) {
            val afterComment = awaitNewComment(beforeComment)
            if (afterComment != null) {
                // Settle it so the generic reply Then steps (CommonSteps' "the reply is …"/"the reply author
                // is …", GenerationSteps' retry) see the finished state exactly like an owner-summoned reply —
                // the ambient comment's failure/retry lifecycle is a peer of it. (There is no DRAFTING comment
                // row, so once the id appears the row is already terminal; awaitSettled returns at once.)
                world.lastReplyId = afterComment
                world.lastBody = settle.awaitSettled(afterComment)
                awaitAmbientBranchQuiescent(afterComment)
            }
        }
    }

    /**
     * Cross-scenario seatbelt for the settle-triggered growth round: it runs on the pool worker AFTER the
     * summon's in-flight holders are done and registers nothing in InFlightGenerations, so the
     * between-scenario reset can neither cancel nor join it — a scenario that returned as soon as the
     * comment settled could leak growth (fake-LLM calls + inserts) into the NEXT scenario's freshly-reset
     * world. Wait (bounded) until the ambient branch is QUIESCENT: the growable frontier under the ambient
     * comment is empty — no POSTED leaf with budget left, the exact production frontier query, which the
     * branch-scoped growth drains deterministically (a FAILED comment's frontier is empty at once, so the
     * failure scenario pays no extra latency) — and nothing on the thread page still renders as drafting.
     */
    private fun awaitAmbientBranchQuiescent(rootId: String) {
        val threadId = comments.findById(rootId)?.threadId
            ?: error("ambient comment $rootId vanished before quiescence could be confirmed")
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var frontier = comments.growableLeaves(threadId, rootId)
        while (System.currentTimeMillis() < deadline) {
            if (frontier.isEmpty() && !Html.hasAttr(http.get("/threads/$threadId").body ?: "", "data-state", "drafting")) {
                return
            }
            Thread.sleep(POLL_MS)
            frontier = comments.growableLeaves(threadId, rootId)
        }
        error(
            "expected the ambient branch under $rootId to drain to an empty growable frontier " +
                "(settle-triggered growth consumes the AMBIENT_GRANT), but after ${POLL_TIMEOUT_MS}ms " +
                "${frontier.size} growable leaf/leaves remain: ${frontier.map { "${it.id} (budget ${it.depthBudget})" }}",
        )
    }

    /**
     * True when the newest ambient_run recorded a dispatched comment ('posted' + action 'comment'). The
     * failure/retry scenario also lands here: the run records a successful DISPATCH ('posted'), while the
     * comment itself settles FAILED — exactly the owner-as-peer lifecycle (§5). Read straight off the row
     * the tick wrote synchronously, so it's true before the async reply row exists.
     */
    private fun latestRunIsDispatchedComment(): Boolean =
        jdbc.query("SELECT outcome, action FROM ambient_run ORDER BY id DESC LIMIT 1") { rs, _ ->
            rs.getString("outcome") == "posted" && rs.getString("action") == "comment"
        }.firstOrNull() == true

    /**
     * Poll (bounded) for a TOP-LEVEL comment id newer than [before] — the fresh ambient reply the worker
     * inserts. Top-level only (parent_id IS NULL): the comment's settle now auto-runs a growth round on
     * the same worker, so the newest comment overall can already be a growth CHILD by the time we look;
     * the ambient comment itself is always top-level (§5 step 4, parentId = null), children never are.
     */
    private fun awaitNewComment(before: String?): String? {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val id = newestTopLevelCommentId()
            if (id != null && id != before) return id
            Thread.sleep(POLL_MS)
        }
        return null
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

    // S2 (plan_docs/ambient-slice-2.md §5 "Run recording"): the run row now also carries WHICH action it
    // dispatched ('post' | 'comment'), rendered as data-action on the drill-down. Keeps the S1 one-arg
    // step above working unchanged (callers that don't care about the action keep using it).
    @Then("the ambient run is recorded with outcome {string} and action {string}")
    fun ambientRunRecordedWithAction(outcome: String, action: String) {
        val body = http.get("/admin/ambient").body ?: ""
        assertTrue(body.contains("data-ambient-run"), "expected a data-ambient-run row in:\n$body")
        assertTrue(
            Html.hasAttr(body, "data-outcome", outcome),
            "expected a run with data-outcome=\"$outcome\" in:\n$body",
        )
        assertTrue(
            Html.hasAttr(body, "data-action", action),
            "expected a run with data-action=\"$action\" in:\n$body",
        )
    }

    // S5 (plan_docs/ambient-slice-5.md §4 article_source.feature): the run row's rendered detail text
    // becomes assertable via a `data-detail` hook (house convention — the text is already rendered in
    // admin_ambient.kte, the hook just makes it grep-able the same way data-outcome/data-action are).
    // RED today: the hook doesn't exist in the template yet, so this fails honestly with "absent" rather
    // than a false positive from a substring match against the human-readable prose elsewhere on the page.
    @Then("the ambient run detail contains {string}")
    fun ambientRunDetailContains(substring: String) {
        val body = http.get("/admin/ambient").body ?: ""
        assertTrue(body.contains("data-ambient-run"), "expected a data-ambient-run row in:\n$body")
        val detail = Html.latestAmbientRunAttr(body, "data-detail")
        assertTrue(
            detail != null && detail.contains(substring),
            "expected the latest ambient run's data-detail to contain \"$substring\" but it was " +
                (detail?.let { "\"$it\"" } ?: "absent (no data-detail attribute rendered on the run row)") +
                " in:\n$body",
        )
    }

    /** The most recently inserted thread's id, or null if there are none yet. */
    private fun newestThreadId(): String? =
        jdbc.query("SELECT id FROM thread ORDER BY rowid DESC LIMIT 1") { rs, _ -> rs.getString("id") }
            .firstOrNull()

    /** The most recently inserted TOP-LEVEL comment's id, or null if there are none yet (S2 comment
     *  action). Restricted to parent_id IS NULL so a settle-triggered growth child never shadows the
     *  ambient comment it grew under (see [awaitNewComment]). */
    private fun newestTopLevelCommentId(): String? =
        jdbc.query("SELECT id FROM comment WHERE parent_id IS NULL ORDER BY rowid DESC LIMIT 1") { rs, _ -> rs.getString("id") }
            .firstOrNull()

    // --- S2 settle-triggered growth (§2): the mini-discussion asserts -----------------------------

    /**
     * Wait (bounded) for the ambient comment's AMBIENT_GRANT to be CONSUMED by the settle-triggered growth
     * round: descendants under the comment reach [expected] (child at budget 1, grandchild at 0) with NO
     * /auto-grow call from the test — the growth is automatic now. Polls for the TARGET COUNT, not for
     * quiescence: there is an observable gap between the comment settling and the growth children landing
     * (the hook runs after the settle on the same worker), so "posted + nothing in flight" is not "done".
     */
    @Then("the ambient comment's mini-discussion grows to {int} replies on its own")
    fun miniDiscussionGrows(expected: Int) {
        val rootId = world.lastReplyId
            ?: error("no ambient comment was dispatched — nothing to watch grow (did the tick no-op?)")
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var count = comments.descendantCount(rootId)
        while (count < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            count = comments.descendantCount(rootId)
        }
        assertEquals(
            expected, count,
            "expected the ambient comment's settle to auto-grow exactly $expected descendants " +
                "(the AMBIENT_GRANT mini-discussion) without any /auto-grow call, but found $count " +
                "after ${POLL_TIMEOUT_MS}ms",
        )
    }

    /**
     * Non-renewal (§2): after the automatic mini-discussion drained the grant (child 1 → grandchild 0), an
     * EXPLICIT /auto-grow finds no growable leaf on the ambient branch — the fuel is spent and nothing
     * ambient ever re-grants. Only the owner's own engagement (comment / /more) refuels from here.
     */
    @Then("a further auto-grow adds nothing more")
    fun furtherAutoGrowAddsNothing() {
        val rootId = world.lastReplyId ?: error("no ambient comment to check for non-renewal")
        val before = comments.descendantCount(rootId)
        http.post("/threads/${world.threadId}/auto-grow")
        val after = comments.descendantCount(rootId)
        assertEquals(
            before, after,
            "an explicit /auto-grow after the drained AMBIENT_GRANT must add nothing (non-renewing fuel), " +
                "but the branch grew from $before to $after descendants",
        )
    }

    private companion object {
        // Bounded wait for the async comment row to appear (mirrors GenerationSettle's poll budget).
        const val POLL_TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
