package com.aiforum.github

/**
 * Pure Tier-0 formatting of a [PullDetail] into a forum thread's opening post — a title and a markdown body
 * (the PR description, a changed-file list, and a truncated diff). Holds NO IO, so every branch is
 * unit-testable.
 *
 * The body is rendered downstream through the escaping `MarkdownRenderer` (the diff in a fenced ```diff
 * block highlights as a diff), AND it is fed to the room as the opening-post context. The diff is therefore
 * line-capped at [DIFF_LINE_BUDGET] so a large PR can't blow the model's context window — the elided tail
 * links out to the PR on GitHub.
 */
object PrThreadFormat {

    /** Max diff lines embedded in the opening post; beyond this the diff is elided with a link to the PR. */
    const val DIFF_LINE_BUDGET = 300

    /** `#123 — Add gh MCP`. */
    fun title(pull: PullDetail): String = "#${pull.number} — ${pull.title}"

    /**
     * The markdown body for one ingested PR-discussion node (Slice 2). An issue comment renders as its own
     * text; a review is prefixed with a bold one-line verdict (Approved / Requested changes / Dismissed) so
     * a bare approval still reads as something, with the review's own words below when it has any. A plain
     * COMMENTED review (already filtered to non-empty) just shows its body.
     */
    fun commentBody(c: PrComment): String {
        val body = c.body.trim()
        if (c.kind != "review") return body
        val verdict = when (c.reviewState) {
            "APPROVED" -> "**✓ Approved this pull request.**"
            "CHANGES_REQUESTED" -> "**✗ Requested changes.**"
            "DISMISSED" -> "**Review dismissed.**"
            else -> null // COMMENTED / unknown: the body speaks for itself
        }
        return when {
            verdict == null -> body
            body.isEmpty() -> verdict
            else -> "$verdict\n\n$body"
        }
    }

    /** The opening-post markdown: description, then a meta line, then changed files, then the diff. */
    fun body(pull: PullDetail): String {
        val sb = StringBuilder()

        // Lead with the PR description (the author's own words), when there is one.
        val description = pull.body.trim()
        if (description.isNotEmpty()) sb.append(description).append("\n\n")

        // A one-line provenance line: link to the PR, author, branch direction, state.
        val draft = if (pull.isDraft) " · draft" else ""
        sb.append("**[PR #${pull.number} on GitHub](${pull.url})** by ${pull.author} · ")
            .append("`${pull.baseRef} ← ${pull.headRef}`").append(" · ").append(pull.state.lowercase())
            .append(draft).append("\n\n")

        // Changed files with their add/del counts.
        if (pull.changedFiles.isNotEmpty()) {
            sb.append("## Changed files (${pull.changedFiles.size})\n\n")
            pull.changedFiles.forEach { sb.append("- `${it.path}` +${it.additions}/-${it.deletions}\n") }
            sb.append("\n")
        }

        // The diff, fenced (highlights as a diff) and capped to the line budget. The fence length is
        // DYNAMIC — see [codeFence] — because a FIXED 3-backtick opener is not safe: a unified-diff
        // CONTEXT line carries a single-space prefix, and commonmark accepts a CLOSING fence indented up
        // to 3 spaces, so a context line that happens to BE a bare code fence (e.g. a markdown file's own
        // fence, unchanged and therefore shown as context) legally closes a fixed 3-backtick opener early,
        // spilling the rest of the diff into the real markdown parser. (A `+`/`-` prefixed line can't do
        // this — its first character is neither whitespace nor a backtick — but [codeFence] solves it
        // generally rather than trusting that every dangerous shape has been enumerated.)
        val diff = pull.diff.trim()
        if (diff.isNotEmpty()) {
            val lines = diff.lines()
            val fencedDiff = lines.take(DIFF_LINE_BUDGET).joinToString("\n")
            val fence = codeFence(fencedDiff)
            sb.append("## Diff\n\n").append(fence).append("diff\n").append(fencedDiff).append("\n").append(fence).append("\n")
            if (lines.size > DIFF_LINE_BUDGET) {
                sb.append("\n> Diff truncated to $DIFF_LINE_BUDGET of ${lines.size} lines — ")
                    .append("[see the full diff on GitHub](${pull.url}/files).\n")
            }
        }

        return sb.toString().trimEnd()
    }

    /**
     * The backtick fence to wrap [text] in: at least 3 backticks, and always ONE MORE than the longest
     * run of consecutive backticks found anywhere inside [text]. Commonmark closes a fenced code block on
     * the first line that is (up to 3 spaces of indent, then) a run of backticks AT LEAST AS LONG as the
     * opening fence — so an opener strictly longer than every interior run can never be closed early by
     * anything inside [text], regardless of where it sits or how it's indented. `maxOf(3, …)` keeps the
     * common case — no backticks in the diff at all — at the conventional 3-backtick fence.
     */
    private fun codeFence(text: String): String {
        val longestBacktickRun = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        return "`".repeat(maxOf(3, longestBacktickRun + 1))
    }
}
