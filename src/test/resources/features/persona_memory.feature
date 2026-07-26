Feature: Each member privately remembers what it lived through, and the memory resurfaces on its own words

  §6.3's floor, made executable (plan_docs/persona-memory.md): every member accumulates a private
  tree of prose memory records — written by a weekly consolidation pass over its own forum
  experience, or authored by the owner — plus an optional owner-only root. A record resurfaces into
  the member's OWN generation prompt when the conversation in front of it shares the record's words,
  and a surfaced record drags its linked antecedent with it. Memory changes WHAT a member says,
  never how often it speaks: no memory ever reaches another member, the dispatcher, the ambient
  gate, or a rail.

  Two spy-selection rules keep these scenarios honest (plan doc §4): a step asserting on a member's
  GENERATION prompt selects the spy call by the member's name — never "the last call", which a
  MemoryScribe judgment (whose own instruction contains the memory text) would satisfy vacuously —
  and the scribe steps select by the scribe's synthetic name. Scribe answers whose shape is
  multi-line are docstrings: Gherkin does not interpret "\n" inside a quoted {string}.

  Seeding Givens write SQL directly (TestData), never a form POST — the persona endpoints compose
  prompts, and cost assertions must read a spy that has seen nothing it shouldn't. Authoring steps
  and asserting steps carry distinct wording ("was given the memory …" seeds; "shows the memory …"
  reads), because Cucumber matches on step TEXT, not on the Given/When/Then keyword.

  RED-first exemptions, by name (plan doc §6): scenarios 1, 3, 6, 7 and 8 below, plus the two
  no-leak halves of the root scenario (20), assert what is NOT in a prompt — they are green against
  an empty implementation and pass vacuously between build steps 3 and 7. Each takes its meaning
  from a positive twin that must go RED and then green beside it: 3, 6, 7 and 8 are scenario 2's
  fixture with exactly one variable flipped (the word, the member, the prompt read, the branch), and
  scenario 4 is the positive twin the hop guards lean on. One honesty note carried from the plan
  doc: scenario 1's "byte-identical" is its Tier-2 name — at HTTP level the assertion decays to
  frame-text absence (the captured prompt carries no memory block); true byte-parity is pinned by
  the Tier-2 unwired-repository test.

  Background:
    Given a persona "sol" exists
    And a persona "paul" exists

  # 1. The floor is never risked: a member with nothing to remember generates exactly as it does
  # today. Exempt from RED-first by name (see the preamble) — this is the empty-parity guard whose
  # meaning comes from scenario 2 going red and then green beside it.
  Scenario: A member with no memories generates with an unchanged prompt
    Given a thread "Scaling SQLite" exists
    And a posted reply from "paul" saying "Indexes help here"
    And the LLM will respond with "Indexes are cheap"
    When the owner summons "sol"
    Then "sol"'s generation prompt carried no memory block

  # 2. The resurfacing mechanism itself: the record's OWN words are the retrieval key (plan doc
  # §2.7), so a memory sharing a word with the conversation is injected into that member's prompt.
  # Asserted on sol's call selected by NAME — a scribe call would satisfy "last call" vacuously.
  Scenario: A memory whose words the thread shares is injected into that member's generation prompt
    Given a thread "Scaling SQLite" exists
    And a posted reply from "paul" saying "The checkpoint is what stalls everyone here"
    And persona "sol" was given the memory "Watched checkpoint tuning eat a whole weekend once"
    And the LLM will respond with "Stalls are fixable"
    When the owner summons "sol"
    Then "sol"'s generation prompt carried the memory "Watched checkpoint tuning eat a whole weekend once"

  # 3. Scenario 2 with the WORD flipped: "resurface when relevant" means silent when irrelevant —
  # zero, not fewer. Exempt from RED-first by name (absence guard).
  Scenario: A memory sharing no words with the scoped context stays out of the prompt
    Given a thread "Scaling SQLite" exists
    And a posted reply from "paul" saying "The checkpoint is what stalls everyone here"
    And persona "sol" was given the memory "Prefers boring rollout habits over clever ones"
    And the LLM will respond with "Indexes are cheap"
    When the owner summons "sol"
    Then "sol"'s generation prompt did not carry the memory "Prefers boring rollout habits over clever ones"

  # 4. The associative hop — §6.3's threading payoff, executable: the antecedent's own words match
  # nothing on screen, and it arrives anyway because its child surfaced. This is the positive twin
  # the hop guards (the root never rides the hop, scenario 20) lean on.
  Scenario: A surfaced memory brings its linked antecedent into the prompt
    Given a thread "Scaling SQLite" exists
    And a posted reply from "paul" saying "My checkpoint config keeps misbehaving"
    And persona "sol" was given the memory "Fell down a fsync rabbit hole two winters back"
    And persona "sol" was given the memory "Checkpoint defaults still feel untrustworthy" extending "Fell down a fsync rabbit hole two winters back"
    And the LLM will respond with "Defaults deserve suspicion"
    When the owner summons "sol"
    Then "sol"'s generation prompt carried the memory "Checkpoint defaults still feel untrustworthy"
    And "sol"'s generation prompt carried the memory "Fell down a fsync rabbit hole two winters back"

  # 5. The prompt-bloat cap (plan doc §2.7 step 3): more than three matches inject only the newest
  # three — a clock ordering computed backend-side, never a persisted magnitude. Seeding order is
  # age order here: the first memory seeded is the oldest.
  Scenario: When more than three memories match only the newest three are injected
    Given a thread "Scaling SQLite" exists
    And a posted reply from "paul" saying "The checkpoint is what stalls everyone here"
    And persona "sol" was given the memory "First met checkpoint stalls on a tiny box"
    And persona "sol" was given the memory "Checkpoint tuning once ate a whole weekend"
    And persona "sol" was given the memory "Still suspicious of checkpoint defaults"
    And persona "sol" was given the memory "Now reads checkpoint docs before anything else"
    And the LLM will respond with "Stalls are fixable"
    When the owner summons "sol"
    Then "sol"'s generation prompt carried the memory "Now reads checkpoint docs before anything else"
    And "sol"'s generation prompt carried the memory "Still suspicious of checkpoint defaults"
    And "sol"'s generation prompt carried the memory "Checkpoint tuning once ate a whole weekend"
    And "sol"'s generation prompt did not carry the memory "First met checkpoint stalls on a tiny box"

  # 6. Scenario 2 with the MEMBER flipped — the I1 cross-persona firewall's negative half. The
  # memory matches the thread perfectly and belongs to somebody else, so it stays out. Exempt from
  # RED-first by name (absence guard).
  Scenario: Another persona's memory never appears in a member's generation prompt
    Given a thread "Scaling SQLite" exists
    And a posted reply from "paul" saying "The checkpoint is what stalls everyone here"
    And persona "paul" was given the memory "Watched checkpoint tuning eat a whole weekend once"
    And the LLM will respond with "Stalls are fixable"
    When the owner summons "sol"
    Then "sol"'s generation prompt did not carry the memory "Watched checkpoint tuning eat a whole weekend once"

  # 7. Scenario 2 with the PROMPT READ flipped: the dispatcher routes, it never remembers — no
  # memory-shaped value reaches routing (plan doc I1/I2). Exempt from RED-first by name (absence
  # guard).
  Scenario: The dispatcher prompt contains no memory text
    Given a thread "Scaling SQLite" exists
    And a posted reply from "paul" saying "The checkpoint is what stalls everyone here"
    And persona "sol" was given the memory "Watched checkpoint tuning eat a whole weekend once"
    And the LLM will respond with "sol"
    And the LLM will respond with "A measured reply"
    When the owner asks the room "who has fought this before?"
    Then the dispatcher's prompt did not carry the memory "Watched checkpoint tuning eat a whole weekend once"

  # 8. Scenario 2 with the BRANCH flipped — BRANCH_ONLY composes for free (plan doc §2.7): a
  # narrower scoped context is a narrower match text, so a memory matching only an out-of-branch
  # sibling stays dormant. Exempt from RED-first by name (absence guard).
  Scenario: Under branch-only scoping a memory matching only an out-of-branch comment stays out
    Given a thread "Scaling SQLite" exists
    And a posted reply from "paul" saying "The scheduler part is what matters to me"
    And a posted reply from "sol" saying "Checkpoint stalls are the real story here"
    And persona "sol" was given the memory "Checkpoint tuning once ate my whole weekend"
    And the LLM will respond with "Staying on this branch"
    When the owner replies under "paul's reply" with branch-only scope
    Then "sol"'s generation prompt did not carry the memory "Checkpoint tuning once ate my whole weekend"

  # 9. D8 behaviorally: digits in prose are legitimate autobiography — the Stays-Cut line is a
  # number that is model-written AND machine-read as a magnitude, and no such reader exists. The
  # body is stored and injected verbatim.
  Scenario: A digit-bearing memory body is stored and injected verbatim
    Given a thread "Scaling SQLite" exists
    And a posted reply from "paul" saying "People argued about this for weeks"
    And persona "sol" was given the memory "We argued about WAL mode in V27"
    And the LLM will respond with "Old arguments echo"
    When the owner summons "sol"
    Then the profile for "sol" shows the memory "We argued about WAL mode in V27" with source "owner"
    And "sol"'s generation prompt carried the memory "We argued about WAL mode in V27"

  # The scribe fixture used from here on: three exchanges by the judged member, nested under an
  # owner comment so that EXACTLY ONE roster member is judgeable — evidence is attributed per member
  # (plan doc §2.4, either direction), and a persona-authored thread would make its author a second
  # judged member whose call would consume the scripted answers meant for the first.

  # 10. The write path end to end: one manual run, one record, and an audit row carrying the cited
  # evidence — the owner's whole control over an auto-applied change is this log.
  Scenario: A manual scribe run writes a memory with cited evidence on the audit log
    Given a thread "Rust in the kernel" exists
    And a posted reply from "owner" saying "Where does the time actually go?"
    And a posted reply from "sol" saying "The scheduler is the interesting part" under "owner"'s reply
    And a posted reply from "sol" saying "Preemption cost decides this" under "owner"'s reply
    And a posted reply from "sol" saying "Nobody benchmarks the wake-up path" under "owner"'s reply
    And the LLM will respond with the answer:
      """
      REMEMBER: Learned that preemption arguments never really end
      """
    When the owner runs the memory pass
    Then the profile for "sol" shows the memory "Learned that preemption arguments never really end" with source "scribe"
    When the owner navigates to "/admin/memory"
    Then the memory history records "sol" remembering "Learned that preemption arguments never really end"
    And the memory history entry cites "Nobody benchmarks the wake-up path"
    And the memory history entry links to the cited comment

  # 11. The V26 cost lesson, executable on day one: NOTHING is the designed steady state, writes no
  # record and no audit row, and STILL closes the window — a second immediate run buys nothing, so
  # the decoy answer is never consumed.
  Scenario: A run answering NOTHING writes no record and the window closes on it
    Given a thread "Rust in the kernel" exists
    And a posted reply from "owner" saying "Where does the time actually go?"
    And a posted reply from "sol" saying "The scheduler is the interesting part" under "owner"'s reply
    And a posted reply from "sol" saying "Preemption cost decides this" under "owner"'s reply
    And a posted reply from "sol" saying "Nobody benchmarks the wake-up path" under "owner"'s reply
    And the LLM will respond with "NOTHING"
    And the LLM will respond with "REMEMBER: A decoy the closed window must never buy"
    When the owner runs the memory pass
    And the owner runs the memory pass
    Then the profile for "sol" shows exactly 0 memories
    And the memory history is empty

  # 12. The parse guardrail binds on rating SHAPES, not on digits (D8): a rating-shaped line is
  # refused, nothing is written, and the window stays OPEN — the next run re-judges the member and
  # the well-formed second answer lands.
  Scenario: A rating-shaped answer is rejected and the member is re-judged next run
    Given a thread "Rust in the kernel" exists
    And a posted reply from "owner" saying "Where does the time actually go?"
    And a posted reply from "sol" saying "The scheduler is the interesting part" under "owner"'s reply
    And a posted reply from "sol" saying "Preemption cost decides this" under "owner"'s reply
    And a posted reply from "sol" saying "Nobody benchmarks the wake-up path" under "owner"'s reply
    And the LLM will respond with the answer:
      """
      REMEMBER: Keeps a mental list of storage tricks
      importance: high, 8/10
      """
    And the LLM will respond with "REMEMBER: Learned that benchmarks mislead without real traffic"
    When the owner runs the memory pass
    And the owner runs the memory pass
    Then the profile for "sol" shows no memory "Keeps a mental list of storage tricks"
    And the profile for "sol" shows the memory "Learned that benchmarks mislead without real traffic" with source "scribe"

  # 13. A rate limit at five on a Sunday is a recorded outcome, not a crash — and it leaves the
  # window OPEN, because nothing was judged: the second run gets its answer through.
  Scenario: An LLM seam failure leaves the window open and the pass completes
    Given a thread "Rust in the kernel" exists
    And a posted reply from "owner" saying "Where does the time actually go?"
    And a posted reply from "sol" saying "The scheduler is the interesting part" under "owner"'s reply
    And a posted reply from "sol" saying "Preemption cost decides this" under "owner"'s reply
    And a posted reply from "sol" saying "Nobody benchmarks the wake-up path" under "owner"'s reply
    And the LLM will fail with a rate-limit
    And the LLM will respond with "REMEMBER: Second chances only exist while the window stays open"
    When the owner runs the memory pass
    # The failing run must COMPLETE, not crash — §6 item 13 pins both halves. Without this line a
    # pass that 500s on the seam failure still goes green here as long as run 2 succeeds.
    Then the response status is 200
    When the owner runs the memory pass
    Then the profile for "sol" shows the memory "Second chances only exist while the window stays open" with source "scribe"

  # 14. D5's duplicate posture, both halves: the row is refused (the owner would otherwise weed
  # noise), the owner's row keeps its provenance, and the window STAMPS — the model did its job, so
  # re-buying the identical judgment weekly (the V26 shape) is impossible and the decoy stays
  # unconsumed.
  Scenario: A scribe answer duplicating an owner-authored memory is refused and still closes the window
    Given persona "sol" was given the memory "Trusts boring rollouts more than clever ones"
    And a thread "Rust in the kernel" exists
    And a posted reply from "owner" saying "Where does the time actually go?"
    And a posted reply from "sol" saying "The scheduler is the interesting part" under "owner"'s reply
    And a posted reply from "sol" saying "Preemption cost decides this" under "owner"'s reply
    And a posted reply from "sol" saying "Nobody benchmarks the wake-up path" under "owner"'s reply
    And the LLM will respond with "REMEMBER: Trusts boring rollouts more than clever ones"
    And the LLM will respond with "REMEMBER: Collects odd benchmark results"
    When the owner runs the memory pass
    And the owner runs the memory pass
    Then the profile for "sol" shows the memory "Trusts boring rollouts more than clever ones" with source "owner"
    And the profile for "sol" shows exactly 1 memory

  # 15. The letter protocol (D4): existing records are offered as letters, NEWEST FIRST — so with
  # two records, A is the migration note (seeded second, newer) and B is the rabbit hole (seeded
  # first, older). EXTENDS: B attaches the new record beneath the older memory.
  Scenario: An answer extending letter B attaches the record beneath that memory
    Given persona "sol" was given the memory "Fell down the write-ahead log rabbit hole once"
    And persona "sol" was given the memory "Keeps notes on every failed migration"
    And a thread "Rust in the kernel" exists
    And a posted reply from "owner" saying "Where does the time actually go?"
    And a posted reply from "sol" saying "The scheduler is the interesting part" under "owner"'s reply
    And a posted reply from "sol" saying "Preemption cost decides this" under "owner"'s reply
    And a posted reply from "sol" saying "Nobody benchmarks the wake-up path" under "owner"'s reply
    And the LLM will respond with the answer:
      """
      REMEMBER: Suspects the log format hides more surprises
      EXTENDS: B
      """
    When the owner runs the memory pass
    Then the profile for "sol" shows the memory "Suspects the log format hides more surprises" beneath "Fell down the write-ahead log rabbit hole once"

  # 16. A broken decoration never costs a paid, well-formed record (D4): a letter outside the
  # offered set degrades to top-level attachment, and the memory is still recorded.
  Scenario: An answer naming a letter outside the offered set attaches top-level and is still recorded
    Given persona "sol" was given the memory "Fell down the write-ahead log rabbit hole once"
    And a thread "Rust in the kernel" exists
    And a posted reply from "owner" saying "Where does the time actually go?"
    And a posted reply from "sol" saying "The scheduler is the interesting part" under "owner"'s reply
    And a posted reply from "sol" saying "Preemption cost decides this" under "owner"'s reply
    And a posted reply from "sol" saying "Nobody benchmarks the wake-up path" under "owner"'s reply
    And the LLM will respond with the answer:
      """
      REMEMBER: Suspects the letter protocol has sharp edges
      EXTENDS: Q
      """
    When the owner runs the memory pass
    Then the profile for "sol" shows the memory "Suspects the letter protocol has sharp edges" with source "scribe"
    And the profile for "sol" shows the memory "Suspects the letter protocol has sharp edges" at top level

  # 17. The blinkers, which are the convergence guardrail: the scribe judging one member sees that
  # member's own memories and NONE of anybody else's — there is no cross-member channel for the
  # room to converge through. Selected off the spy by the scribe's synthetic name.
  Scenario: The scribe prompt for one member carries its memories and none of another member's
    Given persona "sol" was given the memory "Keeps a running list of storage tricks"
    And persona "paul" was given the memory "Reads release notes end to end for fun"
    And a thread "Rust in the kernel" exists
    And a posted reply from "owner" saying "What is actually new here?"
    And a posted reply from "sol" saying "The scheduler is the interesting part" under "owner"'s reply
    And a posted reply from "sol" saying "Preemption cost decides this" under "owner"'s reply
    And a posted reply from "sol" saying "Nobody benchmarks the wake-up path" under "owner"'s reply
    And the LLM will respond with "NOTHING"
    When the owner runs the memory pass
    Then the scribe prompt for "sol" carried "Keeps a running list of storage tricks"
    And the scribe prompt for "sol" did not carry "Reads release notes end to end for fun"

  # 18. Revert deletes, and does NOT roll the window back (D10, an argued departure from S4a/S4b):
  # rollback would guarantee the next run re-reads the same evidence and re-manufactures the row
  # the owner just killed. The decoy sits AHEAD of the summon's reply in the script, so a window
  # wrongly reopened would consume it as a judgment and write a memory this scenario would see; a
  # window that held leaves it to the summon, where it is merely a reply body.
  Scenario: A reverted memory leaves the next prompt and the window does not reopen
    Given a thread "Rust in the kernel" exists
    And a posted reply from "owner" saying "Where does the time actually go?"
    And a posted reply from "sol" saying "The scheduler is the interesting part" under "owner"'s reply
    And a posted reply from "sol" saying "Preemption cost decides this" under "owner"'s reply
    And a posted reply from "sol" saying "Nobody benchmarks the wake-up path" under "owner"'s reply
    And the LLM will respond with "REMEMBER: Preemption arguments never really end"
    And the LLM will respond with "REMEMBER: A decoy the settled window must never buy"
    And the LLM will respond with "Preemption is the whole story"
    When the owner runs the memory pass
    And the owner reverts the latest memory change
    And the owner runs the memory pass
    And the owner summons "sol"
    Then "sol"'s generation prompt did not carry the memory "Preemption arguments never really end"
    And the memory history entry is marked reverted
    And the profile for "sol" shows exactly 0 memories

  # 19. The judgment-site re-read at the revert door (D10): reverting a record the owner already
  # deleted is SKIPPED — logged reason "superseded" — the audit row survives holding its snapshot,
  # and its reverted marker is unchanged.
  Scenario: Reverting a record the owner already deleted is skipped and the audit row survives
    Given a thread "Rust in the kernel" exists
    And a posted reply from "owner" saying "Where does the time actually go?"
    And a posted reply from "sol" saying "The scheduler is the interesting part" under "owner"'s reply
    And a posted reply from "sol" saying "Preemption cost decides this" under "owner"'s reply
    And a posted reply from "sol" saying "Nobody benchmarks the wake-up path" under "owner"'s reply
    And the LLM will respond with "REMEMBER: Preemption arguments never really end"
    When the owner runs the memory pass
    And the owner deletes the memory "Preemption arguments never really end" of "sol"
    And the owner reverts the latest memory change
    Then the memory history records "sol" remembering "Preemption arguments never really end"
    And the memory history entry is not marked reverted

  # 20. Three root rules in one test (§2.3's recorded owner call and §2.2's parent-candidate rule):
  # the root SHIPS (it renders on the profile), the root does NOT inject (prompt identity stays the
  # composed system prompt), and a root standing over records never leaks through the hop. The
  # owner-authoring half drives the real profile form — the only path that stamps owner provenance
  # over HTTP. The two "none of the root's" halves are the parity assertions exempt from RED-first
  # by name (see the preamble).
  Scenario: The owner authors a memory and a root, and only the memory ever reaches a prompt
    Given a thread "Scaling SQLite" exists
    And a posted reply from "paul" saying "The checkpoint keeps stalling for me"
    And persona "sol" was given the root "Grew up fixing farm machinery and never lost the habit"
    And the LLM will respond with "Stalls are fixable"
    When the owner authors the memory "Checkpoint stalls once ate a weekend" for "sol"
    Then the profile for "sol" shows the memory "Checkpoint stalls once ate a weekend" with source "owner"
    And the profile for "sol" shows the root "Grew up fixing farm machinery and never lost the habit"
    When the owner summons "sol"
    Then "sol"'s generation prompt carried the memory "Checkpoint stalls once ate a weekend"
    And "sol"'s generation prompt did not carry the memory "Grew up fixing farm machinery and never lost the habit"

  # 21. Memory never buys airtime (I2), pinned behaviorally rather than by trust: a scribe run that
  # actually writes a record leaves tick parity (zero ambient_run rows), the home page and both
  # rails byte-unchanged. The profile assertion is the witness that the run really ran — without
  # it, a 404ing endpoint would satisfy every "unchanged" claim vacuously.
  Scenario: A scribe run writes zero ambient runs and leaves the forum surfaces unchanged
    Given a thread "Rust in the kernel" exists
    And a posted reply from "owner" saying "Where does the time actually go?"
    And a posted reply from "sol" saying "The scheduler is the interesting part" under "owner"'s reply
    And a posted reply from "sol" saying "Preemption cost decides this" under "owner"'s reply
    And a posted reply from "sol" saying "Nobody benchmarks the wake-up path" under "owner"'s reply
    And the LLM will respond with "REMEMBER: Preemption arguments deserve their own ledger"
    When the owner snapshots the forum activity
    And the owner runs the memory pass
    Then no ambient run was recorded
    And the forum activity is unchanged
    And the profile for "sol" shows the memory "Preemption arguments deserve their own ledger" with source "scribe"

  # 22. The reparent-before-delete discipline as behaviour, not just Tier 1: deleting a mid-chain
  # record hands its child to the grandparent, and the chain still surfaces together — the child
  # matches the conversation, the hop brings the (reparented) grandparent along.
  Scenario: Deleting a mid-chain memory reparents its child and the chain still surfaces together
    Given a thread "Scaling SQLite" exists
    And a posted reply from "paul" saying "My checkpoint config keeps misbehaving"
    And persona "sol" was given the memory "Fell down a fsync rabbit hole two winters back"
    And persona "sol" was given the memory "Kept digging into commit behaviour afterwards" extending "Fell down a fsync rabbit hole two winters back"
    And persona "sol" was given the memory "Ended up distrusting default checkpoint settings" extending "Kept digging into commit behaviour afterwards"
    And the LLM will respond with "Suspicion earned the hard way"
    When the owner deletes the memory "Kept digging into commit behaviour afterwards" of "sol"
    Then the profile for "sol" shows the memory "Ended up distrusting default checkpoint settings" beneath "Fell down a fsync rabbit hole two winters back"
    When the owner summons "sol"
    Then "sol"'s generation prompt carried the memory "Ended up distrusting default checkpoint settings"
    And "sol"'s generation prompt carried the memory "Fell down a fsync rabbit hole two winters back"
