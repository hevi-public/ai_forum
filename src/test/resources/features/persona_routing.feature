Feature: Anyone — the room decides who replies
  The composer defaults its "who answers" selection to "Anyone": instead of the owner naming a persona,
  the AI dispatcher reads the topic and picks which roster member(s) should chime in (§4). This routes
  through the same single LLM seam, so the dispatcher's choice is the first call and the chosen
  persona's reply is the second.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "Sol" exists
    And a persona "Paul" exists

  Scenario: The dispatcher routes an open question to the persona it names
    Given the LLM will respond with "Sol"
    And the LLM will respond with "Add an index on the lookup column"
    When the owner asks the room "how do we make these queries faster?"
    Then the reply is "posted"
    And the reply author is "Sol"
    And the reply body contains "Add an index on the lookup column"

  Scenario: An unparseable routing answer falls back so someone still replies
    Given the LLM will respond with "not sure who"
    And the LLM will respond with "Happy to take a look"
    When the owner asks the room "any thoughts on this?"
    Then the reply is "posted"
    And the reply body contains "Happy to take a look"

  # The dispatcher now reads each persona's structured abilities (V10), not just their name, so it can
  # match the topic to who's actually equipped for it. See plan_docs/persona-traits-routing.md.
  Scenario: The dispatcher's roster carries each persona's skills, not just their name
    Given a persona "Dana" skilled in "design" exists
    And the LLM will respond with "Sol"
    And the LLM will respond with "indexes help here"
    When the owner asks the room "how do we make these queries faster?"
    Then the reply is "posted"
    And the dispatcher's roster lists "design"

  # Relations are dispatcher input too, but deliberately scoped: the block lists only the edges POINTING
  # AT someone already talking, because that is the only relation information that can inform who should
  # weigh in NEXT — the full roster graph is dozens of edges of noise. So a stance stays invisible to the
  # dispatcher until its target is actually in the room, and disappears again from a silent discussion.
  Scenario: The dispatcher sees a stance aimed at someone already in the discussion
    Given a posted reply from "Sol" saying "Indexes help here"
    And persona "Paul" has a stance toward "Sol" of "needles him about hype"
    And the LLM will respond with "Paul"
    And the LLM will respond with "Hype aside, an index is cheap"
    When the owner asks the room "how do we make these queries faster?"
    Then the reply is "posted"
    And the dispatcher's roster lists "needles him about hype"

  Scenario: With nobody talking yet, the dispatcher is told nothing about relations
    Given persona "Paul" has a stance toward "Sol" of "needles him about hype"
    And the LLM will respond with "Sol"
    And the LLM will respond with "Add an index on the lookup column"
    When the owner asks the room "how do we make these queries faster?"
    Then the reply is "posted"
    And the dispatcher's prompt carries no relations section

  # The owner's "looking at" selector scopes WHAT the dispatcher reads to decide who replies — the whole
  # topic (default) or just the branch being replied to — independent of the persona's generation scope.
  Rule: The dispatcher's "looking at" scope is selectable

    Background:
      # tree:  R ─┬─ A ── A1
      #          └─ B
      Given a root comment "R" by "owner"
      And a reply "A" under "R" by "vex"
      And a reply "B" under "R" by "pike"
      And a reply "A1" under "A" by "sol"

    Scenario: This-branch scope shows the dispatcher only the ancestor path
      Given the LLM will respond with "Sol"
      And the LLM will respond with "scoped reply"
      When the owner asks the room under "A1" with branch-only scope
      Then the dispatcher considered node "A1"
      And the dispatcher considered node "A"
      And the dispatcher considered node "R"
      And the dispatcher ignored node "B"

    Scenario: Whole-topic scope shows the dispatcher the sibling branch too
      Given the LLM will respond with "Sol"
      And the LLM will respond with "broad reply"
      When the owner asks the room under "A1" with whole-thread scope
      Then the dispatcher considered node "B"
