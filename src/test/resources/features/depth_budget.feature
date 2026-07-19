Feature: Depth-budget autonomy
  A thread auto-grows ~3–4 reply levels past the owner's last comment, then stalls; an owner comment or
  /more re-grants budget; the budget is per-branch (§4/§7).

  The refuel actor isn't fixed to the owner either: S2 (plan_docs/ambient-slice-2.md §2 "the fuel
  decision") gives the ambient tick's own comment action a small NON-renewing grant
  (`AMBIENT_GRANT = 2`, smaller than the owner's `DEFAULT_GRANT = 4`) instead of inheriting
  `childBudget(parent)` — so a tick-planted comment buys a bounded mini-discussion before stalling again,
  but nothing ambient ever re-grants further. The owner remains the only RENEWABLE fuel (the §7 steering
  lever is untouched); the scenarios below exercise both the owner-grant mechanics and this smaller
  ambient one.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists

  Scenario: Autonomous growth stalls when the depth budget is exhausted
    Given the owner has commented at level 0
    When the room auto-replies
    Then auto-replies stop after about 4 levels

  Scenario: An owner reply re-grants depth budget on that branch
    Given a branch whose depth budget is exhausted
    When the owner replies on that branch
    Then auto-replies resume on that branch
    And other branches stay quiet

  Scenario: /more grants depth budget and is visible to the model
    Given a branch whose depth budget is exhausted
    When the owner invokes /more on that branch
    Then the branch is granted about 3 to 4 more levels
    And the /more directive appears in the context handed to the model

  # S2 (plan_docs/ambient-slice-2.md §2, recon fact): the first summoned round of ANY thread — owner- or
  # persona-authored alike — is born at budget 0 (planGeneration: parentId=null ⇒ childBudget(0)=0). An
  # ambient-opened thread gets no special treatment, so without the owner ever engaging (or a later
  # ambient comment landing) it stalls right there. Pins the tension the fuel decision rests on.
  Scenario: An ambient thread's summoned room stalls at depth 0 without owner engagement
    Given the ArticleSource has the article "Why is SQLite fast?" at "https://example.com/sqlite" summarised "A deep dive into B-trees vs LSM"
    And the LLM will respond with "Indexes are the trick"
    When the owner triggers an ambient tick
    And the room auto-replies
    Then auto-replies stop after about 0 levels

  # The chosen refuel source (§2): unlike the first summon round above, an ambient COMMENT is born with
  # AMBIENT_GRANT = 2 rather than childBudget(0) = 0 — and its settle consumes that fuel by itself (the
  # comment summon hooks the same growth machinery an owner grant fuels), so the bounded mini-discussion
  # needs no owner attention. The explicit /auto-grow afterwards proves the grant is NON-renewing:
  # drained is drained, and only the owner's own engagement refuels a branch.
  Scenario: An ambient comment re-grants a small budget on an ambient branch
    Given a persona "vex" exists with ability "sqlite" and talkativeness 8
    And the LLM will respond with "A small burst of discussion"
    When the owner triggers an ambient tick
    Then the ambient comment's mini-discussion grows to 2 replies on its own
    And a further auto-grow adds nothing more
