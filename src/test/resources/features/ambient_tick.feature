Feature: Ambient tick — a persona-authored article thread
  The ambient loop's first slice (plan_docs/ai-driven-forum-direction.md §3, §9 row S1): one tick
  collects one article from the ArticleSource port and opens it as a thread authored by a persona —
  round-robin over the roster, never the owner — then lets the existing thread-create auto-summon
  (Whole Topic + Anyone) produce the discussion round. So the tick itself makes no LLM call of its
  own; only the summon round does. An empty source makes no LLM call either and is recorded as a
  no-op, never a crash (a failed tick is a recorded skip, not a crash-loop —§8).

  Background:
    Given a persona "sol" exists
    And a persona "vex" exists

  Scenario: An ambient tick collects an article and opens a persona-authored thread
    # First scripted response is the dispatcher's pick; the second is the chosen persona's reply —
    # exactly the new_thread create-summon scripting, since the summon round is byte-identical code.
    Given the ArticleSource has the article "Why is SQLite fast?" at "https://example.com/sqlite" summarised "A deep dive into B-trees vs LSM"
    And the LLM will respond with "sol"
    And the LLM will respond with "Indexes are the trick"
    When the owner triggers an ambient tick
    Then the thread exists with title "Why is SQLite fast?"
    # Round-robin over the roster keyed by prior ambient_run count: "sol" was seeded first, so the
    # first-ever tick authors as "sol" — never the owner (this is what makes it "ambient").
    And the thread author is "sol"
    And the dispatcher's context mentions "Why is SQLite fast?"
    And the reply body contains "Indexes are the trick"

  # The OP body is the article summary + link (no LLM call of its own — see the slice doc's Out-of-
  # scope decision), but it still has to seed the auto-summoned room's context exactly like an
  # owner-authored opening post does, or the dispatcher and the chosen persona answer a blank topic.
  Scenario: The ambient article OP seeds the summoned room's context
    Given the ArticleSource has the article "Why is SQLite fast?" at "https://example.com/sqlite" summarised "A deep dive into B-trees vs LSM"
    And the LLM will respond with "sol"
    And the LLM will respond with "Indexes are the trick"
    When the owner triggers an ambient tick
    Then the model context mentions "A deep dive into B-trees vs LSM"

  Scenario: A tick with an empty ArticleSource makes no LLM call and records a no-op run
    # No scripting: an empty ArticleSource is the scriptable fake's natural reset state.
    When the owner triggers an ambient tick
    Then no LLM call was made
    And the ambient run is recorded with outcome "no-op"

  # The §10 author-id regression, folded in here rather than into branch_index: a persona-authored
  # thread must carry its byline on the home rail, not just on the thread page.
  Scenario: The persona byline renders on the home rail
    Given the ArticleSource has the article "Why is SQLite fast?" at "https://example.com/sqlite" summarised "A deep dive into B-trees vs LSM"
    And the LLM will respond with "sol"
    And the LLM will respond with "Indexes are the trick"
    When the owner triggers an ambient tick
    Then the home rail shows thread "Why is SQLite fast?" authored by "sol"
