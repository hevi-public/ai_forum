Feature: Discuss a GitHub PR in the forum
  From the /github page the owner clicks "Discuss" on an open pull request. The app fetches the PR through
  the read-only GitHubClient seam, creates a forum thread whose opening post carries the change
  (description + changed files + diff), and summons the room — so a persona summarises what changed and the
  team can discuss it. One PR maps to one thread; clicking Discuss again reuses it.

  Background:
    Given the GitHub integration reports the repository "hevi-public/ai_forum"

  Scenario: Discussing a PR opens a thread carrying the change and summons the room to summarise it
    Given a persona "Sol" exists
    And the LLM will respond with "This PR swaps the N+1 query for a single batched fetch."
    And an in-depth pull request #42 "Batch the comment query" by "octocat" described as "Fixes the N+1 in the comment tree." changing "src/CommentRepository.kt" with diff:
      """
      diff --git a/src/CommentRepository.kt b/src/CommentRepository.kt
      +    fun batchFetch(ids: List<String>) = jdbc.query(...)
      -    fun fetchOne(id: String) = jdbc.query(...)
      """
    When the owner clicks Discuss on pull request #42
    Then the thread exists with title "Batch the comment query"
    And the thread shows the opening post "Fixes the N+1 in the comment tree."
    And the thread shows the opening post "src/CommentRepository.kt"
    And the thread carries a reply reading "This PR swaps the N+1 query for a single batched fetch."

  Scenario: The PR's discussion is ingested as team-mate nodes the room reads before summarising
    Given a persona "Sol" exists
    And the LLM will respond with "Summary: batches the comment query; Dana and Paul both weighed in."
    And an in-depth pull request #88 "Batch the query" by "octocat" described as "Speeds up the comment tree." changing "src/Repo.kt" with diff:
      """
      diff --git a/src/Repo.kt b/src/Repo.kt
      +batched fetch
      """
    And pull request #88 has the discussion:
      | author | body                        |
      | dana   | Looks reasonable to me.     |
      | paul   | Can we add a test for this? |
    When the owner clicks Discuss on pull request #88
    Then the thread shows a comment by "dana" reading "Looks reasonable to me."
    And the thread shows a comment by "paul" reading "Can we add a test for this?"
    And the thread carries a reply reading "Summary: batches the comment query; Dana and Paul both weighed in."

  Scenario: Discussing the same PR twice reuses the one thread
    Given an in-depth pull request #7 "Tiny fix" by "octocat" described as "A one-liner." changing "a.kt" with diff:
      """
      diff --git a/a.kt b/a.kt
      +the fix
      """
    When the owner clicks Discuss on pull request #7
    And the owner clicks Discuss on pull request #7 again
    Then both discussions opened the same thread
