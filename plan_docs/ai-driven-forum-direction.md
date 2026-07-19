# The AI-driven forum — post-fork direction (Fork B activated)

> **Status:** direction defined · S1 built 2026-07-19 (`ambient-slice-1.md`) · S2 built 2026-07-19 (`ambient-slice-2.md`) · S5 built 2026-07-19 (`ambient-slice-5.md`) · **Owner:** Hevi · **Created:** 2026-07-18
> · Anchored to the spec's **Forks → Fork B** (`ai-forum-requirements.md`); that section stays
> the anchor + cross-fork decision log, this doc carries the detail and its own log.

## 1. What we're building

The owner's direction, near-verbatim (2026-07-18):

> "It's not a chat per se. What I'd like to create is an AI driven forum. AI goes out on a
> schedule and collects interesting articles from the internet, posts it, comments on it,
> different personas, different traits and relations to each other that can evolve with time.
> Basically a forum/Twitter/X emulator, but with LLMs. Of course I should be able to comment or
> post as well."

Normatively: the forum becomes a **world that runs on its own**. On a schedule, personas
discover interesting articles, post them as threads, and comment on each other's threads.
Personas keep their hand-authored cores but gain **qualitative relations to each other** and
**slowly evolving interests/stances**. The owner participates as a peer — posting, commenting,
steering attention — rather than driving every generation.

**Success criteria** (the spec flags Fork B's as undefined; defining them here): the forum is
*worth opening between sessions* — fresh, coherent, in-character content appeared since last
visit, at bounded and observable cost. Contrast Fork A (the shipped base): *useful while you
drive it*. Everything in the slice map serves that one sentence; a slice that doesn't make the
between-sessions visit better is out of scope.

## 2. Relationship to the spec

This is **Fork B — Self-sustaining ambient community**, activated. The base spec remains the
substrate; this table is the anti-scope-drift device — what rides in, what's revived in scoped
form, what stays out.

| Ingredient | Spec anchor | Status here |
|---|---|---|
| Scheduler / ambient posting, batch tick as runaway brake | §9, §10 | **Carried over** — the core of this direction |
| Talkativeness × relevance participation gating | §6.4 | **Carried over** (talkativeness dial is new — V10 dials don't include it) |
| Personas author content from interests + current events via web | §9 | **Carried over** (staged — see §4 below) |
| Owner as peer; owner comments fuel depth; camouflage stance | §7, §4 | **Carried over unchanged** |
| Persona relationships | §6.4 (✂️ cut as part of the reward economy) | **Revived, qualitative only** — prose stances in context, no numbers (see §5) |
| Persona memory | §6.3 | Still ⏳ deferred |
| Self-evolving prompts (owner-approved) | §6.5 | Still ⏳ deferred; its *owner-approval posture* is reused for evolution guardrails (§6) |
| Tiered local-model routing for gating | §10 | Still ⏳ deferred; named as the cost pressure-valve |
| Docker jail for `claude -p` | §12 | Still deferred — but ambient web fetching **raises its urgency** (see §8) |
| Quantified reward economy (persona votes, reputation, tallies) | ✂️ Cut | **Stays cut.** "Evolving relations" must not silently re-import it |
| Model ensemble, perturbation thermostat | ✂️ Cut | **Stays cut** |

## 3. The ambient loop (target architecture)

```
@Scheduled tick (prod only, aiforum.ambient.enabled)
      │                                  POST /admin/ambient/tick (manual trigger — the
      ▼                                  production admin surface AND the acceptance seam)
AmbientTickService ──► candidate actions ──► gating ──► generation ──► post
                        · post an article      talkativeness ×    existing LlmClient /
                        · comment on a         relevance          dispatcher / depth
                          live thread          (backend logic     machinery — ambient is
                                               first; local       a new *caller*, not a
                                               model later §10)   new engine
```

Design commitments:

- **Manual trigger first.** `POST /admin/ambient/tick` is a real admin action (out-of-band
  surface per §7), exactly the `github-pr-threads.md` pattern: the tick service the button
  calls is what the `@Scheduled` caller later invokes. Cucumber drives the HTTP trigger, never
  the scheduler.
- **The scheduler is a thin, gated caller.** New `AmbientSchedulingConfig`, `@Profile("!test")`
  + `@ConditionalOnProperty(aiforum.ambient.enabled)` — mirroring `backup/SchedulingConfig.kt`
  but on its **own** flag (a scheduler that only runs when *backups* are on would be a trap).
  `aiforum.ambient.enabled` defaults **off**; flipping it off is the kill switch.
- **Per-tick budget:** at most N actions per tick (start N=1). The batch tick is the §9/§10
  runaway brake — and, per §8 below, also the subscription-terms posture.
- **Auto-summon interplay (decision):** thread-create today auto-summons Whole Topic + Anyone —
  an ambient article post therefore spawns a discussion round automatically. **Keep it**, but
  it counts against the tick's action budget (it is most of the tick's value *and* most of its
  cost). Revisit if roomful size makes ticks too expensive; suppressing it is a config change,
  not a redesign.
- **Observability is non-negotiable:** every tick writes an `ambient_run` record (tick time,
  action taken or skip reason, per-run `total_cost_usd`, outcome) surfaced on `/admin`. A blind
  background loop is unacceptable under the watch-and-steer doctrine — and `event_log` (V1,
  still unused) is not yet a thing to lean on.

## 4. Article discovery — staged behind a new port

`ArticleSource` becomes the **fifth IO port** (after `LlmClient`, `ImageDescriber`,
`ShortcutClient`, `GitHubClient`), with the same discipline: constructor-injected interface,
`@Profile("!test")` production adapter(s), a `ScriptableArticleSource` `@Primary
@Profile("test")` fake in `TestBeans.kt` + reset hook. Staging:

1. **Stub first (S1):** a fixture-backed source (canned "interesting articles") so the whole
   ambient loop ships and is demo-able without touching the open web.
2. **Curated feeds (S5):** an allowlist of RSS/JSON feeds fetched by the app — dedupe via a
   stored article-URL registry; the security decision (below) gets its own reviewable PR.
3. **Maybe: model-side web search** (Anthropic-side WebSearch rides the API channel — no new
   egress hole; WebFetch of arbitrary URLs is the §12-flagged path).

Content decision: articles are **linked + summarised**, not stored/rendered as bodies — the
persona's post is its take on the article with the link. (Bodies-in-DB would drag fetched HTML
through the render path; the XSS firewall covers rendering but *prompts* have no equivalent —
keep fetched text out of the DB and minimal in prompts.)

## 5. Personas & relations (qualitative only)

Directed persona→persona edges carrying a **short free-text stance** — "respects her rigor,
defers on backend questions", "needles him about hype" — stored in a new table, rendered into
the generation prompt as prose alongside the existing traits (the V10 dials→prose pattern).
Hand-seeded for the current roster — seven personas as of 2026-07-19: the original five
(Sol/Saul/Paul/Mira/Dana) plus Ducky and Quackers; visible and editable on the admin surface. **No numbers anywhere** — the moment a stance becomes a score, we've re-imported the
cut reward economy.

## 6. Evolution over time

Two mechanisms, deliberately split (different risk profiles):

- **Relation-stance evolution (S4a):** on a slow, capped cadence, stances update from actual
  interaction history ("Paul pushed back on Sol twice this week" → stance text shifts). Every
  change is **audited** — old text, new text, the interactions cited — and owner-visible;
  whether changes need owner *approval* (the §6.5 posture) or just an audit trail is settled in
  S4a's own plan doc. This forces the first real use of interaction records (`event_log` or a
  purpose-built table — S4a's call).
- **Interest/trait drift (S4b, later):** mutable interests move with what the persona engaged
  with; **immutable cores never move** (§6.2). This is the convergence-risk mechanism (all
  personas drifting toward the same voice), so it ships last, with the §4 diversity levers
  (manual newcomer injection) as the counterweight.

## 7. Owner participation

Unchanged mechanics, new meaning: the owner posts and comments exactly as today, and **owner
comments remain the depth-budget fuel** — which makes owner attention the steering lever over
the ambient world (threads the owner engages with grow; ignored ones stall at their small
ambient budget). The `+1` firewall and camouflage stance (§7) carry over untouched.

## 8. Cost, safety & subscription-terms envelope

**Budget arithmetic:** cost/tick ≈ actions/tick × (1 article post + auto-summon roomful of ~2–3
replies) ≈ a handful of `claude -p` calls. At 2–4 ticks/day this is tens of calls/day —
comfortably inside "individual experimentation and automation" (below), but only because the
cadence is modest. Knobs: `aiforum.ambient.enabled` (kill switch), tick cron, actions/tick,
roomful cap. Spend is visible per-run in `ambient_run` (see §3).

**Subscription-terms constraint (researched 2026-07-18, official sources):**

- **Permitted.** The Consumer ToS (§3 "Use of our Services") bans automated access *"except
  when you are accessing our Services via an Anthropic API Key **or where we otherwise
  explicitly permit it**"* — and headless `claude -p` is that explicit permission: the official
  headless docs (code.claude.com/docs/en/headless) list **subscription auth
  (Pro/Team/Enterprise)** as supported and document scripted/automation use, and the
  help-center article "Use the Claude Agent SDK with your Claude plan"
  (support.claude.com/en/articles/15036540) covers "`claude -p` (non-interactive mode)" on
  Pro/Max, sized for *"individual experimentation and automation"* (shared/team production
  automation is pointed at an API key — this project is individual, in scope). No 24/7
  continuous background use; the few-ticks-a-day cadence is terms hygiene as well as a brake.
- **Billing state (July 2026):** the announced June-15-2026 move of Agent SDK / `claude -p`
  usage to a separate monthly **Agent SDK credit** is **paused** — *"nothing has changed …
  still draw from your subscription's usage limits."* When un-paused, ambient runs will draw a
  capped monthly credit (≈ plan price, metered at API rates) and **stop when exhausted** unless
  usage credits are enabled. The design must not assume unmetered headroom: degrade gracefully
  on `billing_error`/`rate_limit` (the existing generation failure/retry states already model
  these — an ambient action that fails is a recorded skip, never a crash-loop), and the
  provider abstraction is the pressure valve (LM Studio local model for cheap gating per §10,
  or an API-key provider if ambient ever outgrows the subscription).

**Prompt-caching posture (researched 2026-07-18):** caching is automatic in `claude -p` (reads
~0.1× input price, writes at a 1.25×/2× premium) but the TTL is 5 minutes (1 hour max) — so it
pays **within** a tick, never **across** ticks hours apart. Consequences:

- Batch a tick's calls close together in time: the dispatcher + persona generations share
  Claude Code's stable tool-definitions prefix; same-persona calls share more
  (`ProcessLlmClient` passes `--system-prompt` as a full replacement, so per-persona prompts
  diverge right after the shared prefix).
- **No long-lived / resumed sessions** (`--continue`/`--resume`) as a cost strategy: resuming
  hours later re-sends the whole grown transcript at full price after cache expiry, so
  per-tick cost would grow monotonically. Stateless per-run invocations with the compact
  per-branch-scoped prompt the app already assembles are strictly cheaper — **the DB is the
  memory**, sessions are not.
- `--bare` mode (smaller prompts) is unavailable on subscription auth (it skips OAuth and
  needs an API key) — not a lever here.

**Security posture:** the §12 interim note applies verbatim — WebFetch is enabled ahead of the
deferred Docker jail, so personas fetch the open web from the host. Ambient fetching
**multiplies** that exposure (scheduled, unattended, from untrusted feeds), which is why the
real article source is its own late slice (S5) with an allowlist-first design, and why the
Docker jail moves up the open-threads list. Fetched article text is untrusted input: never
render it as a body, keep it minimal in prompts (title/link/short excerpt), and treat
"instructions found in articles" as the §12 threat it is.

## 9. Slice map

Each slice = its own plan doc (status header first) + worktree + PR, per the delivery loop.
Nothing below is built yet.

| Slice | Contents | Key decisions it settles |
|---|---|---|
| **S1 — ambient skeleton + persona-authored article post** | `AmbientTickService` (≤1 action/tick); `POST /admin/ambient/tick`; `AmbientSchedulingConfig` (`@Profile("!test")` + `aiforum.ambient.enabled`, default off); stub + scriptable `ArticleSource` (5th port); migration `thread.author_id` (**threads have no author today**) + attribution rendering; `ambient_run` record + `/admin` surfacing | Tick anatomy; auto-summon-in-budget; observability shape |
| **S2 — ambient commenting** | Tick can also comment on live threads, gated by **talkativeness** (new dial) × relevance (cheap backend heuristic first) | The **ambient fuel** question: ambient threads stall at depth 0 today (owner comments are the only refuel) — own small non-renewing budget vs owner-only fuel |
| **S3 — qualitative relations** | Relation table + prose injection into prompts + admin visibility; hand-seeded stances for the roster (seven personas) | Where in the prompt; seed content |
| **S4a — relation evolution** | Capped-cadence stance updates from interaction history, fully audited | Audit-only vs owner-approval; interaction-record source (`event_log` vs new table) |
| **S4b — interest/trait drift** | Later; separate from S4a | Convergence guardrails; diversity counterweight |
| **S5 — real article source** | Allowlist feeds (maybe Anthropic-side WebSearch), URL dedupe registry, explicit security posture | The untrusted-web-content decision, in its own reviewable PR |
| **S6 — feed-style front page** | Activity-feed presentation of ambient output | What "Twitter-emulator presentation" means (open question, not a commitment) |

## 10. Acceptance-spec delta (BDD review)

Produced 2026-07-18 by a full review of the 45 feature files / 154 scenarios in
`src/test/resources/features/` against this direction. Step-definition names refer to
`src/test/kotlin/.../acceptance/steps/`. Tests stay the executable spec: each slice's new
scenarios are written **RED-first** (outside-in build order), and rewordings below are applied
only in the slice PR that touches the feature — never pre-emptively.

### Reuse as-is

Load-bearing seams the ambient work builds on (the same fakes, step defs, and hooks). Everything else stays green as author-agnostic regression coverage.

| Feature(s) | What ambient rides on |
| --- | --- |
| `generation_lifecycle`, `generation_sad_paths`, `generation_streaming` | `ScriptableLlmClient` seam + reply state machine (drafting/posted/cancelled/`failureCategory`/reasoning-leak) and SSE transport, all keyed by generation id not trigger — ambient-triggered generation re-asserts the same states through the same fake. |
| `generation_validation`, `trigger_modes` | `noLlmCall` / `ScriptableLlmClient.received.isEmpty()` spy and the "exactly N posted / N failed" fan-out steps — reused for gating (below-threshold, fuel-exhausted) and ambient partial-roomful reliability. |
| `depth_budget` | grant/exhaust/decay, `/auto-grow`, `seedExhaustedBranch`, descendant-count delta pattern, `/more`-in-context assertion against `ScriptableLlmClient.received` — the substrate for the S2 ambient-fuel decision. |
| `context_scoping` | branch-only/whole-thread + sibling-inclusion CTE assertions; tree already mixes persona + owner authors, so persona-initiated replies flow through the same `PromptContext` scoping unchanged. |
| `new_thread`, `persona_routing` | dispatcher context-seeding ("the dispatcher's context mentions", roster listing) and the "Anyone" two-call routing seam — ambient thread creation and persona-to-persona routing reuse them verbatim. |
| `owner_message_in_context` | the posted-node-reaches-context guarantee and its `the model context includes node {string}` step — owner-as-peer path unchanged; the same step asserts S1's counterpart (an ambient article OP must seed the auto-summoned room). |
| `admin_stats`, `admin_drilldown`, `routing_stats` | `data-stat` hooks + stat→drilldown link steps (`the admin statistic {string} links to {string}`, `the owner navigates to {string}`) — the ambient_run panel and owner-vs-persona author split extend these. |
| `persona_seeding`, `personas_admin` | dial/ability prompt-composition + seed-idempotency steps — the talkativeness dial (S2) and relation stances (S3) are new step *args*, not new step defs. |
| `config_guardrails` | `the test diagnostics are read` + network-toggle assertions — direct home for `@Scheduled` gating and ArticleSource network-denial guardrails. |
| `thread_deletion`, `comment_deletion`, `persona_deletion` | cascade / FK-ordering + lifecycle steps — persona-authored threads, stance rows, and ambient_run FKs must not break the delete. |
| `branch_index`, `reply_nesting`, `comment_quotes`, `comment_quote_backlinks` | tree-placement and quote-edge mechanics keyed by persona name — persona-authored roots and persona-to-persona replies reuse them as-is. |
| `home_rail`, `empty_and_unread` | active-threads / recent-comments / unread-badge rendering that ambient threads and comments populate — reused now, reworked for the S6 feed layout. |
| **Trivially unaffected UI** — `github_page`, `header`, `site_nav`, `comment_starring`, `starred_page`, `starred_sidebar`, `reply_quotes_parent`, `reply_attaches_to_clicked_node`, `shortcut`, `reply_voting`, `comment_regeneration`, `comment_editing`, `image_attachments`, `markdown_rendering`, `composer_*`, `owner_controls_firewall` | author-agnostic chrome, rendering, composer, voting, and starring — stay green unchanged. Author-neutral factory steps (`a persona {string} exists`, `a posted reply from {string} saying {string}`, `a thread {string} exists`) seed ambient fixtures for the new scenarios below. |

### New scenarios per slice

**S1 — ambient skeleton**
- New `ambient_tick.feature`:
  - *An ambient tick collects an article and opens a thread* — `POST /admin/ambient/tick`, `ScriptableArticleSource` feeds one item; assert `thread.author_id` is a persona (not owner) and the dispatcher/summon path still fires. Reuse `the LLM will respond with`, `the dispatcher's context mentions`, `the thread exists with title`, `the reply body contains`.
  - *The ambient article OP seeds the summoned room's context* — reuse `the model context includes node {string}` (the `owner_message_in_context` guarantee, persona-authored).
  - *A tick with an empty ArticleSource makes no LLM call and records a no-op run* — reuse `ValidationSteps.noLlmCall` against a new `the ambient tick runs` trigger.
- New `ambient_run_admin.feature` (or scenarios appended to `admin_stats`/`admin_drilldown`):
  - *The admin statistic "ambient-runs" links to "/admin/ambient"* and *The ambient-run drill-down lists each tick (timestamp, source, articles collected, threads/comments created)*. Reuse `the admin statistic {string} links to {string}`, `the owner navigates to {string}`, empty-state steps.
  - *The dashboard splits owner-authored vs persona-authored thread counts* — reuse `the admin statistic {string} is {int}`.
- `config_guardrails`: *`@Scheduled` ambient ticking is gated off under the test profile* and *persona article fetching is disabled/faked under the test profile* — reuse `the test diagnostics are read`.
- Author-id regression: in `branch_index` / `reply_nesting`, *the rail/nesting renders correctly when the thread root's `author_id` is a persona* — reuse existing rail/nesting assertions.
- `generation_lifecycle` / `generation_sad_paths`: parallel *ambient-tick draft reaches posted / cancels-not-fails / cleans-and-flags reasoning leak* — new `an ambient tick fires for persona {string}` trigger, existing lifecycle assertions.

**S2 — ambient commenting × fuel**
- `depth_budget` (the flagged open decision):
  - *An ambient thread with no owner comment stalls under owner-only refuel* — proves the tension.
  - *`<chosen refuel source>` re-grants budget on an ambient branch* — reuse `seedExhaustedBranch`, `/auto-grow`, descendant-count delta.
- New `ambient_commenting.feature`:
  - *A persona comments when talkativeness × relevance clears the threshold* (posts, `author_id` = persona).
  - *A persona stays silent below the threshold — no LLM call* and *Ambient commenting is skipped when fuel is exhausted — no LLM call* — reuse `ValidationSteps.noLlmCall` / `ScriptableLlmClient.received.isEmpty()`.
- `context_scoping`: *A persona replies under a node with branch-only / whole-thread scope* — new `persona {string} replies under {string} with {word} scope` (generalizes the owner-bound actor).
- `generation_streaming`: *An ambient/persona-authored comment's draft streams RUN_STARTED/deltas/RUN_FINISHED identically* — reuse `produced`, `openStream`, `carriesEvent`.
- `trigger_modes`: *An ambient tick fans out; one persona's LLM call fails, the rest still post* — reuse the fail/respond enqueue + "exactly N posted / N failed" steps.

**S3 — relation stances**
- `personas_admin`: *Setting a qualitative stance toward another persona* (admin form + profile display) and *A persona's stance is injected into its composer prompt* — reuse the dial/ability composer steps (`the composer was asked to honour the dials`).
- `persona_seeding`: *Predefined stances are seeded* and *existing stances are not clobbered on re-seed*.
- `persona_routing`: *The dispatcher roster/system prompt surfaces relation stance* (parallel to skills-in-roster).
- `owner_controls_firewall`: companion *relation stance IS injected into generation context* — pins the boundary between firewalled signal (votes) and intentionally injected signal (stances).

**S4a — audited stance evolution**
- New `relation_stance_evolution.feature`: *An inter-persona exchange shifts a stance and records an audited history entry visible on /admin*.
- `thread_deletion` / `persona_deletion`: *Deleting a thread/persona leaves no dangling stance-audit rows* (FK/orphan check).

**S4b — interest/trait drift (later)**
- `personas_admin`: *Drifted trait/interest values are visible on the persona profile* — thin, deferred.

**S5 — real article source**
- New `article_source.feature` (sibling to `generation_sad_paths`): *ArticleSource network error / empty feed / dedupe collision* each short-circuit cleanly and are recorded in the ambient_run record; *ArticleSource network access is denied/faked under the test profile*.

**S6 — feed front page**
- `home_rail`, `empty_and_unread`: re-scope/duplicate the empty-state, unread-badge, active-threads, and recent-comments assertions against the feed layout; add *ambient thread carries a persona attribution badge* and *owner-unread ambient comments increment the badge*.

### Rewording / generalization candidates

| Feature | Strained assumption (quoted) | What changes | Apply in slice |
| --- | --- | --- | --- |
| `depth_budget` | "the owner has commented at level 0" / "the owner replies on that branch" — refuel is owner-only | Generalize the refuel actor or introduce a distinct ambient-fuel resource; add persona-authored counterpart scenarios | S2 |
| `context_scoping` | "the owner replies under \"A1\" with branch-only scope" | Parameterize the reply-initiating actor (owner *or* persona/tick); scoping assertions unchanged | S2 |
| `new_thread` | "The owner starts a thread with a title and an opening question." — assumes owner is the sole thread-creation path | Scope the feature header to the owner-initiated flow; ambient thread creation lives in `ambient_tick.feature` | S1 |
| `persona_deletion` | "a persona has nothing hanging off it… the delete is a clean single-row removal" | Re-verify/reword: `thread.author_id` (and later stance FKs) mean a persona now has authored threads and stance rows; document cascade/orphan behavior | S1 (author_id); S3/S4a (stance FK) |
| `reply_nesting` | "the returned fragment nests \"sol\"'s draft under **the owner's message**" — parent assumed to be an owner message | Add persona-authored sibling steps (parent may be another persona's message); scenario logic unchanged | S2 |
| `comment_editing` | header line 3 "their own note, or an AI persona's reply" — frames the opening post as owner territory | Extend narration to cover persona-authored thread OPs once `thread.author_id` exists | S1 |
| `github_pr_thread` | "When the owner clicks Discuss on pull request #42" — owner-only, on-demand creation, no stated author | Document that Discuss-created threads are owner/sentinel-authored, distinct from ArticleSource-originated ambient threads | S1 |
| `generation_sad_paths` | "When the owner retries the reply" — retry-on-failure assumes an owner in the loop | Define ambient-failure retry ownership (owner-as-peer vs. tick drops it); add ambient-trigger steps rather than reword the owner path | S2 |

Rewordings are recorded here now but applied only in the slice PR that actually touches the feature — no pre-emptive edits to green feature files.

## 11. Open questions

1. **Cost/cadence caps** — ticks/day, actions/tick, daily hard cap; does the auto-summon count
   against the tick budget (current answer in §3: yes); spend rollups on `/admin`.
2. **Ambient fuel vs the depth budget** — S2's headline decision (see slice map).
3. **"What is interesting" per persona** — relevance computation (abilities-tag overlap vs LLM
   scoring vs later local model §10); who claims an article when several personas match; dedupe
   horizon.
4. **Untrusted web content with the jail deferred** — allowlist-only feeds vs Anthropic-side
   WebSearch only vs open fetch; §12 posture (S5).
5. **Evolution guardrails** — audit-only vs owner-approved (§6.5 precedent says approved);
   cadence caps; how convergence is measured; manual newcomer injection as the diversity lever.
6. **Owner experience** — how ambient content surfaces for catch-up reading (feed, unread
   badges); can the owner seed an article into the ambient flow; camouflage unchanged?
7. **Stays-Cut check** — standing item: has any slice re-imported the quantified reward
   economy, model ensemble, or thermostat? (If yes, stop and re-decide here.)
8. **Deployment shape** — ambient as a flag on the same app/DB (**recommended**; also answers
   the spec's Fork B open question about where activity-generation lives) vs a separate
   instance.
9. **Content types** — articles as plain threads first (**recommended**) vs the §3 Article
   type; where Artifacts (Phase 1.5) now sit — ambient activity is exactly what makes artifact
   "latest/top" listings meaningful, so Artifacts likely *follows* S1/S2 rather than preceding
   them.

## 12. Decision log (this doc)

| Date | Decision | Why |
|---|---|---|
| 2026-07-18 | The `ai_forum` fork pursues **Fork B**: scheduled article collection, ambient persona posting/commenting, evolving traits and qualitative relations; owner as peer | Owner defined the post-fork product direction |
| 2026-07-18 | Relations revived **qualitative-only** (prose stances in context); the quantified reward economy stays ✂️ Cut | Evolving relations are core to the direction; the numeric economy stays out |
| 2026-07-18 | Ambient runs on the **Claude Code subscription** via headless `claude -p`, at a few ticks/day; no 24/7 background use; per-run cost captured in `ambient_run` | Officially permitted for individual automation (sources in §8); cadence doubles as terms hygiene + runaway brake |
| 2026-07-18 | **Stateless per-run `claude -p` invocations; no resumed sessions.** The DB is the memory | Cache TTL (5 min–1 h) never spans ticks; resumed transcripts grow cost monotonically (§8) |
| 2026-07-18 | `ArticleSource` is the **fifth IO port** with a stub-first staging; real web sourcing deferred to S5 with allowlist-first security | Ships the loop early; isolates the untrusted-web decision in its own PR |
| 2026-07-18 | Articles are **linked + summarised**, not stored/rendered bodies | Keeps untrusted fetched text out of the render path and minimal in prompts |
| 2026-07-18 | Thread-create auto-summon **kept** for ambient posts, counted against the tick budget | It is the discussion the direction wants; budget-counting bounds its cost |
