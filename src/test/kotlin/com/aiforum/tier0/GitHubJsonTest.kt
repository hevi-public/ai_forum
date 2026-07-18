package com.aiforum.tier0

import com.aiforum.github.GitHubJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: pure parsing of `gh ... --json` envelopes into the GitHub view types. No IO — canned strings in,
 * data classes out — so every field-extraction branch (nested author/branch/count objects, draft flag,
 * null description, the empty list) is covered without the real `gh` binary.
 */
@Tag("tier0")
class GitHubJsonTest {

    @Test
    fun `parseRepo pulls the summary fields including nested branch and counts`() {
        val json = """
            {"nameWithOwner":"hevi-public/ai_forum","description":"AI forum","url":"https://github.com/hevi-public/ai_forum",
             "defaultBranchRef":{"name":"main"},"stargazerCount":7,"issues":{"totalCount":3},"pullRequests":{"totalCount":2}}
        """.trimIndent()
        val repo = GitHubJson.parseRepo(json)
        assertEquals("hevi-public/ai_forum", repo.nameWithOwner)
        assertEquals("AI forum", repo.description)
        assertEquals("https://github.com/hevi-public/ai_forum", repo.url)
        assertEquals("main", repo.defaultBranch)
        assertEquals(7, repo.stars)
        assertEquals(3, repo.openIssues)
        assertEquals(2, repo.openPrs)
    }

    @Test
    fun `parseRepo treats a blank description as null and tolerates missing nested objects`() {
        val repo = GitHubJson.parseRepo("""{"nameWithOwner":"o/r","description":"","url":"u","stargazerCount":0}""")
        assertNull(repo.description)
        assertEquals("", repo.defaultBranch)
        assertEquals(0, repo.openPrs)
        assertEquals(0, repo.openIssues)
    }

    @Test
    fun `parsePulls maps each row and flattens the author login`() {
        val json = """
            [{"number":12,"title":"Add gh MCP","author":{"login":"octocat"},
              "url":"https://github.com/o/r/pull/12","isDraft":true,"createdAt":"2026-06-20T10:00:00Z"}]
        """.trimIndent()
        val pulls = GitHubJson.parsePulls(json)
        assertEquals(1, pulls.size)
        val pr = pulls.first()
        assertEquals(12, pr.number)
        assertEquals("Add gh MCP", pr.title)
        assertEquals("octocat", pr.author)
        assertTrue(pr.isDraft)
        assertEquals("2026-06-20T10:00:00Z", pr.createdAt)
    }

    @Test
    fun `a missing author falls back to ghost rather than throwing`() {
        val pulls = GitHubJson.parsePulls("""[{"number":1,"title":"t","url":"u","isDraft":false,"createdAt":"2026-06-20T10:00:00Z"}]""")
        assertEquals("ghost", pulls.first().author)
    }

    @Test
    fun `parseIssues maps rows and an empty array yields an empty list`() {
        assertTrue(GitHubJson.parseIssues("[]").isEmpty())
        val issues = GitHubJson.parseIssues("""[{"number":5,"title":"Bug","author":{"login":"hubot"},"url":"u","createdAt":"2026-06-19T10:00:00Z"}]""")
        assertEquals(1, issues.size)
        assertEquals(5, issues.first().number)
        assertEquals("hubot", issues.first().author)
    }

    @Test
    fun `parsePull pulls the description, refs, head sha and changed-file stats`() {
        val json = """
            {"number":42,"title":"Batch the comment query","author":{"login":"octocat"},
             "url":"https://github.com/o/r/pull/42","state":"OPEN","isDraft":true,"body":"Fixes the N+1.",
             "baseRefName":"main","headRefName":"feature","headRefOid":"deadbeef",
             "files":[{"path":"src/CommentRepository.kt","additions":12,"deletions":3}]}
        """.trimIndent()
        val pull = GitHubJson.parsePull(json)
        assertEquals(42, pull.number)
        assertEquals("Batch the comment query", pull.title)
        assertEquals("octocat", pull.author)
        assertEquals("OPEN", pull.state)
        assertTrue(pull.isDraft)
        assertEquals("Fixes the N+1.", pull.body)
        assertEquals("main", pull.baseRef)
        assertEquals("feature", pull.headRef)
        assertEquals("deadbeef", pull.headSha)
        assertEquals(1, pull.changedFiles.size)
        assertEquals("src/CommentRepository.kt", pull.changedFiles.first().path)
        assertEquals(12, pull.changedFiles.first().additions)
        assertEquals(3, pull.changedFiles.first().deletions)
        assertEquals("", pull.diff, "the diff is filled by the client from `gh pr diff`, not parsed here")
    }

    @Test
    fun `parsePull tolerates a missing author and empty file list`() {
        val pull = GitHubJson.parsePull("""{"number":1,"title":"t","url":"u","state":"OPEN","body":""}""")
        assertEquals("ghost", pull.author)
        assertTrue(pull.changedFiles.isEmpty())
        assertTrue(pull.comments.isEmpty())
    }

    @Test
    fun `parsePull merges issue comments and reviews into one chronological discussion, dropping noise`() {
        val json = """
            {"number":1,"title":"t","url":"u","state":"OPEN","body":"",
             "comments":[{"author":{"login":"dana"},"body":"nice work","createdAt":"2026-06-25T10:00:00Z"}],
             "reviews":[
               {"author":{"login":"paul"},"body":"fix the empty case","state":"CHANGES_REQUESTED","submittedAt":"2026-06-25T09:00:00Z"},
               {"author":{"login":"sol"},"body":"","state":"APPROVED","submittedAt":"2026-06-25T11:00:00Z"},
               {"author":{"login":"noise"},"body":"","state":"COMMENTED","submittedAt":"2026-06-25T08:00:00Z"},
               {"author":{"login":"pend"},"body":"draft note","state":"PENDING","submittedAt":"2026-06-25T07:00:00Z"}
             ]}
        """.trimIndent()
        val d = GitHubJson.parsePull(json)
        // Sorted by time: paul 09:00, dana 10:00, sol 11:00. The bodyless COMMENTED review (inline-only) and
        // the PENDING (unsubmitted) review are dropped.
        assertEquals(listOf("paul", "dana", "sol"), d.comments.map { it.author })
        assertEquals("comment", d.comments.first { it.author == "dana" }.kind)
        assertEquals("review", d.comments.first { it.author == "paul" }.kind)
        assertEquals("CHANGES_REQUESTED", d.comments.first { it.author == "paul" }.reviewState)
        assertEquals("APPROVED", d.comments.first { it.author == "sol" }.reviewState)
    }
}
