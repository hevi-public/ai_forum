Feature: Usage observability — cost and tool-call history surfaced in the admin UI
  Issue #16. Issue #15 populated `ambient_run.cost_usd` and `generation_tool_call`; neither was visible
  anywhere. This slice adds the readers and the pages: a visible cost on each /admin/ambient run row, a
  rolling 24h/7d usage strip above the run list (reconciled against the same rows the list shows), and a
  new /admin/tools view of what a generation actually fetched, read, or ran. The direction doc's success
  criterion promises content "at bounded and observable cost" (ai-driven-forum-direction.md) — this is
  the "observable" half; plan_docs/usage-observability.md records the ceiling on how far that can go.

  Scenario: Aggregates reconcile and window correctly
    Given a persona "sol" exists
    And the ArticleSource has the article "Why is SQLite fast?" at "https://example.com/sqlite" summarised "A deep dive into B-trees vs LSM"
    And the LLM will respond with "Indexes are the trick" costing 0.10 USD using tools:
      | tool | input        | output |
      | Read | {"path":"a"} | ok     |
      | Bash | {"cmd":"ls"} | ok     |
    When the owner triggers an ambient tick
    Then the latest ambient run's cost is "0.1000"
    And the latest ambient run happened 3 days ago
    And that run's tool calls started 3 days ago
    And the ArticleSource has the article "WAL mode explained" at "https://example.com/wal" summarised "Write-ahead logging in depth"
    And the LLM will respond with "The WAL trades durability for throughput" costing 0.20 USD using tools:
      | tool | input        | output |
      | Read | {"path":"b"} | ok     |
      | Bash | {"cmd":"ls"} | ok     |
      | Grep | {"q":"wal"}  | ok     |
    When the owner triggers an ambient tick
    Then the latest ambient run's cost is "0.2000"
    # 24h: only the second tick (the first is 3 days old). 7d: both ticks.
    And the usage strip shows a 24h cost of 0.2 with 3 tool calls, and a 7d cost of 0.3 with 5 tool calls
    And the run rows' own costs sum to the usage strip's 7d cost

  Scenario: The run list shows each run's cost visibly
    Given a persona "sol" exists
    And the ArticleSource has the article "Why is SQLite fast?" at "https://example.com/sqlite" summarised "A deep dive into B-trees vs LSM"
    And the LLM will respond with "Indexes are the trick" costing 0.12 USD
    When the owner triggers an ambient tick
    Then the ambient run list shows the cost "$0.1200"

  Scenario: The tool-call view lists what a generation ran
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    And the LLM will respond with "The checkpoint path is where it changes" using tools:
      | tool             | input                      | output                               |
      | Read             | {"file_path":"/src/wal.c"} | static int walCheckpoint(Wal *pWal)  |
      | mcp__gh-readonly | {"owner":"a","repo":"b"}   | +12 -3 in src/wal.c                  |
    When the owner summons "sol"
    Then the reply is "posted"
    And the tool-call list shows this generation's calls:
      | seq | tool             | error | linked |
      | 1   | Read             | false | yes    |
      | 2   | mcp__gh-readonly | false | yes    |
    And the tool-call list contains the text "static int walCheckpoint(Wal *pWal)"
    And the tool-call list contains the text "+12 -3 in src/wal.c"

  Scenario: Oversized tool output renders clipped, not just persisted clipped
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    And the LLM will respond with "It is all in the diff" after a tool call whose output is 6000 characters ending in "NEVER-RENDERED"
    When the owner summons "sol"
    Then the reply is "posted"
    And the tool-call list shows the truncation marker and not the sentinel "NEVER-RENDERED"

  Scenario: A no-usage provider renders the honest unknown
    Given a persona "sol" exists
    And the ArticleSource has the article "Why is SQLite fast?" at "https://example.com/sqlite" summarised "A deep dive into B-trees vs LSM"
    And the LLM will respond with "Indexes are the trick"
    When the owner triggers an ambient tick
    Then the ambient run is recorded with outcome "posted" and action "post"
    And the usage strip shows the honest unknown
