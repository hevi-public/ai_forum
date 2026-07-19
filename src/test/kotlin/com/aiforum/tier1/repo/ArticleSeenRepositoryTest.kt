package com.aiforum.tier1.repo

import com.aiforum.repo.ArticleSeenRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Tier-1: [ArticleSeenRepository] against the real test SQLite DB (V23 `article_seen`). Pins the
 * exists/record round-trip and the PRIMARY KEY dedupe guard the feed source's "post each article once"
 * property relies on. Standalone table (no FKs), so cleanup is a single `DELETE FROM article_seen` in
 * both @BeforeEach and @AfterEach — leaving no rows to surprise another tier-1 class.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class ArticleSeenRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var repo: ArticleSeenRepository

    @BeforeEach @AfterEach
    fun clean() {
        jdbc.update("DELETE FROM article_seen")
    }

    @Test
    fun `an unrecorded url does not exist until recording it makes it exist`() {
        val url = "https://example.org/articles/wal"
        assertFalse(repo.exists(url), "nothing recorded yet")

        repo.record(url)

        assertTrue(repo.exists(url), "recorded url is now seen")
        assertFalse(repo.exists("https://example.org/articles/other"), "a different url is still unseen")
    }

    @Test
    fun `recording the same url twice is an idempotent no-op — PK dedupe, one row, no throw`() {
        val url = "https://example.org/articles/dupe"
        repo.record(url)
        repo.record(url)   // must not throw on the duplicate PRIMARY KEY

        assertTrue(repo.exists(url))
        val rows = jdbc.queryForObject("SELECT COUNT(*) FROM article_seen WHERE url = ?", Int::class.java, url)
        assertEquals(1, rows, "the duplicate record collapsed to a single row (INSERT OR IGNORE)")
    }

    @Test
    fun `first_seen is stamped from the injected clock`() {
        repo.record("https://example.org/articles/stamped")
        // The test profile pins Clock to 2026-01-01T12:00:00Z (FixedClockConfig), so first_seen is exact.
        val stamp = jdbc.queryForObject(
            "SELECT first_seen FROM article_seen WHERE url = ?", String::class.java,
            "https://example.org/articles/stamped",
        )
        assertEquals("2026-01-01T12:00:00Z", stamp)
    }
}
