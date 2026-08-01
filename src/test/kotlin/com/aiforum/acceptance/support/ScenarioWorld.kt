package com.aiforum.acceptance.support

import io.cucumber.spring.ScenarioScope
import org.springframework.stereotype.Component

/**
 * Per-scenario state holder. @ScenarioScope gives a fresh instance per scenario, so nothing leaks
 * across scenarios (see the cucumber-spring-bdd skill — never store scenario state on step fields).
 */
@Component
@ScenarioScope
class ScenarioWorld {
    var threadId: String? = null
    var lastStatus: Int? = null
    var lastBody: String? = null
    var composerTargetId: String? = null

    /** The HX-Trigger response header on the last response, if any — the htmx error advice's
     *  out-of-band failure signal (T1.4). Null when the response carried no such header. */
    var lastHxTrigger: String? = null

    /** The HX-Retarget response header on the last response, if any — how a fragment redirects its own
     *  swap away from the element that asked for it (the room poll's content response retargets the whole
     *  reply list). Null when the response carried no such header. */
    var lastHxRetarget: String? = null

    /** The raw fragment an htmx request returned — a /generate POST's swap payload before any settle
     *  polling, or a room poll's response — so a scenario can assert on the swap structure the browser
     *  actually receives, rather than on a re-rendered page. */
    var lastFragment: String? = null

    /** thread title -> thread id, for steps that act on a thread by its title (e.g. deletion). */
    val threadIds = mutableMapOf<String, String>()

    /** alias (e.g. persona name or "sol's reply") -> reply id, for cross-step references. */
    val replyIds = mutableMapOf<String, String>()
    var lastReplyId: String? = null

    /** alias -> integer snapshot (e.g. a branch's descendant count before autonomous growth). */
    val counts = mutableMapOf<String, Int>()

    /** How many personas the most recent seeding run inserted — for the idempotency assertion. */
    var lastSeedCount: Int? = null

    /** How many persona_stance edges the most recent seeding run inserted — kept separate from
     *  [lastSeedCount] so the persona-count idempotency assertion stays about personas only. */
    var lastStanceSeedCount: Int? = null

    fun rememberReply(alias: String, id: String) {
        replyIds[alias] = id
        lastReplyId = id
    }
}
