package com.aiforum.tier0

import com.aiforum.dto.FeedExcerpt
import com.aiforum.dto.Snippet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the feed card's preview. The body it exists for is the ambient article OP — `AmbientTickService`
 * writes `"${summary}\n\n${url}"` — and a reply-less thread's card previews its own opening post, so an
 * unstripped URL would be the first thing a fresh article thread shows on the front page.
 *
 * The last two tests are the pair that keeps the D8 decision honest: [Snippet] is left untouched, so one
 * test pins WHY the URL rule cannot live there and one pins that the duplicated clip has not drifted.
 */
@Tag("tier0")
class FeedExcerptTest {

    @Test
    fun `an ambient article OP previews the summary alone`() {
        assertEquals(
            "Rust 1.90 ships a smaller borrow checker.",
            FeedExcerpt.of("Rust 1.90 ships a smaller borrow checker.\n\nhttps://blog.rust-lang.org/1.90", 120),
        )
    }

    @Test
    fun `a bare url mid-sentence is stripped and the prose closes over the gap`() {
        assertEquals(
            "posted at earlier today",
            FeedExcerpt.of("posted at https://example.com/a?b=c#d earlier today", 80),
        )
    }

    @Test
    fun `stripping is case-insensitive and covers the other scheme the rule names`() {
        assertEquals("mirror:", FeedExcerpt.of("mirror: FTP://ftp.example.com/pub/x.tar", 80))
    }

    @Test
    fun `a scheme-less host stays, because in prose it is a word the sentence needs`() {
        assertEquals("we moved to example.com last year", FeedExcerpt.of("we moved to example.com last year", 80))
    }

    @Test
    fun `a markdown link keeps its label`() {
        assertEquals(
            "see YAGNI for why",
            FeedExcerpt.of("see [YAGNI](https://martinfowler.com/bliki/Yagni.html) for why", 80),
        )
    }

    @Test
    fun `an over-long preview still ellipsises`() {
        assertEquals("one two…", FeedExcerpt.of("one two three", 8))
    }

    @Test
    fun `an empty body previews as empty`() {
        assertEquals("", FeedExcerpt.of("", 80))
    }

    @Test
    fun `a url-only body previews as empty rather than as a stray ellipsis`() {
        assertEquals("", FeedExcerpt.of("https://example.com/a", 8))
    }

    @Test
    fun `Snippet keeps the url, which is the whole reason this object exists`() {
        // The premise of FeedExcerpt's KDoc, pinned rather than asserted in prose: Snippet's parser
        // carries TablesExtension and nothing else, so a bare URL is never a Link whose destination
        // gets dropped — it reaches the preview as ordinary text. If this ever goes green-to-red,
        // FeedExcerpt's reason for existing changed and the KDoc is the thing to re-read.
        val ambientOp = "Rust 1.90 ships a smaller borrow checker.\n\nhttps://blog.rust-lang.org/1.90"
        assertTrue(
            Snippet.oneLine(ambientOp, 120).contains("https://blog.rust-lang.org/1.90"),
            "Snippet.oneLine was expected to keep the bare URL: ${Snippet.oneLine(ambientOp, 120)}",
        )
    }

    @Test
    fun `on a url-free body it agrees with Snippet oneLine character-for-character`() {
        // D8 duplicates Snippet's clip rather than editing Snippet. That is only safe while the two
        // cannot silently diverge, so the agreement is a test and not a convention: flattening,
        // whitespace collapse and the ellipsis are all compared, at and past the clip length.
        val bodies = listOf(
            "one two three",
            "## Short version\n\nIt *works*, but **bold** and `code` survive as text",
            "> quoted line\n\nand the reply below it",
            "- first\n- second",
            "before\n\n```kotlin\nfun x() = 1\n```\n\nafter",
            "",
        )
        for (body in bodies) {
            for (max in listOf(8, 80)) {
                assertEquals(
                    Snippet.oneLine(body, max),
                    FeedExcerpt.of(body, max),
                    "diverged on body=<$body> max=$max",
                )
            }
        }
    }
}
