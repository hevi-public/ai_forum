Feature: Deleting a persona — removing a member from the room

  The owner can remove a persona from the members list (modelled on thread deletion, §8). What hangs
  off a persona now splits along one line: HISTORY versus LIVE RELATIONAL STATE.

  History survives its subject. Comment authorship — and, per §10 of
  plan_docs/ai-driven-forum-direction.md, thread authorship too — is stored as a plain attribution
  string, not a foreign key to persona(id) (V1 schema), so past bylines on both threads and comments
  are untouched by a deletion and keep reading exactly as they did.

  Live state does not. Since V24 (plan_docs/ambient-slice-3.md) a persona also carries qualitative
  RELATIONS: directed persona→persona stances, real foreign keys in both directions with ON DELETE
  CASCADE. A stance toward — or from — someone who has left the room has nothing left to mean, and a
  dangling one would be injected into a prompt naming a persona who no longer posts. So deleting a
  member takes its own stances AND everyone else's stances about it with it, in one statement,
  enforced by SQLite rather than by application code. Worth knowing before reaching for
  delete-and-reseed as a shortcut: the persona row comes back, hand-authored stances do not.
  The member leaves the list; the other members, and the relations between them, are untouched.

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

  # Both directions cascade, and only those: the room's opinion ABOUT the departing member goes, its own
  # opinion OF the room goes, and an edge between two members who stayed is left alone. The outgoing
  # direction can't be read off a profile once its holder is gone, so the scenario re-adds a member under
  # the same id — a genuinely cascaded row stays gone, whereas an orphan left behind resurfaces there.
  Scenario: Deleting a persona removes its stances in both directions
    Given a persona "Survivor" exists
    And a persona "Bystander" exists
    And persona "Doomed" has a stance toward "Survivor" of "needles her about hype"
    And persona "Survivor" has a stance toward "Doomed" of "finds his certainty tiring"
    And persona "Survivor" has a stance toward "Bystander" of "trusts her reading of a paper"
    When the owner deletes the "Doomed" persona
    Then the profile for "Survivor" shows no stance toward "Doomed"
    And the profile for "Survivor" shows a stance toward "Bystander" of "trusts her reading of a paper"
    When a persona "Doomed" exists
    Then the profile for "Doomed" shows no stance toward "Survivor"
