package com.aiforum.tier2.service

import com.aiforum.domain.Comment
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ScopeMode
import com.aiforum.github.ChangedFile
import com.aiforum.github.GitHubClient
import com.aiforum.github.GitHubResult
import com.aiforum.github.PrComment
import com.aiforum.github.PullDetail
import com.aiforum.github.PullResult
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.GitHubPrThreadRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.ThreadRepository
import com.aiforum.service.GenerationService
import com.aiforum.service.GitHubPrIngestionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tier-2: GitHubPrIngestionService running its real orchestration over fakes at the one IO seam (the
 * GitHubClient) plus in-memory repo/generation stubs (the all-open plugin makes the @Service/@Repository
 * methods overridable). Pins the branches — created (thread + mapping + discussion nodes + summon),
 * idempotent (short-circuit, no re-fetch), unavailable (nothing written). The end-to-end
 * summon-settles-into-a-reply is proven in github_pr_thread.feature.
 */
@Tag("tier2")
class GitHubPrIngestionServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC)

    private class FakeGitHub(private val result: PullResult) : GitHubClient {
        var pullCalls = 0
        override fun overview() = GitHubResult.Unavailable("n/a")
        override fun pull(number: Int): PullResult {
            pullCalls++
            return result
        }
    }

    private class RecordingThreads : ThreadRepository(JdbcTemplate(), Clock.systemUTC()) {
        val inserted = mutableMapOf<String, Pair<String, String>>() // id -> (title, body)
        override fun insert(id: String, title: String, body: String) {
            inserted[id] = title to body
        }
    }

    // events is a shared ordering log so a test can assert the discussion is posted BEFORE the summon.
    private class RecordingComments(private val events: MutableList<String> = mutableListOf()) :
        CommentRepository(JdbcTemplate(), Clock.systemUTC()) {
        val inserted = mutableListOf<Pair<Comment, Instant>>()
        override fun insertAt(c: Comment, createdAt: Instant) {
            inserted += c to createdAt
            events += "comment:${c.authorId}"
        }
    }

    private class InMemoryMap : GitHubPrThreadRepository(JdbcTemplate(), Clock.systemUTC()) {
        val rows = mutableListOf<Mapping>()
        override fun findByPr(repo: String, prNumber: Int) =
            rows.firstOrNull { it.repo == repo && it.prNumber == prNumber }
        override fun insert(id: String, repo: String, prNumber: Int, threadId: String, headSha: String?) {
            rows += Mapping(id, repo, prNumber, threadId, headSha)
        }
    }

    private class SpyGeneration(private val events: MutableList<String> = mutableListOf()) : GenerationService(
        object : LlmClient {
            override fun generate(request: LlmRequest, cancellation: CancellationToken) = LlmResponse("x")
        },
        CommentRepository(JdbcTemplate(), Clock.systemUTC()),
        PersonaRepository(JdbcTemplate()),
    ) {
        val summons = mutableListOf<String>()
        override fun summonAsync(
            threadId: String,
            parentId: String?,
            personaIds: List<String>,
            text: String,
            scope: ScopeMode,
            includeSiblings: Boolean,
            postAsOwner: Boolean,
            routingScope: ScopeMode,
            initialBudget: Int?,
            onSettled: ((List<String>) -> Unit)?,
        ) {
            summons += threadId
            events += "summon"
        }
    }

    private fun pullOk(number: Int = 42, comments: List<PrComment> = emptyList()) = PullResult.Ok(
        PullDetail(
            number = number, title = "Batch", author = "octocat",
            url = "https://github.com/o/r/pull/$number", state = "OPEN", isDraft = false,
            body = "Fixes the N+1.", baseRef = "main", headRef = "feature", headSha = "deadbeef",
            changedFiles = listOf(ChangedFile("a.kt", 2, 1)), diff = "diff --git a/a.kt b/a.kt\n+x",
            comments = comments,
        ),
    )

    private fun service(github: GitHubClient, threads: RecordingThreads, comments: RecordingComments, map: InMemoryMap, gen: SpyGeneration) =
        GitHubPrIngestionService(github, threads, comments, map, gen, clock, "o/r")

    @Test
    fun `a fresh PR creates a thread carrying the formatted OP, records the mapping, and summons the room`() {
        val github = FakeGitHub(pullOk(42))
        val threads = RecordingThreads()
        val comments = RecordingComments()
        val map = InMemoryMap()
        val gen = SpyGeneration()

        val result = service(github, threads, comments, map, gen).ingest(42)

        val created = assertInstanceOf(GitHubPrIngestionService.Result.Created::class.java, result)
        // The opening post carries the rendered PR (title + description + changed file).
        val (title, body) = threads.inserted.getValue(created.threadId)
        assertEquals("#42 — Batch", title)
        assertTrue(body.contains("Fixes the N+1."), "OP carries the description:\n$body")
        assertTrue(body.contains("a.kt"), "OP carries the changed file:\n$body")
        // Mapping recorded for idempotency, with the head sha for a future re-sync.
        assertEquals(1, map.rows.size)
        assertEquals(42, map.rows.first().prNumber)
        assertEquals(created.threadId, map.rows.first().threadId)
        assertEquals("deadbeef", map.rows.first().headSha)
        // No discussion on this PR → no comment nodes.
        assertTrue(comments.inserted.isEmpty())
        // The room was summoned on the new thread.
        assertEquals(listOf(created.threadId), gen.summons)
        assertEquals(1, github.pullCalls)
    }

    @Test
    fun `the PR discussion is posted as gh nodes, in order, before the room is summoned`() {
        val events = mutableListOf<String>()
        val github = FakeGitHub(
            pullOk(
                42,
                comments = listOf(
                    PrComment("dana", "Looks reasonable to me.", "2026-06-25T09:00:00Z", kind = "comment"),
                    PrComment("paul", "Add a test for the empty case?", "2026-06-25T10:00:00Z", kind = "review", reviewState = "CHANGES_REQUESTED"),
                ),
            ),
        )
        val threads = RecordingThreads()
        val comments = RecordingComments(events)
        val map = InMemoryMap()
        val gen = SpyGeneration(events)

        val created = assertInstanceOf(
            GitHubPrIngestionService.Result.Created::class.java,
            service(github, threads, comments, map, gen).ingest(42),
        )

        // Two top-level POSTED nodes authored as gh:<login>, in PR-chronological order, each stamped with
        // its real GitHub timestamp.
        assertEquals(2, comments.inserted.size)
        val (dana, danaAt) = comments.inserted[0]
        assertEquals("gh:dana", dana.authorId)
        assertEquals(GenerationState.POSTED, dana.state)
        assertNull(dana.parentId, "discussion nodes sit top-level under the post")
        assertTrue(dana.body.contains("Looks reasonable to me."))
        assertEquals(Instant.parse("2026-06-25T09:00:00Z"), danaAt)
        val paul = comments.inserted[1].first
        assertEquals("gh:paul", paul.authorId)
        assertTrue(paul.body.contains("Requested changes"), "a review folds its verdict in: ${paul.body}")
        // The discussion is in place BEFORE the room is summoned, so the dispatcher reads it.
        assertEquals(listOf("comment:gh:dana", "comment:gh:paul", "summon"), events)
        assertEquals(listOf(created.threadId), gen.summons)
    }

    @Test
    fun `an already-ingested PR returns its existing thread without re-fetching or re-creating`() {
        val github = FakeGitHub(pullOk(42))
        val threads = RecordingThreads()
        val comments = RecordingComments()
        val map = InMemoryMap().apply { insert("M0", "o/r", 42, "T-existing", null) }
        val gen = SpyGeneration()

        val result = service(github, threads, comments, map, gen).ingest(42)

        val existing = assertInstanceOf(GitHubPrIngestionService.Result.Existing::class.java, result)
        assertEquals("T-existing", existing.threadId)
        assertEquals(0, github.pullCalls, "an ingested PR must not hit the gh seam again")
        assertTrue(threads.inserted.isEmpty(), "no second thread created")
        assertTrue(comments.inserted.isEmpty(), "no discussion re-posted")
        assertEquals(1, map.rows.size, "no duplicate mapping")
        assertTrue(gen.summons.isEmpty(), "no re-summon")
    }

    @Test
    fun `an unavailable PR fetch writes nothing and surfaces the reason`() {
        val github = FakeGitHub(PullResult.Unavailable("PR not found"))
        val threads = RecordingThreads()
        val comments = RecordingComments()
        val map = InMemoryMap()
        val gen = SpyGeneration()

        val result = service(github, threads, comments, map, gen).ingest(42)

        val unavailable = assertInstanceOf(GitHubPrIngestionService.Result.Unavailable::class.java, result)
        assertEquals("PR not found", unavailable.reason)
        assertEquals(1, github.pullCalls)
        assertTrue(threads.inserted.isEmpty(), "a failed fetch creates no thread")
        assertTrue(comments.inserted.isEmpty(), "a failed fetch posts no discussion")
        assertTrue(map.rows.isEmpty(), "a failed fetch records no mapping")
        assertTrue(gen.summons.isEmpty(), "a failed fetch summons no one")
    }
}
