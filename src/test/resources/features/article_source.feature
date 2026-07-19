Feature: Article source outcomes — failure and distinguishable no-ops
  S5 (plan_docs/ambient-slice-5.md §4): the real FeedArticleSource can never wire under the test
  profile — that profile wall IS the security rail (§3's "Network under test" row) — so its feed/parse/
  dedupe internals pin at Tier-0/Tier-1, never here. Acceptance instead pins the tick-level CONTRACT
  through the ArticleSource port fake (ScriptableArticleSource): what the tick RECORDS for each of the
  source's three outcomes. A failing source records a failed run carrying its own message. An empty
  source records a no-op carrying the source's own reason, rather than one fixed generic string. And a
  dedupe-exhausted source records a no-op DISTINGUISHABLE from the plain-empty case — an operator
  reading /admin/ambient can tell "feeds returned no items" apart from "all items already seen" instead
  of both looking identical.

  Background:
    Given a persona "sol" exists

  Scenario: A failing article source records a failed run with its message
    Given the ArticleSource fails with "feed unreachable"
    When the owner triggers an ambient tick
    Then the ambient run is recorded with outcome "failed" and action "post"
    And the ambient run detail contains "feed unreachable"

  Scenario: An empty source records a no-op with the source's reason
    Given the ArticleSource is empty because "feeds returned no items"
    When the owner triggers an ambient tick
    Then the ambient run is recorded with outcome "no-op"
    And the ambient run detail contains "feeds returned no items"

  Scenario: A dedupe-exhausted source records a distinguishable no-op
    Given the ArticleSource is empty because "all 12 feed items already seen"
    When the owner triggers an ambient tick
    Then the ambient run is recorded with outcome "no-op"
    And the ambient run detail contains "all 12 feed items already seen"
