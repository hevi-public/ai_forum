# Cross-session context — the on-repo copy

> **Contract:** this file is the on-repo copy of cross-session memory. Any session (or human) that
> learns something durable — a convention, a gotcha, a feature landing — updates **this file**, not
> just private session memory. Private memory may cache it; this file is the record a second human
> can read. Convert relative dates to absolute. Last full sync: **2026-07-10**.

## What this project is

The **AI Forum** (forked 2026-07-18 from HAIP into `hevi-public/ai_forum`; direction shifting
toward AI chat): an owner-driven brainstorming forum where hand-authored AI personas reply
in a nested comment tree; per-branch context scoping is the differentiator. Single-user
PoC — no auth layer, security hardening deliberately deferred (see the 2026-06 audit in
`plan_docs/audit-remediation-tier1-tier2.md` / `audit-deferred-tier3-tier4.md`).

- Spec: `plan_docs/ai-forum-requirements.md` (§-numbered; Gherkin features cite the §s).
- Stack: Spring Boot 4.1, Kotlin 2.4, Java 21, Gradle 9.5, JTE 3.2 (`.kte`), SQLite + Flyway,
  Cucumber 7.34, JUnit 6, htmx + vanilla ES modules. No Hibernate, no mocking libraries.
- Gate: `./gradlew verifyAll` (jsTest + mcp gates + tiers 0–2 + acceptance). CI runs the identical
  command via `docker compose run --rm build`. jenv wires Java 21 — no `JAVA_HOME` prefix needed.
- Doctrine: `how-we-work/README.md` (testing + organisation), `.claude/skills/` (the four
  execution-ready skills), `plan_docs/` (one design doc per feature, status header on top).

## Working conventions

- **PR voices (user-approved 2026-06-25):** all GitHub activity posts under the single account
  **@hevi-public**; the AI voices sign to stay distinguishable. Implementer signs
  `— 🛠️ Forge · Claude implementation agent — posting under @hevi-public, distinct from the human
  owner and ⚖️ Assay (the reviewer).` Reviewer/supervisor headlines `## 🟢/🟡/🔴 Supervisor review`
  and signs as **⚖️ Assay**. The main/coordinator agent posts PR comments (subagents are blocked
  from external GitHub writes); workers push code and report back. Revisit when agents get separate
  GH accounts.
- **Worktree sessions (cost a real incident):** agent worktrees live UNDER the main checkout
  (`<repo>/.claude/worktrees/<name>`). Never `cd` to the main-repo path — edits/builds silently land
  on `main`. Run from the worktree cwd; verify with `git rev-parse --show-toplevel` +
  `git branch --show-current` before building. The ~50 worktrees share one `~/.gradle` whose build
  cache has a `generateJte` key bug that can restore a **stale precompiled JTE template** (a template
  change "doesn't take" in the browser) — build with `--no-build-cache` when that bites.
- **Delivery loop:** design in a `plan_docs/*.md` (status header first), slice it, one worktree per
  slice, PR reviewed by ⚖️ Assay, merge, then re-sync any skill the change invalidated (T2.7 —
  skills are a release artifact; see `plan_docs/docs-drift-audit.md` for the standing audit).

## Stack gotchas not encoded in a skill

The wiring traps that belong to a specific layer are already in the four skills (Flyway/SQLite ones
in `sqlite-spring-jdbc`, JTE ones in `jte-spring-kotlin`, Cucumber ones in `cucumber-spring-bdd`).
The cross-cutting ones a newcomer still needs:

- **htmx caches a form's request path** — mutating `hx-post` via `setAttribute` is ignored (shipped
  broken once, PR #52; no test tier catches it). Decide the URL at request time in a
  `htmx:configRequest` listener keyed off a `data-*` marker.
- **`app.js` is an ES module** importing unit-tested `*-core.mjs` modules — the frontend "tier 0"
  pattern: pure decision logic in `*-core.mjs` (node:test), DOM glue in the `.js`. Node ≥ 21 is
  required and now declared (`.nvmrc` pins 22; the `jsTest` Gradle task preflights it).
- **Headless `claude -p` silently denies permission-gated tools** (WebFetch etc.) — pre-authorise
  with `--allowedTools`; wired behind `aiforum.llm.web-fetch-enabled`. ⚠ Web fetch is enabled in
  dev+prod ahead of the deferred Docker jail (§12) — personas fetch the open web from the host.
- **Local models via LM Studio:** avoid Gemma (leaks inline reasoning into replies); use
  **Qwen3.5 9B with thinking off**. Strip/flag pipeline + debug profile documented in
  `plan_docs/local-model-reasoning-leak.md`.
- A stale dev `bootRun` may hold port 8081 from a prior session — verify boots on a spare port
  (`--server.port=8085`). Prod runs `./gradlew bootRunProd` (persistent DB at
  `~/.ai_forum/data/aiforum.db`); `bootRun` stays the throwaway dev DB.

## Feature state (2026-07-10)

Everything below is merged to `main` and green under `verifyAll` unless marked otherwise.

**Core loop (M1, done 2026-06-20/21):** thread + nested persona replies with per-branch context
scoping, depth budget + auto-grow, +1 voting with the owner-vote firewall, cancel/retry/sad-path
UX, composer (chips, Single↔Roomful, slash palette, @mentions), "Anyone" dispatcher routing,
vim-style keyboard nav (`nav-core.mjs`), persona seeding (Sol/Saul/Paul/Mira/Dana), dark mode,
admin stats dashboard + drill-downs (`/admin`, `/admin/stats` routing health).

**The post is a thread-backed root node** (V7 `thread.body`): the OP is structural root of the
comment tree; create auto-summons Whole Topic + Anyone. Comment-title and move-to-topic deferred.

**Persona model:** abilities tags + fixed dials (V9) — an LLM composes `system_prompt` from them at
create/edit (dials rendered as prose, not numbers); the router uses traits for dispatch
(`plan_docs/persona-traits-routing.md`, routing observability V15 + `/admin/stats` parse-miss rate).

**Markdown bodies** (GFM → HTML via commonmark, server-side highlight.js on GraalJS): two-half XSS
firewall — `escapeHtml` + `sanitizeUrls` with `data:` excluded (`plan_docs/markdown-rendering.md`).

**Image attachments** (V13): caption-only LLM injection; `ImageDescriber` is the second IO port;
content-addressed `ImageStore` under `~/.ai_forum` (`plan_docs/image-attachments.md`).

**Reply revisions** (V14): regenerate/edit creates revisions, lazy-materialised, ‹2/3› switcher;
`comment.body` is the denormalised selected revision.

**Comment quotes** (V18, 2026-06-27): citation links between comments, forward-quoting slice built;
backlinks/selector-cone deferred (`plan_docs/comment-quotes.md`).

**GitHub PR threads** (V19, "Discuss this PR"): PR → forum thread the room summarises; Slice 1
built (mapping table, `gh` seam, `/github` Discuss button); Slice 2 (comment discussion) pending
(`plan_docs/github-pr-threads.md`). `GitHubClient` is the fourth IO port.

**AG-UI live token streaming** (2026-06-26): AG-UI-shaped SSE events, hybrid SSR+SSE additive over
the existing poll; `AguiWire` is the single spec-coupling point; deferred: `event_log` persistence.

**Error toast UX** (T1.4): toast-only on non-2xx + `HX-Trigger`, reload-persistent with 24h TTL.

**Audit status:** Tier-1+2 remediation done — PRs #77–#87 merged 2026-06-26 (⚖️ Assay reviewed).
Tier-3/4 items deliberately deferred (single-user PoC): `plan_docs/audit-deferred-tier3-tier4.md`.

**2026-07-10 (this branch):** the three how-we-work ✗ findings fixed — silent-green build-gate
holes closed (acceptance scenario floor, Node preflight, `mcp/` gated, compose runs `verifyAll`),
skills re-synced to the four-port reality, this file + `CLAUDE.md` created, stale `HANDOVER.md`
deleted.

**2026-07-18 (fork):** repo forked from `hevi-public/HAIP` to `hevi-public/ai_forum` to pursue an
AI-chat direction. First fork commit: origin repointed, MIT LICENSE adopted from the new repo's
initial commit, data home renamed `~/.haip` → `~/.ai_forum` (prod DB, backups, image store — fresh
DB unless you copy the old dir over), `aiforum.github.repo` now `hevi-public/ai_forum`, and the
HAIP naming swept out of packages/launch configs/docs (`HAIP_design/` kept as the design source).

## Open threads / near-term

- Composer branch-context controls (`plan_docs/composer-branch-context-controls.md`) — designed,
  not built: surface the context-scope control + include-siblings toggle; settle sibling semantics.
- GitHub PR threads Slice 2 (comment discussion).
- Quote backlinks / selector-cone (deferred from V18).
- Phase 1.5 headliner: **Artifacts** (spec §3/§15) — needs a design spike first (no settled
  protocol for how `claude -p` signals an artifact; sandboxed render decision, §12).
- Docker jail for persona tool use (§10–§12) — still deferred; web-fetch note above applies.
