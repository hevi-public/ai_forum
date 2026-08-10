package com.aiforum.tier0

import com.aiforum.github.ChangedFile
import com.aiforum.github.PrThreadFormat
import com.aiforum.github.PullDetail
import com.aiforum.markdown.MarkdownRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the markdown → trusted-HTML rendering behind reply/post bodies. No IO seam — commonmark and the
 * GraalJS-hosted highlight.js both run in-process, so this is pure input → output. It also doubles as the
 * smoke test that highlight.js actually loads and runs under GraalJS on this JDK.
 *
 * The load-bearing guarantees: (1) raw HTML in a body stays inert (the XSS firewall behind `$unsafe{}`),
 * (2) fenced blocks with a known language come back syntax-highlighted, and (3) no-language / unknown /
 * malformed blocks degrade to a plain escaped block instead of throwing.
 */
@Tag("tier0")
class MarkdownRendererTest {

    @Test
    fun `plain prose renders as a paragraph`() {
        val html = MarkdownRenderer.render("just a sentence")
        assertTrue(html.contains("<p>just a sentence</p>"), html)
    }

    @Test
    fun `markdown emphasis renders to tags`() {
        val html = MarkdownRenderer.render("this is **bold** and *italic*")
        assertTrue(html.contains("<strong>bold</strong>"), html)
        assertTrue(html.contains("<em>italic</em>"), html)
    }

    @Test
    fun `raw HTML in a body is escaped, not executed (XSS firewall)`() {
        val html = MarkdownRenderer.render("hi <script>alert('x')</script>")
        assertFalse(html.contains("<script>"), "raw <script> must never reach the output:\n$html")
        assertTrue(html.contains("&lt;script&gt;"), html)
    }

    @Test
    fun `raw HTML table is escaped — tables must come via markdown, not raw HTML`() {
        val html = MarkdownRenderer.render("<table><tr><td>x</td></tr></table>")
        assertFalse(html.contains("<table>"), "raw <table> must be inert:\n$html")
        assertTrue(html.contains("&lt;table&gt;"), html)
    }

    @Test
    fun `a link with a script-scheme destination is neutralized (URL half of the firewall)`() {
        // Mixed case included: scheme matching must be case-insensitive or it's a trivial bypass.
        for (dest in listOf("javascript:alert('x')", "data:text/html;base64,PHNjcmlwdD4=", "vbscript:msgbox", "JaVaScRiPt:alert(1)")) {
            val html = MarkdownRenderer.render("[click me]($dest)")
            val scheme = dest.substringBefore(':')
            assertFalse(html.contains("$scheme:", ignoreCase = true), "hostile $scheme: href must not survive:\n$html")
            assertTrue(html.contains("href=\"\""), "destination should be emptied, not the link dropped:\n$html")
            assertTrue(html.contains("click me"), "link text must still render:\n$html")
        }
    }

    @Test
    fun `an image with a script-scheme destination is neutralized`() {
        for (dest in listOf("javascript:alert('x')", "data:text/html;base64,PHNjcmlwdD4=", "vbscript:msgbox")) {
            val html = MarkdownRenderer.render("![pic]($dest)")
            val scheme = dest.substringBefore(':')
            assertFalse(html.contains("$scheme:", ignoreCase = true), "hostile $scheme: src must not survive:\n$html")
            assertTrue(html.contains("src=\"\""), "destination should be emptied:\n$html")
        }
    }

    @Test
    fun `safe https and relative link destinations survive sanitization`() {
        val https = MarkdownRenderer.render("[docs](https://example.com/docs)")
        assertTrue(https.contains("href=\"https://example.com/docs\""), https)
        // Internal links (quote backlinks, story refs) are relative — sanitization must not eat them.
        val relative = MarkdownRenderer.render("[a thread](/threads/1)")
        assertTrue(relative.contains("href=\"/threads/1\""), relative)
    }

    @Test
    fun `GFM pipe tables render to a real table`() {
        val md = """
            | Component | Status |
            | --------- | ------ |
            | Button    | Shipped |
        """.trimIndent()
        val html = MarkdownRenderer.render(md)
        assertTrue(html.contains("<table>"), html)
        assertTrue(html.contains("<th>Component</th>"), html)
        assertTrue(html.contains("<td>Shipped</td>"), html)
    }

    @Test
    fun `a fenced block with a known language is syntax-highlighted`() {
        val md = "```yaml\nname: saul\nrole: frontend\n```"
        val html = MarkdownRenderer.render(md)
        assertTrue(html.contains("class=\"hljs language-yaml\""), html)
        // hljs wraps tokens in spans — proof the highlighter actually ran, not just a class slapped on.
        assertTrue(html.contains("hljs-"), "expected highlight.js token spans:\n$html")
    }

    @Test
    fun `an unknown language degrades to a plain escaped block, no exception`() {
        val md = "```notalang\nsome <code> & text\n```"
        val html = MarkdownRenderer.render(md)
        assertFalse(html.contains("hljs-"), "unknown language must not be highlighted:\n$html")
        assertTrue(html.contains("&lt;code&gt;"), "code text must still be HTML-escaped:\n$html")
    }

    @Test
    fun `a fence with no language is a plain escaped block`() {
        val md = "```\nplain & <unhighlighted>\n```"
        val html = MarkdownRenderer.render(md)
        assertFalse(html.contains("hljs"), html)
        assertTrue(html.contains("<pre><code>"), html)
        assertTrue(html.contains("&lt;unhighlighted&gt;"), html)
    }

    @Test
    fun `a single newline becomes a line break, a blank line starts a new paragraph`() {
        val oneBreak = MarkdownRenderer.render("line one\nline two")
        assertTrue(oneBreak.contains("line one<br>"), "single newline should be a <br>:\n$oneBreak")
        val paragraphs = MarkdownRenderer.render("para one\n\npara two")
        assertTrue(paragraphs.contains("<p>para one</p>"), paragraphs)
        assertTrue(paragraphs.contains("<p>para two</p>"), paragraphs)
    }

    @Test
    fun `a blank body renders to empty string`() {
        assertTrue(MarkdownRenderer.render("   ").isEmpty())
    }

    // --- PR diffs (issue #18): PrThreadFormat's diff fence end-to-end through the real renderer. A PR
    // diff is attacker-influenceable (anyone can open a PR against a public repo), so the two-half XSS
    // firewall (escapeHtml + sanitizeUrls, PR #92) must hold across this path too, AND the diff must stay
    // genuinely CONTAINED in its code block — the fence-escape bug it's guarding against isn't raw-HTML
    // XSS (escapeHtml already inerts literal HTML), it's markdown injection: a fence that closes early
    // hands the rest of the diff to the real markdown parser, and MARKDOWN-SYNTAX images/links (not raw
    // HTML) render for real off an https: URL that sanitizeUrls has no reason to touch.

    private fun prPull(diff: String) = PullDetail(
        number = 7, title = "Hostile diff", author = "octocat",
        url = "https://github.com/o/r/pull/7", state = "OPEN", isDraft = false,
        body = "", baseRef = "main", headRef = "feature", headSha = "deadbeef",
        changedFiles = listOf(ChangedFile("README.md", 2, 2)), diff = diff,
    )

    @Test
    fun `a PR diff that fence-escapes stays contained — the attacker's markdown never goes live`() {
        // " ```" (one leading space — a diff context-line marker — then three backticks) is what a
        // markdown file's own fence looks like as UNCHANGED context in a real PR diff, fully within an
        // attacker's control on their own fork. Followed by a markdown image, a script-scheme link, and a
        // raw HTML tag: if the fence breaks early, the image is the dangerous one — markdown image syntax
        // isn't raw HTML, so escapeHtml doesn't touch it, and its https: URL isn't a scheme sanitizeUrls
        // blocks either.
        val diff = listOf(
            "diff --git a/README.md b/README.md",
            "index 1111111..2222222 100644",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -10,5 +10,5 @@",
            " ```",
            " ![pwned](https://evil.example/pwned.png)",
            " [click me](javascript:alert(1))",
            " <img src=x onerror=alert(1)>",
            " ```",
            "-old line",
            "+new line",
        ).joinToString("\n")
        val body = PrThreadFormat.body(prPull(diff))
        val html = MarkdownRenderer.render(body)

        assertEquals(
            1, Regex("language-diff").findAll(html).count(),
            "expected exactly one highlighted diff block, no accidental second fence reopening:\n$html",
        )
        assertFalse(html.contains("<img"), "the attacker's markdown image must stay inert diff text, never a live <img>:\n$html")
        assertFalse(html.contains("src=\"https://evil.example"), "the attacker's image src must never go live:\n$html")
        assertFalse(html.contains("href=\"javascript:", ignoreCase = true), "a script-scheme href must never survive, even from diff content:\n$html")
        assertTrue(html.contains("language-diff"), "the diff should still render highlighted:\n$html")
        assertTrue(html.contains("evil.example"), "the attacker's URL text should still be visible, just as inert code — not dropped, not live:\n$html")
    }

    @Test
    fun `a script-scheme URL on an ordinary diff line never becomes a live link`() {
        // A "+"-prefixed line can't masquerade as a closing fence (its first character isn't whitespace
        // or a backtick), so this content is already inside the fence either way — a defense-in-depth
        // check that the URL half of the firewall holds for diff content generally, fence bug or not.
        val diff = listOf(
            "diff --git a/notes.md b/notes.md",
            "@@ -1 +1 @@",
            "-old",
            "+[click me](javascript:alert(1))",
        ).joinToString("\n")
        val html = MarkdownRenderer.render(PrThreadFormat.body(prPull(diff)))
        assertTrue(html.contains("language-diff"), "the diff should render highlighted, not fall back:\n$html")
        assertFalse(html.contains("href=\"javascript:", ignoreCase = true), "a script-scheme URL inside diff content must never surface as a live href:\n$html")
    }

    @Test
    fun `a dangling fence left open by the PR description is closed, so the diff's own context-line fence can't spill it live`() {
        // The DESCRIPTION is attacker-authored too (it's the PR's own body). Left with an unclosed ```
        // fence, commonmark would treat everything below it — the meta line, "## Changed files", "##
        // Diff", and the diff's own opening fence — as that fence's literal content, UNTIL some later
        // line validly closes it. The diff's own " ```" context line (one leading space, three
        // backticks, nothing else) is exactly such a closer: it would close the DESCRIPTION's fence
        // early, spilling the rest of the diff — the hostile image/link/raw-HTML lines — into the real
        // markdown parser. Mirrors the fence-escape case above, but the dangling fence is in the
        // description, not the diff.
        val description = "Check this fix:\n```"
        val diff = listOf(
            "diff --git a/README.md b/README.md",
            "index 1111111..2222222 100644",
            "--- a/README.md",
            "+++ b/README.md",
            "@@ -10,5 +10,5 @@",
            " ```",
            " ![pwned](https://evil.example/pwned.png)",
            " [click me](javascript:alert(1))",
            " <img src=x onerror=alert(1)>",
            " ```",
            "-old line",
            "+new line",
        ).joinToString("\n")
        val pull = PullDetail(
            number = 8, title = "Hostile description", author = "octocat",
            url = "https://github.com/o/r/pull/8", state = "OPEN", isDraft = false,
            body = description, baseRef = "main", headRef = "feature", headSha = "deadbeef",
            changedFiles = listOf(ChangedFile("README.md", 2, 2)), diff = diff,
        )
        val body = PrThreadFormat.body(pull)
        val html = MarkdownRenderer.render(body)

        assertEquals(
            1, Regex("language-diff").findAll(html).count(),
            "expected exactly one highlighted diff block, no accidental second fence reopening:\n$html",
        )
        assertFalse(html.contains("<img"), "the attacker's markdown image must stay inert diff text, never a live <img>:\n$html")
        assertFalse(html.contains("src=\"https://evil.example"), "the attacker's image src must never go live:\n$html")
        assertFalse(html.contains("href=\"javascript:", ignoreCase = true), "a script-scheme href must never survive, even via a description-fence escape:\n$html")
        assertTrue(html.contains("language-diff"), "the diff should still render highlighted:\n$html")
        assertTrue(html.contains("evil.example"), "the attacker's URL text should still be visible, just as inert code — not dropped, not live:\n$html")
    }

    @Test
    fun `a fenced diff block highlights with hljs-addition and hljs-deletion spans for + and - lines`() {
        // End-to-end proof the highlight.js bundle's "diff" language is wired up (the issue's "check
        // before building" — see plan_docs and PrThreadFormat's DIFF fence): a real +/- diff renders both
        // the language class and hljs's own addition/deletion token spans, which is what static/hljs-
        // theme.css's .hljs-addition/.hljs-deletion colors key off.
        val md = "```diff\n+added line\n-removed line\n context line\n```"
        val html = MarkdownRenderer.render(md)
        assertTrue(html.contains("class=\"hljs language-diff\""), "expected the diff language to be recognized:\n$html")
        assertTrue(html.contains("hljs-addition"), "expected an hljs-addition span for the + line:\n$html")
        assertTrue(html.contains("hljs-deletion"), "expected an hljs-deletion span for the - line:\n$html")
    }
}
