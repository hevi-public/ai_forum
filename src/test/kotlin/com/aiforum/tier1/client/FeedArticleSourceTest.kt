package com.aiforum.tier1.client

import com.aiforum.ambient.AmbientFeedProperties
import com.aiforum.ambient.FeedArticleSource
import com.aiforum.ambient.FeedUnavailableException
import com.aiforum.repo.ArticleSeenRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

/**
 * Tier-1: the genuinely un-fakeable plumbing of [FeedArticleSource] — HTTP fetch, the byte cap, and the
 * round-robin + dedupe flow — with HTTP mocked at the one seam (`MockRestServiceServer` bound to the
 * injected `RestClient.Builder`, the OpenAiImageDescriberTest seam) and the REAL [ArticleSeenRepository]
 * over the test SQLite DB (like the other tier-1 repo tests). No network, no live feeds — every host here
 * is an `.example` name the mock intercepts. The source itself is `@Profile("!test")` (it can never wire
 * under test — the security rail), so it is constructed directly with the mock-bound builder.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class FeedArticleSourceTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var articleSeen: ArticleSeenRepository

    @BeforeEach @AfterEach
    fun clean() {
        jdbc.update("DELETE FROM article_seen")
    }

    private val feedA = "https://feed-a.example/rss"
    private val feedB = "https://feed-b.example/rss"

    /** Build a source whose HTTP goes to a MockRestServiceServer bound to the same builder. */
    private fun source(feeds: List<String>, maxBytes: Long = 1_048_576): Pair<FeedArticleSource, MockRestServiceServer> {
        val builder = RestClient.builder()
        // ignoreExpectOrder: which feed a tick fetches first depends on the round-robin cursor, so assert
        // the SET of requests, not their order.
        val server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build()
        val src = FeedArticleSource(builder, AmbientFeedProperties(feeds = feeds), articleSeen, maxBytes)
        return src to server
    }

    /** A minimal RSS 2.0 body carrying the given (title, link) items. */
    private fun rss(vararg items: Pair<String, String>): String {
        val body = items.joinToString("") { (t, u) -> "<item><title>$t</title><link>$u</link><description>d</description></item>" }
        return """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel>$body</channel></rss>"""
    }

    private fun respondXml(server: MockRestServiceServer, feed: String, body: String, count: ExpectedCount? = null) {
        val expect = if (count != null) server.expect(count, requestTo(feed)) else server.expect(requestTo(feed))
        expect.andRespond(withSuccess(body, MediaType.TEXT_XML))
    }

    @Test
    fun `yields the first unseen item and marks it seen`() {
        val (src, server) = source(listOf(feedA))
        respondXml(server, feedA, rss("First" to "https://a.example/1"))

        val article = src.next()!!

        assertEquals("First", article.title)
        assertEquals("https://a.example/1", article.url)
        assertTrue(articleSeen.exists("https://a.example/1"), "the yielded url is now recorded seen")
        assertNull(src.emptyReason(), "a successful yield clears the empty reason")
        server.verify()
    }

    @Test
    fun `a second tick skips an item already seen and reports the dedupe reason`() {
        val (src, server) = source(listOf(feedA))
        // Same feed content on both ticks (nothing new published between them).
        respondXml(server, feedA, rss("First" to "https://a.example/1"), ExpectedCount.times(2))

        val first = src.next()!!
        assertEquals("https://a.example/1", first.url)

        val second = src.next()
        assertNull(second, "the only item is now seen, so the second tick yields nothing")
        assertEquals("all 1 feed items already seen", src.emptyReason())
        server.verify()
    }

    @Test
    fun `the round-robin cursor advances across ticks`() {
        val (src, server) = source(listOf(feedA, feedB))
        // Feed A has TWO items; without cursor advance the 2nd tick would re-drain A (yielding A2). With it,
        // the 2nd tick starts at feed B and yields B1 — so B1 on tick two is the rotation proof.
        respondXml(server, feedA, rss("A1" to "https://a.example/1", "A2" to "https://a.example/2"))
        respondXml(server, feedB, rss("B1" to "https://b.example/1"))

        val first = src.next()!!
        val second = src.next()!!

        assertEquals("https://a.example/1", first.url, "tick one starts at feed A")
        assertEquals("https://b.example/1", second.url, "tick two advanced to feed B, not re-drained feed A")
        server.verify()
    }

    @Test
    fun `when every feed errors it throws FeedUnavailableException with an aggregated message`() {
        val (src, server) = source(listOf(feedA, feedB))
        server.expect(requestTo(feedA)).andRespond(withServerError())
        server.expect(requestTo(feedB)).andRespond(withServerError())

        val ex = assertThrows(FeedUnavailableException::class.java) { src.next() }

        assertTrue(ex.message!!.contains("feed-a.example"), "message names feed A: ${ex.message}")
        assertTrue(ex.message!!.contains("feed-b.example"), "message names feed B: ${ex.message}")
        server.verify()
    }

    @Test
    fun `one feed down but another good still yields (degraded)`() {
        val (src, server) = source(listOf(feedA, feedB))
        server.expect(requestTo(feedA)).andRespond(withServerError())
        respondXml(server, feedB, rss("B1" to "https://b.example/1"))

        val article = src.next()!!

        assertEquals("https://b.example/1", article.url, "the healthy feed still supplies an article")
        server.verify()
    }

    @Test
    fun `an over-cap response body is treated as a feed error, not parsed`() {
        val (src, server) = source(listOf(feedA), maxBytes = 50)
        // The body is far larger than the 50-byte cap, so it is rejected before FeedParser ever sees it.
        respondXml(server, feedA, rss("Big" to "https://a.example/1"))

        val ex = assertThrows(FeedUnavailableException::class.java) { src.next() }

        assertTrue(ex.message!!.contains("cap"), "the over-cap rejection reaches the aggregated message: ${ex.message}")
        assertFalse(articleSeen.exists("https://a.example/1"), "nothing was recorded from the rejected feed")
        server.verify()
    }

    @Test
    fun `no feeds configured yields null with the no-items reason`() {
        val (src, server) = source(emptyList())

        assertNull(src.next())
        assertEquals("feeds returned no items", src.emptyReason())
        server.verify() // no HTTP was attempted
    }

    @Test
    fun `all items already seen yields null with the dedupe-exhausted reason`() {
        articleSeen.record("https://a.example/1") // pre-seed as already posted
        val (src, server) = source(listOf(feedA))
        respondXml(server, feedA, rss("First" to "https://a.example/1"))

        assertNull(src.next())
        assertEquals("all 1 feed items already seen", src.emptyReason())
        server.verify()
    }
}
