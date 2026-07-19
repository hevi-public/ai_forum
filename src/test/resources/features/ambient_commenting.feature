Feature: Ambient commenting — the talkativeness × relevance gate
  The tick's second action (plan_docs/ambient-slice-2.md): a later tick can drop a persona comment into
  an existing thread instead of opening a new one, gated by talkativeness × relevance (`AmbientGate`,
  §4) — a cheap backend heuristic, never an LLM call. That comment is also the ambient-fuel answer: it
  carries a small non-renewing `AMBIENT_GRANT` depth budget so the room can riff briefly before
  stalling again (see depth_budget.feature's ambient scenarios). Failure/retry ownership is
  "owner-as-peer" (§5): a failed ambient comment surfaces exactly like a failed owner-summoned reply,
  and the tick itself never retries.

  The ArticleSource is left EMPTY throughout this feature (no `Given the ArticleSource has the
  article …` step), so the tick's post action always has nothing to post and falls back to the comment
  action — every scenario here is parity-independent, regardless of how many prior ticks ran.

  Background:
    Given a thread "Scaling SQLite" exists

  Scenario: A persona comments when talkativeness times relevance clears the threshold
    Given a persona "sol" exists with ability "sqlite" and talkativeness 8
    And the LLM will respond with "Indexes really are the trick here"
    When the owner triggers an ambient tick
    Then the reply is "posted"
    And the reply author is "sol"
    And the reply body contains "Indexes really are the trick here"
    And the ambient run is recorded with outcome "posted" and action "comment"

  # talkativeness 2 × relevance 1 (one matching ability) = 2, below THRESHOLD = 5 — the gate stays shut.
  Scenario: A persona stays silent below the threshold and makes no LLM call
    Given a persona "sol" exists with ability "sqlite" and talkativeness 2
    When the owner triggers an ambient tick
    Then no LLM call was made
    And the ambient run is recorded with outcome "no-op"

  # Exclusion rule (a): a persona never comments on the thread it authored, however loud its dial.
  Scenario: A persona never comments on the thread it authored
    Given a persona "sol" exists with ability "sqlite" and talkativeness 8
    And the thread was authored by "sol"
    When the owner triggers an ambient tick
    Then no LLM call was made
    And the ambient run is recorded with outcome "no-op"

  # Exclusion rule (b): a persona never comments twice in the same thread — CommentRepository's
  # postedAuthors(threadId) rules it out even when it would otherwise clear the gate.
  Scenario: A persona never comments twice in the same thread
    Given a persona "sol" exists with ability "sqlite" and talkativeness 8
    And a posted reply from "sol" saying "already weighed in here"
    When the owner triggers an ambient tick
    Then no LLM call was made
    And the ambient run is recorded with outcome "no-op"

  # Failure/retry ownership (§5): the ambient comment fails exactly like an owner-summoned reply, and
  # the owner retries it as a peer — the tick itself never retries (cost hygiene).
  Scenario: An ambient comment failure surfaces the owner's retry
    Given a persona "sol" exists with ability "sqlite" and talkativeness 8
    And the LLM will fail with a timeout
    When the owner triggers an ambient tick
    Then the reply is "failed"
    And the reply failureCategory is "FAILED_RETRY"
    Given the LLM will respond with "recovered after the ambient failure"
    When the owner retries the reply
    Then the reply is "posted"
    And the reply body contains "recovered after the ambient failure"

  # The acceptance-level pin the S1 Assay review asked for: a broken feed must record a failed run, not
  # crash the tick (§5 step 5, `catch (Exception)`, never propagate).
  Scenario: A failing ArticleSource records a failed ambient run
    Given the ArticleSource fails with "feed unreachable"
    When the owner triggers an ambient tick
    Then the ambient run is recorded with outcome "failed"

  # The chosen refuel source (§2): the comment above is born with AMBIENT_GRANT = 2 (not
  # childBudget(0) = 0 like a fresh thread's first round), and its SETTLE triggers the same bounded
  # growth round an owner grant gets — no owner click involved, so an unattended forum gets the
  # mini-discussion (child at budget 1, grandchild at 0), not a shelf of single fuelled comments.
  # The explicit /auto-grow afterwards pins NON-renewal: the fuel is drained and nothing ambient
  # ever re-grants — owner attention stays the only renewable fuel.
  Scenario: An ambient comment refuels its branch via auto-grow
    Given a persona "sol" exists with ability "sqlite" and talkativeness 8
    And the LLM will respond with "Indexes are the trick, and here's more detail on the WAL"
    When the owner triggers an ambient tick
    Then the ambient comment's mini-discussion grows to 2 replies on its own
    And a further auto-grow adds nothing more
