# Cross-session context — the on-repo copy

> **Contract:** this file is the on-repo copy of cross-session memory. Any session (or human) that
> learns something durable — a convention, a gotcha, a feature landing — updates **this file**, not
> just private session memory. Private memory may cache it; this file is the record a second human
> can read. Convert relative dates to absolute. Last full sync: **2026-07-26**.

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

**2026-07-26 (Ambient Slice 4b, V27):** what a member is *into* now moves
(`plan_docs/ambient-slice-4b.md`). Each member holds up to four **mutable interests** — short prose
phrases in `persona_interest` (V27), each carrying its own `seeded|owner|drifted` provenance — and a
weekly pass reads what that member actually wrote (`CommentRepository.exchangesSince`, reused verbatim,
zero new repository reads), asks the model whether it has moved on from one open interest toward
something else, and **swaps one for one**. Every swap is audited in `interest_change` with the dropped
phrase, its **old provenance**, and the cited engagements snapshotted as prose; the owner reads them at
**`/admin/interests`**, reverts what they disagree with, and sees a room map beside the log. Own prefix,
own gated scheduler pair (`aiforum.interest-drift.enabled`, **default off**, Sun 04:30), ungated
`POST /admin/interests/drift` — the only way the acceptance suite can reach the slice.

Seven things worth knowing before touching it:

- **Interests deliberately do NOT feed `AmbientGate.relevance`** — the rejection that shaped the slice.
  That gate *counts* ability-tag hits, multiplies by talkativeness and argmaxes across the roster, so a
  model writing tags there writes its own airtime: the cut reward economy with no column named *score*.
  `AmbientGate`, `AmbientTickService` and `PersonaRouter.rosterLine` are untouched. Drift changes **what**
  a member says, never how often it gets to say it.
- **There is no `core` column, and that is a decision.** The immutable core is `descriptor` +
  `abilities` + `dials` + whichever interests the owner pinned; per-interest `owner` provenance is what
  makes it **per-persona** (requirements §6.2). Enforced four ways: the pass holds no write path to
  `PersonaRepository.update` (a Tier-2 fake **fails the test if `update` is called at all**), `owner`
  rows are skipped before any spend, two *separately named* parse refusals, and a stated prompt frame.
- **The no-numbers guardrail is now in the DATABASE, and the scoping is the interesting half.**
  `CHECK (source = 'owner' OR interest NOT GLOB '*[0-9]*')` binds the rows the *pass* writes and lets the
  owner type "web3". Unscoped it would throw inside `PersonaController.edit`, where interest writes run
  **before** the prompt logic — so a refused phrase would abort the save and cost the owner their
  descriptor and dial edits. Note what shipped: the parse and the owner path agree with the CHECK by
  sharing `Interests`' two **constants**, not by calling one validator; the design said otherwise.
- **The window is `persona.interests_judged_at`, stamped on `Drifted` AND `Unchanged`, never on a
  refusal or a seam failure.** S4a's cost defect avoided from day one: the judge is *told* to answer
  `NONE` when nothing moved, so `NONE` is the steady state of a settled member and writes no audit row.
  `PersonaRepository` never learns the column exists; `markJudged(id, at)` takes `at` from the instant
  the evidence was **read**, and `markJudged(…, null)` clears, which is what lets a revert reopen it.
- **Generation-time injection only; a drift buys no recompose** — deliberately the opposite of S4a.
  `InterestProse.block` is appended in `GenerationService.withPersonaContext` (renamed from
  `withStances`) after the stance block and one step **before** `ContextAssembler`, so the vote firewall
  stays a pure function. The block takes `List<String>` — no provenance — so a model can never learn
  which of its interests are protected. `ComposerPrompts`, `PersonaSpec`, `inputsChanged` and
  `persona-form-core.mjs` needed **no change at all**.
- **Convergence is made visible, never measured.** `TopicSpread` (pure) renders a phrase and the members
  holding it **by name** — more-than-half is shared, exactly-half is not, exactly-one is sole — with no
  count keyed to a member, no aggregate on the repository, and no ability to fire anything. Pinned at
  Tier 0 (structurally, on the instruction's parameter list) and Tier 2 (the judge prompt is
  byte-identical over a converged and an un-converged roster). It detects **lexical** convergence only.
- **Five traps this build paid for, all cheap to re-hit:**
  - **A door normaliser that is not idempotent is a data-corruption bug.** `Interests.clean` strips one
    matched quote pair; the parse cleans once and `upsert` cleaned again, so a doubly-quoted answer was
    compared as one phrase and stored as another — missing the already-held and owner-pinned refusals,
    overwriting the owner's row, and leaving a revert that would delete the owner's own phrase. The rule:
    the value you hand back must be a fixed point of whatever the door applies.
  - **Seeding is not idempotent when the key contains the text.** The stance seeder is safe because its
    key is `(from,to)` and evolution updates in place; the interest key includes the phrase, so a phrase
    the pass DROPPED is genuinely absent and a per-phrase presence check re-inserts it every boot. The
    third seed phase is first-seed-only **per member** for that reason.
  - **`COLLATE NOCASE` folds case and nothing else** — `" agents"` and `"agents"` are two primary keys, a
    duplicate the DDL cannot see. `upsert`/`delete` clean at the one door every writer comes through.
  - **Cucumber matches on step TEXT, not on the Given/When/Then keyword.** Two authoring `Given`s
    resolved to `@Then` assertions and silently asserted against a member nobody had configured.
    Authoring steps got their own wording (`… was authored with abilities {string}`).
  - **Profile URLs are the SLUG (V5)**, and **Gherkin does not interpret `\n` inside a quoted string** —
    a two-line `DROP:`/`TAKE:` answer is a docstring (`the LLM will respond with the answer:`).
- **Two tests that could not fail were found and replaced** — worth stealing as a review lens. One
  asserted a string was absent that was never passed in (true against every possible implementation,
  *including* the future one that grows a roster parameter); it is structural now, pinning
  `InterestDriftPrompts.instruction`'s parameter list. The other compared `phrasesOf` against the
  expression `phrasesOf` is defined as. Ask of every new assertion: *what implementation would make this
  red?*

The judgment is re-read at the judgment site, not from the pass's snapshot (S4a's `df4c183` lesson —
hence the `no-interests-mid-pass` / `all-owner-authored-mid-pass` skip reasons), candidates are ordered
oldest-window-first so a cap rotates instead of starving the tail, the four writes are one
`TransactionTemplate.execute` (injected explicitly — `@Transactional` on a self-invoked private method
compiles, reads as a guarantee and does nothing), and `StubLlmClient` gained an `__interest_judge__`
branch so a stub demo does not look broken. Suite 213 → 234 scenarios (19 in the new
`interest_drift.feature`, one appended to `owner_controls_firewall.feature`, one to
`config_guardrails.feature`); 71 tier-0/1 tests with V27 and the five pure objects, 12 more for the stub
branch, and a 19-test Tier-2 orchestration class. The ambient fan-out flake was fixed in this slice too
— see the read-skew entry below.

**2026-07-26 (Persona memory, V28):** each member now holds a private, thread-shaped memory tree
(`plan_docs/persona-memory.md` — off-map §6.3, its own name, not an S-number): prose **records**
written by the weekly Memory Scribe pass (third instance of the evolution-pass template,
`aiforum.memory`, **default off**, ungated `POST /admin/memory/run`) or authored by the owner on
the profile, plus an optional owner-only **root** (storage only — injected NEVER this slice, the
recorded owner call). Records resurface deterministically when the scoped context shares their
words (binary whole-word overlap + one associative hop, ≤3 matched + parents, ≤5 total, injected
as the fourth `withPersonaContext` block live at settle) and every scribe write is audited at
`/admin/memory` with revert — which deletes but deliberately does NOT roll the watermark back.
Suite 237 → 263 scenarios; tier 0/1/2: 397/243/156. The slice then went through a
**seven-dimension adversarially-verified review** (0 blockers, 0 majors, 9 minors, 10 nits — all
addressed in the follow-up commit, plan doc §10.7), which is where two of the learnings below come
from.

Durable learnings, the close-out audit's and the review's yield (plan doc §10.3, §10.7):

- **The NUL divergence class — a THIRD way validated-vs-stored splits.** S4b left us two (a
  non-idempotent door cleaner; cleaning at two sites). The third is two measuring sites agreeing
  on every input except one: Kotlin's `trim`/`isBlank`/`\s+`/`codePointCount` all pass U+0000
  through, while SQLite's `length()` counts characters only **up to the first NUL** — so a
  NUL-opening body passed `MemoryText.validate`, then tripped V28's `CHECK (length(body) > 0)`
  mid-write as an uncaught 500 on the owner's own form. **Only a LEADING NUL is that loud**: a
  MID-NUL body satisfies both CHECKs (`length('a'||char(0)||'b')` is 1) and would have stored
  SILENTLY with an undercounted length — the divergence itself rather than a crash, and the worse
  half precisely because nothing goes red. Refuse on the character, never on its position. The
  rule: when a design says two sides "agree by construction", name the input domain the agreement
  holds over — and refuse NUL at any door whose bound a SQLite length CHECK backstops. (Only NUL
  truncates the count; refusing other control characters is over-rejection, its own defect class.)
- **A doctrine file may not carry an impossibility claim nobody re-ran.** The review found two in
  `sqlite-spring-jdbc`, both introduced by this slice: "FK adds are impossible" and "CHECKs are
  `CREATE TABLE`-only". Both are folk versions of much narrower truths, re-tested at the shell and
  against the shipped xerial 3.53.2 before rewriting: a **new** column MAY carry a `REFERENCES`
  clause and it IS enforced (only table-level/composite FKs, and FKs on existing columns, are
  refused — and the new column must default to NULL); a CHECK **can** be retrofitted, it just
  validates the whole table and aborts on the first violator, and the table-level
  `ALTER … ADD CHECK` syntax is missing on older engines. Skills are read *instead of* checking, so
  an overstatement there costs a future session a full table rebuild on a live file for what one
  `ADD COLUMN` does. The decisions those claims propped up (V28's root CHECK at table birth) all
  survived the correction — the rationale was wrong, not the call.
- **"Mutation X reddens test Y" is a claim, and the only way to know is to run it.** The close-out
  audit reads code against docs; it cannot see a ledger entry that is accurate about the code and
  false about the *fixture*. Persona memory's §7 credited an acceptance scenario with a mutation it
  could not detect (the root body shared no word with the fixture, so dropping the picker's
  `kind='record'` filter left it unmatched and green), and a second entry named a mutation that
  reddened nothing at all. Both were found by execution, not reading. Fifteen pins were
  mutation-verified at build time and were all correct; the two that were not are exactly the two
  that were wrong. Related, same review: a class the audit patches at ONE site usually has more —
  the lexicographic-ISO cut was fixed at one of four and shipped live at the other three.
- **The records-only parent-candidate rule is the root-protection pattern.** The design review's
  one blocking finding: every surface that offers parent candidates — retrieval's hop, the
  scribe's letter list, the profile picker, the form endpoint, the repository belt — draws from
  `kind='record'` rows ONLY, so the owner-only root can never be dragged into a prompt by the
  associative hop nor cascade-deleted by a re-author, even off hand-SQL rows. Protect a privileged
  row by making it structurally absent from every candidate set, not by filtering at one site.
- **Letter protocol + snapshot re-read is the shape for model-chosen references.** Parents are
  offered as letters A–Z over a snapshot; the selector resolves against THE SNAPSHOT THE MODEL
  SAW, the resolved id is then re-verified against current rows at write time (the bed019fe rule,
  applied at both sites), and unknown/out-of-set/vanished selectors degrade to top-level with
  their own logged events — the paid record is never refused over a stale reference, and letters
  keep digits out of a model-facing protocol.

- **S6 — the feed front page** ✅ built 2026-07-27 (V29, `plan_docs/ambient-slice-6.md`). Two views over
  one front page — activity-sorted thread **cards** and a reverse-chronological **activity stream** —
  chosen by a toggle persisted in a one-row `owner_pref` table. Suite 263 → 283; tier 0/1/2 439/265/156,
  jsTest 100.
  **The slice map is now complete.** Five durable learnings, each bought by running something:

  - **JTE does NOT escape `>` in ATTRIBUTE context** (measured on `OwaspHtmlTemplateOutput`; it does in
    body context). Since `Html.threadRowAttr`'s `<[^>]*…[^>]*>` cannot cross a literal `>`, any
    free-form prose in a `data-*` value truncates the tag and makes **every hook after it unreadable** —
    silently, on a page four feature files probe. **Rule: `data-*` values carry ids, slugs, enums,
    integers or explicit `"true"`/`"false"`; prose is CHILD TEXT.**
  - **JTE DROPS a Boolean-valued attribute when it is false.** That is how `open="${threads.isEmpty()}"`
    yields the collapsed composer `home_rail` asserts. A boolean hook must be `.toString()`, or the
    false case becomes unassertable and its test silently vacuous.
  - **A page-wide `Html.contains` probe is a test waiting to stop failing.** `HomeRailSteps.railShows`
    asserted a comment body *anywhere* on the page, justified by a KDoc claiming the home page renders
    bodies only inside one rail box — which a card excerpt falsifies. It was scoped through a new
    `Html.railBox` **before** the markup moved. When tightening a probe, verify NEGATIVELY: mutate the
    markup so the old probe would have passed, and confirm the new one reddens.
  - **A mutation-ledger row is a hypothesis until it is run.** Three of S6's were wrong: one claimed a
    mutation reddens "#1 only" (21 scenarios redden), one claimed a distinction the step shape cannot
    deliver, and one named a mutation that reddens with *or* without the fix, so it proved nothing.
    All three were caught by executing them, and corrected in place.
  - **Under a fixed test `Clock`, every seeded row shares one `created_at`.** An ordering scenario
    written naively passes while asserting an arbitrary UUID order, then breaks later on an unrelated
    id change. `TestData` gained per-call defaulted `agoSeconds`; a **global monotonic stagger was
    refused** because it moves the unread boundary under every scenario that seeds a comment then reads.

## Open threads / near-term

- **What's next, from the record rather than invention.** Persona memory landed 2026-07-26
  (`persona-memory.md`, V28), closing the item named on 2026-07-21 as what follows S4b — so **S6,
  the feed-style front page, is the only slice left on the direction doc's map** (§9); its open
  question (§11.6) is unanswered and `home_rail` / `empty_and_unread` are already earmarked for
  rework. Still deliberately open in §11.5 (S4b's leftover, untouched by memory): whether manual
  create + the room map discharge the requirements' diversity lever, or the *synthesised,
  centre-of-mass-aware* newcomer is a slice of its own. Memory's own deferred aspiration
  (graph-walk recall, FTS/embeddings, root INJECTION) has no slice and no owner call yet.
- **Memory recall is categorically dead for an unspaced-script persona** (found by the persona-memory
  review, 2026-07-26; characterized, not fixed — plan doc §10.4). `MemoryRecall`'s ≥5-code-point word
  floor plus a tokenizer that splits only on `NON_WORD` means a CJK-language member matches on
  verbatim recurrence of a whole glued run and nothing else: individually meaningful CJK words are
  1–4 code points (日本語 is 3) so they all fall under the floor, and unspaced prose offers no split
  characters — while Latin members recall normally. The script-change boundary logic `WholeWords` was
  extracted to carry is therefore unreachable from `wordsOf` for its own motivating case. Nothing
  reddens (it is deterministic, and no fixture is CJK). Deliberately out of scope for that slice
  because `wordsOf` feeds a matcher shared with `AmbientGate`, so redefining "word" moves ambient
  gating for every non-Latin ability string too. A per-script floor is the shape of the fix and it is
  a slice of its own — pick it up with any recall rework (FTS/embeddings would dissolve it).
- ~~**`GenerationController`'s `/room` fragment carries the flake that `ThreadController.renderThread`
  was fixed for**~~ — ✅ **fixed 2026-07-30.** It read only the in-flight registry, which a node LEAVES
  the moment it settles (persist, then evict), so a summon whose drafts all settled before the first
  poll answered exactly like a summon that produced nothing: the poller dropped itself and the owner
  sat on a thread with no replies until the next load. Three things the fix is worth remembering for:
  - **The union now lives in one place — `web/ThreadReplies.read`** (registry first, then the DB,
    dedupe by id) — because the read ORDER is the invariant and it had been stated once per surface.
    Both `ThreadController.renderThread` and the room poll read through it; a third surface must too.
  - **`isSummoning` is the only answer to "is more still coming" — content is not.** The fix's first
    cut decided poller-vs-content on whether the union was empty, which the review caught as a
    blocker: an owner note posted mid-routing is a POSTED row, so the union goes non-empty while the
    room has produced *nothing*, and the terminal response swapped the poller away before the drafts
    landed — **the same bug, re-entered from the other side.** The old registry-only read had hidden
    this, because a registry entry cannot exist until routing registered it, so "non-empty" silently
    meant "routing concluded"; widening the read dropped that implication. Both surfaces now gate on
    the routing window: while `isSummoning` holds, the poll re-emits the poller and only the poller.
  - **The page had the same hole, and closing it fixed a test barrier for free.** `thread.kte` rendered
    the poller only under `summoning && replies.isEmpty()`, so an owner who posted a note mid-routing
    and reloaded got a page that never polled. It now renders one reply list with the poller inside it
    whenever a summon is routing — which also makes `data-empty-state="summoning"` observable on a
    non-empty thread, and *that* is what makes `awaitThreadSettled` a real barrier on a Discuss thread
    (which starts non-empty, so the hook never rendered and the helper waited for nothing).
    Verified by mutation: a 500ms delay in the summon worker fails that scenario before the change and
    passes it after.
  - **The room poll retargets the whole reply list** (`HX-Retarget: .reply-list` + `HX-Reswap`) once
    routing has concluded, rather than replacing the poller in place — the response carries persisted
    rows including that mid-wait note, so an in-place swap would leave the browser holding it twice.
    Un-pinnable end-to-end: no tier drives a browser, so acceptance pins the *header*, and the
    no-duplication it buys is a DOM property nothing in `verifyAll` executes. **That gap is exactly
    where the blocker lived** — green CI over a broken poller, found by reading htmx's source against
    the templates in review, not by any test.
  - **`ScriptableLlmClient.Behavior.HangUntilReleased`** exists now: the deterministic way to hold the
    ROUTING phase open in a scenario. `HangUntilCancelled` cannot serve there — routing registers no
    draft, so the cancel endpoint has no node id to reach, and a parked routing worker survives
    `inFlight.reset()`. The gate is open by default and `reset()` opens it as a seatbelt.
  - **A controller-tier test reaches what acceptance cannot here.** `tier2/web/RoomPollTest` constructs
    the real controller with `MockHttpServletResponse` and pins all three answers — poller while
    routing (headers absent), terminal + `HX-Retarget`/`HX-Reswap` once it concludes, empty poller when
    a summon produced nothing — deterministically, no worker, no waiting. The htmx headers are the half
    no browserless suite can watch LAND, but *that they are set, and on which branch*, is a plain
    controller fact; "no tier can pin this" was half true and cost a blocker. `ThreadRepliesTest`
    likewise pins the deterministic halves (dedupe, `anyPosted`, `isEmpty`) — only the read ORDER is a
    genuine race, and the class doc says so rather than implying the tests cover it.
  - **The cost the fix accepts, so it isn't rediscovered as a bug:** the terminal response replaces the
    whole reply list, and a summon can now be routing on a thread that already has replies (the ambient
    tick comments on live threads; PR ingestion summons under a posted discussion). So when routing
    concludes, any *client-only* state inside that list is destroyed — text typed into an inline
    composer, an open regenerate/delete `<details>`, a drafting node's accumulated SSE text. Nothing
    carries `hx-preserve`. Judged the better trade — the alternative is the room staying invisible —
    but if it ever bites, `hx-preserve` on the inline composer is the cheap half.
  - **A summon that BEGINS after the page rendered still puts no poller there.** The page reads
    `isSummoning` once, at render; an ambient tick on a thread the owner already has open therefore
    delivers nothing until a reload. Pre-existing and unfixed — noted because the fix above makes the
    poller look more universal than it is.
  - **A page-wide `Html.contains` over a fragment carrying an OOB rail is the same trap S6 recorded.**
    The branch index renders a snippet of each posted body, so "the fragment carries this reply" stayed
    green with the list regressed to drafts-only. `Html.replyNodes` scopes the probe to the reply
    articles; verified by mutation (render `replies.drafting` → the scenario reddens, and did not
    before).
  - **Widening the endpoint moved a test helper's meaning**: `GenerationSettle.awaitRoomDrafts` →
    `awaitRoomReplies`, since the fragment now returns settled nodes too. That made it *wrong* for a
    thread that starts non-empty — a Discuss thread posts the PR discussion synchronously, so the
    first poll returns that comment and the helper waits for nothing; `GitHubPrThreadSteps` moved to
    `awaitThreadSettled`. Widening a read widens every caller's definition of "has something landed".
- **A summon parked between `beginSummon` and `register` escapes the between-scenario reset**
  (found by the PR #10 review under an artificial worker delay, 2026-07-31; **pre-existing, not fixed**).
  `InFlightGenerations.reset()` cancels and joins registered *holders*, and routing registers none — so a
  summon still routing at scenario end keeps a pool thread and bleeds its LLM calls into the next
  scenario's spy (`trigger_modes.feature` fails with a leaked call sequence). Invisible at normal speed
  because routing against the fake returns instantly. If it ever bites for real, the fix is a
  routing-phase token the reset can trip, not a longer await.
- **S4b follow-ups (none blocking, all verified against the code).**
  1. **`coarseFloor` is dead under the shipped config.** The floor is the oldest watermark and only when
     *every* member has one, but a member below the engagement floor is skipped before any judgment and
     is therefore never stamped — so one permanently quiet member (Dana, `talkativeness: 2`) keeps it
     `null` for good and every run materialises every persona-to-persona exchange, bodies included. The
     Tier-2 test that pins the floor pre-stamps both members, a state production never reaches.
  2. **Revert rolls the window back to `MAX(changed_at)`** — a post-LLM *write* instant — into a column
     whose contract is the pre-query *read* instant. This is S4a follow-up 2 above, repeated verbatim in
     a new slice; every fixture clock is fixed, so no test can see it.
  3. **`aiforum.interest-drift.max-interests` is bound and read by nothing.** The ceiling the owner meets
     is `PersonaController.MAX_INTERESTS = 4`; changing the yml key changes no behaviour.
  4. **`PersonaInterestRepository.sharedInterests()` returns persona IDs while the room map renders
     NAMES.** `InterestAdminController` maps them and documents why, but `id == name` for every seeded
     member, so no test at any tier can catch a regression — only a hand-created member whose id and name
     diverge would pin it.
  5. **An owner cannot pin an existing phrase verbatim.** A resubmitted, unchanged phrase keeps its
     source by design, so only a *rewritten* phrase is stamped `owner`; the field note was corrected to
     say so. Whether a verbatim pin should exist is an owner call.
  6. **Thread titles are the one live cross-member channel.** Every member writes in the same ambient
     article threads, so on a `source: feed` install one week's headline reaches all seven judge prompts
     — which makes `InterestDriftService`'s absolute "no cross-member channel" claim wider than the code.
  7. **The observability contract has no `LogCapture` anywhere**, and `PersonaController` still has no
     test class; §10.4 of the slice's plan doc is the full list of unpinned claims.
- **S4a follow-ups (PR #6 review, second pass — none blocking, all verified against the code).** The
  owner-clobber race the second review found was fixed in the PR; these were not:
  1. `StanceEvolutionService.evolveEdge` cites the FULL window (`renderCited(exchanges)`) while the judge
     only ever saw `takeLast(12)`, so `/admin/stances` presents evidence the model never read and the
     `cited` TEXT is unbounded. `renderCited`'s KDoc claims the opposite — a one-line fix plus the doc.
  2. `revert` stamps `judged_at` from `MAX(changed_at)` — a post-LLM **write** instant — into a column
     whose contract is the pre-query **read** instant (`RelationStanceRepository.markJudged` KDoc), so a
     revert can move a window FORWARD past exchanges nothing ever judged. Fix: carry the run's `readAt`
     on the audit row, or clamp the revert so it can only move the stamp back.
  3. `PersonaPromptRefresher.refresh` still has `personas.find` outside its guard, so it can throw out of
     a method whose KDoc promises it never does — one `SQLITE_BUSY` there skips every holder queued
     behind it and logs a successful run as `stance.evolve.failed`.
  4. `coarseFloor` requires a watermark on EVERY `persona_stance` row, but owner-authored and
     never-conversing edges are skipped before any stamp — so on the shipped 42-edge seed the floor is
     null forever and the unbounded exchange read it was added to prevent still happens. Read cost only.
  5. **The dev DB will never get `idx_stance_change_edge`.** V25 was edited in place after that DB had
     already applied it; `application-dev.yml` sets `validate-on-migrate: false`, so Flyway neither
     complains nor re-runs it. A V27+ `CREATE INDEX IF NOT EXISTS` is the repair (per the
     `sqlite-spring-jdbc` skill's rule that an applied migration is immutable).
  6. `recomposed=` in the run's log line counts refresh ATTEMPTS — `refresher.refresh`'s Boolean is
     discarded — so the summary overstates how many prompts were rewritten.
  7. A pass rejected by the single-flight guard 303s to the same page as a pass that ran, and its `0` is
     indistinguishable from "ran, changed nothing". The owner has no way to tell.
  8. Stale doc claims: `StanceChangeRepository`'s class KDoc and `lastStandingChangeAt`'s KDoc and V25's
     header all still describe `lastStandingChangeAt` as half the window boundary, but the pass reads it
     **zero** times (its only caller is `revert`); `StanceEvolutionProperties.cron` still says "read by
     nobody" though `/__diag` now reads it; `ambient-slice-4a.md`'s head-of-queue residual is framed as a
     null-window effect when any oldest edge with an unusable answer does it (and at cap=1 the whole graph
     freezes with `changed=0`).
  > Test-suite gaps worth knowing, same review: no fake can fail a *framing* read, so neither the
  > run-level catch nor the `finally` that releases the guard is exercised — a latched-guard mutation
  > ships green. `PersonaPromptRefresher` has no test at any tier (the only double overrides `refresh`
  > wholesale). The `nullsFirst` half of `byWindowAge` has no failing test. The `.feature` diff in the fix
  > commit is comment-only, so acceptance gained no coverage for the root cost defect.
- **The ambient fan-out flake was a read-skew bug in `ThreadController.renderThread`, fixed 2026-07-26.**
  The page did two reads at two instants — the DB tree first, the in-flight registry second — and a
  settling node crosses between them, because the worker persists the row and *then* evicts the registry
  entry. A node that persisted AND was evicted in that gap appeared in **neither** read: gone from the
  page entirely, neither drafting nor posted, as though that persona never spoke. The poller then saw no
  drafting node, called the room quiescent, and the count assertion read a room with a member missing.
  Reading the registry first closes it: the same node then appears in both, and the existing dedupe drops
  the stale draft view in favour of the settled row.
  This is a **UI bug, not only a test bug** — an owner loading a thread at the wrong millisecond would
  have seen a reply that generated fine silently absent until the next load.
  How it was found, since three earlier guesses were wrong: the count assertion in `TriggerModeSteps` now
  reports the whole room on failure (every node with its state, the summoning flag, and the LLM spy's call
  sequence). The first failure after that printed `nodes=[…=posted, …=failed]` with
  `llmCalls=[Moderator, sol, vex, pike]` — all four calls made, pike's node nowhere — which named the
  mechanism immediately. Keep that instrumentation: `expected 2 but was 1` cost three sessions.
  **Not pinned by a test.** A unit pin wants `ThreadController` (11 constructor deps, private
  `renderThread`), and the acceptance scenario only catches it intermittently. If this area is touched
  again, the read order is the invariant: **in-flight first, DB second.**
- Superseded, kept for the record: **the earlier diagnosis that this was the settle deadline was wrong.**
  `trigger_modes.feature` → "An ambient article tick fans out to the room; one persona fails, the rest
  still post" fails intermittently at `TriggerModeSteps.kt:37` with **expected 2 posted, got 1** — the
  settle helper returns a room that is only partly settled, so the count assertion reads a snapshot
  rather than an outcome. History: 2026-07-19 (`cb9601c2`), main at `811f430`, twice on the S4a branch,
  and once locally *after* `43ba812` raised `GenerationSettle.TIMEOUT_MS` from 5s to 20s; three
  full-suite runs straight after that were clean, so call it roughly one run in four locally.
  **`43ba812`'s commit message claims the deadline "is what was left" — that claim is wrong**, since a
  4× deadline still fails. The raise is harmless (a stuck-draft guard, spent only when something is
  wedged) but it is not the fix.
  What has been ruled out, with evidence: cross-run contention (`runs-on: ubuntu-latest`, so the two
  runs a commit starts are separate VMs); scripted-response leakage between scenarios
  (`DatabaseResetHooks.resetFakes` clears both `script` and `received` at `@Before(order = 10)`);
  late draft registration (`GenerationService.summonAsync` registers *every* plan before `endSummon`
  clears `summoning`, so the room poller cannot observe a no-drafts gap mid-round).
  What is left to look at: what the thread page renders for a node between `settleOne` persisting it
  and `markDone` evicting it from `InFlightGenerations`, and whether a FAILED settle leaves any
  rendered trace at all. Next move is to instrument the settle (log the body each poll sees when the
  count is short) rather than to raise the deadline again.
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
