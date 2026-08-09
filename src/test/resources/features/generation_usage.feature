Feature: Generation usage and tool calls — what a turn cost, and what it reached for
  Issue #15. Two facts have always been in the CLI's stream-json and thrown away on the floor: what the
  turn COST, and which tools it called to write the reply. `ambient_run.cost_usd` has held NULL since
  V21 (whose header said so, and said it awaited an LlmClient contract change — this is that change),
  and a tool fetch that shaped a persona's answer left no trace at all, while every other influence on a
  reply (stance, interest, memory) has a full audit table.

  So: the settled reply carries its cost out to the tick that dispatched it, and the tool calls land in
  `generation_tool_call` at settle. Absence stays honest throughout — a provider that reports no cost
  leaves the column NULL (UNKNOWN), never a rendered zero, and a provider with no tool loop leaves the
  trace empty, which is the correct account of a turn that used no tools.

  Rendering the trace is issue #16; the only surface this slice adds is the run row's own cost.

  Scenario: An ambient tick records the summed cost of its generations
    # A lone persona short-circuits the "Anyone" dispatcher (no routing call), so the single scripted
    # response IS the generation whose cost the run must carry.
    Given a persona "sol" exists
    And the ArticleSource has the article "Why is SQLite fast?" at "https://example.com/sqlite" summarised "A deep dive into B-trees vs LSM"
    And the LLM will respond with "Indexes are the trick" costing 0.12 USD
    When the owner triggers an ambient tick
    Then the ambient run is recorded with outcome "posted" and action "post"
    And the latest ambient run's cost is "0.1200"

  # The tick's comment action fans out further than the summon it dispatched: the settle triggers a
  # bounded growth round (ambient_commenting's mini-discussion). That growth is the SAME tick's spend, so
  # it lands on the SAME run — a cost that only counted the first reply would understate an ambient tick
  # by however much the room riffed on its own, which is the one figure the operator is watching.
  Scenario: The settle-triggered growth is charged to the run that caused it
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists with ability "sqlite" and talkativeness 8
    And the LLM will respond with "Indexes are the trick, and here's more detail on the WAL" costing 0.05 USD
    And the LLM will respond with "The checkpoint interval is the other half of it" costing 0.03 USD
    And the LLM will respond with "And autocheckpoint tuning after that" costing 0.03 USD
    When the owner triggers an ambient tick
    Then the ambient comment's mini-discussion grows to 2 replies on its own
    And the latest ambient run's cost is "0.1100"

  Scenario: A generation's tool calls are persisted at settle, linked to the posted reply
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    And the LLM will respond with "The checkpoint path is where it changes" using tools:
      | tool               | input                        | output                                |
      | Read               | {"file_path":"/src/wal.c"}   | static int walCheckpoint(Wal *pWal)   |
      | mcp__gh-readonly   | {"owner":"a","repo":"b"}     | +12 -3 in src/wal.c                   |
    When the owner summons "sol"
    Then the reply is "posted"
    And the generation's tool calls are recorded:
      | seq | tool             | linked |
      | 1   | Read             | yes    |
      | 2   | mcp__gh-readonly | yes    |

  # A Bash/diff/fetch output is unbounded, and an audit trail that stores megabytes per turn stops being
  # an audit trail. The sentinel is what makes this honest: a stored summary that still carries it was
  # never clipped, however short it happens to look.
  Scenario: Oversized tool output is clipped at the persistence boundary
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    And the LLM will respond with "It is all in the diff" after a tool call whose output is 6000 characters ending in "NEVER-PERSISTED"
    When the owner summons "sol"
    Then the reply is "posted"
    And the recorded tool output is at most 4000 characters and does not contain "NEVER-PERSISTED"

  # The plain `will respond with` scripting IS the openai/opencode/stub shape: no usage, no tool loop.
  # This slice must leave that shape byte-for-byte as it was — an absent cost stays absent (NULL means
  # UNKNOWN, and a rendered 0.0000 would be a lie about a run that certainly spent something), and an
  # empty trace is the correct account of a turn that used no tools, not a missing one.
  Scenario: A provider that reports no usage leaves the cost absent and records no tool calls
    Given a persona "sol" exists
    And the ArticleSource has the article "Why is SQLite fast?" at "https://example.com/sqlite" summarised "A deep dive into B-trees vs LSM"
    And the LLM will respond with "Indexes are the trick"
    When the owner triggers an ambient tick
    Then the ambient run is recorded with outcome "posted" and action "post"
    And the latest ambient run has no recorded cost
    And no tool calls were recorded
