package com.aiforum.tier0

import com.aiforum.ambient.FeedParseException
import com.aiforum.ambient.FeedParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure, dependency-free RSS/Atom parser (plan_docs/ambient-slice-5.md §2 "Parse", §3 security
 * posture). No Spring, no IO — feed XML strings in, [com.aiforum.ambient.FeedItem]s out. The hostile-input
 * cases (DOCTYPE/XXE, entity bomb, malformed) pin the hardened reader that lets everything above trust the
 * parser never expands an external entity or hangs on a bomb.
 */
@Tag("tier0")
class FeedParserTest {

    @Test
    fun `parses RSS 2 items — title, link, description`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>Example feed</title>
              <item>
                <title>SQLite WAL explained</title>
                <link>https://example.org/wal</link>
                <description>Why WAL lets readers and one writer proceed.</description>
              </item>
              <item>
                <title>Recursive CTEs</title>
                <link>https://example.org/ctes</link>
                <description>Walking a self-referencing table.</description>
              </item>
            </channel></rss>
        """.trimIndent()

        val items = FeedParser.parse(xml)

        assertEquals(2, items.size)
        assertEquals("SQLite WAL explained", items[0].title)
        assertEquals("https://example.org/wal", items[0].url)
        assertEquals("Why WAL lets readers and one writer proceed.", items[0].summary)
        assertEquals("https://example.org/ctes", items[1].url)
    }

    @Test
    fun `parses Atom entries — title, link href, summary with content fallback`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom feed</title>
              <entry>
                <title>Kotlin coroutines</title>
                <link href="https://example.org/other" rel="related"/>
                <link href="https://example.org/coroutines" rel="alternate"/>
                <summary>Structured concurrency and dispatchers.</summary>
              </entry>
              <entry>
                <title>Tokenization</title>
                <link href="https://example.org/tokens"/>
                <content>Byte-pair encoding, token counts vs word counts.</content>
              </entry>
            </feed>
        """.trimIndent()

        val items = FeedParser.parse(xml)

        assertEquals(2, items.size)
        assertEquals("Kotlin coroutines", items[0].title)
        // prefers rel="alternate" over the rel="related" link listed first
        assertEquals("https://example.org/coroutines", items[0].url)
        assertEquals("Structured concurrency and dispatchers.", items[0].summary)
        // second entry has no <summary>, so falls back to <content>
        assertEquals("https://example.org/tokens", items[1].url)
        assertEquals("Byte-pair encoding, token counts vs word counts.", items[1].summary)
    }

    @Test
    fun `a DOCTYPE (XXE payload) is rejected outright`() {
        // Classic XXE: an external entity that would exfiltrate a local file. disallow-doctype-decl means
        // the parser refuses the DOCTYPE before any entity is ever resolved.
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <rss version="2.0"><channel>
              <item><title>&xxe;</title><link>https://example.org/1</link></item>
            </channel></rss>
        """.trimIndent()

        assertThrows(FeedParseException::class.java) { FeedParser.parse(xml) }
    }

    @Test
    fun `an entity-expansion bomb is rejected outright`() {
        // "Billion laughs" — nested entities that expand exponentially. Also carried by a DOCTYPE, so the
        // same disallow-doctype-decl flag rejects it before any expansion can begin.
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [
              <!ENTITY lol "lol">
              <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;">
              <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;">
            ]>
            <rss version="2.0"><channel>
              <item><title>&lol3;</title><link>https://example.org/1</link></item>
            </channel></rss>
        """.trimIndent()

        assertThrows(FeedParseException::class.java) { FeedParser.parse(xml) }
    }

    @Test
    fun `summaries are HTML-stripped, entity-decoded and hard-truncated`() {
        val longTail = "x".repeat(500)
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <item>
                <title>Clean me</title>
                <link>https://example.org/1</link>
                <description><![CDATA[<p>Hello <b>bold</b> &amp; clear</p> $longTail]]></description>
              </item>
            </channel></rss>
        """.trimIndent()

        val item = FeedParser.parse(xml).single()

        assertTrue(item.summary.startsWith("Hello bold & clear"), "tags stripped, &amp; decoded: ${item.summary}")
        assertTrue('<' !in item.summary, "no markup survives: ${item.summary}")
        assertTrue(item.summary.length <= 400, "summary hard-truncated to ~400 chars, was ${item.summary.length}")
        assertTrue(item.summary.endsWith("…"), "truncation marker present")
    }

    @Test
    fun `double-HTML-encoded markup is stripped, not re-materialised`() {
        // The XML parser's native decode of &amp; leaves "&lt;script&gt;…" — no literal '<' until
        // clean()'s own entity decode runs. A strip-before-decode pipeline manufactures a live
        // <script> tag AFTER its only strip pass (the confirmed review finding this test pins).
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <item>
                <title>Sneaky &amp;lt;b&amp;gt;title&amp;lt;/b&amp;gt;</title>
                <link>https://example.org/1</link>
                <description>&amp;lt;script&amp;gt;alert(1)&amp;lt;/script&amp;gt; useful text</description>
              </item>
            </channel></rss>
        """.trimIndent()

        val item = FeedParser.parse(xml).single()

        assertTrue('<' !in item.summary && '>' !in item.summary, "no markup survives double-encoding: ${item.summary}")
        assertTrue("useful text" in item.summary, "legitimate text kept: ${item.summary}")
        assertTrue('<' !in item.title, "title equally protected: ${item.title}")
    }

    @Test
    fun `entity-splicing across stripped tags cannot assemble markup`() {
        // Stripping "<b></b>" out of "&am<b></b>p;lt;" splices the fragments into "&amp;lt;" — a
        // fixed-point clean() must re-decode+re-strip until stable so no literal '<' ever emerges.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <item>
                <title>Splice</title>
                <link>https://example.org/1</link>
                <description><![CDATA[&am<b></b>p;lt;script&am<b></b>p;gt;alert(1) tail]]></description>
              </item>
            </channel></rss>
        """.trimIndent()

        val item = FeedParser.parse(xml).single()

        assertTrue('<' !in item.summary && '>' !in item.summary, "spliced entities must not become markup: ${item.summary}")
        assertTrue("tail" in item.summary, "legitimate text kept: ${item.summary}")
    }

    @Test
    fun `a long title is truncated to ~200 chars`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <item>
                <title>${"t".repeat(300)}</title>
                <link>https://example.org/1</link>
                <description>x</description>
              </item>
            </channel></rss>
        """.trimIndent()

        val item = FeedParser.parse(xml).single()
        assertTrue(item.title.length <= 200, "title hard-truncated to ~200, was ${item.title.length}")
    }

    @Test
    fun `items whose link is not http or https are skipped`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <item><title>Script</title><link>javascript:alert(1)</link><description>x</description></item>
              <item><title>Data</title><link>data:text/html,pwned</link><description>x</description></item>
              <item><title>Relative</title><link>/relative/path</link><description>x</description></item>
              <item><title>Good</title><link>https://example.org/ok</link><description>x</description></item>
            </channel></rss>
        """.trimIndent()

        val items = FeedParser.parse(xml)

        assertEquals(1, items.size, "only the http(s) item survives the scheme allowlist")
        assertEquals("Good", items[0].title)
        assertEquals("https://example.org/ok", items[0].url)
    }

    @Test
    fun `malformed XML fails cleanly as a FeedParseException`() {
        val xml = "<rss version=\"2.0\"><channel><item><title>oops</title>"  // never closed

        val ex = assertThrows(FeedParseException::class.java) { FeedParser.parse(xml) }
        // message is a safe, generic diagnosis — never an echo of the raw feed content
        assertTrue(ex.message?.contains("parse") == true, "message names the parse failure: ${ex.message}")
    }
}
