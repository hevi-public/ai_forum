# Audit Remediation Plan — Tier 1 (now) & Tier 2 (cheap insurance)

**Date:** 2026-06-25. **Lens:** single-user, local-only PoC. **Execution model:** this plan is implemented
in a **separate thread**; the author of this doc is the **supervisor/reviewer**. The worker implements one
task at a time, reports back per the protocol at the bottom, and the supervisor reviews before the next task.

Deferred/by-design items are in [audit-deferred-tier3-tier4.md](audit-deferred-tier3-tier4.md). Honour the
project's testing rules throughout — see the **bdd-tiered-testing**, **sqlite-spring-jdbc**, and
**cucumber-spring-bdd** skills. **Mock only at the one LLM seam.** Every new behaviour gets a test at the
lowest tier that can prove it.

**Pre-flight (worker, before any task):** confirm a clean baseline by running the suite green —
`docker compose run --rm build` (runs `generateJte compileKotlin jsTest tier0 tier1 tier2 acceptance`).
If baseline is red, stop and report; do not build on red.

---

## TIER 1 — Protect local data & make failures legible

These bite a single user with no second party required: losing/corrupting your own DB, or the app breaking
confusingly. Highest value first.

### T1.1 — Implement (or remove) the backup that `backups.enabled` already promises
**Severity: High.** The single biggest real risk for a PoC you care about.

- **Problem:** `aiforum.backups.enabled: true` exists in `application.yml` / `application-prod.yml` /
  `application-dev.yml`, is asserted off under test by `ProfileGuard.kt:24`, and is *exposed* by
  `DiagnosticsController.kt:19` — but **no code performs a backup** (no `VACUUM INTO`, `@Scheduled`,
  `Files.copy`). The flag implies protection that does not exist. The prod DB is a single file at
  `~/.ai_forum/data/aiforum.db` with WAL and no snapshot.
- **Supervisor decision (locked 2026-06-25): IMPLEMENT** (a snapshot is cheap and the data is the whole
  point of the tool). Save directory: **`~/.ai_forum/backup`** (singular).
- **Approach (implement):**
  - New `@Component` `SqliteBackup` in `com.aiforum.config` (or a new `backup` package). Gated on
    `@ConditionalOnProperty("aiforum.backups.enabled", havingValue = "true")` so it never wires under test
    (test sets it false; `ProfileGuard` already enforces that).
  - Mechanism: `VACUUM INTO '<dest>'` via `JdbcTemplate` — WAL-safe online snapshot producing a clean,
    consistent single-file copy. Destination: **`~/.ai_forum/backup/aiforum-<UTC-timestamp>.db`** (reuse
    `SqlitePath` for `~` expansion; create the `~/.ai_forum/backup` dir if absent). `VACUUM INTO` requires the
    dest path NOT already exist — timestamped names guarantee that.
  - Schedule: add `@EnableScheduling` (a small `@Configuration`), run on startup once + daily
    (`@Scheduled`). Use the injected `Clock` (do not call `Instant.now()` directly — the codebase injects
    `Clock`, see `ClockConfig`).
  - Retention: keep the last N (e.g. 7); delete older snapshots. Make N a property
    (`aiforum.backups.keep: 7`).
  - Timestamp source: `Clock` is injected, but `@Scheduled` cron + filename need a real instant — use the
    injected `Clock.instant()` for the filename so tests can pin it.
- **Tests (Tier 1 — real SQLite, no LLM):** new `SqliteBackupTest` under `tier1`. Point it at a temp
  SQLite DB with a couple of rows, invoke the backup method directly, assert: (a) a snapshot file appears
  at the expected path; (b) opening the snapshot and querying returns the rows (it's a valid DB); (c)
  retention deletes the oldest when count exceeds `keep`. Follow `sqlite-spring-jdbc` DB-setup conventions.
- **Acceptance:** snapshot file produced & readable; retention trims; nothing wires under the test profile
  (`ProfileGuard` still passes); suite green.

### T1.2 — Add transaction boundaries to multi-statement writes
**Severity: High.** One framework move closes a cluster of partial-write / race risks.

- **Problem:** **zero `@Transactional` in the codebase.** Each `jdbc.update` auto-commits independently, so
  multi-statement units are non-atomic. A crash or `SQLITE_BUSY` mid-operation leaves half-applied state in
  the owner's own DB.
- **Wiring note (verify, don't assume):** there is **no custom `DataSource` bean** (datasource is Spring
  Boot autoconfigured from yaml), so `DataSourceTransactionManagerAutoConfiguration` provides a
  `PlatformTransactionManager` and Boot enables `@Transactional` **without** an explicit
  `@EnableTransactionManagement`. Worker must confirm a transaction actually rolls back (see test) rather
  than assume the proxy is active.
- **Proxy caveat:** `@Transactional` only applies on calls **through the Spring proxy** (external calls).
  All target methods below are called from another bean (service/controller), so the proxy is in play.
  Internal self-calls (e.g. `editBody` → `addRevision`) correctly run inside the outer method's
  transaction — that's the desired behaviour, leave them as plain calls.
- **Apply `@Transactional` to (confirmed multi-write units):**
  - `CommentRepository.deleteSubtree` (`CommentRepository.kt:294`) — 3 batch DELETEs + per-id DELETE loop.
  - `CommentRepository.deleteByThread` (`CommentRepository.kt:315`) — same shape.
  - `CommentRepository.editBody` (`CommentRepository.kt:120`) — seed idx0 + addRevision + selectRevision.
  - `GenerationService.regenerate` — `addRevision` (seed) + `addRevision` (new) + `selectRevision`.
  - Audit `GenerationService.retry` and the owner-node-then-summon create path while here; wrap any that
    issue >1 write that must be atomic. Report which you wrapped.
- **Explicitly NOT needed:** `CommentRepository.toggleStar` (`:181`) is **already atomic** — the flip is a
  single SQL `CASE` update; the trailing `SELECT` only reads back the value for the return and is a benign
  display read for a single user. Leave it; note it in the report so the supervisor knows it was considered.
- **Tests (Tier 1):** add a rollback test proving atomicity — e.g. drive `deleteSubtree`/`editBody` into a
  forced mid-unit failure (inject a `JdbcTemplate`/repo seam that throws on the Nth statement, or provoke a
  constraint violation) and assert **no partial mutation** survived (row counts unchanged). Use
  `assertThrows` + post-state assertions. Keep the fault at a real boundary (in the spirit of the existing
  `FailingCommentRepository` pattern), not a mock of internals.
- **Acceptance:** transaction manager confirmed active (rollback test fails without `@Transactional`,
  passes with it); no partial state after a forced failure; existing repo Tier-1 + acceptance tests green.

### T1.3 — Cycle/depth guard on the recursive CTEs
**Severity: Medium.** A single bad `parent_id` write turns a tree walk into an infinite loop (a hang, not a
graceful error).

- **Problem:** `ancestorPath` (`:221`, `UNION ALL` up `parent_id`), `descendantCount` (`:249`), and
  `subtreeIdsDeepestFirst` (`:331`) have no cycle/depth bound. Nothing in the schema prevents
  `parent_id == id` or a 2-cycle.
- **Approach:** add a recursion-depth counter to each recursive CTE and bound it
  (`WHERE <counter> < 10000` — comfortably above any real thread depth). Pattern:
  `WITH RECURSIVE x(id, lvl) AS (SELECT id, 0 … UNION ALL SELECT …, lvl+1 … WHERE lvl < 10000)`.
  (Switching `ancestorPath` to `UNION` instead of `UNION ALL` also breaks a cycle but changes semantics on
  legitimately repeated ids — prefer the explicit depth bound for all three, it's uniform and obvious.)
- **Tests (Tier 1):** construct a small cycle directly in the test DB (insert two comments, then `UPDATE`
  their `parent_id`s to point at each other — bypassing the app's acyclic invariant), call each CTE method
  inside `assertTimeoutPreemptively` (e.g. 2s), and assert it **terminates** (returns/throws) rather than
  hangs. Note: this requires writing rows that the app would never create — that's intentional, it proves
  the guard.
- **Acceptance:** all three CTE methods terminate on a cyclic graph; normal-tree results unchanged.

### T1.4 — Honest failure UX: server `@ControllerAdvice` + client htmx error handling
**Severity: Medium.** This is the daily-friction one — LLM calls fail, and today the user gets a stuck
spinner / a Whitelabel HTML page swapped into a fragment slot, with no feedback.

- **Problem (two halves):**
  - **Server:** no `@ControllerAdvice` / `@ExceptionHandler` anywhere. An uncaught exception returns the
    default Whitelabel error *page* — which htmx swaps into a fragment target, corrupting the view.
  - **Client:** no global `htmx:responseError` / `htmx:sendError` listener. A failed poll/regenerate/`+1`
    leaves the `hx-disabled-elt` button permanently disabled (the swap that would re-enable it never
    arrives) and the spinner spinning.
- **Approach:**
  - Add a `@ControllerAdvice` that, for htmx requests (`HX-Request` header present), returns a small error
    **fragment** (a `.kte` matching the existing `fragments/` style) with an appropriate status, instead of
    the Whitelabel page. Non-htmx requests can keep default handling for now.
  - Add a global `htmx:responseError` + `htmx:sendError` listener (in `app.js` or a small dedicated module
    consistent with the existing pure-core/glue split) that re-enables the triggering element and surfaces
    a non-blocking notice (toast/inline). Keep logic in a `*-core.mjs` if there's anything unit-testable
    (e.g. "given an error event targeting element X, it should be re-enabled").
- **Tests:**
  - **Acceptance (Cucumber):** a scenario where the LLM seam is programmed to fail (`ScriptableLlmClient`
    `Fail`) and the response is asserted to be the error *fragment*, not a whole error page — assert against
    a stable `data-*` hook per the jte-spring-kotlin convention.
  - **JS (Tier-0 `*-core.mjs`):** if you extract a core function, unit-test the re-enable/notice decision.
- **Acceptance:** forced LLM failure yields a clean inline error fragment + a re-enabled control (no stuck
  spinner); htmx fragment contract preserved; suite green.

---

## TIER 2 — Cheap insurance (do when convenient)

Latent at PoC scale or one-line changes. Low risk, do after Tier 1.

### T2.1 — Bind the server to loopback *(promoted from the Tier-3 auth cluster — the one cheap win worth doing now)*
**Severity: Low effort / removes the only real network exposure.**
- **Change:** set `server.address: 127.0.0.1` in `application-prod.yml` and `application-dev.yml`
  (config-only). Do **not** touch `application-test.yml` (the acceptance suite's `RANDOM_PORT` /
  `TestRestTemplate` already use loopback; leave it unperturbed).
- **Rationale:** for a local single-user tool this converts the "no auth / binds `0.0.0.0`" Critical into a
  non-issue without building auth. Full auth stays deferred (T3.1).
- **Tests:** none needed (config). Smoke-verify the app still serves on `127.0.0.1:8080` (prod) /
  `8081` (dev). Confirm acceptance suite still green (it shouldn't be affected).
- **Acceptance:** app reachable on loopback, not on the LAN IP; suite green.

### T2.2 — Test `OpenAiImageDescriber` (close the one untested IO seam)
**Severity: Low (coverage integrity).**
- **Problem:** `OpenAiImageDescriber.kt` (RestClient POST, base64 data-URI, vision request shape, inline
  response parse `choices.firstOrNull().message.content`, error paths) has **zero tests** — its siblings
  `OpenAiLlmClient` and `OpenAiResponseParser` are tested.
- **Approach:** add a Tier-1 test mirroring `tier1/OpenAiLlmClientTest.kt` using `MockRestServiceServer`.
  Cover: (a) happy path → caption returned; (b) empty `choices` → `VisionUnavailableException`
  (`OpenAiImageDescriber.kt:84`); (c) non-2xx → `VisionUnavailableException` (`:54`).
- **Acceptance:** three cases pass; mirrors the established `OpenAiLlmClientTest` shape.

### T2.3 — Bound the generation thread pool
**Severity: Low (latent for single user) — one-line change.**
- **Problem:** `InFlightGenerations.kt:49` uses `Executors.newCachedThreadPool()` — **unbounded** workers,
  each doing an LLM call *and* DB writes against a 5-connection Hikari pool over single-writer SQLite. A
  single user rarely fans out hard, so this is latent, but the fix is trivial.
- **Approach:** replace with a bounded pool (e.g. `newFixedThreadPool(N)`, N≈4, or a
  `ThreadPoolExecutor` with a bounded queue) keeping the existing daemon-thread factory + `generation-N`
  naming. Keep `corePoolSize`/lazy-start behaviour reasonable (the comment at `:46` notes the Tier-2 unit
  test constructs an instance that never submits — ensure no threads spin until first `submit`). Leave
  `reset()` / `@PreDestroy shutdown()` semantics intact.
- **Tests:** existing `tier2/GenerationServiceTest` must stay green. No new test strictly required;
  optionally assert the factory still names threads `generation-*`.
- **Acceptance:** bounded pool; existing async/cancel tests green.

### T2.4 — De-flake the wall-clock poll in `GenerationServiceTest`
**Severity: Low (CI reliability).**
- **Problem:** `tier2/GenerationServiceTest.kt:~258` busy-polls `System.currentTimeMillis() + 5_000` with
  `Thread.sleep(10)` waiting for an async POSTED comment — can flake on a slow box.
- **Approach:** await a `CountDownLatch`/`Future` the `ScriptableLlmClient` test double signals on settle,
  instead of wall-clock polling. Coordinate with `InFlightGenerations`' `done` latch if cleaner.
- **Acceptance:** test no longer time-dependent; green across repeated runs.

### T2.5 — Unique persona slug *(optional — include if cheap)*
**Severity: Low.**
- **Problem:** `persona.slug` / `handle` are not `UNIQUE`, but `findBySlug` (`PersonaRepository.kt:38`)
  assumes uniqueness (`firstOrNull`). Two same-named personas collide on profile links.
- **Approach:** add migration **`V16__persona_slug_unique.sql`** (next sequential version after V15) —
  `CREATE UNIQUE INDEX idx_persona_slug ON persona(slug)`. Add collision-suffixing in
  `PersonaRepository.insert` (append `-2`, `-3`… on conflict). **Pre-check:** if any existing DB already
  holds duplicate slugs the unique-index migration will fail — for a fresh PoC DB this is fine; note the
  caveat in the report.
- **Tests (Tier 1):** insert two personas named the same; assert distinct slugs and that `findBySlug`
  resolves each.
- **Acceptance:** distinct slugs enforced; migration applies on a clean DB; suite green.

### T2.6 — Composite index for the dominant read *(optional — perf, irrelevant at PoC volume)*
**Severity: Low.** Only if touching migrations anyway.
- **Change:** migration `V17__comment_thread_order_idx.sql` (after V16) —
  `CREATE INDEX idx_comment_thread_order ON comment(thread_id, depth, created_at)` to serve
  `threadComments` / `growableLeaves`' `WHERE thread_id … ORDER BY depth, created_at` without a sort.
- **Acceptance:** index present; suite green. (No behavioural test; pure perf.)

### T2.7 — Update the project skills to reflect the changes
**Severity: Low (keeps the skills a trustworthy spec).** The `.claude/skills/*` are written as the source of
truth for how this codebase works; the Tier-1/2 changes introduce patterns they don't yet describe. Do this
**last**, once the code has landed, so the skills document what actually shipped.
- **`sqlite-spring-jdbc`** — the main one. Add the new persistence patterns: the `VACUUM INTO` snapshot +
  scheduled-backup component (T1.1), the `@Transactional` boundaries on multi-statement repo writes and the
  Boot-autoconfigured `DataSourceTransactionManager` (no explicit `@EnableTransactionManagement`) (T1.2),
  and the depth/cycle guard on recursive CTEs (T1.3). If T2.5/T2.6 land, note the unique-slug index and the
  composite read index.
- **`bdd-tiered-testing`** — if any new test introduces a pattern worth codifying (e.g. the forced-rollback
  atomicity test for T1.2, or the `assertTimeoutPreemptively` cycle-termination test for T1.3), add it so
  the "one seam" guarantee and tier placement stay clear.
- **`jte-spring-kotlin`** / **`cucumber-spring-bdd`** — if T1.4 adds an error fragment + a failure-path
  acceptance scenario, document the new `fragments/` error view and its stable `data-*` hook, and the
  `htmx:responseError` contract the acceptance test asserts against.
- **Acceptance:** each skill touched matches the merged code; no skill describes a pattern that wasn't
  actually used. Cross-check against the existing memory notes ([[haip-stack-gotchas]], [[haip-routing]])
  for anything that should also be refreshed.

---

## Supervisor protocol (worker ↔ supervisor)

1. **One task at a time, lowest tier / highest value first.** Suggested order:
   **T1.1 → T1.2 → T1.3 → T1.4 → T2.1 → T2.2 → T2.3 → T2.4 → (T2.5, T2.6 if cheap) → T2.7 last.**
   T1.1 and T1.2 are the headline data-safety items — do them first. **T2.7 (skills) runs last**, after the
   code it documents has merged.
2. **Decision gates (ask the supervisor before coding):**
   - T1.1: **resolved — implement**, save dir `~/.ai_forum/backup`. No further gate.
   - T1.2: report the final list of methods wrapped (esp. whether `retry` / the create path were included).
   - T2.5/T2.6: confirm the supervisor wants the optional migrations before adding schema.
3. **Definition of done per task:** code + test at the correct tier + `docker compose run --rm build` green
   (the full pipeline, incl. `jsTest` and `acceptance`) + a short written report: what changed, which
   files, which tier the test landed in, anything surprising, and any follow-up spotted.
4. **Testing rules are non-negotiable:** mock only at the LLM seam; new behaviour gets the lowest-tier test
   that proves it; respect `ProfileGuard` (test profile → test DB, backups off). If a fix seems to need a
   second mock seam, **stop and report** — that's a design smell to discuss, not to push through.
5. **Do not** start Tier-3/Tier-4 work; if a Tier-1/2 fix surfaces a Tier-3/4 item, note it in the report
   and leave it for [audit-deferred-tier3-tier4.md](audit-deferred-tier3-tier4.md).
6. **Worktree:** all work happens on a feature branch in the worker's thread; the supervisor reviews the
   diff per task before the next one starts.

## Done-when (whole plan)
All Tier-1 tasks merged and green; Tier-2 tasks merged or explicitly deferred with reason; the
`backups.enabled` flag is either backed by a working snapshot or removed (no more implied-but-absent
protection); a forced LLM failure produces a clean inline error rather than a stuck spinner; the full
Docker pipeline is green.
