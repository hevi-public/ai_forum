package com.aiforum.llm

/**
 * The truncation caps for persisted tool-call summaries (issue #15), and the one function that applies
 * them. Pure Tier-0 — no IO, no clock, no config.
 *
 * WHY A CAP AT ALL. A tool's input is a JSON blob a model wrote and its output is whatever the tool
 * printed: a `Bash` invocation, a PR diff, a fetched page. Any of those is unbounded, and an audit trail
 * that stores megabytes per turn stops being an audit trail and becomes the biggest table in the file.
 * The summary exists to let an operator recognise WHICH call this was, not to reconstruct it.
 *
 * WHY THE RESULT IS EXACTLY [cap] LONG. A clipped string ends in [MARKER], INSIDE the budget rather than
 * appended past it, so "no stored summary exceeds its cap" is a property that holds by construction and
 * can be asserted with a bare `length <= cap`. SQLite has no enforceable length constraint worth the name
 * (a `CHECK (length(x) <= 4000)` is decorative when the writer is the only door), so the cap is enforced
 * in Kotlin — at the parser AND again at the repository, because a defensive re-clip costs nothing and
 * a future second writer will not read this comment.
 */
object ToolSummaries {

    /** Enough to recognise the arguments a model passed; a tool input is a JSON object, not a payload. */
    const val INPUT_CAP = 2000

    /** Twice the input cap: output is where the genuinely unbounded text lives (diffs, page bodies). */
    const val OUTPUT_CAP = 4000

    /** Terminates every clipped summary, so "was this truncated?" is answerable without the original. */
    const val MARKER = "…[truncated]"

    /**
     * [s] unchanged when it is null or already within [cap]; otherwise clipped so the result is EXACTLY
     * [cap] characters and ends in [MARKER]. A cap smaller than the marker degrades to a bare prefix of
     * the marker rather than throwing — defensive only; the two real caps are far above it.
     */
    fun clip(s: String?, cap: Int): String? {
        if (s == null || s.length <= cap) return s
        val keep = (cap - MARKER.length).coerceAtLeast(0)
        return s.take(keep) + MARKER.take(cap)
    }
}
