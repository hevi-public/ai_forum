package com.aiforum.tier0

import com.aiforum.github.ChangedFile
import com.aiforum.github.PrComment
import com.aiforum.github.PrThreadFormat
import com.aiforum.github.PullDetail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: pure formatting of a [PullDetail] into a thread's opening post — title + markdown body. No IO; the
 * diff-truncation budget and the section assembly are proven here so the acceptance test only has to show
 * the formatted body reached the page.
 */
@Tag("tier0")
class PrThreadFormatTest {

    private fun pull(
        number: Int = 42,
        title: String = "Batch the comment query",
        body: String = "Fixes the N+1 in the comment tree.",
        changedFiles: List<ChangedFile> = listOf(ChangedFile("src/CommentRepository.kt", 12, 3)),
        diff: String = "diff --git a/src/CommentRepository.kt b/src/CommentRepository.kt\n+added\n-removed",
        isDraft: Boolean = false,
    ) = PullDetail(
        number = number, title = title, author = "octocat",
        url = "https://github.com/o/r/pull/$number", state = "OPEN", isDraft = isDraft,
        body = body, baseRef = "main", headRef = "feature", headSha = "deadbeef",
        changedFiles = changedFiles, diff = diff,
    )

    @Test
    fun `title is the number and PR title`() {
        assertEquals("#42 — Batch the comment query", PrThreadFormat.title(pull()))
    }

    @Test
    fun `body leads with the description, then meta, changed files, and a fenced diff`() {
        val body = PrThreadFormat.body(pull())
        assertTrue(body.startsWith("Fixes the N+1 in the comment tree."), "description leads:\n$body")
        assertTrue(body.contains("[PR #42 on GitHub](https://github.com/o/r/pull/42)"), "links to the PR:\n$body")
        assertTrue(body.contains("`main ← feature`"), "shows branch direction:\n$body")
        assertTrue(body.contains("## Changed files (1)"), "has a changed-files section:\n$body")
        assertTrue(body.contains("- `src/CommentRepository.kt` +12/-3"), "lists the file with counts:\n$body")
        assertTrue(body.contains("```diff"), "fences the diff as a diff block:\n$body")
        assertTrue(body.contains("+added"), "carries the diff content:\n$body")
    }

    @Test
    fun `a blank description is omitted so the body opens on the meta line`() {
        val body = PrThreadFormat.body(pull(body = "  "))
        assertTrue(body.startsWith("**[PR #42 on GitHub]"), "no leading blank lines when description is empty:\n$body")
    }

    @Test
    fun `a draft is flagged on the meta line`() {
        assertTrue(PrThreadFormat.body(pull(isDraft = true)).contains("· draft"))
        assertFalse(PrThreadFormat.body(pull(isDraft = false)).contains("· draft"))
    }

    @Test
    fun `no changed files means no changed-files section`() {
        val body = PrThreadFormat.body(pull(changedFiles = emptyList()))
        assertFalse(body.contains("## Changed files"), "section omitted when there are no files:\n$body")
    }

    @Test
    fun `a diff over the line budget is truncated with a note linking to the PR`() {
        val bigDiff = (1..(PrThreadFormat.DIFF_LINE_BUDGET + 50)).joinToString("\n") { "+line $it" }
        val body = PrThreadFormat.body(pull(diff = bigDiff))
        assertTrue(body.contains("+line ${PrThreadFormat.DIFF_LINE_BUDGET}"), "keeps lines up to the budget")
        assertFalse(body.contains("+line ${PrThreadFormat.DIFF_LINE_BUDGET + 1}"), "drops lines past the budget")
        assertTrue(body.contains("Diff truncated to ${PrThreadFormat.DIFF_LINE_BUDGET} of ${PrThreadFormat.DIFF_LINE_BUDGET + 50} lines"), "explains the truncation:\n$body")
        assertTrue(body.contains("https://github.com/o/r/pull/42/files"), "links to the full diff")
    }

    @Test
    fun `a blank diff means no diff section`() {
        val body = PrThreadFormat.body(pull(diff = ""))
        assertFalse(body.contains("```diff"), "no fence when there's no diff:\n$body")
    }

    // --- diff fencing: a PR diff is attacker-influenceable (anyone can open a PR against a public repo),
    // so it must not be able to break out of its own fence. A unified-diff CONTEXT line carries a
    // single-space prefix, and commonmark accepts a CLOSING fence indented up to 3 spaces — so a context
    // line that is exactly "```" (e.g. a markdown file's own fence, unchanged and therefore shown as
    // context) legally closes a fixed 3-backtick opener early. The fence must instead be sized to the
    // content: strictly longer than the longest run of backticks anywhere inside it.

    /** The opening fence string ("```", "````", …) PrThreadFormat used for the "## Diff" section. */
    private fun openingDiffFence(body: String): String =
        Regex("## Diff\n\n(`+)diff\n").find(body)?.groupValues?.get(1)
            ?: error("no diff fence found in body:\n$body")

    @Test
    fun `a diff whose context line is a bare code fence stays inside one fenced block`() {
        val diff = listOf(
            "diff --git a/README.md b/README.md",
            "index 1111111..2222222 100644",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1,3 +1,3 @@",
            " ```",
            "-old fenced content",
            "+new fenced content",
            " ```",
        ).joinToString("\n")
        val body = PrThreadFormat.body(pull(diff = diff))
        val fence = openingDiffFence(body)
        assertTrue(
            fence.length > 3,
            "opening fence must be longer than any backtick run in the diff (a fixed \"```\" is closeable by the diff's own \" ```\" context line), got \"$fence\":\n$body",
        )
        assertTrue(
            body.contains("${fence}diff\n$diff\n$fence"),
            "the whole diff — including its own \" ```\" lines — must sit verbatim between ONE opening and ONE closing fence of the SAME (longer) length:\n$body",
        )
    }

    @Test
    fun `a diff containing a 5-backtick run gets a fence of at least 6 backticks`() {
        val diff = "diff --git a/x.md b/x.md\n+line with a run: `````\n-plain line"
        val body = PrThreadFormat.body(pull(diff = diff))
        val fence = openingDiffFence(body)
        assertTrue(
            fence.length >= 6,
            "a 5-backtick run inside the diff needs a fence of at least 6 backticks, got \"$fence\" (len ${fence.length}):\n$body",
        )
    }

    @Test
    fun `a diff with no backticks still uses the conventional 3-backtick fence`() {
        val body = PrThreadFormat.body(pull()) // default fixture diff carries no backticks
        assertEquals("```", openingDiffFence(body), "clean diffs must render exactly as before:\n$body")
    }

    // --- description fence closing (issue #18 follow-up): the PR DESCRIPTION (pull.body) is raw
    // attacker-authored markdown too, and it sits ABOVE the machine-generated sections. The diff's own
    // fence is now sized to its content (codeFence, above), but that only protects the diff's OWN
    // opener — it can't help if the DESCRIPTION itself is left with a dangling open fence: commonmark
    // then treats everything below it as that fence's content UNTIL some later line validly closes it
    // (up to 3 leading spaces, same char, at least as long, nothing else) — and a diff's own space-
    // prefixed " ```" context line is exactly such a closer, closing the description's fence early and
    // spilling the REST of the diff into live markdown. The fix closes any fence the description left
    // open before the machine sections are appended.

    @Test
    fun `a description ending in a dangling triple-backtick fence is closed before the machine sections, so a diff's own context-line fence can't spill into it`() {
        val description = "Check this fix:\n```\nfun x() = 1"
        val diff = listOf(
            "diff --git a/README.md b/README.md",
            "index 1111111..2222222 100644",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -1,3 +1,3 @@",
            " ```",
            "-old fenced content",
            "+new fenced content",
            " ```",
        ).joinToString("\n")
        val body = PrThreadFormat.body(pull(body = description, diff = diff))

        // The description's own fence must be closed right after its content — BEFORE the meta line,
        // "## Changed files", or "## Diff" — with a matching ``` closer, so nothing that follows can
        // ever be swallowed by it.
        assertTrue(
            body.startsWith("$description\n```\n\n**[PR #"),
            "description's dangling fence should be closed right after its own content, before the meta line:\n$body",
        )
        // The diff section must still render as its own, separately-fenced block.
        assertTrue(body.contains("## Diff\n\n"), "the diff section must still render:\n$body")
        val fence = openingDiffFence(body)
        assertTrue(
            body.contains("${fence}diff\n$diff\n$fence"),
            "the diff must still sit verbatim inside its OWN fence, untouched by the description fix:\n$body",
        )
    }

    @Test
    fun `a description ending in a dangling tilde fence is also closed`() {
        val description = "Notes:\n~~~\nsome unclosed content"
        val body = PrThreadFormat.body(pull(body = description))
        assertTrue(
            body.startsWith("$description\n~~~\n\n**[PR #"),
            "a tilde fence should be closed with a matching ~~~ closer:\n$body",
        )
    }

    @Test
    fun `a description ending in a dangling 5-backtick fence gets a closer at least as long`() {
        val description = "Look:\n`````\nsome unclosed content"
        val body = PrThreadFormat.body(pull(body = description))
        assertTrue(
            body.startsWith("$description\n`````\n\n**[PR #"),
            "a 5-backtick opener needs a closer of at least 5 backticks, not just 3:\n$body",
        )
    }

    @Test
    fun `a description with a properly closed fence is untouched (byte-identical passthrough)`() {
        val description = "Before\n```\nsome code\n```\nAfter, unfenced"
        val body = PrThreadFormat.body(pull(body = description))
        assertTrue(
            body.startsWith("$description\n\n**[PR #"),
            "an already-closed fence must not gain an extra closer:\n$body",
        )
    }

    // --- commentBody: one PR-discussion node's markdown (Slice 2) ---

    private fun comment(body: String, kind: String = "comment", reviewState: String? = null) =
        PrComment(author = "dana", body = body, createdAt = "2026-06-25T09:00:00Z", kind = kind, reviewState = reviewState)

    @Test
    fun `commentBody renders an issue comment as its own trimmed text`() {
        assertEquals("Looks reasonable.", PrThreadFormat.commentBody(comment("  Looks reasonable.  ")))
    }

    @Test
    fun `commentBody folds an approval verdict in, even with no body`() {
        assertTrue(PrThreadFormat.commentBody(comment("", kind = "review", reviewState = "APPROVED")).contains("Approved"))
        val withBody = PrThreadFormat.commentBody(comment("LGTM, ship it", kind = "review", reviewState = "APPROVED"))
        assertTrue(withBody.contains("Approved"))
        assertTrue(withBody.contains("LGTM, ship it"))
    }

    @Test
    fun `commentBody marks a changes-requested review`() {
        val body = PrThreadFormat.commentBody(comment("please add a test", kind = "review", reviewState = "CHANGES_REQUESTED"))
        assertTrue(body.contains("Requested changes"), body)
        assertTrue(body.contains("please add a test"), body)
    }

    @Test
    fun `commentBody shows a plain commented review as just its body`() {
        assertEquals("a passing note", PrThreadFormat.commentBody(comment("a passing note", kind = "review", reviewState = "COMMENTED")))
    }
}
