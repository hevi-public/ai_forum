Feature: Personas & admin
  PENDING the implementing team. The owner can view a persona profile and add/edit personas via the
  admin form, which persists the persona card (§6). Drafted as the spec.

  Scenario: View a persona profile
    Given a persona "sol" exists
    When the owner opens the profile for "sol"
    Then the profile shows the persona's name and descriptor

  Scenario: Admin adds a new persona
    When the owner adds a persona "lune" described as "systems poet"
    Then the persona "lune" exists
    And "lune" appears in the members list

  # The members page must actually offer the create affordance in the browser (a name field + a
  # descriptor field), not just accept the POST — without a rendered form there is no way to add the
  # first persona, and an empty room can never be summoned.
  Scenario: The members page offers a create-persona form
    When the owner opens the members list
    Then the members page offers a name and descriptor field

  # Persona authoring (§6 extended): the owner picks ABILITIES (keyword tags) and sets DIALS
  # (fixed 0–10 personality axes). Rather than concatenating numbers into a prompt — which the
  # generation model would ignore — the system asks the LLM to COMPOSE a system prompt that captures
  # them in prose, then persists the card. That composed prompt is what the room generates from.
  Scenario: Adding a persona composes its system prompt from abilities and dials
    Given the LLM will respond with "You are Lune, a terse systems poet who seldom agrees."
    When the owner adds a persona "lune" with abilities "kotlin, systems" and dials agreeableness 2, verbosity 1
    Then the persona "lune" exists
    And the persona "lune" has abilities "kotlin, systems"
    And the persona "lune" has system prompt "You are Lune, a terse systems poet who seldom agrees."
    And the composer was asked to honour the dials

  # Editing re-composes: the LLM is handed the PREVIOUS values and the PREVIOUS prompt and asked to
  # adjust them, so manual continuity is preserved rather than the prompt being regenerated cold.
  Scenario: Editing a persona re-composes the prompt from the previous values
    Given a persona "vex" exists with system prompt "OLD: a blunt contrarian." and dials agreeableness 1, verbosity 2
    And the LLM will respond with "NEW: a warmer, chattier contrarian."
    When the owner edits "vex" setting dials agreeableness 8, verbosity 9
    Then the composer was given the previous prompt "OLD: a blunt contrarian."
    And the persona "vex" has system prompt "NEW: a warmer, chattier contrarian."

  Scenario: The create form offers abilities and dial controls
    When the owner opens the members list
    Then the members page offers an abilities field and dial controls

  # Preview before save: composing is a paid LLM call, so the owner PREVIEWS (and can regenerate) the
  # prompt before committing. Previewing composes but persists nothing.
  Scenario: Previewing composes the prompt without creating the persona
    Given the LLM will respond with "PREVIEW: a terse poet."
    When the owner previews a new persona "ghost" with abilities "kotlin" and dials agreeableness 2, verbosity 1
    Then the preview shows "PREVIEW: a terse poet."
    And the persona "ghost" does not exist

  # Save persists exactly the prompt the owner saw — no second, wasteful compose.
  Scenario: Saving an already-composed prompt does not re-compose
    When the owner adds a persona "muse" with prompt "HAND-PICKED: a warm muse." and abilities "poetry"
    Then the persona "muse" has system prompt "HAND-PICKED: a warm muse."
    And no composition call was made

  Scenario: The create form offers a preview control and an editable prompt
    When the owner opens the members list
    Then the members page offers a preview control and a prompt field

  # Cancel is a plain navigation back to the profile — nothing is persisted until Save.
  Scenario: The edit form offers a cancel link back to the profile
    Given a persona "vex" exists with system prompt "OLD" and dials agreeableness 1, verbosity 2
    When the owner opens the edit form for "vex"
    Then the edit form offers a cancel link back to "vex"'s profile

  # Server resync backstop (#2): the prompt is left untouched but the dials changed, so the stale prompt
  # is re-composed rather than persisted out of sync (this is what protects a JS-off / bypassed submit).
  Scenario: Saving changed dials without touching the prompt re-composes
    Given a persona "vex" exists with system prompt "OLD: a blunt contrarian." and dials agreeableness 1, verbosity 2
    And the LLM will respond with "RESYNCED: a warmer contrarian."
    When the owner saves "vex" with the unchanged prompt "OLD: a blunt contrarian." and dials agreeableness 8, verbosity 9
    Then the persona "vex" has system prompt "RESYNCED: a warmer contrarian."

  # A deliberately hand-edited prompt is the owner's; persist it verbatim, no paid re-compose.
  Scenario: Saving a hand-edited prompt persists it as-is
    Given a persona "vex" exists with system prompt "OLD: a blunt contrarian." and dials agreeableness 1, verbosity 2
    When the owner saves "vex" with the edited prompt "HAND-EDITED: bespoke voice." and dials agreeableness 8, verbosity 9
    Then the persona "vex" has system prompt "HAND-EDITED: bespoke voice."
    And no composition call was made

  # S2 (plan_docs/ambient-slice-2.md §3): talkativeness — P(comment), spec §6.4 — is a fifth dial. The
  # create/edit forms and the profile page iterate Dials.KEYS generically, so appending "talkativeness"
  # to that one list is the whole production change; these two scenarios pin the seam it must satisfy.
  Scenario: The create form offers a talkativeness dial control
    When the owner opens the members list
    Then the members page offers a talkativeness dial control

  Scenario: A talkativeness value round-trips through create and the profile display
    When the owner adds a persona "gale" with abilities "sqlite" and dials agreeableness 5, verbosity 5, talkativeness 8
    Then the persona "gale" has dial "talkativeness" value 8

  # S3 (plan_docs/ambient-slice-3.md): QUALITATIVE RELATIONS. A persona holds a directed, free-text
  # stance about each other member — "needles him about hype" — which is prose the model reads, never a
  # score. (The moment a relation becomes a number it is rankable, then optimisable, and the cut reward
  # economy is back under a new column name; there is no number anywhere in this model, by design.)
  # Relations are the owner's to author, so they are shown on the profile and edited on the edit form.
  Scenario: Setting a qualitative stance toward another persona
    Given a persona "ada" exists with every dial at 5
    And a persona "bee" exists
    When the owner saves "ada" with a stance toward "bee" of "needles him about hype"
    Then the profile for "ada" shows a stance toward "bee" of "needles him about hype"

  # Clearing the field is how a relation is retired — an empty stance is not an empty opinion worth
  # rendering, it means there is no edge.
  Scenario: Clearing a stance field removes the relation
    Given a persona "ada" exists with every dial at 5
    And a persona "bee" exists
    And persona "ada" has a stance toward "bee" of "needles him about hype"
    When the owner saves "ada" with a stance toward "bee" of ""
    Then the profile for "ada" shows no stance toward "bee"

  Scenario: The edit form offers a stance field toward each other member
    Given a persona "ada" exists
    And a persona "bee" exists
    And persona "ada" has a stance toward "bee" of "needles him about hype"
    When the owner opens the edit form for "ada"
    Then the edit form offers a stance field toward "bee"
    And the stance field toward "bee" is prefilled with "needles him about hype"

  # Stances reach generation dynamically — they were never baked into the stored prompt the way dials,
  # abilities and the descriptor are — so there is nothing for a recompose to reconcile. Editing one
  # must therefore stay FREE: no paid LLM call, and no silent server-side recompose from the
  # inputsChanged backstop, which the same save would trigger if a dial had moved.
  Scenario: Editing only a stance costs nothing
    Given a persona "ada" exists with every dial at 5
    And a persona "bee" exists
    When the owner saves "ada" with a stance toward "bee" of "defers to him on databases"
    Then the profile for "ada" shows a stance toward "bee" of "defers to him on databases"
    And no composition call was made

  # When a compose DOES happen for some other reason, the composer sees the persona's stances too, so a
  # standing relation can be woven into who the persona IS rather than only recalled at reply time.
  # (Trigger here is the resync backstop above: prompt untouched, dials moved.)
  Scenario: A persona's stance is injected into its composer prompt
    Given a persona "vex" exists with system prompt "OLD: a blunt contrarian." and dials agreeableness 1, verbosity 2
    And a persona "sol" exists
    And persona "vex" has a stance toward "sol" of "needles him about hype"
    And the LLM will respond with "NEW: a warmer contrarian."
    When the owner saves "vex" with the unchanged prompt "OLD: a blunt contrarian." and dials agreeableness 8, verbosity 9
    Then the composer was handed the stance "needles him about hype"

  # Reframing the room's prompts (or seeding new stances) leaves every STORED prompt stale, so the owner
  # needs one deliberate action that refreshes them all. Deliberate is the point: it is a paid call per
  # member and it overwrites hand-edited prompts, so the members page must say so rather than hide a
  # bulk spend behind an innocuous button.
  Scenario: The members page offers a recompose-all control
    Given a persona "ada" exists
    When the owner opens the members list
    Then the members page offers a recompose-all control
    And the recompose-all control warns what it costs

  # The loop walks personas in NAME order, so "ada" is composed before "bee" and the two scripted
  # responses land in that order — naming them alphabetically is what makes the pairing unambiguous.
  Scenario: Recomposing all prompts refreshes every persona
    Given a persona "ada" exists with system prompt "OLD: ada." and dials agreeableness 1, verbosity 2
    And a persona "bee" exists with system prompt "OLD: bee." and dials agreeableness 1, verbosity 2
    And the LLM will respond with "FRESH: ada."
    And the LLM will respond with "FRESH: bee."
    When the owner recomposes every persona's prompt
    Then the persona "ada" has system prompt "FRESH: ada."
    And the persona "bee" has system prompt "FRESH: bee."

  # One bad response must not roll back the batch or block the members after it: the failed persona
  # keeps the prompt it had, and the loop carries on. Again in name order — "ada" takes the failure.
  Scenario: A failed recompose leaves that persona's prompt alone and the rest still refresh
    Given a persona "ada" exists with system prompt "OLD: ada." and dials agreeableness 1, verbosity 2
    And a persona "bee" exists with system prompt "OLD: bee." and dials agreeableness 1, verbosity 2
    And the LLM will fail with a timeout
    And the LLM will respond with "FRESH: bee."
    When the owner recomposes every persona's prompt
    Then the persona "ada" has system prompt "OLD: ada."
    And the persona "bee" has system prompt "FRESH: bee."
