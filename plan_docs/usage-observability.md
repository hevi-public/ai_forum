# Usage observability — cost and tool-call history in the admin UI

> **Status:** ✅ built 2026-08-09 (#15 data, #16 surfaces) · **Owner:** Hevi · **Created:** 2026-08-09
> Parent: `ai-driven-forum-direction.md` §8 (Cost, safety & subscription-terms envelope), §11.1 (open
> question "Cost/cadence caps" — "spend rollups on `/admin`") · Predecessor: issue #15 (structured turn
> result: `ambient_run.cost_usd`, `generation_tool_call`), whose data this slice reads and renders.

The direction doc's success criterion is content "at bounded and **observable** cost"
(`ai-driven-forum-direction.md` §1). Issue #15 made the cost bounded-in-principle by capturing it
(`ambient_run.cost_usd`) and made a generation's tool use auditable (`generation_tool_call`) — but
shipped no reader and no page, so neither fact was actually *observable* by the owner. This slice adds
the readers (`AmbientRunRepository.costSince`, `GenerationToolCallRepository.countSince`/`recent`) and
the two admin surfaces that read them: a visible cost on every `/admin/ambient` run row, a rolling
24h/7d usage strip above the run list, and a new `/admin/tools` view of what a generation actually
fetched, read, or ran.

## 1. What the stream gives

`claude -p`'s terminal `result` envelope carries `total_cost_usd`, `duration_ms`, a token `usage` block
(input/output only — `cache_creation_`/`cache_read_` counts are excluded because they measure the
provider's cache behaviour, not the size of the turn), and `modelUsage` (a map keyed by the model(s)
actually used). This is true of **both** output formats the app can ask for: `--output-format json`
(the plain, non-streaming envelope) carries these fields directly, and `--output-format stream-json`
(the streaming path) carries the *same* fields in its terminal `result` line. `LlmResponseParser.usageOf`
is the ONE function that derives `LlmUsage` from this envelope, and the streaming client re-parses its
captured terminal line through that same function — so cost, duration, tokens and the reasoning-leak
verdict are byte-identical between the two paths by construction, not by convention. `usage` is null
only when every derived field is null (`costUsd`/`tokens`/`durationMs`/`model` all absent) — `usage !=
null` means "the provider said something," not "the envelope parsed." `modelUsage`'s keys are sorted
before being comma-joined into `LlmUsage.model`, so the string is stable across runs regardless of map
iteration order.

## 2. The ceiling — what is NOT observable, and why

(researched 2026-08-09; sources: code.claude.com/docs/en/costs and the headless docs
(code.claude.com/docs/en/headless) — see also `work-fork-direction.md` §3.4, a sibling PR's
investigation of the same ceiling, which carries the dated source notes)

**The 5-hour and weekly subscription plan-limit bars cannot be read programmatically, and this is a
structural fact about the tooling, not a gap this app failed to close.** Recorded here so it is not
re-investigated and re-concluded again in six months:

- There is no API and no headless flag that returns the plan-limit windows
  (code.claude.com/docs/en/costs, code.claude.com/docs/en/headless). `/usage` is an **interactive
  TUI**; the docs describe its bars as computed from **local session history** on the machine running
  Claude Code — a client-side view over files on disk, not a server-queryable resource, with no
  non-interactive or scriptable form.
- OTel export (`OTEL_METRICS_EXPORTER` / `CLAUDE_CODE_ENABLE_TELEMETRY`) gives **tokens and cost per
  invocation** (code.claude.com/docs/en/costs) — the same shape of figure `total_cost_usd` already
  gives us — but it does **not** surface the account-level 5-hour/weekly plan windows either.
  Telemetry and the plan-limit bars are two different data sources inside Claude Code, and only one of
  them is exported.
- **Conclusion: per-invocation cost from the stream (`total_cost_usd`, captured since V21 and rendered
  by this slice) is the maximum observability available to this application.** There is no deeper
  figure to go get. If the owner wants to know "how close am I to the plan limit," the answer today is
  "open `/usage` yourself" — not a limitation of this codebase's plumbing.
- **The dollar figures are notional, not a bill.** On a Claude subscription (Pro/Max), spend does not
  draw down a metered balance the way an API key would — `total_cost_usd` is the API-equivalent price
  of the tokens used, useful for **trend** ("is this week's ambient loop pricier than last week's?")
  but not for **billing** ("this tick cost the owner $0.12"). The aggregates on `/admin/ambient` should
  be read as a cost *signal*, not an invoice.

## 3. `cost_usd` semantics

`ambient_run.cost_usd` is written in **two phases**, both additive via `AmbientRunRepository.addCost`'s
`COALESCE`: the tick's own summon fan-out settles and its cost is charged first, then the
settle-triggered growth round (the ambient comment's mini-discussion, up to `DepthBudget.AMBIENT_GRANT`)
settles and its cost is charged second. Two phases, not one, so a growth round that throws still leaves
the run priced for what it definitely cost — charging once at the end would lose the whole figure to the
one failure mode the growth hook's own catch already tolerates.

**NULL means unknown, never `$0`.** A provider that reports no usage (openai, opencode, the stub, an
older CLI) leaves `cost_usd` NULL, and every reader in this slice — `costSince`'s SQL `SUM` (which
silently skips NULL rows rather than treating them as zero), the per-row `AmbientRunView.costUsd`, the
usage strip's `UsageAggregatesView.cost24h`/`cost7d` — preserves that NULL as an absent value rather than
a rendered `"0.0000"`. Coalescing "we don't know" into zero would tell the owner an ambient tick was
free when it may not have been.

**Known understatements — real gaps, recorded rather than hidden:**

- **The "Anyone" dispatcher's routing pick is unpriced.** `PersonaRouter.pick()` calls the 2-arg
  `llm.generate(request, CancellationToken())` seam and reads only `.text` — the returned
  `LlmResponse.usage` is discarded. A multi-persona ambient tick's routing call therefore contributes
  nothing to `ambient_run.cost_usd`, even though it is a real, billed invocation.
- **Retry, regenerate, and owner-initiated summons are unattributed.** `GenerationService.retry` and
  `.regenerate` capture NEITHER cost NOR tool calls at all, by design (documented in their own KDoc):
  both call the 2-arg non-streaming seam, and — this is the load-bearing reason — a retry or regenerate
  is an owner action with **no ambient run behind it**, so there is no row to charge the spend to;
  inventing one would smuggle owner-initiated spend into the ambient loop's accounting. The owner's own
  *initial* summons (and auto-grow) DO run through the same `settleOne` ambient generations use, so
  `resp.usage?.costUsd` IS computed for them — but it is only ever attached transiently to the returned
  `ReplyView.costUsd` for the settle-triggered-growth hook to read; nothing persists it, because there
  is no `ambient_run` row for an owner-driven generation to attach to either. Net effect: **the owner's
  own LLM *spend* is invisible to `/admin/ambient`'s cost figures** — the run list's per-row `cost_usd`
  and the usage strip's `cost24h`/`cost7d` (`AmbientRunRepository.costSince`) cover ONLY ambient-tick-
  dispatched generations, never what the owner's own summons/retries/regenerates cost.
  **Tool-call VOLUME does not share that scope, and this is worth stating precisely rather than folding
  it into the sentence above: `GenerationToolCallRepository.countSince` (§4) counts every
  `generation_tool_call` row with no ambient-vs-owner filter — `run_id` carries no origin marker at all
  (§4, V30's header) — and `settleOne` writes a trace for every generation it settles, owner summons
  included.** So the strip's `toolCalls24h`/`toolCalls7d` and `/admin/tools` DO surface the owner's own
  tool calls; only the *spend* half of `/admin/ambient` is ambient-only. `usage_observability.feature`'s
  "An owner summon's tool calls count toward the strip, but its cost does not" scenario pins exactly this
  split — written adversarially, asserting the OLD (wrong) "ambient-only" reading first and confirming it
  reddened (`expected: <0> but was: <1>`) before landing the correct assertion.
- **A settle that throws records no trace.** `GenerationService.settleOne`'s exception branch
  deliberately captures nothing — the tool calls (and cost) a turn made before it died live only inside
  the parser owned by the seam that just threw, and smuggling them out through the exception would put
  audit plumbing into the failure taxonomy. A failed generation's tool-call trace, if any tools ran
  before the failure, is lost — the trace exists only for a turn that reached settle.

## 4. `generation_tool_call` semantics

One row per observed tool invocation, written at settle (V30, issue #15) — **streaming-CLI only**, and
that is structural, not a coverage gap: `--output-format stream-json`'s NDJSON carries the assistant's
`tool_use` parts and the following `tool_result`s; the plain-json envelope carries no content array at
all, and the openai/opencode/stub providers run no tool loop. For every one of those, an empty trace is
the *correct* account of the turn. Summaries are clipped to `ToolSummaries.INPUT_CAP` (2000) /
`OUTPUT_CAP` (4000), each ending in a marker (`…[truncated]`) **inside** the cap, so "was this
truncated?" is answerable from the stored string alone — enforced at the parser and again at the
repository door. `comment_id` is NULL exactly when the generation did not POST (a failed run) —
deliberately kept, not discarded, because a failed run's trace is precisely when an operator most wants
to see what the model was doing before it died — and `ON DELETE CASCADE`s when its comment is deleted
(contrast `ambient_run.thread_id`'s `ON DELETE SET NULL`: spend survives the thread it opened, but a
trace with nothing left to explain is noise). `run_id` carries **no foreign key** in either direction —
it is the GENERATION's id (the in-flight node id, which is also the settled comment's id when POSTED),
and one tick fans out N generations plus a growth round while an owner summon has no tick at all, so no
single parent table could hold the reference.

## 5. The surfaces

- **`/admin/ambient`** — the existing run list, now with a visible cost on each priced row
  (`admin-list__cost` text, `· $0.1200`, rendered only when `run.costUsd != null` — no filler text on an
  unpriced row, matching the attribute's own absent-means-unknown idiom) and a usage strip above the
  list: `<section data-usage-aggregates data-cost-24h data-cost-7d data-tool-calls-24h
  data-tool-calls-7d>` with human-readable prose and a link to `/admin/tools`. `data-cost-24h`/`-7d` are
  omitted (not `"0.0000"`) when every run in the window is unpriced; the tool-call counts always render
  — 0 is a real, known count, never an unknown. The two halves have DIFFERENT populations, and the prose
  now says so ("N tool calls (all generations)"): cost is ambient-runs-only (`costSince`), tool-call
  counts are every `generation_tool_call` row regardless of origin (`countSince`) — see §3.
- **`/admin/tools?comment={id}`** — the new trace view. Newest-call-first, each row (`<li
  data-tool-call data-tool-run data-tool-comment data-tool-seq data-tool-name data-tool-error>`) carries
  its hooks on ONE element; input/output summaries render as child text (never in an attribute — free
  text in a `data-*` value can truncate every hook after it on the tag, per the S6 rule), styled by
  `.admin-list__tool-text` (mono, wrapped, `max-height: 40vh; overflow-y: auto` — this page's own class,
  not a dependency on or duplication of issue #17's global `pre`-containment CSS, which lives on a
  different branch; the two merge cleanly later). `data-tool-comment` is omitted for an unlinked
  (failed-run) trace.
- Linked from `/admin`'s index page (`data-admin-link="tools"`) alongside the other drill-downs.

## 6. Deferred

`LlmUsage.model` (the sorted, comma-joined `modelUsage` keys) is captured per generation but has no
reader or surface yet — a per-model cost/count breakdown (e.g. "how much of this week's spend was
Sonnet vs. Haiku") is a natural follow-up once more than one model is actually in rotation, and is left
for whichever slice needs it.

## 7. Decision log

| Date | Decision | Why |
|---|---|---|
| 2026-08-09 | **The plan-limit bars (5h/weekly) are NOT programmatically observable** — no API, no headless flag; `/usage` is an interactive TUI over local session history; OTel export carries tokens/cost but not plan windows. Per-run stream cost is the ceiling. | Recorded so this is not re-investigated and re-concluded again later; see §2 (researched 2026-08-09; code.claude.com/docs/en/costs, code.claude.com/docs/en/headless — also `work-fork-direction.md` §3.4, which carries the dated source notes). |
| 2026-08-09 | **NULL renders as an absent attribute / omitted prose figure, never a claimed `0.0000`/`0`** — cost fields (never known-zero) and, on the strip, cost specifically (tool-call counts DO render `0`, since a count has no "unknown" case). | Continues #15's absent-means-unknown idiom to the aggregate layer; a coalesced zero would tell the owner a tick was free when it may not have been. |
| 2026-08-09 | **Per-reply cost is NOT displayed on forum pages** — `ReplyView.costUsd` stays a settle-time carrier read only by the ambient growth hook; admin run/tool views are the only rendered surface. | Rendering a per-reply price tag would create exactly the member-attached, rankable magnitude the V24–V28 no-numbers guardrail exists to refuse — a cost is an operator-accounting fact about an invocation, not a score hung on a persona. |
| 2026-08-09 | **`/admin/tools` filters flat by `?comment=`, not a per-run drilldown from `/admin/ambient`.** | No run→generation join exists, by design (V30's header): `generation_tool_call.run_id` carries no FK because one tick fans out N generations plus a growth round, and an owner summon has no tick at all — there is no single parent id to drill down from. |
