package com.aiforum.dto

/**
 * A read-only snapshot of forum-wide statistics for the admin dashboard (GET /admin). Assembled by
 * StatsRepository from plain aggregate queries over the current schema — no new storage, no mutation.
 *
 * The well-known comment states and reasoning-leak verdicts are exposed via fixed accessors so the
 * template can emit stable data-stat hooks even when a bucket has zero rows; the open-ended buckets
 * (failure categories, caption states) are kept as maps and rendered row-per-entry.
 */
data class ForumStats(
    val threads: Int,
    val personas: Int,
    val commentsByState: Map<String, Int>,
    val failuresByCategory: Map<String, Int>,
    val personaActivity: List<PersonaActivity>,
    val votes: Int,
    val regeneratedComments: Int,
    val attachments: Int,
    val attachmentBytes: Long,
    val captionsByState: Map<String, Int>,
    val reasoningLeaks: Map<String, Int>,
    // Ambient loop (plan_docs/ambient-slice-1.md): total recorded ticks, and the thread count split by
    // authorship — owner-authored (author_id IS NULL) vs persona-authored (the ambient loop's threads).
    val ambientRuns: Int = 0,
    val threadsOwner: Int = 0,
    val threadsPersona: Int = 0,
    // Audited relation-stance rewrites (plan_docs/ambient-slice-4a.md): how many times the evolution pass
    // has moved an edge. Note carefully WHAT this counts — audit ROWS, i.e. how often the pass acted. It
    // is not a property of any relationship and never reaches a prompt: the relation model itself is prose
    // by hard guardrail (V24/V25 headers), and a per-pair tally is exactly the scoreboard that cut.
    val stanceChanges: Int = 0,
) {
    val commentsTotal: Int get() = commentsByState.values.sum()

    /** POSTED comments authored by each persona/author, highest first. */
    fun stateCount(state: String): Int = commentsByState[state] ?: 0

    fun leakCount(verdict: String): Int = reasoningLeaks[verdict] ?: 0
}

/** One row of the per-author POSTED-comment leaderboard. [name] is null for non-persona authors. */
data class PersonaActivity(val authorId: String, val name: String?, val posted: Int)
