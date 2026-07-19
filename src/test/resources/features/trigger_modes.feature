Feature: Generation triggers — sequential fan-out and partial-roomful
  Fan-out runs sequentially in M1 (§4), and crucially one persona failing does NOT abort the room: the
  others still post (partial-roomful). LLM behaviours are enqueued in persona order.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    And a persona "vex" exists
    And a persona "pike" exists

  Scenario: One persona fails, the rest of the room still posts
    Given the LLM will respond with "sol's take"
    And the LLM will fail with a timeout
    And the LLM will respond with "pike's take"
    When the owner fans out to "sol, vex, pike"
    Then exactly 2 replies are posted
    And exactly 1 reply is failed

  # S2 ambient variant (plan_docs/ambient-slice-2.md §5 step 3, "post" action): the tick's own summon
  # rides the SAME "Whole Topic + Anyone" dispatcher fan-out a create-thread summon does — a
  # byte-identical summonAsync path, unchanged from S1 — so partial-roomful holds for an
  # ambient-opened thread too, not just an owner-created one.
  Scenario: An ambient article tick fans out to the room; one persona fails, the rest still post
    Given the ArticleSource has the article "Why is SQLite fast?" at "https://example.com/sqlite" summarised "A deep dive into B-trees vs LSM"
    And the LLM will respond with "sol, vex, pike"
    And the LLM will respond with "sol's take"
    And the LLM will fail with a timeout
    And the LLM will respond with "pike's take"
    When the owner triggers an ambient tick
    Then exactly 2 replies are posted
    And exactly 1 reply is failed
