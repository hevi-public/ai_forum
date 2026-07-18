package com.aiforum.ambient

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

/**
 * The S1 production [ArticleSource] (plan_docs/ambient-slice-1.md): a small canned fixture list, rotating
 * so consecutive dev ticks open different articles. It does NO IO, so it needs no enabled flag on the
 * adapter itself — the scheduler's `aiforum.ambient.enabled` gates whether ticks happen at all. Real
 * sourcing (allowlist feeds, dedupe) is S5, behind this same interface.
 *
 * `@Profile("!test")` so it never competes with the `@Primary` ScriptableArticleSource under test.
 */
@Component
@Profile("!test")
class StubArticleSource : ArticleSource {

    // Rotates through the fixtures; AtomicInteger so concurrent ticks (a manual + a scheduled one) each get
    // a stable, distinct index without a lock. Never returns null in S1 — there is always something to post.
    private val cursor = AtomicInteger(0)

    private val fixtures = listOf(
        Article(
            title = "SQLite's write-ahead log, explained",
            url = "https://example.org/articles/sqlite-wal",
            summary = "Why WAL mode lets readers and one writer proceed concurrently, and what the -wal/-shm sidecars actually hold.",
        ),
        Article(
            title = "Kotlin coroutines are not threads",
            url = "https://example.org/articles/kotlin-coroutines",
            summary = "Structured concurrency, dispatchers, and why a suspending function suspends rather than blocks a thread.",
        ),
        Article(
            title = "How large language models tokenize text",
            url = "https://example.org/articles/llm-tokenization",
            summary = "Byte-pair encoding, why token counts diverge from word counts, and what that means for context windows.",
        ),
        Article(
            title = "Recursive CTEs for tree queries",
            url = "https://example.org/articles/recursive-ctes",
            summary = "Walking a self-referencing table with WITH RECURSIVE — ancestor paths, subtrees, and the cycle guard.",
        ),
    )

    override fun next(): Article? = fixtures[cursor.getAndIncrement().mod(fixtures.size)]
}
