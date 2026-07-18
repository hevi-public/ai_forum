Feature: GitHub page — /github
  GET /github renders a read-only snapshot of the configured repository (summary + open PRs + open
  issues), fetched through the GitHubClient seam. It is off by default and shows a clear "not configured"
  off-state until enabled, so the page is always reachable without erroring.

  Scenario: The page shows an off-state when the integration is unavailable
    Given the GitHub integration is unavailable with reason "GitHub integration is off."
    When the owner visits the GitHub page
    Then the GitHub page shows the unavailable notice "GitHub integration is off."

  Scenario: The page renders the repo summary with its open PRs and issues
    Given the GitHub integration reports the repository "hevi-public/ai_forum"
    And an open pull request #12 "Add gh MCP" by "octocat"
    And an open issue #5 "Flaky acceptance test" by "hubot"
    When the owner visits the GitHub page
    Then the GitHub page shows the repository "hevi-public/ai_forum"
    And the GitHub page lists pull request #12
    And the GitHub page lists issue #5

  Scenario: With no open PRs or issues the page shows empty sections
    Given the GitHub integration reports the repository "hevi-public/ai_forum"
    When the owner visits the GitHub page
    Then the GitHub page shows the repository "hevi-public/ai_forum"
    And the GitHub page shows no open pull requests
    And the GitHub page shows no open issues
