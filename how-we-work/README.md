# How we test & organise the AI Forum

> **Status:** ✅ written 2026-07-10 · **Owner:** Hevi · every number below was grep-derived and then
> independently re-derived by a second pass; claims that didn't survive adversarial verification were
> corrected before landing here.
>
> 🖼 **The one-page version:** open [`infographic.html`](infographic.html) in a browser. It doubles
> as a slide deck — hit **Present** (or `P`) and flip through with ←/→/Space; `Esc` exits.

This document explains the project's two load-bearing systems — the **tiered, one-seam test
architecture** and the **docs-as-infrastructure organisation** — and then evaluates them honestly:
what they buy a human, what they buy an AI agent, where they will crack under scale, and what should
be built next.

---

## 1. The numbers (verified 2026-07-10; gate widened same day — see §6 item 2)

| Metric | Value |
|---|---|
| Automated checks in the merge gate | **607** — 346 JUnit tests + 153 Gherkin scenarios + 85 JS tests + 23 MCP-server tests |
| Tier 0 / Tier 1 / Tier 2 tests | 156 / 136 / 54 (21 / 17 / 9 classes) |
| Acceptance spec | 44 `.feature` files, 153 scenarios (158 after outline expansion), 32 step-definition files |
| Mocking libraries (MockK, Mockito, …) | **0** — not even as a dependency |
| Test : production code | 9,663 : 7,803 LOC (**1.24 : 1** — more test code than product code) |
| Production source | 91 Kotlin files, 35 JTE templates, 19 Flyway migrations, 11 repositories |
| Semantic UI contract | 157 distinct `data-*` attributes in templates (what acceptance asserts instead of CSS) |
| Reverts in the entire git history | 0 |

---

## 2. The testing approach

### 2.1 The spec came first — literally

The repo's first *feature* commit (day 2, `dcde588`) was the acceptance layer itself: 13 `.feature`
files, the step-definition scaffold, `DatabaseResetHooks`, and the Tier-0 tests — before most of the
production code existed. The suite is not a safety net added to a product; the product was built
*behind* the suite. Build order is outside-in (acceptance against mockups first, then views, then
domain/persistence behind the frozen contract), which is what lets sessions implement without
renegotiating the spec.

The `.feature` files are written as genuine specification, not test scripts: declarative,
DOM-agnostic, carrying rationale prose and citations into the requirements doc (`§4`, `§7/§13`,
`T1.4`). A newcomer — human or agent — learns what the product does by reading
[`src/test/resources/features/`](../src/test/resources/features/), not by reverse-engineering
controllers.

### 2.2 The tier ladder

Four Kotlin tiers plus a JS rung, each a Gradle task, ordered lowest-first (advisory
`shouldRunAfter`) so **the lowest failing tier names the culprit** — you never start debugging from a
Cucumber stack trace when a millisecond Tier-0 test already failed on the same logic.

| Tier | What it proves | Real | Stand-ins | Checks |
|---|---|---|---|---|
| **Tier 0** | Pure logic: state machine, context firewall, parsers, markdown, routing traits | everything | none | 156 |
| **Tier 1** | The IO boundary itself: repositories vs **real SQLite**, `claude -p` adapter vs a real `/bin/sh` subprocess, HTTP adapter vs a real local socket, Flyway pipeline, backup | real DB, real subprocesses, real sockets | controlled sub-seam substitutes only (a shell script, `MockRestServiceServer`) | 136 |
| **Tier 2** | Services/controllers as plain objects (no Spring): orchestration, fan-out, cancel, error taxonomy | real service + domain logic | hand-rolled scriptable `LlmClient`, in-memory repo fakes, fixed `Clock` | 54 |
| **Acceptance** | Full stack over HTTP: one `@SpringBootTest(RANDOM_PORT)` context, real JTE SSR, real SQLite + Flyway | everything except IO ports | the four scriptable seam fakes + fixed Clock | 153 scenarios |
| **jsTest** | Pure JS cores (`*-core.mjs`: toast store, nav, quote scanner) via `node:test` | — | injected `now` | 85 |
| **mcp gates** | The two `mcp/` servers: gh-readonly (node:test) and shortcut (tsc typecheck + node:test) | — | none | 23 |

One command runs the ladder: `./gradlew verifyAll` (also wired into `check`; the default `test` task
is disabled so there is no untiered side door). CI is deliberately a thin ~25-line workflow whose
only build step is `docker compose run --rm build` — **the same command a developer runs locally**
(compose no longer overrides the image's `verifyAll` default, so a gate added to `verifyAll` is in
CI automatically), and "passes locally, fails in CI" is structurally impossible for the Docker path.

Honest caveats, verified: tier ordering is advisory (`shouldRunAfter`), not a dependency chain; and
the merge gate is *convention* — there is currently no GitHub branch protection enforcing a green
run. The three former silent-green holes were closed 2026-07-10 (§6 item 2): the acceptance task now
enforces a floor on *executed* scenarios read from cucumber's `report.json` (Gradle's
`failOnNoDiscoveredTests` alone cannot catch a tag-filter regression — filtered scenarios still
count as "discovered", reported skipped), `jsTest`'s Node ≥ 21 requirement is declared (`.nvmrc`,
`engines`) and preflight-checked with an actionable failure, and both `mcp/` servers' tests run in
the gate.

### 2.3 The seam doctrine — mock only at the IO boundary

The suite's honesty guarantee: **there are no mocking libraries in this repo.** Every test double is
plain, readable Kotlin, and doubles are allowed only at constructor-injected IO ports. In the `test`
profile, `@Primary @Profile("test")` beans stand in at four ports —

- `ScriptableLlmClient` (the LLM — scripted per scenario from Gherkin: *"Given the LLM will fail with
  a timeout"*, with a `received` spy so scenarios can assert what the model was **not** shown)
- `ScriptableImageDescriber` (vision captions)
- `ScriptableShortcutClient` (Shortcut API)
- `ScriptableGitHubClient` (`gh` CLI)

— plus a fixed `Clock` and `FailingCommentRepository`, a one-shot fault-injection wrapper that
otherwise delegates to the **real** repository. Everything above the ports runs real code; a green
higher-tier test means the wired-together system actually composes.

Two precision notes the folklore version gets wrong (both grep-verified):

1. It is not literally "one seam" — it is **one *class* of seam** (IO ports), currently four
   scriptable fakes. The pattern has been extended three times (vision, Shortcut, GitHub) without
   breaking the doctrine: each new integration lands behind its own port with a scriptable fake,
   following the `LlmClient` template.
2. Tier 2 does **not** touch SQLite — its repo fakes are in-memory subclasses of the real repository
   classes. That is consistent with the doctrine (the DB *is* IO, so the repo is a port), but "real
   SQLite in every tier" would be false; it's real in Tier 1 and acceptance. There is also exactly
   one double sitting *above* the seam (`SpyGeneration` in one Tier-2 test) — a known judgment call,
   not a licence.

Why so strict? Because a suite that mocks internally can stay green while the system is broken, and
in an agent-driven repo the suite is the primary control layer against drift. No mock DSL also means
an agent can't auto-mock its way past a design problem: needing a second seam is "a design smell to
discuss, not to push through."

### 2.4 Error scenarios, logging, and the UI contract

- **Failure is first-class spec.** Every generation failure mode — timeout, process error,
  rate-limit, empty, malformed, cancel, partial-roomful, couldn't-save — has explicit scenarios,
  driven by scripting the seam fake, asserting both the state transition and the user-visible
  outcome + working retry.
- **Logging is IO.** Operational log lines carry structured `event` ids (`gh.unavailable`,
  `llm.timeout`) and are asserted (level + id + fields) via `LogCapture` at the SLF4J layer — a log
  line is a tested contract, so tooling can be built on it. Silence is asserted too.
- **`data-*` semantic hooks.** Acceptance asserts only stable `data-*` attributes (157 of them),
  never CSS classes. This paid off measurably: the error-toast UX went through **three full
  redesigns under a fully green suite** (fragment swap → HX-Trigger → toast-only + TTL). JTE
  templates are precompiled and typed against DTOs, so a template/DTO mismatch is a *compile*
  failure — a classic agent hallucination converted into an unmissable build break.
- **Red-first machinery.** Scenarios can be committed ahead of implementation tagged `@wip`
  (filtered by `cucumber.filter.tags=not @wip`), and discovery mode (`-Pdiscovery=true` /
  `DISCOVERY_MODE=true`) flips `ignoreFailures` so a sea of red doesn't abort a scaffolding build.
  Note the honest limits: discovery mode lets failures pass — there is **no** "fail if a @wip
  scenario passes" mechanism (folklore says otherwise) — and today zero `@wip` tags remain; the
  spec is fully green.

### 2.5 Has it actually worked?

Evidence, not vibes:

- A **full adversarial audit** (2026-06-25) after a week of multi-agent, PR-per-day cadence found
  exactly **two Low-severity test findings** — one untested adapter, one flaky wall-clock poll —
  both fixed within a day. The tiered architecture itself was graded a strength, "recorded so it
  isn't re-litigated."
- **Zero reverts** in the entire history, across all branches.
- The seam doubles compounded: the day-2 `FailingCommentRepository` powered the transaction-rollback
  fix four days later; `Given the LLM will fail` was reused wholesale for the unrelated toast-UX
  feature.
- Pain got institutionalised, not repeated: the V7 Flyway checksum split was fixed with a test
  extended in the same commit, then codified into the `sqlite-spring-jdbc` skill; the one flaky
  poll became the skill's "de-flake via a test-double latch" section.

---

## 3. The organisation approach

### 3.1 Four documentation layers, each with a job

| Layer | Role | Lifetime |
|---|---|---|
| [`plan_docs/`](../plan_docs/) (20 files) | **Decide.** One doc per feature: status header, data model with the exact Flyway DDL, dated & locked "Decisions (owner)" blocks, slices, deferred items with promotion triggers, a tiered test plan. Rejected approaches are kept *with the why*. | Durable |
| [`.claude/skills/`](../.claude/skills/) (4 skills) | **Encode how-to.** Wiring traps and conventions an agent can't derive from code (Spring Boot 4 removed TestRestTemplate; the Cucumber engine needs a classpath selector under Gradle). Auto-trigger on the relevant paths. Maintained as a release artifact — updating them was a numbered audit task with its own PR. | Durable, must track code |
| [`src/test/resources/features/`](../src/test/resources/features/) | **Specify.** The executable spec — the only layer that can't silently lie, because it runs. | Durable, self-enforcing |
| [`how-we-work/context.md`](context.md) + session memory | **Coordinate.** Cross-session state: conventions, gotchas, the feature-state map. context.md is the on-repo record (added 2026-07-10 — private `~/.claude` memory may cache it but is never the only copy); a stale `HANDOVER.md` that once brokered an `app.js` conflict between two live parallel branches was deleted the same day. | context.md durable; memory ephemeral |

There is a deliberately **thin [`CLAUDE.md`](../CLAUDE.md)** (added 2026-07-10, reversing the
earlier deliberate omission once cross-session context moved on-repo): a router to the four layers
plus the non-negotiables, not a doctrine dump. There is still **no issue tracker and no coverage
tooling**: plan-doc status headers + the deferred-audit file (every deferral carries a concrete
promotion trigger) fill the first role, and "every behaviour gets a test at the lowest tier that
proves it" substitutes for the second.

### 3.2 The delivery loop

```
plan doc (decisions locked) ──► slices ──► worktree branch per agent session (claude/*)
      ▲                                          │
      │                                          ▼
skills re-synced ◄── merge ◄── PR (persona-signed review: 🛠️ Forge implements, ⚖️ Assay reviews)
```

- **Slices sized to one PR**, at high cadence (PRs #77–#90 in ~3 days at peak).
- **Skills are a release artifact (T2.7)** — standing PR checkbox: *did this change invalidate a
  skill snippet, and was the skill re-synced in the same PR?* The recurring prose-vs-code audit is a
  written work order at [`plan_docs/docs-drift-audit.md`](../plan_docs/docs-drift-audit.md).
- **Parallel agents coordinate through documents**: migration numbers are pre-claimed in plan docs
  (the V18/V19 collision between two live branches was avoided by convention), and cross-session
  handovers land in [`context.md`](context.md) and PR descriptions (the ad-hoc root `HANDOVER.md`
  pattern was retired 2026-07-10 after going stale).
- **The audit as a work order**: `audit-remediation-tier1-tier2.md` encodes a supervisor/worker
  protocol — decision gates, per-task definition-of-done — and git history shows it executed to the
  letter: 11 `claude/audit-t*` branches, PRs #77–#87, skills-update last as ordered.
- **Caveat**: the persona review roles have real process force but fictional provenance — everything
  is one human plus agents under the single `@hevi-public` identity. It is a discipline aid, not
  independent review.

---

## 4. Pros & cons

### For a human

**Pros**

1. **Failures self-localise.** The tier that fails names the layer that broke; debugging starts at a
   millisecond test, not a full-stack trace.
2. **No mock DSL to learn.** Every double is ordinary Kotlin you can step through; failure injection
   reads as a Gherkin one-liner.
3. **The spec teaches the product.** 44 feature files teach it directly; plan docs preserve
   decisions *and rejected paths* with dates — rare solo-repo gold for a joining teammate.
4. **Redesigns are cheap.** The `data-*` contract means restyling can't break 153 scenarios — proven
   three times in one review cycle.
5. **CI == local.** One Docker command, no matrix, no cache config to understand.

**Cons**

1. **Doctrine docs can drift** — and had: five live drifts ("exactly one seam" vs four ports, two
   skills disagreeing on the acceptance engine, `TestRestTemplate`, a wrong starter version, stale
   sketches) were found 2026-07-10 and fixed the same day. The *mechanism* remains a risk; the
   counter is the standing audit work order (`plan_docs/docs-drift-audit.md`) plus sketches that now
   point at their source-of-truth files. A human copying a skill snippet is safe only as long as
   that loop keeps running.
2. **Cross-session context now lives on-repo** ([`context.md`](context.md) + a thin `CLAUDE.md`,
   added 2026-07-10) — a second human can read it. Residual: it's a copy the maintainer's private
   memory must keep feeding; the contract header says durable learnings land here first, but
   nothing enforces it.
3. **A green build used to be able to lie in three places** — all closed 2026-07-10 (§2.2, §6
   item 2): acceptance now enforces an executed-scenario floor from `report.json`, `jsTest`
   preflights Node ≥ 21 (declared in `.nvmrc`/`engines`), and both `mcp/` servers' tests are in
   `verifyAll` (which CI now runs verbatim). Residual: the merge gate is still convention — no
   branch protection.
4. **Untyped step glue.** Acceptance steps POST raw `Map`s (so specs compile before controllers
   exist) — great for red-first agents, but it costs IDE navigation and rename safety across 32
   step files.
5. **History archaeology is confusing**: persona-signed reviews under one identity (the stale
   HANDOVER.md that compounded this was deleted 2026-07-10).

### For an AI agent

**Pros**

1. **Ground truth displaces hallucinated requirements.** The executable spec (plus the seam spy: "the
   model's context contained no vote signal") specifies even *invisible* contracts an agent could
   never recover from the UI.
2. **A cheap verification gradient.** Milliseconds (tier 0) → seconds (tier 1/2) → one Spring context
   (acceptance) matches an agent's iterate-verify loop; the deterministic LLM double makes an LLM app
   hermetically verifiable at all — no keys, no quota, no model flakiness.
3. **Skills are retrieval-free context** for exactly the knowledge that isn't derivable from code,
   auto-loaded at the right moment.
4. **Plan docs are cross-session memory and a coordination protocol** — a fresh session resumes
   mid-feature from the status header; parallel sessions pre-claim resources in writing.
5. **Shortcut behaviour is structurally blocked**: no mock library to reach for, and JTE
   precompilation turns view-layer hallucinations into compile errors before any test runs.

**Cons**

1. **Convention volume is a context-window tax**: touching acceptance plausibly means two overlapping
   ~350-line skills + the feature file + the plan doc + the requirements section it cites.
2. **Skill drift produces confidently-wrong agents** — an agent trusting a stale snippet reproduces
   the deprecated pattern with high confidence; drift compounds because the next agent copies it.
3. **Tier discipline is honor-system.** Tier membership is a `@Tag` the author picks; tolerated
   exceptions (`ContextLoadsTest` — a `@SpringBootTest` tagged tier2; `Clock.systemUTC()` inside
   tier-2 fakes) are precedents agents will pattern-match on.
4. **Silent-pass holes were agent-widenable** — a session that broke the Cucumber suite selector
   used to green the build with all 153 scenarios unrun. Closed 2026-07-10: zero executed scenarios
   now fails the task with an explicit message (and the count is printed on every run, so gradual
   `@wip` creep stays visible). The lesson generalises: any *new* gate an agent adds needs its own
   "would red actually show?" check.
5. **Duplicated FK-safe wipe lists** (`DatabaseResetHooks` wipes 11 tables; tier-1 classes curate
   their own shorter lists) are a per-migration landmine — it has already caused one cross-class
   leak flake (`68a0748`).

---

## 5. What scales, what breaks

**Scales well:** the tier structure itself (growth lands where it's cheap — tiers 0+2 grew to 210
tests with zero slow-suite pressure, and the pressure valve "push enumeration down to Tier 2" is
pre-written); the seam pattern (three new integrations landed on the paved road); the `data-*` +
typed-template contract; and the plan-doc protocol, which is what already made 11-branch parallel
agent work possible.

**Watch list** (severity now → trigger):

| Risk | Now | Trigger | Cheapest counter |
|---|---|---|---|
| Acceptance wall-clock (serial by design: one context, per-scenario 11-table wipe, settle polling) | Medium | scenario growth; docs' own ceiling: "fine at ~63… becomes the thing nobody runs past a few hundred" — 158 executed today (now printed on every acceptance run) | enforce "one journey per feature, enumeration at Tier 0/2"; surface scenario count + runtime per PR |
| Silent-zero acceptance pass | ✅ Closed 2026-07-10 (executed-scenario floor from `report.json`; count printed per run) | a runner refactor that stops cucumber writing `report.json` would resurface it — the `doFirst` delete makes that red, not green | keep the floor beside the task; raise it if `@wip` creep ever matters |
| Skill/doc drift | Low–Med (five drifts fixed 2026-07-10; audit work order standing) | more parallel sessions; drift compounds by imitation | run `plan_docs/docs-drift-audit.md` quarterly; keep T2.7 as a standing PR checkbox; sketches carry source-of-truth pointers |
| Duplicated DB-wipe lists | Medium | every new migration (V20+) | one shared `wipeAll()` + a guard test diffing the list against `sqlite_master` |
| Flyway numbering under concurrent agents | Low–Med | 3+ concurrent branches needing migrations | a duplicate-V-number build check; keep the plan-doc reservation convention |
| Worktree/Gradle cache contamination (stale JTE served from shared `~/.gradle`) | Low–Med | more concurrent builds | encode `--no-build-cache` for JTE generation in build.gradle.kts instead of in one person's memory |
| SQLite single-writer + un-parallelisable acceptance DB | Low (correct PoC posture) | multi-user deployment | none now; the Tier-1 repo suite over real SQLite *is* the pre-built Postgres migration harness |

---

## 6. What else is needed (ranked)

Accepted investments — each is either a hole in what *green means*, a prose rule that should become
mechanical, or the missing feedback loop for real users. Nothing here adds a new tier or a second
seam class.

1. **Markdown link-URL sanitization + tests (S).** ✅ **Done — PR #92** (2026-07-10, same day it was
   found). The HTML-escaping half of the renderer's XSS firewall was tested; the link-destination
   half wasn't sanitized. Fixed with `sanitizeUrls` + an `http/https/mailto` allowlist, pinned by
   Tier-0 hostile-scheme cases and an acceptance scenario — see `plan_docs/markdown-rendering.md`
   §Security for the convention.
2. **Close the silent-green holes (S).** ✅ **Done — 2026-07-10.** Acceptance enforces an
   executed-scenario floor read from cucumber's `report.json` (with a `doFirst` delete so a stale
   report can't fake a pass — Gradle's `failOnNoDiscoveredTests` alone cannot catch a tag-filter
   regression since filtered scenarios still count as "discovered"); `jsTest` and the mcp tasks
   preflight Node ≥ 21 (`.nvmrc` pins 22, root `engines` declares it); both `mcp/` servers' tests
   run in `verifyAll` (`mcpGhTest`, `mcpShortcutInstall` + `mcpShortcutTest` — `npm ci` keyed on the
   lockfile); and compose no longer overrides the image's `verifyAll` command, so the CI stage list
   can't drift from the gate. Every guard was proven to go red before landing.
3. **Centralise DB reset/seeding in `testsupport/` (S).** One canonical FK-safe table registry +
   `wipeAll()` used by hooks and every Tier-1 class, plus a guard test against `sqlite_master`.
4. **Opt-in LLM provider contract task (M).** The one thing the hermetic suite cannot prove is that
   `claude -p` / the OpenAI endpoint still emit what the parsers assume — and envelope drift is a
   *proven* incident class here (reasoning-leak, think-token wrinkle). A non-gating `providerContract`
   task runs the real adapters and refreshes the Tier-0 canned envelopes.
5. **Konsist architecture tests at Tier 0 (S–M).** Mechanise the prose rules: no `Instant.now()`
   mid-stack, `domain/` imports nothing from Spring, tier-2 classes are `@SpringBootTest`-free,
   doubles implement IO ports only. These are plain JUnit — they slot into the existing tier model.
6. **A prod-error surface (S–M).** Structured event-id logging is built and tested but nothing
   consumes it. Persist ERROR events to SQLite and render on `/admin/stats` — the first consumer,
   needed the day the first outside user hits a bug the owner didn't witness.
7. **Recurring docs-drift audit (S).** ✅ **Done — 2026-07-10.** The five live drifts were fixed and
   the audit is now a standing, re-runnable work order at
   [`plan_docs/docs-drift-audit.md`](../plan_docs/docs-drift-audit.md) (claims → verify against the
   tree → drift table → fix; first run logged as the worked example). Rerun quarterly or after any
   build-wiring/doctrine refactor.
8. **Scoped mutation testing (M).** PIT on tiers 0+2 only (plain junit-jupiter — the recorded
   PIT/Cucumber blocker doesn't apply), on-demand, as the gate proving an assertion migrated safely
   out of acceptance.

**Considered and rejected** (with the trigger that would flip them): coverage measurement (first
external contributor → diff-coverage only), performance/load rig (multi-user deploy or observed
`busy_timeout` contention), accessibility automation (when a Playwright layer exists anyway), visual
regression (permanently — it opposes the `data-*` redesign-survival contract), flaky-test quarantine
(permanently — fix flakes by design, as T2.4 did), dependency-update automation (honour the T3.5
deferral; do flip on free security-only alerts).

---

## 7. Provenance

Produced 2026-07-10 by a 12-agent research workflow: four parallel mappers (architecture, census,
organisation, history), four critique lenses (human, AI, scale/risk, gaps), three adversarial
verifiers (seam claim, gate claims, numbers), and a completeness critic. Two of fifteen headline
numbers failed independent re-derivation and were corrected (tier-1 test count 137→136; test LOC
9,643→9,663); three folklore claims were qualified or refuted before publication ("one seam",
"blocks a merge", "fails if @wip passes"). Treat this file like the skills: **it drifts** — re-run
the numbers before quoting them in six months.
