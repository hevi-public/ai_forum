# Tested, structured logging — experiment status & follow-ups

Status: **experiment, partially shipped** (2026-06). The premise: **logging is IO**, so we test it like
any other output. With everything below a seam stubbed, a log line is deterministic; an asserted log line
is a **contract**; a tested contract is safe to **standardise**; and a standardised log surface is what
lets us build tooling (alerting, analysis) on top. This doc records what's in place and the follow-ups
deliberately deferred for later consideration.

## What's shipped

- **The principle, in the skill.** [`bdd-tiered-testing`](../.claude/skills/bdd-tiered-testing/SKILL.md) has
  a "Logging is IO — assert it" section: capture at the SLF4J/Logback level (`level + message`, never the
  ambient timestamp/thread/MDC layout), pin the logger name to the production class (not `javaClass`, or a
  test subclass logs under a different name), and assert silence where it matters.
- **A structured event-id convention.** Every operational line carries a stable, namespaced `event` id
  plus typed fields via the SLF4J fluent key-value API. The id (and field keys) are the breaking surface;
  the human message is free to change. Shared helper: `observability/LogEvents.kt`
  (`LoggingEventBuilder.event(id)`). Ids live as `EV_*` constants on the emitting class, which doubles as
  that emitter's event catalogue.
- **Test support.** `testsupport/LogCapture.kt` — a Logback `ListAppender` scoped to one logger, exposing
  `warns()/infos()/debugs()`, plus `withEvent(id)` / `keyValue(e, key)` for the structured layer.
- **Two seams instrumented + tested** (id + level + fields asserted in Tier 1):
  - `gh.*` — `gh.unavailable` (WARN), `gh.startup.ok` (INFO), `gh.startup.unavailable` (WARN),
    `gh.list.failed` (DEBUG).
  - `llm.*` — `llm.spawn` (DEBUG), `llm.timeout` (WARN), `llm.cancelled` (INFO).
  - `llm.jail.*` (added 2026-08-09 with the Docker jail — `llm-sandbox.md` §7) — `llm.jail.ready` (INFO),
    `llm.jail.image_missing` (WARN), `llm.jail.startup_failed` (ERROR) on `JailRuntime`;
    `llm.jail.docker_unavailable` (ERROR), `llm.jail.run_failed` (WARN) on `ProcessLlmClient`. The `ready`
    line reports the credential **mode**, never the token — asserted, because that leak would be silent.

## Follow-ups (deferred — for later consideration)

### 1. A machine-readable log sink (JSON appender)

The events carry key-value pairs, but the app's default console layout renders them into a formatted
string — an analyzer would have to re-parse prose. To make the structured fields actually reach a sink as
fields, add a JSON encoder (e.g. `net.logstash.logback:logstash-logback-encoder`) wired in a
`logback-spring.xml`, ideally behind a profile so local dev keeps the human console and prod/ops emits
JSON. The `event` id and fields then land as first-class JSON keys. This is itself testable (assert the
encoder is configured / the rendered line parses as JSON with the expected keys), so it stays inside the
same discipline. **Why deferred:** no log consumer exists yet, so the sink format is speculative until
there's a concrete analyzer/aggregator to target.

### 2. An event-id registry + guard test

Ids are currently per-emitter constants with no global check. As the surface grows, add a lightweight
registry (or a test that reflects over the `EV_*` constants) asserting ids are **unique** and **namespaced**
(`<area>.<event>`), so a typo or collision fails the build rather than silently splitting a metric. Could
also generate a catalogue (the list of ids + their fields) from that registry for documentation/tooling.
**Why deferred:** two namespaces (`gh.*`, `llm.*`) is small enough to eyeball; the guard earns its keep
once more seams are instrumented.

### 3. Roll the convention out to the remaining seams

Only `gh` and the generation seam are instrumented. The operational signal that matters most for a
single-user forum running unattended also lives at:

- **`repo/` (persistence)** — write failures, the `FailingRepositoryToggle` path, busy-timeout/retry on
  SQLite.
- **`web/` controllers** — request-level faults, validation rejections (already spy-asserted at the seam;
  a structured `*.rejected` event would make them observable in logs too).
- **`OpenAiLlmClient`** — the HTTP generation path (transport errors, non-2xx, rate-limit) is the sibling
  of `ProcessLlmClient` and currently untested-for-logging.
- **`images/` vision seam** — describe failures.

Each follows the same recipe: pick an `<area>.*` namespace, emit `event` + fields, assert id+level+fields
in the seam's tier test. **Why deferred:** instrument as each area is next touched, rather than a big-bang
sweep, so the convention is applied with real context.

## Notes / open questions

- **Message vs id contract.** Today both are asserted (`assertEquals` on the full message). That pins
  wording too, which is stricter than the contract demands. If wording churn becomes annoying, relax the
  message assertions to `contains`/structure while keeping id+fields exact — but only once the id is the
  thing consumers actually depend on (i.e. after a sink exists).
- **Field typing.** `keyValue(...)` stringifies for assertions; a JSON sink would preserve numeric types
  (e.g. `timeoutMs`). Worth keeping field values as real types at the call site (we already pass `Long`),
  not pre-stringified, so the sink can emit them typed.
