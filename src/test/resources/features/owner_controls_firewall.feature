Feature: Owner controls — the +1 firewall
  The anti-sycophancy core (§7/§13): the owner's +1 is recorded with full attribution and shown to the
  owner, but it is firewalled at the prompt boundary — it never appears in the context handed to a
  model. Asserted by spying on what the LlmClient actually received.

  Background:
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    And a persona "vex" exists

  Scenario: Owner +1 is recorded and shown, but never reaches the model
    Given a posted reply from "sol" saying "Indexes help here"
    And the LLM will respond with "Vex builds on that"
    When the owner gives a +1 to "sol"'s reply
    Then the owner sees a vote count of 1 on "sol"'s reply
    When the owner summons "vex"
    Then the model's context included "sol"'s words "Indexes help here"
    And the model's context contained no vote signal

  # The firewall is about intent, not paranoia — it is a rule about WHICH signals shape a persona's voice,
  # not a blanket ban on anything owner-adjacent. Two signals sit on opposite sides of the same boundary:
  # the owner's +1 is structurally EXCLUDED (it would teach the room what the owner rewards — the whole
  # anti-sycophancy point of §7/§13), while a hand-authored relation stance is structurally INCLUDED (it is
  # character the owner wrote on purpose, and colouring one persona's tone toward another is exactly what
  # it is for). Pinning both polarities in one context means a later refactor of prompt assembly cannot
  # quietly flip either half — dropping the stance or leaking the vote turns this scenario red.
  Scenario: A relation stance is injected into the very context the +1 is kept out of
    Given a posted reply from "sol" saying "Indexes help here"
    And persona "vex" has a stance toward "sol" of "needles him about hype"
    And the LLM will respond with "Hype aside, an index is cheap"
    When the owner gives a +1 to "sol"'s reply
    And the owner summons "vex"
    Then the model's system prompt carried the stance "needles him about hype"
    And the model's context contained no vote signal

  # Same boundary, one slice on: a DRIFTED interest (S4b) is the other thing deliberately inside the
  # context the +1 is kept out of — and it arrives without anyone composing a prompt, since interests are
  # injected at generation time rather than baked in. The summon is deliberately the LAST call in this
  # scenario: `the model's context contained no vote signal` reads the spy's most recent call, so a drift
  # pass running after it would put the JUDGE's prompt under the firewall assertion instead of the
  # generation prompt, and the scenario would pass while proving the wrong thing.
  Scenario: A drifted interest is injected into the very context the +1 is kept out of
    Given a posted reply from "sol" saying "Preemption cost decides this"
    And persona "sol" is into "kernel scheduling"
    And the LLM will respond with "The wake-up path is the whole story."
    When the owner gives a +1 to "sol"'s reply
    And the owner summons "sol"
    Then "sol"'s system prompt carried the interest "kernel scheduling"
    And the model's context contained no vote signal

  # Same boundary, a third slice on: a RESURFACED memory (plan_docs/persona-memory.md) is the newest
  # thing deliberately inside the context the +1 is kept out of — injected at generation time from
  # the member's own private store when the conversation shares the record's words, with no compose
  # bought anywhere. The summon is again deliberately the LAST call in the scenario: `the model's
  # context contained no vote signal` reads the spy's most recent call, so a memory pass running
  # after it would put the SCRIBE's prompt under the firewall assertion instead of the generation
  # prompt, and the scenario would pass while proving the wrong thing.
  Scenario: A resurfaced memory is injected into the very context the +1 is kept out of
    Given a posted reply from "sol" saying "Checkpoint stalls keep biting us"
    And persona "sol" was given the memory "Spent a weekend chasing checkpoint stalls"
    And the LLM will respond with "That stall pattern is familiar"
    When the owner gives a +1 to "sol"'s reply
    And the owner summons "sol"
    Then "sol"'s generation prompt carried the memory "Spent a weekend chasing checkpoint stalls"
    And the model's context contained no vote signal
