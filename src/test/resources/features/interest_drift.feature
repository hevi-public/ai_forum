Feature: What the members are into drifts with what they actually wrote

  S3 gave the members opinions of each other and S4a let those drift. What each member is INTO was
  still furniture. This slice (plan_docs/ambient-slice-4b.md) lets it move: on its own weekly cadence a
  pass reads what a member actually wrote in the forum, asks whether it has moved on from one of its
  open interests toward something else, and swaps one for one.

  This is the convergence-risk slice — all the members drifting toward one voice — so four properties
  make it safe enough to run unattended, and each is pinned below.

  A member's character is its own and no pass rewrites it. Its descriptor, its expertise, its dials,
  and — per member — whatever interests the owner pinned by hand. What is fixed for one member is not
  what is fixed for another, and that is what anchors the room's diversity.

  Drift is a SWAP, never a growth: one interest set down, one taken up. No member can accumulate the
  room's interests, so convergence needs displacement, and every displacement is a line in the log with
  an undo next to it.

  It cannot smuggle in a number. An interest is prose by hard guardrail, and the one place a number
  could enter is the model's answer — so an answer carrying a digit is refused and the interests stand.
  "Kept coming back to storage engines" is an interest; "priority 2 of 5" is a score, and a score is
  the reward economy this design cut, wearing a new name.

  And nothing about the rest of the room reaches the judging model. It is shown one member's own
  character, own interests and own words. There is no cross-member signal in the loop at all.

  A note on the judge's answer shape: it is a two-line DROP/TAKE pair, so these scenarios enqueue it as
  a docstring. Gherkin does not interpret "\n" inside a quoted string, so a one-line {string} would
  enqueue a literal backslash-n and the parse would refuse an answer the model actually got right.

  Background:
    Given a persona "Sol" exists
    And a persona "Paul" exists

  # The plain path, on the branch the ambient loop actually produces: S2's comment lands top-level on
  # someone else's article thread, so the exchange is resolved through the thread's author, not a parent.
  Scenario: A member's interests move toward what it has actually been writing about
    Given persona "Sol" is into "typography"
    And persona "Sol" is into "small tools"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Paul"
    And a posted reply from "Sol" saying "The scheduler is the interesting part here, not the syntax"
    And a posted reply from "Sol" saying "Preemption cost is what will decide this"
    And a posted reply from "Sol" saying "Nobody benchmarks the wake-up path"
    And the LLM will respond with the answer:
      """
      DROP: typography
      TAKE: kernel scheduling
      """
    When the owner runs the interest drift pass
    Then the profile for "Sol" shows the interest "kernel scheduling"
    And the profile for "Sol" shows no interest "typography"

  # I3, as its own scenario rather than an aside: whatever the answer says, the member holds exactly as
  # many interests afterwards as before. A model that could add one would be growing its own footprint.
  Scenario: A drift sets one interest down and takes one up, and the count does not change
    Given persona "Sol" is into "typography"
    And persona "Sol" is into "small tools"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Paul"
    And a posted reply from "Sol" saying "The scheduler is the interesting part"
    And a posted reply from "Sol" saying "Preemption cost decides this"
    And a posted reply from "Sol" saying "Nobody benchmarks the wake-up path"
    And the LLM will respond with the answer:
      """
      DROP: typography
      TAKE: kernel scheduling
      """
    When the owner runs the interest drift pass
    Then the profile for "Sol" shows 2 interests

  # The per-member half of the immutable core, enforced at the parse so the attempt is READABLE rather
  # than silently discarded.
  Scenario: An interest the owner pinned is never set down, and the refusal is recorded
    Given the owner has pinned "boring technology choices" as an interest of "Sol"
    And persona "Sol" is into "small tools"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Paul"
    And a posted reply from "Sol" saying "The scheduler is the interesting part"
    And a posted reply from "Sol" saying "Preemption cost decides this"
    And a posted reply from "Sol" saying "Nobody benchmarks the wake-up path"
    And the LLM will respond with the answer:
      """
      DROP: boring technology choices
      TAKE: kernel scheduling
      """
    When the owner runs the interest drift pass
    Then the profile for "Sol" shows the interest "boring technology choices"
    And the profile for "Sol" shows no interest "kernel scheduling"

  # The never-clobber contract held BEFORE the judgment, so a member the owner has taken over by hand is
  # also a member this pass stops spending money on.
  Scenario: A member whose every interest is the owner's is never judged
    Given the owner has pinned "boring technology choices" as an interest of "Sol"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Paul"
    And a posted reply from "Sol" saying "The scheduler is the interesting part"
    And a posted reply from "Sol" saying "Preemption cost decides this"
    And a posted reply from "Sol" saying "Nobody benchmarks the wake-up path"
    When the owner runs the interest drift pass
    Then no LLM call was made

  # Drift is opt-in PER MEMBER: with nothing authored there is nothing to swap, so an owner who authors
  # no interests pays nothing even with the pass switched on.
  Scenario: A member the owner has given no interests is never judged
    Given a thread "Rust in the kernel" exists
    And the thread was authored by "Paul"
    And a posted reply from "Sol" saying "The scheduler is the interesting part"
    And a posted reply from "Sol" saying "Preemption cost decides this"
    And a posted reply from "Sol" saying "Nobody benchmarks the wake-up path"
    When the owner runs the interest drift pass
    Then no LLM call was made

  # A quiet forum costs nothing, and the engagement floor is why: one comment is not a change of heart.
  Scenario: A pass with nothing new to read makes no LLM call
    Given persona "Sol" is into "typography"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Paul"
    And a posted reply from "Sol" saying "Fair enough"
    When the owner runs the interest drift pass
    Then no LLM call was made

  # The cost defect S4a shipped and had to fix, pinned here from day one and at acceptance level: the
  # model is TOLD to answer NONE when nothing moved, so NONE is the steady state of a settled member and
  # writes no audit row. If the window came from the audit table, that member would buy a judgment every
  # week forever. The second pass proves the window closed: its scripted answer is a real drift that
  # never gets asked for.
  Scenario: A member looked at once is not looked at again when nothing moved
    Given persona "Sol" is into "typography"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Paul"
    And a posted reply from "Sol" saying "The scheduler is the interesting part"
    And a posted reply from "Sol" saying "Preemption cost decides this"
    And a posted reply from "Sol" saying "Nobody benchmarks the wake-up path"
    And the LLM will respond with "NONE"
    And the LLM will respond with the answer:
      """
      DROP: typography
      TAKE: kernel scheduling
      """
    When the owner runs the interest drift pass
    And the owner runs the interest drift pass
    Then the profile for "Sol" shows the interest "typography"
    And the profile for "Sol" shows no interest "kernel scheduling"

  # The no-numbers guardrail, executable. The one place a number could enter is the model's answer.
  Scenario: A judgment carrying a number is refused and the interests stand
    Given persona "Sol" is into "typography"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Paul"
    And a posted reply from "Sol" saying "The scheduler is the interesting part"
    And a posted reply from "Sol" saying "Preemption cost decides this"
    And a posted reply from "Sol" saying "Nobody benchmarks the wake-up path"
    And the LLM will respond with the answer:
      """
      DROP: typography
      TAKE: kernel scheduling, priority 2 of 5
      """
    When the owner runs the interest drift pass
    Then the profile for "Sol" shows the interest "typography"
    And the interest history is empty

  # An answer about somebody else's interests is not an answer about this member's.
  Scenario: A judgment naming an interest the member does not hold is refused
    Given persona "Sol" is into "typography"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Paul"
    And a posted reply from "Sol" saying "The scheduler is the interesting part"
    And a posted reply from "Sol" saying "Preemption cost decides this"
    And a posted reply from "Sol" saying "Nobody benchmarks the wake-up path"
    And the LLM will respond with the answer:
      """
      DROP: release engineering
      TAKE: kernel scheduling
      """
    When the owner runs the interest drift pass
    Then the profile for "Sol" shows the interest "typography"
    And the interest history is empty

  # The immutable set is NOT global (requirements §6.2), so two members with DIFFERENT fixed things are
  # in one scenario: Sol's pin holds while Paul's open interest moves, and neither character, expertise,
  # dial nor stored prompt moves for either of them.
  Scenario: Each member's character is its own, and no pass rewrites it
    Given the owner has pinned "boring technology choices" as an interest of "Sol"
    And persona "Paul" is into "typography"
    And the persona "Sol" was authored with abilities "databases, storage"
    And the persona "Paul" was authored with the system prompt "You are Paul, who reads release notes for fun."
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Sol"
    And a posted reply from "Paul" saying "The scheduler is the interesting part"
    And a posted reply from "Paul" saying "Preemption cost decides this"
    And a posted reply from "Paul" saying "Nobody benchmarks the wake-up path"
    And the LLM will respond with the answer:
      """
      DROP: typography
      TAKE: kernel scheduling
      """
    When the owner runs the interest drift pass
    Then the profile for "Paul" shows the interest "kernel scheduling"
    And the profile for "Sol" shows the interest "boring technology choices"
    And the persona "Sol" has abilities "databases, storage"
    And the persona "Paul" has system prompt "You are Paul, who reads release notes for fun."
    And the persona "Sol" still has the descriptor "Sol"

  # Audit-only auto-apply means the log IS the control, so it has to carry enough to judge the judgment:
  # what went, what arrived, and the words it was read from, linked.
  Scenario: The drift is audited with what was set down, what was taken up, and the words it was judged from
    Given persona "Sol" is into "typography"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Paul"
    And a posted reply from "Sol" saying "The scheduler is the interesting part"
    And a posted reply from "Sol" saying "Preemption cost decides this"
    And a posted reply from "Sol" saying "Nobody benchmarks the wake-up path"
    And the LLM will respond with the answer:
      """
      DROP: typography
      TAKE: kernel scheduling
      """
    When the owner runs the interest drift pass
    And the owner navigates to "/admin/interests"
    Then the interest history records "Sol" setting down "typography" and taking up "kernel scheduling"
    And the interest history entry cites "Nobody benchmarks the wake-up path"
    And the interest history entry links to the cited comment

  Scenario: The interest history is empty before anything has drifted
    When the owner navigates to "/admin/interests"
    Then the interest history is empty

  Scenario: The owner reverts a drift they disagree with
    Given persona "Sol" is into "typography"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Paul"
    And a posted reply from "Sol" saying "The scheduler is the interesting part"
    And a posted reply from "Sol" saying "Preemption cost decides this"
    And a posted reply from "Sol" saying "Nobody benchmarks the wake-up path"
    And the LLM will respond with the answer:
      """
      DROP: typography
      TAKE: kernel scheduling
      """
    When the owner runs the interest drift pass
    And the owner reverts the latest interest change
    Then the profile for "Sol" shows the interest "typography"
    And the profile for "Sol" shows no interest "kernel scheduling"
    And the interest history entry is marked reverted

  # Revert undoes; it does not freeze. Freezing is what pinning is for — so the reverted phrase is back
  # with its original provenance and its window is reopened, and a second pass can move it again.
  Scenario: A reverted interest is free to drift again
    Given persona "Sol" is into "typography"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Paul"
    And a posted reply from "Sol" saying "The scheduler is the interesting part"
    And a posted reply from "Sol" saying "Preemption cost decides this"
    And a posted reply from "Sol" saying "Nobody benchmarks the wake-up path"
    And the LLM will respond with the answer:
      """
      DROP: typography
      TAKE: kernel scheduling
      """
    And the LLM will respond with the answer:
      """
      DROP: typography
      TAKE: release engineering
      """
    When the owner runs the interest drift pass
    And the owner reverts the latest interest change
    And the owner runs the interest drift pass
    Then the profile for "Sol" shows the interest "release engineering"

  # A rate limit at half past four on a Sunday is a recorded outcome, not a crash — and it leaves the
  # window OPEN, because nothing was judged.
  Scenario: A failed judgment leaves the interests standing and the pass completes
    Given persona "Sol" is into "typography"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Paul"
    And a posted reply from "Sol" saying "The scheduler is the interesting part"
    And a posted reply from "Sol" saying "Preemption cost decides this"
    And a posted reply from "Sol" saying "Nobody benchmarks the wake-up path"
    And the LLM will fail with a rate-limit
    When the owner runs the interest drift pass
    Then the profile for "Sol" shows the interest "typography"
    And the interest history is empty

  # D7, executable: the drifted phrase reaches the GENERATING model on the next reply, with no compose
  # bought anywhere. Asserted on Sol's own call by name — an InterestJudge call is not the dispatcher, so
  # "the last non-dispatcher call" would match the judge, whose own prompt contains the phrase.
  Scenario: A drifted interest reaches the generating model without a recompose
    Given persona "Sol" is into "typography"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Paul"
    And a posted reply from "Sol" saying "The scheduler is the interesting part"
    And a posted reply from "Sol" saying "Preemption cost decides this"
    And a posted reply from "Sol" saying "Nobody benchmarks the wake-up path"
    And the LLM will respond with the answer:
      """
      DROP: typography
      TAKE: kernel scheduling
      """
    And the LLM will respond with "Preemption is the whole story."
    When the owner runs the interest drift pass
    And the owner summons "Sol"
    Then "Sol"'s system prompt carried the interest "kernel scheduling"
    And no composition call was made

  # The convergence guardrail as behaviour: two members share a phrase, and the judging model is still
  # shown nothing but the member in front of it. There is no cross-member channel to optimise through.
  Scenario: Nothing about the rest of the room reaches the judging model
    Given persona "Sol" is into "typography"
    And persona "Paul" is into "release engineering"
    And a thread "Rust in the kernel" exists
    And the thread was authored by "Paul"
    And a posted reply from "Sol" saying "The scheduler is the interesting part"
    And a posted reply from "Sol" saying "Preemption cost decides this"
    And a posted reply from "Sol" saying "Nobody benchmarks the wake-up path"
    And the LLM will respond with "NONE"
    When the owner runs the interest drift pass
    Then the judging model was shown only "Sol"'s own interests

  # The readout: a phrase and the members holding it, by NAME. Never a count, never a score on a member.
  Scenario: The room map names an interest more than one member holds
    Given persona "Sol" is into "release engineering"
    And persona "Paul" is into "release engineering"
    When the owner navigates to "/admin/interests"
    Then the room map shows "release engineering" held by "Paul, Sol"

  # The diversity counterweight, and why it needs no sampler: a hand-added member arrives holding none of
  # the room's interests and drift-inert, which is a fixed point away from the room's centre without
  # anyone computing the centre.
  Scenario: A newcomer arrives holding none of the room's interests
    Given persona "Sol" is into "release engineering"
    And the LLM will respond with "You are Mira, who asks about the person using the thing."
    When the owner adds a persona "Mira" described as "asks who this is actually for"
    And the owner runs the interest drift pass
    Then the profile for "Mira" shows 0 interests

  # The owner's own write path, which nothing else in this feature exercises: every other Given here
  # writes SQL so the zero-cost scenarios stay honest, and that left the whole edit-form mechanism —
  # pinning, retraction, the ceiling, the guards — reachable by no test at any tier.
  #
  # Three properties in one save, because they are one interaction: a NEW phrase is authored as the
  # owner's (which is how pinning happens at all), a phrase left out of the submission is retracted, and
  # a phrase resubmitted unchanged keeps the provenance it had — without that last rule, opening the
  # form and pressing Save would freeze every phrase the member holds, which is not a decision the owner
  # made.
  Scenario: The owner authors, pins and retracts interests on the edit form
    Given persona "Sol" is into "typography"
    And persona "Sol" is into "small tools"
    When the owner saves "Sol"'s interests as "typography | kernel scheduling"
    Then the profile for "Sol" shows the interest "kernel scheduling" as the owner's
    And the profile for "Sol" shows the interest "typography"
    And the profile for "Sol" shows no interest "small tools"
    And the profile for "Sol" shows 2 interests

  # One unusable field must not take the phrase it was editing with it. The fieldset is RECONCILED, so
  # dropping just the bad value would delete whatever it replaced — the owner retypes a prefilled phrase,
  # overshoots the length, and the interest disappears with no message and no undo. Leaving the set
  # exactly as it was costs a resubmit instead of a phrase.
  Scenario: A save carrying an unusable phrase changes nothing at all
    Given persona "Sol" is into "typography"
    And persona "Sol" is into "small tools"
    When the owner saves "Sol"'s interests as "x | small tools"
    Then the profile for "Sol" shows the interest "typography"
    And the profile for "Sol" shows the interest "small tools"
    And the profile for "Sol" shows 2 interests
