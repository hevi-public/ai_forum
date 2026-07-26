Feature: The forum is seeded with a default persona team
  A fresh forum needs a usable set of personas without the owner hand-authoring them first, so the
  predefined personas in config (aiforum.seed.personas) are inserted on first startup if they are
  absent. The seed is idempotent: a reboot never duplicates a persona or clobbers an owner's edits (§6).

  Scenario: The predefined personas are seeded into an empty forum
    Given an empty forum
    When the predefined personas are seeded
    Then every predefined persona appears in the members list

  Scenario: Re-seeding never duplicates the predefined personas
    Given an empty forum
    And the predefined personas have already been seeded
    When the predefined personas are seeded again
    Then no personas are added the second time
    And every predefined persona appears exactly once in the members list

  # Personas also carry directed, free-text opinions of each other (aiforum.seed.stances) so a fresh
  # forum starts with texture between members rather than a room of blank strangers. Seeding the roster
  # seeds these alongside it, in the same idempotent spirit: a reboot never duplicates a persona AND
  # never clobbers an owner's hand-rewritten stance (§6).
  Scenario: The predefined stances are seeded alongside the roster
    Given an empty forum
    When the predefined personas are seeded
    Then the profile for "Sol" shows a stance toward "Saul" of "Fond of him, doesn't take his layer seriously - treats frontend problems as backend problems in a costume, and says so just often enough to sting."

  Scenario: Re-seeding never clobbers an owner-edited stance
    Given an empty forum
    And the predefined personas have already been seeded
    And the owner has rewritten the stance from "Sol" toward "Saul" as "Owner's note: Sol has quietly started reading Saul's PRs before anyone else's."
    When the predefined personas are seeded again
    Then the profile for "Sol" shows a stance toward "Saul" of "Owner's note: Sol has quietly started reading Saul's PRs before anyone else's."

  # A config entry can outlive the persona it points at — e.g. the owner removes someone from the
  # roster by hand without also pruning aiforum.seed.stances. That must degrade to "this one edge is
  # skipped", never take the whole boot down with it, and it must not stop other, valid edges from
  # seeding.
  Scenario: A configured stance whose persona is missing is skipped, not a boot failure
    Given an empty forum
    And the predefined personas are seeded
    And persona "Quackers" has been removed from the roster
    When only the predefined stances are re-seeded
    Then the profile for "Sol" shows a stance toward "Saul" of "Fond of him, doesn't take his layer seriously - treats frontend problems as backend problems in a costume, and says so just often enough to sting."
    And the profile for "Sol" shows no stance toward "Quackers"

  # S4b: the interest phase is FIRST-SEED-ONLY PER MEMBER, and the distinction is load-bearing. An
  # interest is keyed by its own text, so a phrase the drift pass legitimately set down reads as
  # "missing" from the config's point of view — and a per-phrase check would put it straight back on the
  # next boot. That would grow the member past its ceiling, silently undo every drift a restart followed,
  # and re-converge the room on the seed list, which is the opposite of what this slice is for.
  Scenario: A phrase a member no longer holds is not re-seeded on the next boot
    Given an empty forum
    And the predefined personas are seeded
    And persona "Sol" has set down the seeded interest "storage engines under real load"
    When the predefined personas are seeded again
    Then the profile for "Sol" shows no interest "storage engines under real load"
