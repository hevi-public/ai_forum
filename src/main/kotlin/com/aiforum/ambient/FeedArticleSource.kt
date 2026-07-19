package com.aiforum.ambient

import com.aiforum.repo.ArticleSeenRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Every configured feed errored on this tick — the tick records a `failed` run carrying the aggregated
 *  per-feed messages (plan_docs/ambient-slice-5.md §2, the S2 attribution machinery). */
class FeedUnavailableException(message: String) : RuntimeException(message)

/**
 * The real [ArticleSource] (plan_docs/ambient-slice-5.md): pulls articles from an owner-curated allowlist
 * of RSS/Atom feeds, dedupes against the `article_seen` registry, and hands the tick the same
 * `Article(title, url, summary)` the stub does. Selected by `aiforum.ambient.source=feed`; the stub stays
 * the `matchIfMissing` default, so existing setups are untouched. `@Profile("!test")` — the real source
 * can NEVER wire under test (that profile wall IS the security rail, §3 "Network under test").
 *
 * Fetch is hardened against a hostile / dead web (§3):
 *  - the HTTP call runs on a daemon [FutureTask] with a per-feed deadline (the manual tick runs on the
 *    request thread and must not hang on a dead feed — [com.aiforum.llm.OpenAiLlmClient.awaitWithin]
 *    precedent);
 *  - the response body is byte-capped BEFORE parsing (memory-abuse guard);
 *  - parsing is [FeedParser]'s DTD-rejecting reader (XXE + entity-bomb), and only http(s) item links
 *    survive.
 *
 * `open` + a primary constructor taking a `RestClient.Builder` (the [com.aiforum.images.OpenAiImageDescriber]
 * seam), so a Tier-1 test binds a `MockRestServiceServer` and injects a small byte cap without real IO.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.ambient", name = ["source"], havingValue = "feed")
open class FeedArticleSource(
    restClientBuilder: RestClient.Builder,
    props: AmbientFeedProperties,
    private val articleSeen: ArticleSeenRepository,
    private val maxBytes: Long,
) : ArticleSource {

    @Autowired
    constructor(
        props: AmbientFeedProperties,
        articleSeen: ArticleSeenRepository,
    ) : this(RestClient.builder(), props, articleSeen, props.feedMaxBytes)

    private val log = LoggerFactory.getLogger(FeedArticleSource::class.java)
    private val http: RestClient = restClientBuilder.build()

    // https-ONLY: drop any non-https feed at construction (a boot-time warn, count surfaced via /__diag's
    // ambientFeedCount — which counts the CONFIGURED list; this is the effective, vetted subset). A plain
    // http feed is an unencrypted trust anchor; the allowlist must be TLS.
    private val feeds: List<String> = props.feeds.filter { url ->
        val ok = isHttps(url)
        if (!ok) {
            log.atWarn().setMessage("dropping non-https ambient feed: {}").addArgument(url)
                .addKeyValue("event", EV_FEED_REJECTED).addKeyValue("feed", url).log()
        }
        ok
    }

    // Round-robin start offset across ticks (the stub's AtomicInteger precedent), so consecutive ticks
    // prefer different feeds rather than always draining the first.
    private val cursor = AtomicInteger(0)
    private val feedTimeout: Duration = Duration.ofSeconds(10)

    // The source's account of the last null yield, read by the tick's no-op detail. @Volatile because a
    // manual tick (request thread) and a scheduled tick (scheduler thread) may each call next()/emptyReason().
    @Volatile
    private var lastEmptyReason: String? = null

    override fun next(): Article? {
        val n = feeds.size
        if (n == 0) {
            lastEmptyReason = REASON_NO_ITEMS
            return null
        }
        val start = cursor.getAndIncrement()
        var totalItems = 0
        val errors = ArrayList<String>(n)
        for (i in 0 until n) {
            val feed = feeds[Math.floorMod(start + i, n)]
            try {
                val items = FeedParser.parse(fetchWithinDeadline(feed))
                totalItems += items.size
                for (item in items) {
                    if (!articleSeen.exists(item.url)) {
                        articleSeen.record(item.url)               // mark seen ON YIELD (§Decision log)
                        lastEmptyReason = null
                        log.atInfo().setMessage("ambient feed yielded \"{}\" from {}")
                            .addArgument(item.title).addArgument(hostOf(feed))
                            .addKeyValue("event", EV_FEED_YIELD).addKeyValue("feed", hostOf(feed)).log()
                        return Article(item.title, item.url, item.summary)
                    }
                }
            } catch (e: Exception) {
                val host = hostOf(feed)
                val reason = e.message ?: e.javaClass.simpleName
                errors += "$host: $reason"
                log.atWarn().setMessage("ambient feed {} failed: {}").addArgument(host).addArgument(reason)
                    .addKeyValue("event", EV_FEED_ERROR).addKeyValue("feed", host)
                    .addKeyValue("reason", reason).log()
            }
        }
        // Nothing yielded. If EVERY feed errored, the tick has no material at all — a failed run with the
        // aggregated diagnosis. If at least one feed answered (just with no new items), it's a no-op.
        if (errors.size == n) {
            throw FeedUnavailableException("all $n feed(s) failed — ${errors.joinToString("; ")}")
        }
        lastEmptyReason = if (totalItems == 0) REASON_NO_ITEMS else "all $totalItems feed items already seen"
        return null
    }

    override fun emptyReason(): String? = lastEmptyReason

    /**
     * Fetch one feed on a daemon worker, bounded by [feedTimeout] — a dead/slow feed must not hang the
     * tick's thread. The blocking HTTP call runs inside the [FutureTask]; the deadline lives in
     * [FutureTask.get]. A timeout/transport fault surfaces as a plain [RuntimeException] the per-feed
     * catch in [next] aggregates.
     */
    private fun fetchWithinDeadline(feedUrl: String): String {
        val task = FutureTask { fetchOnce(feedUrl) }
        Thread(task).apply { isDaemon = true; name = "ambient-feed" }.start()
        return try {
            task.get(feedTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            throw RuntimeException("timed out after ${feedTimeout.toSeconds()}s")
        } catch (e: ExecutionException) {
            throw RuntimeException(e.cause?.message ?: e.message ?: "fetch failed")
        } catch (e: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            throw RuntimeException("interrupted")
        }
    }

    /** One blocking GET. Non-2xx and over-cap bodies both throw — treated as a feed error by the caller. */
    private fun fetchOnce(feedUrl: String): String =
        http.get()
            .uri(URI.create(feedUrl))            // absolute URI, no template expansion of a stray {} in the URL
            .exchange { _, response ->
                if (response.statusCode.isError) throw RuntimeException("HTTP ${response.statusCode.value()}")
                readCapped(response.body)
            }

    /** Read at most [maxBytes] from the response, rejecting anything larger BEFORE it is parsed (so a
     *  hostile giant body can't exhaust memory). Bounded to maxBytes + one 8 KiB chunk. */
    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val r = input.read(chunk)
            if (r < 0) break
            total += r
            if (total > maxBytes) throw RuntimeException("response exceeded ${maxBytes}-byte cap")
            out.write(chunk, 0, r)
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun isHttps(url: String): Boolean =
        try { URI(url).scheme?.equals("https", ignoreCase = true) == true } catch (e: Exception) { false }

    private fun hostOf(feedUrl: String): String =
        try { URI(feedUrl).host ?: feedUrl } catch (e: Exception) { feedUrl }

    private companion object {
        const val REASON_NO_ITEMS = "feeds returned no items"

        // Structured event ids for the operator log (the OpenAiImageDescriber precedent).
        const val EV_FEED_YIELD = "ambient.feed.yield"
        const val EV_FEED_ERROR = "ambient.feed.error"
        const val EV_FEED_REJECTED = "ambient.feed.rejected"
    }
}
