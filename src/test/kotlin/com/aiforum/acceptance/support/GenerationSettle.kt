package com.aiforum.acceptance.support

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Mirrors the browser's htmx poll for the async summon path (§4): after a generation starts (the POST
 * returns a DRAFTING fragment), poll `GET /replies/{id}` until the node settles, so step assertions read
 * the final state and the LlmClient spy has been populated. Bounded, so a genuinely stuck draft fails
 * loudly with the last (still-drafting) body rather than hanging the suite.
 */
@Component
@Profile("test")
class GenerationSettle(private val http: HttpClient) {

    /** Poll one node until it is no longer drafting; returns the settled single-node fragment. */
    fun awaitSettled(id: String): String {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var body = ""
        while (System.currentTimeMillis() < deadline) {
            body = http.get("/replies/$id").body ?: ""
            if (!Html.hasAttr(body, "data-state", "drafting")) return body
            Thread.sleep(POLL_MS)
        }
        return body
    }

    /** Settle every node and concatenate the fragments, so count-based assertions see the whole room. */
    fun awaitAllSettled(ids: List<String>): String = ids.joinToString("\n") { awaitSettled(it) }

    /**
     * Poll the create-time room summon until the drafts appear (§4). The summon now routes on a worker
     * (summonAsync), so the create response carries no drafts — GET /threads/{id}/room returns the
     * "summoning" poller while the dispatcher is still choosing, then the drafts as a reply-list fragment
     * once they're registered. Returns the draft node ids (empty if none land before the deadline).
     */
    fun awaitRoomDrafts(threadId: String): List<String> {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val ids = Html.allReplyIds(http.get("/threads/$threadId/room").body ?: "")
            if (ids.isNotEmpty()) return ids
            Thread.sleep(POLL_MS)
        }
        return emptyList()
    }

    /**
     * Poll the FULL thread page until nothing on it is still drafting, then return its body. Unlike
     * [awaitRoomDrafts] (which reads the narrower in-flight "room" fragment: a node that settles fast can
     * be marked done and evicted before ever being observed there), the thread page unions the persisted
     * tree with whatever's still in flight (`ThreadController.renderThread`), so a settled node is never
     * missed no matter how many personas fan out or how unevenly fast they settle. A multi-persona round
     * (e.g. the ambient tick's dispatcher fan-out) needs this rather than [awaitRoomDrafts] alone: that
     * helper only signals "routing has concluded" (the FIRST non-empty poll), it does not reliably return
     * every id from the round. Callers call [awaitRoomDrafts] first for that signal (so an all-settled,
     * all-empty poll here can't be mistaken for "the round hasn't started yet"), then this to actually
     * wait out every persona's settle.
     */
    fun awaitThreadSettled(threadId: String): String {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var body = ""
        while (System.currentTimeMillis() < deadline) {
            body = http.get("/threads/$threadId").body ?: ""
            // Two live states must both be absent before the room is quiescent. "Summoning" covers the
            // async routing window: beginSummon fires synchronously inside summonAsync (before the
            // trigger's HTTP response), and every draft is registered before endSummon clears it — so a
            // poll can never observe the no-drafts gap between dispatch and registration (the CI-only
            // flake in the ambient fan-out scenario: a sample landing there saw "no drafting" and
            // returned a partial room).
            val summoning = Html.hasAttr(body, "data-empty-state", "summoning")
            val drafting = Html.hasAttr(body, "data-state", "drafting")
            if (!summoning && !drafting) return body
            Thread.sleep(POLL_MS)
        }
        return body
    }

    private companion object {
        /**
         * The STUCK-DRAFT guard, deliberately not a performance budget. Every poll here waits on fake
         * seams, so a healthy settle takes milliseconds and this number never costs a green run anything
         * — it is only spent when something is genuinely wedged, and its whole job is to fail loudly
         * instead of hanging the suite.
         *
         * It was 5s, and that made the ambient fan-out scenario ("one persona fails, the rest still
         * post") flaky on CI: three sequential persona settles behind a dispatcher call, on a cold JVM in
         * a container on a shared hosted runner, occasionally ran past the deadline — and expiry RETURNS
         * the still-drafting body rather than throwing, so the room was counted while half-settled and the
         * scenario died on an opaque count mismatch far from the cause. Observed on 2026-07-19
         * (`cb9601c2`), on main (`811f430`), and twice on the S4a branch, always this one scenario, always
         * one of the two runs the push+pull_request pair starts for a commit.
         *
         * Four times the old ceiling, because the constraint is the slowest CI runner rather than the
         * fastest local one. If this scenario flakes again, the next move is instrumenting the settle
         * rather than raising the number again: past this point a deadline this long means wedged, not
         * slow.
         */
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 20L
    }
}
