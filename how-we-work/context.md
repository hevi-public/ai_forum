# Cross-session context — the on-repo copy

> **Contract:** this file is the on-repo copy of cross-session memory. Any session (or human) that
> learns something durable — a convention, a gotcha, a feature landing — updates **this file**, not
> just private session memory. Private memory may cache it; this file is the record a second human
> can read. Convert relative dates to absolute. Last full sync: **2026-07-19**.

## What this project is

The **AI Forum** (forked 2026-07-18 from HAIP into `hevi-public/ai_forum`; direction: the
**AI-driven forum** — spec Fork B, scheduled article collection + ambient persona activity;
`plan_docs/ai-driven-forum-direction.md`): today an owner-driven brainstorming forum where
hand-authored AI personas reply in a nested comment tree; per-branch context scoping is the
differentiator. Single-user
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
- A stale dev `bootRun` may hold port 8020 from a prior session — verify boots on a spare port
  (`--server.port=8085`). Prod runs `./gradlew bootRunProd` (persistent DB at
  `~/.ai_forum/data/aiforum.db`); `bootRun` stays the throwaway dev DB.

## Feature state (2026-07-19)

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
(mapping table, `gh` seam, `/github` Discuss button) and Slice 2 (discussion ingest, gh-tools
for personas) both merged — PR #91 landed 2026-07-13 (`plan_docs/github-pr-threads.md`).
`GitHubClient` is the fourth IO port.

**AG-UI live token streaming** (2026-06-26): AG-UI-shaped SSE events, hybrid SSR+SSE additive over
the existing poll; `AguiWire` is the single spec-coupling point; deferred: `event_log` persistence.

**Error toast UX** (T1.4): toast-only on non-2xx + `HX-Trigger`, reload-persistent with 24h TTL.

**Audit status:** Tier-1+2 remediation done — PRs #77–#87 merged 2026-06-26 (⚖️ Assay reviewed).
Tier-3/4 items deliberately deferred (single-user PoC): `plan_docs/audit-deferred-tier3-tier4.md`.

**2026-07-10 (this branch):** the three how-we-work ✗ findings fixed — silent-green build-gate
holes closed (acceptance scenario floor, Node preflight, `mcp/` gated, compose runs `verifyAll`),
skills re-synced to the four-port reality, this file + `CLAUDE.md` created, stale `HANDOVER.md`
deleted.

**2026-07-18 (fork):** repo forked from `hevi-public/HAIP` to `hevi-public/ai_forum` to pursue a
new product direction. First fork commit: origin repointed, MIT LICENSE adopted from the new repo's
initial commit, data home renamed `~/.haip` → `~/.ai_forum` (prod DB, backups, image store — fresh
DB unless you copy the old dir over), `aiforum.github.repo` now `hevi-public/ai_forum`, and the
HAIP naming swept out of packages/launch configs/docs (`HAIP_design/` kept as the design source).

**2026-07-18 (direction defined):** the fork's purpose written down — the **AI-driven forum**
(spec **Fork B** activated): on a schedule, personas collect interesting articles from the web,
post them, and comment on each other's threads; traits + **qualitative** relations evolve over
time; the owner participates as a peer. Direction doc: `plan_docs/ai-driven-forum-direction.md`
(success criteria, ambient-loop architecture, `ArticleSource` as the fifth IO port, slice map
S1–S6, acceptance-spec delta over the 45 features, subscription-terms/cost/caching envelope —
ambient runs headless `claude -p` on the subscription, few ticks/day, stateless per-run calls).
Spec bumped to v1.16 (Fork B decision-log rows + pointer; header version re-synced). Dev port
moved **8081 → 8020** (`application-dev.yml` + `.claude/launch.json`; prod stays 8080).

**2026-07-19 (Ambient Slice 1, V20+V21):** the ambient skeleton is in
(`plan_docs/ambient-slice-1.md`): `ArticleSource` is the **fifth IO port** (fixture
`StubArticleSource` in prod, `ScriptableArticleSource` in test), `AmbientTickService` posts one
persona-authored article thread per tick (OP = summary + link, **no LLM call of its own** — the
auto-summon round is the discussion; round-robin author pick until S2's relevance gating),
`POST /admin/ambient/tick` is the ungated manual trigger, the `@Scheduled` caller is gated
`@Profile("!test")` + `aiforum.ambient.enabled` (**default off** — the flag kills only the
scheduler, never the button). `thread.author_id` (V20) is a **plain attribution string, no FK**
(the `comment.author_id` precedent — bylines survive persona deletion); `ambient_run` (V21) logs
every tick (`thread_id` FK `ON DELETE SET NULL`, `cost_usd` NULL until per-run cost capture) and
surfaces on `/admin` (ambient-runs / owner-threads / persona-threads tiles + `/admin/ambient`
drill-down). `ambient_tick.feature` was written RED-first; suite now 168 scenarios. Note: the
seeded roster is **seven** personas (Ducky + Quackers joined the original five).

**2026-07-19 (Ambient Slice 2, V22):** ambient commenting is in (`plan_docs/ambient-slice-2.md`):
the tick alternates action preference by run-count parity (even=post, odd=comment) with
cross-fallback, still ≤1 executed action. Comment action: `findActive(10)` × roster, excluding
the thread's author persona and personas already POSTED in the thread, scored by the pure
`AmbientGate` (**talkativeness × relevance ≥ 5**; relevance = word-boundary ability-tag hits in
thread title+OP; no LLM — §6.4/§10 posture). `talkativeness` is the fifth `Dials.KEYS` entry
(read paths default missing keys — stored JSON never self-heals). **The fuel decision:** the
ambient comment is born with `DepthBudget.AMBIENT_GRANT = 2` via `summonAsync(initialBudget=…)`
and its settle triggers `autoGrow` via the new `onSettled` hook — a bounded unattended
mini-discussion (child 1 → grandchild 0), never re-granted; the owner stays the only renewable
fuel (steering lever intact). Ambient failure retry = owner-as-peer; the tick never retries.
`ambient_run.action` (V22) distinguishes post/comment runs (`data-action` on `/admin/ambient`).
Seeds now carry per-persona `abilities` + `dials` (first-seed only) — without ability tags
relevance is always 0 and no ambient comment can ever fire. Suite 168 → 181 scenarios.
Build hygiene: every Gradle test task now starts from a fresh `build/aiforum-test.db`
(`freshTestDb` in build.gradle.kts) — acceptance leftovers used to FK-block tier1's per-class
cleanup lists when running tiers after acceptance locally.

**2026-07-19 (Ambient Slice 5, V23):** the real article source is in
(`plan_docs/ambient-slice-5.md`): `FeedArticleSource` pulls from an **owner-curated https-only
RSS/Atom allowlist** (`aiforum.ambient.feeds`), selected by `aiforum.ambient.source: feed`
(**`stub` stays the default** — the `aiforum.llm.provider` `@ConditionalOnProperty` template;
the test profile structurally never wires a real source, which is the security rail). Parsing is
a hand-rolled hardened `FeedParser` (Tier-0): DTDs rejected outright (kills XXE + entity bombs),
1 MiB byte cap before parse, 10s per-feed daemon-FutureTask deadline, http(s)-only item links,
summaries HTML-stripped + truncated (§4 content decision: link + excerpt, bodies never stored).
URL dedupe via `article_seen` (V23, marked on yield; stub bypasses dedupe by design). The port
gained defaulted `emptyReason()` → no-op runs now carry distinguishable details ("feeds returned
no items" vs "all N feed items already seen"), assertable via the new `data-detail` hook on
`/admin/ambient` rows. `/__diag` gained `ambientSource` + `ambientFeedCount`. Settles
direction-doc open question 4: **allowlist-only**; WebSearch stays deferred; prompt-injection
via feed text remains the documented §12 residual until the jail. Suite 181 → 184 scenarios.

**2026-07-21 (Ambient Slice 3, V24):** qualitative relations are in
(`plan_docs/ambient-slice-3.md`). `persona_stance` (V24) holds **directed, free-text** persona→persona
stances — no numbers anywhere, which is the standing guard against re-importing the cut reward economy.
Both endpoints are real FKs with `ON DELETE CASCADE` (a stance is *live state*, unlike a comment byline,
which is history and survives its subject) — so deleting a persona now also drops everyone's stances
*about* it, and "delete + let re-seed recreate" is no longer a safe way to refresh a descriptor. The
`source` column (`seeded|owner|evolved`) is written but **read by nothing yet**: it exists now because it
cannot be backfilled, and S4a must be able to tell the owner's own wording from a seed it may rewrite.
Injection happens in three places: generation (`GenerationService.assembleContext` appends
`StanceProse.block` to the persona's system prompt **before** `ContextAssembler`, so the vote firewall
stays a pure boundary; **outgoing edges only**, filtered to personas actually present in the scoped
context — which makes BRANCH_ONLY narrow the stance set for free), the composer
(`ComposerPrompts.instruction`, with a don't-enumerate steer), and the dispatcher
(`PersonaRouter.relationsBlock`, scoped to edges pointing at someone **already talking** — the full
42-edge graph in every routing call would swamp the skills/topic signal). Admin: stances render on the
profile (`data-stance-to`) and are edited on the persona edit form (`stance_<id>` fields, blank =
retract, written as `source=owner`); `stance_*` is deliberately inert in `persona-form-core.mjs` and
excluded from the `inputsChanged` backstop, so a stance edit is free. New `POST /personas/recompose`
rewrites every stored prompt **fresh** (`prior = null`) from current traits + stances — the explicit,
paid way to pick up a framing change on a live DB, since seeding never clobbers a stored prompt. The
three hardcoded prompts (`ComposerPrompts.SYSTEM`, `PersonaRepository.systemPromptFor`,
`PersonaRouter.systemPrompt`) and the seven seed descriptors/abilities were reframed from "the owner
poses questions and the room replies" to the ambient article forum. Suite 184 → 199 scenarios.

> Gotcha found the hard way: JTE parses `@param` declarations itself, and a generic carrying a comma
> (`Map<String, String>`) breaks that parse with a misleading "Unexpected end of template expression"
> pointing at an unrelated line. Pass a prepared `List<SomeView>` instead. Also: `MigrationPipelineTest`
> pins the highest applied migration version — bump it with every new migration.

**2026-07-25 (Ambient Slice 4a, V25):** relation stances now **evolve** (`plan_docs/ambient-slice-4a.md`).
A pass reads the persona→persona exchanges in the comment tree since the last standing change, asks the
model to judge their **tone**, and rewrites the affected `persona_stance` rows — auto-applied, no approval
queue (direction-doc §11.5). Every change is captured in `stance_change` (V25) with old text, **old
provenance**, and the cited exchanges snapshotted as prose; the owner reads old→new at **`/admin/stances`**
and reverts what they disagree with.

Five things worth knowing before touching it:

- **The interaction read must include top-level comments.** S2's ambient comment lands on someone else's
  article thread with `parent_id NULL`, so its addressee is `thread.author_id`, not a parent row.
  `CommentRepository.exchangesSince` covers both branches; a plain reply→parent self-join would look
  correct in tests and almost never fire in the live forum. Persona-ness is decided against the **roster**
  (a string heuristic would silently admit `gh:` authors).
- **S4a runs are deliberately NOT recorded in `ambient_run`.** `AmbientRunRepository.count()` drives the
  ambient tick's post/comment parity *and* its round-robin author index — extra rows there would silently
  change which persona posts which article.
- **The no-numbers guardrail is now executable.** `StanceJudge.parse` refuses any digit-bearing answer
  outright: the one place a number could enter the relation model is the judge's output, and a Tier-0 test
  pins the refusal. "Pushed back twice" is prose; "trust 4/5" cannot reach the table.
- **Revert undoes, it does not freeze** — it restores the old text *and* the old `source`, so a reverted
  seeded row goes back to `seeded` and may drift again. Freezing is what the persona edit form's `owner`
  stamp is for.
- **The evolution window is PER EDGE and lives in `persona_stance.judged_at` (V26)**, not one global
  watermark and not the audit table. Two traps here, both found by review and both worth knowing before
  touching this code:
  - A **global** boundary is quietly lossy — one pair's success moves it for every pair that failed, was
    capped out, or came back unusable in the same run, and their evidence is never judged again.
  - Keying the window off *recorded changes* is quietly expensive — the judge is told to repeat a
    standing view unchanged when nothing moved, so **`Unchanged` is the steady state of a settled pair**
    and it writes no audit row, so the window never advances and that pair re-buys the same judgment every
    run forever. The watermark is therefore stamped on any **usable** verdict (changed *or* unchanged) and
    deliberately **not** on a refusal or a seam failure, which must stay retryable.
  Candidates are ordered by window age so a cap rotates instead of starving the tail. Both properties have
  Tier-2 tests that fail against the wrong implementation — verified by mutation, not assumed.
- **Owner calls 2026-07-25:** auto-recompose on evolution (settling S3's staleness tension — extracted into
  `PersonaPromptRefresher`, shared with `POST /personas/recompose`), its own gated scheduler pair
  (`aiforum.stance-evolution.enabled`, **default off**, `/__diag` rail + config_guardrails scenario), and
  **no per-run cap by default** (`max-edges-per-run: 0`). That last one plus auto-recompose means a busy
  run costs one judgment per qualifying pair **plus** one compose per affected persona — the scheduler
  defaulting off is what keeps unattended spend opt-in. Suite 200 → 213 scenarios.

## Open threads / near-term

- **S4b — interest/trait drift**, then **persona memory** (§6.3, revived into the near-term roadmap at
  the owner's request 2026-07-21; currently has no slice or plan doc). S4b is now the next ambient slice,
  and it carries the convergence risk S4a deliberately left alone (§11.5's remaining open items: how
  convergence is measured, and manual newcomer injection as the diversity lever).
- Persona-voice OP upgrade still deferred (needs an OP failure lifecycle).
- **Feed-fetch socket timeouts** (Assay follow-up on PR #4): `FeedArticleSource`'s RestClient has
  no connect/read timeout — the tick thread is deadline-protected, but a truly hung socket parks
  the daemon worker until OS TCP timeout. Add client-level timeouts when next touching the file.
- Docker jail for persona tool use (§10–§12) — still deferred, but **urgency raised**: ambient
  web fetching is scheduled + unattended (direction doc §8); web-fetch note above applies.
- Composer branch-context controls (`plan_docs/composer-branch-context-controls.md`) — designed,
  not built: surface the context-scope control + include-siblings toggle; settle sibling semantics.
- Quote backlinks / selector-cone (deferred from V18).
- **Artifacts** (spec §3/§15) — still needs its design spike (claude -p emit protocol; sandboxed
  render, §12), now re-framed: ambient activity (Fork B) is what makes artifact "latest/top"
  listings meaningful, so Artifacts follows ambient S1/S2 rather than preceding them.
