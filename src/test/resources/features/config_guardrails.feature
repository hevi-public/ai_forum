Feature: Config guardrails
  Config drifts silently, so the guardrails are themselves tested (§14): under the test profile the app
  must use the test DB and disable backups. These rails assert the wiring from the outside.

  Scenario: The test profile uses the test DB and disables backups
    When the test diagnostics are read
    Then the active datasource points at the test database
    And backups are disabled
    And the active profile is "test"

  Scenario: The test profile never authorises personas to reach the network
    # Headless `claude -p` denies WebFetch / MCP tools unless `--allowedTools` pre-authorises them (see
    # ProcessLlmClient). These rails keep both toggles off under test so CI personas can't hit the network
    # or the gh-readonly GitHub tools (both fetch untrusted content from the host).
    When the test diagnostics are read
    Then persona web fetch is disabled
    And persona GitHub tools are disabled

  # The ambient loop (plan_docs/ambient-slice-1.md) is a background job with real cost — its scheduler
  # must stay off under test just like backups, and its ArticleSource must be the scriptable fake, never
  # a live fetcher, so ticks in the suite are deterministic and free.
  # S5 (plan_docs/ambient-slice-5.md §2, §4): the real source is chosen by `aiforum.ambient.source`
  # (stub | feed), defaulting to stub, and its feed allowlist (`AmbientFeedProperties`) must exist —
  # empty — under test even though FeedArticleSource itself can never wire here (see the "Network
  # under test" rail row in the slice doc's §3 threat table).
  Scenario: The test profile gates ambient ticking off and fakes the article source
    When the test diagnostics are read
    Then ambient ticking is disabled
    And the article source is the scriptable fake
    And the ambient source selection defaults to the stub
    And no feeds are configured under test

  # The stance evolution pass (plan_docs/ambient-slice-4a.md D12) is the SECOND scheduled job in this app
  # that spends LLM calls, and it runs at 04:00 with nobody watching — so it gets the same rail ambient
  # ticking has, on its own switch. The cap is asserted alongside the switch for a subtler reason: it can
  # only be read at all if the properties bean was bound from a non-profiled @Configuration, which is what
  # keeps this rail readable under a profile where the scheduler itself can never wire.
  Scenario: The test profile gates the stance evolution pass off
    When the test diagnostics are read
    Then stance evolution is disabled
    And the stance evolution edge cap is unlimited by default

  # The THIRD scheduled job in this app that spends LLM calls (plan_docs/ambient-slice-4b.md D8), and the
  # one with the largest blast radius on the room's character — so it gets the same rail the other two
  # have, on its own switch. The member cap is asserted beside the switch for the same subtler reason: it
  # can only be read at all if the properties bean was bound from a non-profiled @Configuration, which is
  # what keeps this rail readable under a profile where the scheduler itself can never wire.
  Scenario: The test profile gates the interest drift pass off
    When the test diagnostics are read
    Then interest drift is disabled
    And the interest drift member cap is unlimited by default
