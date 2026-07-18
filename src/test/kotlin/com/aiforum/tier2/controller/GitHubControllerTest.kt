package com.aiforum.tier2.controller

import com.aiforum.github.GitHubClient
import com.aiforum.github.GitHubOverview
import com.aiforum.github.GitHubResult
import com.aiforum.github.Issue
import com.aiforum.github.PullRequest
import com.aiforum.github.PullResult
import com.aiforum.github.RepoSummary
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
import com.aiforum.web.GitHubController
import com.aiforum.web.GitHubPageView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.ui.ExtendedModelMap
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tier-2: the real [GitHubController] running against a fake [GitHubClient] seam (the one mock level) and a
 * fixed clock. Proves the controller maps an Ok snapshot into the page view (relativising timestamps,
 * badging already-discussed PRs) and an Unavailable result into the off-state, with no Spring context.
 *
 * The discuss endpoint's full ingestion path is covered end-to-end in github_pr_thread.feature.
 */
@Tag("tier2")
class GitHubControllerTest {

    private val clock = Clock.fixed(Instant.parse("2026-06-24T12:00:00Z"), ZoneOffset.UTC)

    private class StubClient(private val result: GitHubResult) : GitHubClient {
        override fun overview() = result
        override fun pull(number: Int) = PullResult.Unavailable("not used here")
    }

    // A real GitHubPrIngestionService whose only stubbed collaborator is the mapping repo (so existingThreads
    // returns a programmed badge map). The other deps are bare — none of their methods run on the page path.
    private val genStub = GenerationService(
        object : LlmClient {
            override fun generate(request: LlmRequest, cancellation: CancellationToken) = LlmResponse("x")
        },
        CommentRepository(JdbcTemplate(), clock),
        PersonaRepository(JdbcTemplate()),
    )

    private fun ingestionWith(existing: Map<Int, String>) = GitHubPrIngestionService(
        StubClient(GitHubResult.Unavailable("x")),
        ThreadRepository(JdbcTemplate(), clock),
        CommentRepository(JdbcTemplate(), clock),
        object : GitHubPrThreadRepository(JdbcTemplate(), clock) {
            override fun threadIdsByNumbers(repo: String, numbers: List<Int>) = existing
        },
        genStub,
        clock,
        "",
    )

    private fun render(result: GitHubResult, existing: Map<Int, String> = emptyMap()): GitHubPageView {
        val model = ExtendedModelMap()
        val view = GitHubController(StubClient(result), ingestionWith(existing), clock).page(model)
        assertEquals("github", view)
        return model.getAttribute("page") as GitHubPageView
    }

    @Test
    fun `an Ok snapshot becomes an available page with relativised timestamps`() {
        val overview = GitHubOverview(
            repo = RepoSummary("hevi-public/ai_forum", "AI forum", "https://x", "main", 7, 3, 2),
            pulls = listOf(PullRequest(12, "Add gh MCP", "octocat", "u", isDraft = true, createdAt = "2026-06-22T12:00:00Z")),
            issues = listOf(Issue(5, "Bug", "hubot", "u", createdAt = "2026-06-24T11:00:00Z")),
        )
        val page = render(GitHubResult.Ok(overview))

        assertTrue(page.available)
        assertEquals("hevi-public/ai_forum", page.repo?.nameWithOwner)
        assertEquals(1, page.pulls.size)
        assertEquals(12, page.pulls.first().number)
        assertTrue(page.pulls.first().isDraft)
        assertEquals("2d", page.pulls.first().ago)   // 2026-06-22 → 2026-06-24
        assertEquals("1h", page.issues.first().ago)   // 11:00 → 12:00
        assertNull(page.pulls.first().threadId, "an un-ingested PR carries no thread link")
    }

    @Test
    fun `a PR already ingested carries its thread id for the View thread link`() {
        val overview = GitHubOverview(
            repo = RepoSummary("o/r", null, "u", "main", 0, 0, 0),
            pulls = listOf(
                PullRequest(12, "Discussed", "octocat", "u", isDraft = false, createdAt = "2026-06-24T11:00:00Z"),
                PullRequest(13, "Fresh", "octocat", "u", isDraft = false, createdAt = "2026-06-24T11:00:00Z"),
            ),
            issues = emptyList(),
        )
        val page = render(GitHubResult.Ok(overview), existing = mapOf(12 to "thread-12"))

        assertEquals("thread-12", page.pulls.first { it.number == 12 }.threadId)
        assertNull(page.pulls.first { it.number == 13 }.threadId)
    }

    @Test
    fun `an Unavailable result becomes the off-state page carrying the reason`() {
        val page = render(GitHubResult.Unavailable("GitHub integration is off."))
        assertFalse(page.available)
        assertEquals("GitHub integration is off.", page.reason)
        assertNull(page.repo)
        assertTrue(page.pulls.isEmpty())
    }

    @Test
    fun `an unparseable timestamp falls back to the raw string instead of throwing`() {
        val overview = GitHubOverview(
            repo = RepoSummary("o/r", null, "u", "main", 0, 0, 0),
            pulls = listOf(PullRequest(1, "t", "a", "u", isDraft = false, createdAt = "not-a-date")),
            issues = emptyList(),
        )
        val page = render(GitHubResult.Ok(overview))
        assertEquals("not-a-date", page.pulls.first().ago)
    }
}
