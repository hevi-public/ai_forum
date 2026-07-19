package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Clock

/**
 * The ambient feed source's dedupe registry (V23 `article_seen`, plan_docs/ambient-slice-5.md §2
 * "Dedupe"): every article URL [com.aiforum.ambient.FeedArticleSource] has ever yielded, so a later
 * tick against unchanged feed content never re-posts the same article. Shaped like
 * [GitHubPrThreadRepository] — plain `JdbcTemplate` + injected [Clock] (no `Instant.now()`, so a fixed
 * test clock keeps `first_seen` deterministic).
 */
@Repository
class ArticleSeenRepository(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {

    /** True if [url] has already been recorded (i.e. an earlier tick posted it). */
    fun exists(url: String): Boolean =
        (jdbc.queryForObject("SELECT COUNT(*) FROM article_seen WHERE url = ?", Int::class.java, url) ?: 0) > 0

    /**
     * Record [url] as seen. `INSERT OR IGNORE` so a re-record of the same URL is an idempotent no-op that
     * preserves the original `first_seen` (the PK dedupe guard), never a constraint-violation throw.
     */
    fun record(url: String) {
        jdbc.update(
            "INSERT OR IGNORE INTO article_seen(url, first_seen) VALUES (?, ?)",
            url, clock.instant().toString(),
        )
    }
}
