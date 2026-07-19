Feature: Deleting a persona — removing a member from the room

  The owner can remove a persona from the members list (modelled on thread deletion, §8). Unlike a
  thread, a persona has nothing hanging off it: comment authorship — and, per §10 of
  plan_docs/ai-driven-forum-direction.md, thread authorship too — is stored as a plain attribution
  string, not a foreign key to persona(id) (V1 schema). Deletion therefore remains a clean
  single-row removal, and existing bylines on both threads and comments survive it by design.
  The member leaves the list; the others are untouched.

  Background:
    Given a persona "Doomed" exists

  Scenario: The delete control is offered on a member row
    When the owner opens the members list
    Then the delete control is present on the "Doomed" member row

  Scenario: Deleting a persona removes it from the members list
    When the owner deletes the "Doomed" persona
    Then the response status is 200
    When the owner opens the members list
    Then the members list no longer shows the "Doomed" persona

  Scenario: Deleting a persona leaves other personas intact
    Given a persona "Survivor" exists
    When the owner deletes the "Doomed" persona
    And the owner opens the members list
    Then the members list no longer shows the "Doomed" persona
    And the members list still shows the "Survivor" persona
