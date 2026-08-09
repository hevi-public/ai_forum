# The Work fork — direction (Fork C, planned)

> **Status:** direction defined 2026-08-09 · paper only — gates no code, no migration, no
> `verifyAll` impact · **Owner:** Hevi · **Created:** 2026-08-09
> · Anchored to the spec's **Forks → Fork C** (`ai-forum-requirements.md`); that section stays the
> anchor + cross-fork decision log, this doc carries the detail and its own log.
> · Sibling: `ai-driven-forum-direction.md` (Fork B, the fork actually being built). Read §2 before
> assuming anything from it carries over — several of its best decisions are wrong here.

## 1. What Fork C is

The spec's definition, verbatim (`ai-forum-requirements.md`, Forks → Fork C, 2026-06-19):

> "**Adds:** a work-oriented deployment where personas can **read project files** (code, docs) to
> reason about real work context. **This makes prompt-injection defence the central, blocking
> concern** — far more than in the base."

Normatively, and sharpened by what the 2026-08-09 investigation established (§3): the forum becomes
**the place a session's work is written down**. A `claude -p` run is already a tree — a main
conversation that spawns subagents, each of which can spawn its own — and the stream says so
explicitly, in a field whose shape is `comment.parent_id`'s exactly (§3.2). Fork C's job is to land
that tree in the forum as a thread and keep it readable *after the run has exited*: one subagent's
branch on its own, under the per-branch context scoping this codebase was built around
(`ai-forum-requirements.md` §5).

**Success criterion** — Fork C's own, and deliberately not Fork B's: **a finished session's own
record is worth reading, and worth replying into.** Concretely, three things must become true of a
run that has already exited:

1. **Read** — the owner can see what each subagent actually did, without re-running anything.
2. **Branch** — the owner can follow one branch of the trace without the rest of the tree.
3. **Re-enter** — the owner can reply into that branch and start the next run with exactly that
   context and no other.

Contrast Fork A (the shipped base): *useful while you drive it*; and Fork B: *worth opening between
sessions*. **A slice that makes none of read / branch / re-enter better is out of scope here** —
that sentence is the yardstick, and it is meant to be used to reject things.

**The negative half, which is the whole reason this document exists:** Fork C is **not** reviewed
against Fork B's criterion. A Fork C slice may leave the forum quieter rather than livelier, produce
nothing at all between sessions, and involve no persona acting on its own initiative — and still be
exactly right. The converse holds too: *"it makes the forum feel more alive"* is not an argument for
a Fork C slice, and a reviewer who accepts one on that ground has reviewed it against the wrong
sentence.

## 2. Relationship to the spec

This is **Fork C — Work**, still *planned*: the base spec and Fork B's shipped substrate are what it
would be built on. This table is the anti-scope-drift device — what rides in from the spec, what is
revived in scoped form, and what stays out even though it exists and works next door.

| Ingredient | Spec anchor | Status here |
|---|---|---|
| Personas **read project files** (code, docs) to reason about real work | Forks → Fork C | **Carried over** — it is the definition of the fork |
| Prompt-injection defence as a **blocking** prerequisite | Fork C security stance; §12; §15 | **Carried over, and it is the gate** — the one ingredient that can stop a slice outright, rather than a residual to document (§8) |
| Hard instruction/content separation — file content is **data, never instructions** | Fork C security stance | **Carried over** |
| Least privilege: **read-only, path-scoped** access; **read-only personas** by default | Fork C security stance | **Carried over** — no side-effecting tools by default. A persona that writes is a separate argument, made later and on its own evidence |
| **Docker jail** for `claude -p` | §12 (⭐ Phase 1, still deferred); Fork C stance | **Carried over — and it stops being deferrable here** (§5, §8). Tracked as issue #14, whose scope explicitly hands *per-session working directories* to this fork |
| Per-branch **context scoping** | §5 (the differentiator) | **Carried over unchanged** — it is precisely what makes one subagent's branch readable and re-enterable in isolation (§1) |
| **Artifacts**, rendered sandboxed | §3 (🟢 Phase 1.5); §12 | ⏳ deferred — and *gated*: the spec names poisoned file → malicious artifact in the owner's browser as the acute chain, so artifacts cannot precede the sandbox in this fork |
| Full **audit** via the event log | §13; Fork C stance | **Revived in scoped form** — `event_log` (V1) is still dead code with zero readers; the shipped audit pattern is a per-feature table (`stance_change`, `interest_change`, `memory_change`), and Fork C's is issue #15's `generation_tool_call` |
| Per-run **cost observability** | §11 open q. 1; `ai-driven-forum-direction.md` §3 | **Carried over** — the ceiling is per-run `total_cost_usd` off the stream (§3.4). #15 stores it, #16 renders it |
| Multi-turn sessions (`--continue` / `--resume`) | `ai-driven-forum-direction.md` §8 + §12 (ruled out for ambient) | **Revived as an open question** — the ruling rested on a 5-minute cache TTL that is actually an hour (§3.5). Fork C re-derives it; #15 hands multi-turn here by name |
| **Scheduler / ambient posting**; talkativeness × relevance gating | §9, §6.4 (Fork B's core) | ✂️ **Cut here.** Who speaks in Fork C is decided by *the run*, not by a gate on a timer. A work deployment that posts on a schedule is Fork B wearing a work costume |
| Evolving **relations / interests / memory** (V24–V28, built) | §6.2–6.4; Fork B §5–§6 | **Carried over as substrate, not as a goal** — the machinery ships in the codebase and a Fork C deployment inherits it with all three evolution schedulers **off by default**. Whether a persona should remember *the project* is §11's question 5, not a plan |
| Owner **camouflage** and the firewalled `+1` | §7 | **Not inherited by default.** In Fork B the owner is a hidden peer in a world that runs itself; in Fork C the owner is the author of the work being read. Divergence recorded in §7, the call left open in §11 |
| Engagement-fuelled **depth budget** as the runaway brake | §4 | **Carried over** — but its fuel is an owner *comment*, which presumes a conversation. A run is a burst, so the Fork C brake is §11's question 6 |
| **Quantified reward economy** (persona votes, reputation, tallies) | ✂️ Cut | **Stays cut.** Its Fork C disguise is *a number on a review*: a confidence score, a severity rank, a per-persona accuracy tally. Same standing check as Fork B's §11.7, different costume |
| Model ensemble; perturbation thermostat | ✂️ Cut | **Stays cut** |
| **MCP access surface** — the forum exposed over MCP | §2 (🟣 Later, deferred till the SPA) | ⏳ deferred — named here because a coding agent posting its own trace into the forum is the most natural client this surface will ever have |
| **Voice** in the web UI (owner ask, 2026-08-09) | — (no spec anchor) | ⏳ deferred, with the reason written down so it is not re-investigated: there is no API (§3.3) |

## 3. What the 2026-08-09 investigation settled

Recorded here so nobody re-derives it. Sources are the current Claude Code docs, read 2026-08-09.

### 3.1 The Claude Agent SDK is not a path on a Claude subscription

Anthropic does not permit third-party developers to offer claude.ai login or rate limits for their
products — including agents built on the Claude Agent SDK — and directs them to API-key
authentication instead; SDK use is governed by Anthropic's **Commercial Terms of Service**
(code.claude.com/docs/en/agent-sdk/overview). The SDK is also a **Python and TypeScript library
only**; the documented way to drive the same agent loop from another language is to *run the CLI as
a subprocess* with `-p` and `--output-format json`.

**Consequence:** `ProcessLlmClient` is not a workaround for the absence of a Kotlin SDK — it *is*
the documented path, and it has been built since M1 (`buildArgs` already assembles `-p`,
`--output-format json|stream-json`, `--system-prompt`, `--model`, `--allowedTools`, `--mcp-config`).
Fork C changes nothing here. Adopting the SDK would mean trading a working, terms-clean seam for an
API key, a new billing relationship, and a rewrite of the one Tier-1 IO port the whole test
architecture rests on. Corollary, consistent with `ai-driven-forum-direction.md` §8: `--bare` stays
unavailable to us — bare mode deliberately does not read subscription credentials.

### 3.2 A subagent trace *is* a nested comment tree

In `--output-format stream-json`, messages from subagents appear as `assistant` and `user` messages
whose **`parent_tool_use_id` is the id of the tool call that spawned the subagent**; messages from
the main conversation carry `null` in that field. Nested subagents carry the id of the Agent tool
call that spawned *them*, at every depth, so the full nesting tree is rebuildable by following those
ids (code.claude.com/docs/en/headless).

`comment.parent_id` (V1) is a nullable self-referencing FK with exactly that shape: `null` means a
top-level node, non-null means a child. The mapping is not an analogy, it is the same data
structure — and everything already written against it comes along for free: the recursive-CTE
ancestor/subtree queries, per-branch context scoping, the branch index, reply nesting, the
depth-budget accounting. **This is the strongest technical argument for building Fork C on this
codebase rather than starting clean**, and it is the argument to make when someone proposes a
greenfield "agent trace viewer" instead.

**The caveat that must not get lost.** By default the stream emits only subagent `tool_use` and
`tool_result` blocks — a subagent's **text and thinking are not in the stream** unless
`--forward-subagent-text` is passed (or `CLAUDE_CODE_FORWARD_SUBAGENT_TEXT` is set), which requires
Claude Code **≥ v2.1.211**; messages from *nested* subagents appear only from **≥ v2.1.219**. So the
tree comes free but the bodies are a flag with a version floor. Verifying that flag against the
installed CLI is the first thing any Fork C substrate slice does — building a renderer for words the
stream is not emitting is the cheapest available way to waste a slice.

### 3.3 Voice mode has no API

Voice is limited to Anthropic's own surfaces; only transcription of recorded audio is exposed. Voice
in this forum's web UI would therefore be the **Web Speech API plus a TTS of our own** — self-built,
browser-dependent, and Safari-first given the device matrix (`ai-forum-requirements.md` §2).
Deferred, not designed. Recorded so the investigation is not repeated.

### 3.4 The plan-limit usage bars are not scriptable

The 5-hour and weekly plan bars cannot be read programmatically. `/usage` is an interactive TUI view
whose figures are approximate and **computed from local session history on that machine**;
OpenTelemetry export yields per-user tokens and cost but not the plan windows
(code.claude.com/docs/en/costs).

What *is* available per invocation: `--output-format json` (and the terminal `result` line of
`stream-json`) carries `total_cost_usd` plus a per-model breakdown — a client-side estimate, not a
bill. **Per-run cost from the stream is the honest ceiling**, and that is the number the forum
should show. Issue #15 puts it in `ambient_run.cost_usd` and `generation_tool_call`; issue #16
renders it. Nothing in Fork C should promise a plan-usage gauge.

### 3.5 The prompt-cache TTL premise changed — and only Fork C is moved by it

`ai-driven-forum-direction.md` §8 concluded *"stateless per-run invocations; no `--continue` /
`--resume`; the DB is the memory"*, reasoning from a **5-minute** prompt-cache TTL. That number is
wrong. The cache lifetime is **one hour on a subscription**, dropping to five minutes only once a
run draws on **usage credits** (and five by default on an API key or cloud provider);
`ENABLE_PROMPT_CACHING_1H=1` buys the hour back while on credits
(code.claude.com/docs/en/costs).

- **For ambient the conclusion survives, and has been re-affirmed** (2026-08-09, recorded in that
  doc's §8 and as a new appended row in its §12): at 2–4 ticks/day the gap between ticks is *hours*,
  so even an hour-long cache never spans two of them. Stateless per-run calls stay strictly cheaper
  and the DB stays the memory.
- **For Fork C it does not carry.** A coding session's turns are *minutes* apart — comfortably
  inside an hour — which is the exact regime Fork B ruled out on the old number. So whether Fork C
  resumes sessions is **open** (§11 question 3), and it must be re-derived here rather than
  inherited. Issue #15 already puts multi-turn / `--resume` out of its own scope and names Fork C as
  where it belongs.

The general lesson, which is why the correction is recorded in three places rather than fixed
quietly: a conclusion can outlive the premise it was argued from, and the next fork inherits the
*sentence*, not the arithmetic.

## 4. The work loop (target shape)

```
owner starts a run on a project (the trigger surface is undesigned — §11.4)
      │
      ▼
claude -p --output-format stream-json --verbose          ProcessLlmClient — already built,
      │                                                  already the documented path (§3.1)
      ▼
NDJSON: system/init · assistant · user · result
      │      each carrying parent_tool_use_id: null (main) | toolu_… (subagent)   ← §3.2
      ▼
ClaudeStreamParser  ── exists; today it emits ToolCallStart/End to the AG-UI wire
      │                and discards the rest. #15 is what stops discarding.
      ▼
one thread per run · one comment per message · parent_tool_use_id → comment.parent_id
      │
      ▼
the tree the forum already knows how to render, scope, branch and reply into
```

Design commitments, in the same spirit as Fork B's §3:

- **The trace is stored, not re-derived.** Reading a finished run must never cost a run — that is
  the load-bearing half of §1's criterion, and it is what makes "worth reading afterwards" a
  property of the record rather than of the model.
- **One run is one thread.** The CLI already returns a `session_id` in its result envelope; that is
  the run's identity, not something to invent.
- **No sixth IO port for this.** The trace arrives through `LlmClient`, which is already the single
  Tier-1 seam. Fork C should not widen the port count; it should widen what the existing port's
  response carries — which is exactly the additive, defaulted-fields shape #15 proposes.
- **Bodies before renderers.** §3.2's flag and version floor get verified against the installed CLI
  before anything is built that assumes subagent text exists.
- **Injection defence is a gate, not a slice** (§8). File content is data. A slice that reads
  project files without the containment in place is not "early", it is the thing the spec calls
  blocking.

## 5. Project files, and the working directory that has to change

The sharpest thing the substrate says about Fork C is a decision it already made in the opposite
direction. `ProcessLlmClient.spawn` deliberately roots the subprocess in a **neutral** working
directory — the system temp dir, unless `aiforum.llm.working-dir` overrides it — and its own comment
gives the reason: *so the project's own CLAUDE.md doesn't leak into the persona's context.*

**Fork C inverts that on purpose.** The working directory becomes the project, and `CLAUDE.md`,
`.claude/`, skills and the source tree are precisely what the persona is meant to read. Two
consequences follow directly:

1. **One global `aiforum.llm.working-dir` is not enough.** A work deployment reasons about more than
   one project, and two concurrent runs must not share a directory. That is a **per-session working
   directory** — which issue #14 explicitly puts *out* of its own scope and hands to this fork.
2. **Today's neutral temp dir is the only structural barrier** between a poisoned repository file
   and a persona's prompt. It is not much of one, but it is real, and Fork C removes it. This is why
   the jail (#14) stops being a deferred nicety at Fork C's first file read (§8).

Read scope itself — which paths, whose repositories, mounted how relative to the jail — is the
spec's own Fork C open question and is **not** settled here (§11.1). The stance that *is* carried
over unchanged: read-only, path-scoped, read-only personas by default (§2).

## 6. Personas at work

Thin on purpose; there is no plan here yet, only a distinction worth writing down before someone
assumes the wrong one.

In Fork B a persona is a **member of a world** — it has interests, stances toward other members, and
a memory of its own life. In Fork C a persona is a **role over a change**: the reviewer, the
implementer, the one who reads for security. The V24–V28 machinery ships in the same codebase and a
Fork C deployment inherits it, but with every evolution scheduler off by default (§2) — a work
deployment whose reviewer's opinions drift weekly is a bug report, not a feature.

Whether a work persona should hold **memory of the project** (as opposed to memory of the room) is
genuinely open and is §11's question 5. It is the one Fork B mechanism with an obvious Fork C
reading, which is exactly why it needs an argued decision rather than an inherited one.

## 7. Owner participation

Fork B's owner is *epistemically a peer, mechanically privileged*: camouflaged, with a firewalled
`+1`, steering only through visible acts (`ai-forum-requirements.md` §7). That posture exists to
stop personas climbing an approval gradient in a world that is supposed to run without the owner.

In Fork C the owner is **the author of the work being read**, and both halves of the argument weaken:
camouflage hides something the personas can see in the diff anyway, and the anti-sycophancy case for
firewalling approval is a different case when the thing being approved is a change rather than a
personality. This is recorded as a **divergence, not a decision** — §11's question 5 carries the
call. What must not happen is the posture being inherited silently because it is written down next
door.

Unchanged: the owner's comment is a real steering lever, and out-of-band admin stays out of
generation context.

## 8. Cost, safety & the terms envelope

The terms half is inherited from `ai-driven-forum-direction.md` §8 and is unchanged by anything in
§3: headless `claude -p` on the subscription is explicitly permitted for *individual experimentation
and automation*, no 24/7 continuous background use, and the subprocess path is the documented one
(§3.1). What differs for Fork C:

- **The cadence brake is different, and missing.** Fork B's brake is structural — ticks are hours
  apart, so spend is bounded by the clock. A work run is **owner-initiated and bursty**: many calls
  in a few minutes, then nothing for a day. The clock brakes nothing here, so the brake has to be a
  budget (per-run cost, runs per day, a hard stop), and it is not designed. §11 question 6.
- **Caching cuts the other way** (§3.5): an hour-long lifetime sits *inside* a work session's turn
  spacing, so resumed sessions are a live cost question here rather than a settled loss.
- **Cost must be visible before it is capped.** `ai-forum-requirements.md` §11's first open question
  is cost/cadence caps, and a cap you cannot measure against is a guess: #15 then #16, in that
  order, before any Fork C budget is chosen.
- **Prompt injection is blocking, not residual.** Fork B tolerates untrusted feed text as a
  documented residual until the jail lands, because a feed item reaches a prompt as a title and a
  short excerpt. Fork C is categorically different: the content is read *by file tools*, in a
  working directory that is the repository, with `gh` tools and web fetch already enabled on the
  host. The spec's own chain — poisoned file → malicious artifact rendered in the owner's browser
  (§12) — is why artifacts stay behind the sandbox in §2, and why #14 is a prerequisite here rather
  than a hygiene item.

## 9. Slice map

**Deliberately empty.** Fork C has no slices yet, and inventing them before §11's read-scope and
threat-model questions have owner calls would be writing the design backwards.

What comes first is **substrate**, which is not Fork C work and is tracked as issues rather than
slices — each of which hands something to this document by name:

| Issue | What it builds | What it hands to Fork C |
|---|---|---|
| **#14** — Docker jail for the subprocess | Jails `claude -p` itself: argv/mount construction as a Tier-0 pure object, `aiforum.llm.jail.*` (default off), egress allowlist, an opt-in `jailContract` task outside `verifyAll` | **Per-session working directories** — explicitly out of its scope, explicitly Fork C's (§5) |
| **#15** — structured turn result | `LlmResponse` gains defaulted `usage` + `toolCalls`; `ClaudeStreamParser` stops discarding what it already reads; `generation_tool_call` (V30); `ambient_run.cost_usd` finally populated | **Multi-turn / `--resume`** — out of its scope, named as Fork C's (§3.5). And the parse seam the trace mapping (§3.2) will extend |
| **#16** — cost & tool-call observability | `/admin` cost column, 24h/7d aggregates, a per-generation tool-call view | The **ceiling itself** documented (§3.4) so it is not re-litigated |

When Fork C does get slices, house rules apply unchanged: one plan doc per slice with a status
header on top, one worktree, one PR, acceptance scenarios written **RED-first** before the code.

## 10. Acceptance-spec delta

**Not produced.** Fork B's §10 was a full review of 45 feature files / 154 scenarios against its
direction; the suite is 283 scenarios now, and Fork C owes the same review when its first slice is
designed — not before, since a delta written against an undesigned slice is fiction.

Named now only so the review is not started from zero, the features most likely strained by a trace
that is a tree of tool calls rather than a conversation: `context_scoping` (branch-only scope over a
subagent's branch), `reply_nesting` / `branch_index` (a node that is a tool call, not a message),
`config_guardrails` (the working-directory and jail rails, which are exactly the kind of config that
must be asserted rather than trusted — §5), `generation_sad_paths` (a run that dies mid-trace, with
half a tree already persisted).

Standing rule, unchanged: rewordings are applied only in the slice PR that touches the feature. No
pre-emptive edits to green feature files.

## 11. Open questions

1. **Read scope, and where project files sit relative to the jail** — the spec's own Fork C open
   question, still open. Which paths, whose repositories, mounted read-only how, and what a persona
   is told about the boundary.
2. **The prompt-injection threat model** with file read (+ artifacts, + web) — trust boundaries,
   sanitisation, least privilege, audit. The spec calls this **blocking**, and §8 agrees: it is the
   gate on the first slice, not a parallel workstream.
3. **Multi-turn: does Fork C use `--resume`?** Re-derive from §3.5's corrected number; do not
   inherit Fork B's §8 ruling. The trade is a warm hour-long cache against a transcript that grows
   monotonically and a session that is state living outside the DB.
4. **What a run *is* in the data model.** One thread per run is the leaning (§4). Open: is a comment
   ever a *tool call*, or only a message with tool calls attached (#15's `generation_tool_call`
   already chooses the latter for the audit trail); how much tool output is stored and at what
   truncation; and what the trigger surface even is — an admin button, an MCP tool the agent calls
   itself (§2), or a directory watcher.
5. **The owner model, and persona state.** Does camouflage carry (§7)? Does the `+1` firewall mean
   anything over a diff? Should a work persona hold memory of *the project* (§6)? Three questions,
   one owner call, because answering them separately is how the two forks' postures blur.
6. **The cost brake for a bursty cadence** (§8) — per-run cost cap, runs/day, or a hard stop; and
   what a run does when it hits it mid-trace.
7. **Stays-Cut check** — standing item, the Fork C twin of `ai-driven-forum-direction.md` §11.7. Has
   any slice re-imported the quantified reward economy in work clothes: a confidence score, a
   severity rank, a per-persona accuracy tally, anything model-written that becomes machine-read as
   a magnitude? And the two Fork C-specific relapses: has anything started posting **on a timer**
   (that is Fork B), or given a persona **write access** by default (§2)?
8. **Deployment shape.** Fork B's answer was "a flag on the same app and DB". Fork C is not
   obviously the same call: a deployment reading a private repository has a different blast radius
   from one reading public RSS, and the jail's credential exposure (#14) is shared per-deployment,
   not per-fork.
9. **Voice** (§3.3) — deferred. Revisit only if an API surface appears; there is nothing to design
   against today.

## 12. Decision log (this doc)

| Date | Decision | Why |
|---|---|---|
| 2026-08-09 | Fork C gets **its own success criterion** — *a finished session's record is worth reading and replying into* (read / branch / re-enter) — and is explicitly **not** reviewed against Fork B's "worth opening between sessions" | Coding work will never satisfy the ambient yardstick, so every Fork C slice reviewed against it fails for a reason that has nothing to do with the slice. Two forks sharing one criterion also blurs their decision logs into each other, which is the failure this document exists to prevent |
| 2026-08-09 | **The CLI-subprocess path stays; the Claude Agent SDK is not a Fork C option** | Anthropic does not permit third-party products to use claude.ai login or rate limits — the SDK included — and SDK use falls under the Commercial Terms; the SDK is Python/TypeScript only, and the documented cross-language path is running the CLI as a subprocess, which `ProcessLlmClient` already is (§3.1). Adopting it would trade a built, terms-clean Tier-1 seam for an API key and a rewrite of the one port the test architecture rests on |
| 2026-08-09 | **Fork C is built on this codebase, not started clean** | `parent_tool_use_id` → `comment.parent_id` is not an analogy but the same structure (§3.2), so the recursive-CTE branch queries, per-branch context scoping, nesting/rail rendering and depth accounting all already exist and are already tested. A greenfield trace viewer would re-derive the one part of this project that is genuinely finished |
| 2026-08-09 | **Subagent bodies are a flag with a version floor**, and verifying it against the installed CLI is the first act of any Fork C substrate slice | The default stream carries only subagent `tool_use`/`tool_result`; text and thinking need `--forward-subagent-text` (Claude Code ≥ v2.1.211), and nested-subagent messages appear only from ≥ v2.1.219 (§3.2). The tree is free; the words are not — and a renderer built for words the stream never emits is a whole slice spent on nothing |
| 2026-08-09 | The **Docker jail (#14) stops being deferrable at Fork C's first file read** | `ProcessLlmClient.spawn` roots the subprocess in a neutral temp dir specifically so the project's own `CLAUDE.md` cannot leak into a prompt; Fork C inverts that decision on purpose (§5), removing the only structural barrier between a poisoned repository file and the model. What is a documented residual for ambient feed text is the spec's blocking concern here |
| 2026-08-09 | **Per-run `total_cost_usd` from the stream is the observability ceiling**; plan-limit bars are not a target | `/usage` is an interactive view computed from local session history on one machine, and OpenTelemetry exports tokens and cost but no plan windows (§3.4). Writing the ceiling down now is cheaper than re-litigating it in six months, and it keeps #16 from promising a gauge that cannot be built |
| 2026-08-09 | The **prompt-cache TTL premise is corrected (one hour on a subscription)**, and Fork C **re-derives** the sessions decision rather than inheriting Fork B's | The 2026-07-18 "stateless per-run" conclusion was argued from five minutes, and survives on ambient cadence because ticks are hours apart — but a coding session's turns are minutes apart, inside the corrected hour, which is exactly the regime the old number ruled out (§3.5). A conclusion that outlives its premise must be re-argued at the fork that lives in the different regime |
| 2026-08-09 | **Fork B's owner posture is not inherited by default** — camouflage and the `+1` firewall are recorded as a divergence (§7), with the call left open in §11 | The camouflage argument protects a world that is supposed to run without its owner; in a work deployment the owner is the author of the work and the personas can read the diff regardless. Recording it as an open divergence is the only way it does not get inherited silently just because it is written down next door |
| 2026-08-09 | **No slice map yet** (§9). Substrate first, as issues #14 → #15 → #16; Fork C's own first slice waits on owner calls for read scope and the threat model | The spec calls injection defence blocking, so a file-reading slice designed ahead of that answer would be designed against a boundary nobody has drawn. Meanwhile the three substrate issues each hand something here by name, so the ordering is already implied by their own scopes |
