Feature: Streaming generation events (AG-UI)
  A drafting node streams its generation as AG-UI events over Server-Sent Events, so the browser can show
  text appear live and then swap in the server-rendered reply. The stream is purely additive: an unknown or
  already-settled node yields an immediately-complete stream and the client falls back to the htmx poll.

  Scenario: A drafting node streams its AG-UI events over SSE
    Given a streaming generation "stream-node-1" has produced "Indexes " then "help here"
    When the owner opens the event stream for "stream-node-1"
    Then the event stream carries an AG-UI "RUN_STARTED" event
    And the event stream carries the text deltas "Indexes " and "help here"
    And the event stream carries an AG-UI "RUN_FINISHED" event

  Scenario: Streaming an unknown node completes at once so the client polls instead
    When the owner opens the event stream for "no-such-node"
    Then the event stream is empty

  # S2 ambient variant (plan_docs/ambient-slice-2.md §6): the SSE transport is trigger-agnostic —
  # InFlightGenerations doesn't know or care whether a run came from an owner POST or the ambient tick's
  # comment action — so an ambient comment's draft streams over the exact same path, just a
  # differently-labelled run id.
  Scenario: An ambient comment's draft streams its AG-UI events over SSE too
    Given a streaming generation "ambient-comment-1" has produced "Indexes " then "help here"
    When the owner opens the event stream for "ambient-comment-1"
    Then the event stream carries an AG-UI "RUN_STARTED" event
    And the event stream carries the text deltas "Indexes " and "help here"
    And the event stream carries an AG-UI "RUN_FINISHED" event
