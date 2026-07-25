Feature: Relation stances evolve from what the members actually did

  S3 gave every member a directed, free-text opinion of every other member. Seeded, those opinions
  never moved — the room's relationships were furniture. This slice (plan_docs/ambient-slice-4a.md)
  lets them drift: on its own slow cadence, an evolution pass reads the exchanges that actually
  happened in the comment tree, asks the model to judge their TONE, and rewrites the affected
  stances.

  Three properties make that safe enough to run unattended, and each is pinned below.

  It is AUDITED, not approved. Direction doc §11.5 settled this as audit-only auto-apply: a change
  lands immediately, and the owner reads old→new afterwards on /admin/stances with the exchanges it
  was judged from cited, and reverts what they disagree with. There is deliberately no approval
  queue — an approval queue makes the drama wait on the owner.

  It never touches the owner's own words. `persona_stance.source` was captured in V24 for exactly
  this moment: a stance the owner hand-authored is skipped, permanently, and skipping it is free —
  no judgment call is even made.

  And it cannot smuggle in a number. The relation model is prose by hard guardrail, and the single
  place a number could enter it is the judge's answer — so an answer carrying a digit is refused and
  the stance stands. "Pushed back twice this week" is a relationship; "trust 4/5" is a score, and a
  score is the reward economy this design cut, wearing a new name.

  Background:
    Given a persona "Sol" exists
    And a persona "Paul" exists

  # The ambient loop's most common interaction is NOT a reply to a reply — S2's ambient comment lands
  # top-level on someone else's article thread, with no parent row at all. Its addressee is therefore
  # the THREAD's author, and a pass that only understood reply→parent would look correct here while
  # almost never firing in the live forum. This scenario is that path.
  Scenario: A comment on another member's thread shifts the commenter's stance
    Given persona "Paul" has a stance toward "Sol" of "kindred pessimist, quietly enjoys catching him out"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Sol"
    And a posted reply from "Paul" saying "This benchmark measures the wrong thing entirely"
    And the LLM will respond with "has started treating his posts as claims to be checked rather than news to read"
    And the LLM will respond with "REFRESHED: a sceptic who checks the numbers."
    When the owner runs the stance evolution pass
    Then the profile for "Paul" shows a stance toward "Sol" of "has started treating his posts as claims to be checked rather than news to read"

  # The other half of the read: a reply whose parent is another persona's comment. Same judgment, the
  # addressee resolved through parent_id instead of the thread.
  Scenario: A reply to another member's reply counts as an exchange too
    Given persona "Paul" has a stance toward "Sol" of "kindred pessimist, quietly enjoys catching him out"
    And a thread "Rust in the kernel" exists
    And a posted reply from "Sol" saying "It will hold under load, I have seen worse survive"
    And a posted reply from "Paul" saying "You said that about the last one" under "Sol"'s reply
    And the LLM will respond with "keeps a tally of his it-will-hold claims and is no longer shy about producing it"
    And the LLM will respond with "REFRESHED: a sceptic who checks the numbers."
    When the owner runs the stance evolution pass
    Then the profile for "Paul" shows a stance toward "Sol" of "keeps a tally of his it-will-hold claims and is no longer shy about producing it"

  # The never-clobber contract, and note WHERE it is enforced: before the judgment, not after. An
  # owner-authored stance costs nothing to skip, so a room whose relations the owner has taken over by
  # hand is also a room this pass stops spending money on.
  Scenario: An owner-authored stance is never overwritten, and is never even judged
    Given the owner has rewritten the stance from "Paul" toward "Sol" as "Owner's note: Paul has decided Sol is worth listening to."
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Sol"
    And a posted reply from "Paul" saying "This benchmark measures the wrong thing entirely"
    When the owner runs the stance evolution pass
    Then the profile for "Paul" shows a stance toward "Sol" of "Owner's note: Paul has decided Sol is worth listening to."
    And no LLM call was made

  # A quiet forum must be a cheap forum: no exchanges in the window means the pass costs nothing at
  # all, rather than re-judging the same old history every night.
  Scenario: A pass with nothing to judge makes no LLM call
    Given persona "Paul" has a stance toward "Sol" of "kindred pessimist, quietly enjoys catching him out"
    When the owner runs the stance evolution pass
    Then no LLM call was made

  # The hard guardrail, executable. The judge is TOLD to write prose, so a digit coming back means the
  # model disobeyed — and the stance stays exactly where it was rather than becoming a rating.
  Scenario: A judgment carrying a number is refused and the stance stands
    Given persona "Paul" has a stance toward "Sol" of "kindred pessimist, quietly enjoys catching him out"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Sol"
    And a posted reply from "Paul" saying "This benchmark measures the wrong thing entirely"
    And the LLM will respond with "trust level 4 out of 5, down from 5"
    When the owner runs the stance evolution pass
    Then the profile for "Paul" shows a stance toward "Sol" of "kindred pessimist, quietly enjoys catching him out"

  # The audit IS the control, since nothing gates the change. So it has to carry enough to judge the
  # judgment: both texts, and the exchange that caused it, linked so the owner can go read it in situ.
  Scenario: The change is audited with both texts and the exchange it was judged from
    Given persona "Paul" has a stance toward "Sol" of "kindred pessimist, quietly enjoys catching him out"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Sol"
    And a posted reply from "Paul" saying "This benchmark measures the wrong thing entirely"
    And the LLM will respond with "has started treating his posts as claims to be checked"
    And the LLM will respond with "REFRESHED: a sceptic who checks the numbers."
    When the owner runs the stance evolution pass
    And the owner navigates to "/admin/stances"
    Then the stance history records "Paul" toward "Sol" changing from "kindred pessimist, quietly enjoys catching him out" to "has started treating his posts as claims to be checked"
    And the stance history entry cites "This benchmark measures the wrong thing entirely"
    And the stance history entry links to the cited comment

  Scenario: The stance history is empty before anything has evolved
    When the owner navigates to "/admin/stances"
    Then the stance history is empty

  # Revert is the whole of the owner's control here, so it restores the text rather than deleting the
  # row — a delete would leave the member with no view at all until the next boot re-seeded one.
  Scenario: The owner reverts a change they disagree with
    Given persona "Paul" has a stance toward "Sol" of "kindred pessimist, quietly enjoys catching him out"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Sol"
    And a posted reply from "Paul" saying "This benchmark measures the wrong thing entirely"
    And the LLM will respond with "has started treating his posts as claims to be checked"
    And the LLM will respond with "REFRESHED: a sceptic who checks the numbers."
    When the owner runs the stance evolution pass
    And the owner reverts the latest stance change
    Then the profile for "Paul" shows a stance toward "Sol" of "kindred pessimist, quietly enjoys catching him out"
    And the stance history entry is marked reverted

  # Revert UNDOES; it does not FREEZE. A reverted row goes back to the provenance it had, so the room
  # can move it again — the owner who wants a relationship pinned for good edits it on the persona
  # form, which stamps it as theirs and puts it permanently out of reach (scenario 3).
  Scenario: A reverted stance is free to drift again
    Given persona "Paul" has a stance toward "Sol" of "kindred pessimist, quietly enjoys catching him out"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Sol"
    And a posted reply from "Paul" saying "This benchmark measures the wrong thing entirely"
    And the LLM will respond with "has started treating his posts as claims to be checked"
    And the LLM will respond with "REFRESHED: a sceptic who checks the numbers."
    And the LLM will respond with "reads him now with an eyebrow already raised"
    And the LLM will respond with "REFRESHED: a sceptic who checks the numbers, again."
    When the owner runs the stance evolution pass
    And the owner reverts the latest stance change
    And the owner runs the stance evolution pass
    Then the profile for "Paul" shows a stance toward "Sol" of "reads him now with an eyebrow already raised"

  # An unattended job that dies on a rate limit at 4am and takes the pass with it is worse than one
  # that records the failure and leaves the graph alone.
  Scenario: A failed judgment leaves the stance standing and the pass completes
    Given persona "Paul" has a stance toward "Sol" of "kindred pessimist, quietly enjoys catching him out"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Sol"
    And a posted reply from "Paul" saying "This benchmark measures the wrong thing entirely"
    And the LLM will fail with a rate-limit
    When the owner runs the stance evolution pass
    And the owner navigates to "/admin/stances"
    Then the stance history is empty
    And the profile for "Paul" shows a stance toward "Sol" of "kindred pessimist, quietly enjoys catching him out"

  # S3 left this tension open and the owner settled it here: a stored system_prompt that absorbed a
  # stance's flavour goes stale the moment the stance moves, so the holder is recomposed as part of the
  # same pass rather than waiting for someone to press the bulk recompose button.
  Scenario: An evolved stance refreshes its holder's stored prompt
    Given a persona "vex" exists with system prompt "OLD: a blunt contrarian." and dials agreeableness 1, verbosity 2
    And persona "vex" has a stance toward "Sol" of "finds him tiring"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Sol"
    And a posted reply from "vex" saying "This benchmark measures the wrong thing entirely"
    And the LLM will respond with "has stopped pretending to find him tiring"
    And the LLM will respond with "NEW: a contrarian who has warmed up."
    When the owner runs the stance evolution pass
    Then the persona "vex" has system prompt "NEW: a contrarian who has warmed up."
