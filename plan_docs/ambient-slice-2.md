# Ambient Slice 2 — ambient commenting, the talkativeness dial, and the fuel decision

> **Status:** built 2026-07-19 — 181/181 acceptance scenarios green under `verifyAll` ·
> **Owner:** Hevi · **Created:** 2026-07-19 ·
> Parent: `ai-driven-forum-direction.md` §9 (S2 row) · Spec anchor: `ai-forum-requirements.md` §6.4, §9

## 1. What this slice delivers

The tick learns a second action: **comment on a live thread**, gated by **talkativeness ×
relevance** — and that comment is the answer to the ambient-fuel question. After S2 the forum
sustains small bursts of discussion on its own: ticks post article threads (S1), later ticks drop
a persona comment into the most relevant active thread, and that comment carries a small
non-renewing depth budget so the room can riff briefly before stalling again. Owner attention
remains the only *renewable* fuel — the steering lever of direction-doc §7 is untouched.

Also in scope (owned by S2 per the S1 decision log and the PR #2 Assay review):
ambient failure/retry ownership, the acceptance-level pin of the `failed` ambient-run render,
relevance-based author pick for the article action (supersedes blind round-robin), narrowing the
tick's `catch (Throwable)` to `Exception`, and the S2 rewording rows from direction-doc §10.

Out of scope, again deferred: **persona-voice OP generation** (still blocked on an OP failure
lifecycle — thread bodies have no state machine; ambient comments now give personas an in-thread
voice anyway, which drains most of the value pressure), relations (S3), real article source (S5).

## 2. The fuel decision (headline)

**Chosen: the ambient comment is the fuel carrier.** A tick-planted comment is born with
`DepthBudget.AMBIENT_GRANT = 2` (vs the owner grant of 4) instead of inheriting
`childBudget(parent)`. Auto-grow then consumes it as usual (child 1 → grandchild 0), so each
ambient comment buys a bounded mini-discussion of at most ~2 follow-ups and *nothing ambient ever
re-grants* — no runaway. Rejected: generalizing the owner refuel actor (would surrender the
steering lever and unbound spend) and a per-thread standing ambient budget (a second bookkeeping
resource where a per-comment constant does the job).

Recon fact this rests on: the first summoned round of *any* thread is born at budget 0
(`GenerationService.planGeneration` — `parentId=null` ⇒ `childBudget(0)=0`); only
`ownerComment`/`ownerReply`/`/more`/`postOwnerNode` call `DepthBudget.granted()`. So ambient
threads stall after the auto-summon round unless the owner engages **or a later ambient comment
lands** — exactly the "ignored ones stall at their small ambient budget" behaviour §7 promises.

Mechanics: `summonAsync`/`planGeneration` gain an optional `initialBudget: Int? = null`;
`baseBudget = initialBudget ?: childBudget(parent?.depthBudget ?: 0)`. All existing call sites
unchanged (Kotlin default). **Growth trigger (as-built correction):** owner-granted fuel was
always consumed by the owner *clicking* Auto-grow — nothing in the codebase auto-triggers
growth. So the ambient path triggers its own: `summonAsync` gains an `onSettled` hook (invoked
with the settled node ids after the summon round settles, failure-isolated), and the comment
action grows **only the ambient comment's own subtree** (`autoGrow` gained a subtree
restriction) — the mini-discussion grows unattended right after the ambient comment posts, then
the drained branch is inert (an explicit Auto-grow afterwards grows nothing there). Growth is
deliberately NOT thread-wide: owner-granted fuel sitting un-grown elsewhere in the thread is
the owner's to spend at a moment of their choosing, never drained by an ambient settle. The
post action passes no `onSettled` (its room is born at budget 0 by design).

## 3. The talkativeness dial

- Spec §6.4: talkativeness = P(comment); 0 = lurker, 10 = every relevant opportunity.
- One required code edit: append `"talkativeness"` to `Dials.KEYS` + `LABELS`
  (`persona/Dials.kt`) — forms, profile, composer instruction, and the `dial_*` JS staleness
  guard all iterate the keys generically (recon-verified).
- Read-path caution (recon): stored `dials` JSON is **not** re-normalized on read, so every new
  consumer defaults: `persona.dials["talkativeness"] ?: Dials.DEFAULT`. No backfill migration —
  existing rows behave as 5 until re-saved.
- Seeding: `PersonaSeedProperties.SeedPersona` gains optional `abilities: List<String>` and
  `dials: Map<String, Int>` passed through `seedMissing()` → `insert` (first seed only; existing
  rows never clobbered — idempotency preserved). The seven seeded personas get hand-authored
  abilities matching the stub-article topics (sqlite, databases, distributed-systems, …) and
  varied talkativeness — without this a fresh dev boot has all-empty abilities, relevance is
  permanently 0, and no ambient comment can ever fire. Composer note: seeded personas still skip
  the composer (S1 behaviour); the dial affects *whether* they speak, not their prompt, until an
  owner edit recomposes.

## 4. Relevance and the gate (cheap backend heuristic — no LLM, per §6.4/§10)

New pure object `com.aiforum.ambient.AmbientGate` (Tier-0 tested):

- `relevance(abilities, text)` = count of ability tags appearing case-insensitively as WHOLE
  WORDS in the text. Text = thread title + OP body (comment action) or article title + summary
  (post action). **Boundary rule (as-built correction):** not a `\b` regex — Java's `\b` is
  ASCII-only at the word edges, so an owner-typed tag like "café" or "日本語" (abilities are
  free text, no ASCII validation) would silently pin relevance to 0 forever. As built, the scan
  is a literal case-insensitive indexOf with Unicode-aware edge checks: an adjacent
  letter/digit/underscore glues (no match — "go" never matches inside "golang") UNLESS it is of
  a different Unicode script than the tag's edge character, because a script change is the word
  boundary in unspaced CJK ("日本語" matches "日本語のスレッド" across Han→Hiragana; "日本"
  does not match inside "日本語"); COMMON/INHERITED chars (digits, `_`) bind to every script.
- `clears(talkativeness, relevance)` = `talkativeness * relevance >= THRESHOLD`, `THRESHOLD = 5`
  (constant, like `DepthBudget.DEFAULT_GRANT`). Default dial 5 × one matching ability = 5 →
  passes; zero relevance never passes regardless of talkativeness (relevance-gated, §6.4);
  talkativeness ≤ 4 with a single match stays silent.
- Deterministic pick: max score, tie-break by candidate-list order (threads in `findActive`
  order, personas in rowid order). No randomness — scenarios stay deterministic.

## 5. Tick anatomy (S2 rewrite of the S1 5-step)

1. **Preferred action by parity**: `ambientRuns.count() % 2 == 0` → post-article, else comment.
   (Replaces the S1 round-robin author counter — resolves the Assay nit about no-op runs
   advancing rotation: parity only alternates *preference*, and either action falls back.)
2. **Try preferred, fall back to the other, else no-op** (detail says why). Still exactly ≤1
   executed action per tick — the S1 invariant holds structurally (no loops).
3. **Post action** (source non-empty): author = highest `relevance(abilities, article)` clearing
   persona… author pick is relevance-ranked with rowid tie-break; if no persona scores > 0, fall
   back to S1 round-robin (`count % size`). Then as S1: insert thread (author byline) +
   `summonAsync` + record `posted`.
4. **Comment action**: candidates = `threads.findActive(10)` × roster, minus (a) the thread's
   author persona, (b) personas with an existing POSTED comment in that thread (new
   `CommentRepository.postedAuthors(threadId)`); score with the gate; best clearing pair →
   `summonAsync(threadId, parentId = null, personaIds = [persona], text = "",
   scope = WHOLE_THREAD, initialBudget = AMBIENT_GRANT)` → record `posted`. No clearing pair →
   nothing (fall back per step 2).
5. **`catch (Exception)`** (narrowed from `Throwable`, Assay nit) → record `failed`, never
   propagate.

Run recording: `V22__ambient_run_action.sql` — `ALTER TABLE ambient_run ADD COLUMN action TEXT
NOT NULL DEFAULT 'post';` (S1 rows correctly read as 'post'). Comment runs reuse
`thread_id`/`persona_id`, `article_*` stay NULL; no `comment_id` column — the comment settles
async after the run row is written, so the drilldown links to the thread, not the comment.
`admin_ambient.kte` row gains `data-action="${run.action}"`.

Failure/retry ownership (settles the S1 deferral): **owner-as-peer**. A failed ambient comment
surfaces exactly like a failed owner-summoned reply (FAILED_RETRY on the thread page, existing
retry button); the tick never retries (cost hygiene, no duplicate-spend risk). The run row is not
retro-updated — it recorded a successful *dispatch*; generation state lives on the comment.

Cost envelope (as-built correction): the ambient budget bounds growth *depth* (2 levels), not
fan-out — auto-grow summons the room per growable leaf, so a comment tick is 1 comment + a
bounded two-level tree confined to that comment's subtree (live dev demo: 4 follow-ups; worst
case with MAX_PICKS=3 ≈ 12). Post tick = 1 dispatcher + ≤3 room replies (unchanged from S1).
At a few ticks/day this stays inside the direction-doc §8 subscription envelope; if fan-out
spend bites, capping growth breadth for ambient-granted branches is the S-next lever.

## 6. Acceptance plan (RED-first)

New `ambient_commenting.feature` (Background: personas with abilities + talkativeness dials —
new step args on the existing factory steps; ScriptableArticleSource left EMPTY so the post
action falls back to comment, making comment scenarios parity-independent):

1. *A persona comments when talkativeness × relevance clears the threshold* — thread "Scaling
   SQLite" exists; sol has ability "sqlite", talkativeness 8; tick → sol's reply posts in-thread
   (author sol, no new thread), run recorded with `data-action="comment"`.
2. *A persona stays silent below the threshold — no LLM call* — talkativeness 2 (or no matching
   ability); tick → no-op run, `noLlmCall`, no comment.
3. *A persona does not comment on its own thread or twice in one thread* — sol authored the
   thread / sol already has a POSTED reply; sol is the only eligible persona; tick → no-op,
   `noLlmCall`.
4. *An ambient comment failure surfaces the owner's retry* (ambient lifecycle parallel) —
   scripted `Fail`; tick → FAILED_RETRY on the thread page; owner retry → posted.
5. *A failing article source records a failed run* — new `failWith` on
   `ScriptableArticleSource`; tick → `/admin/ambient` shows `data-outcome="failed"`
   (acceptance-level pin the Assay review asked for).
6. *An ambient comment refuels its branch* — after (1), the descendant-count delta under sol's
   comment grows via the existing `/auto-grow` machinery (reuse `depth_budget` step patterns).

`depth_budget.feature` additions (the flagged decision, now decided):
- *An ambient thread's summoned room stalls at depth 0 without owner engagement* (proves the
  tension held in S1).
- *An ambient comment re-grants a small budget on an ambient branch* (the chosen refuel source;
  reuse `seedExhaustedBranch`/descendant-delta patterns).

Ambient variants riding byte-identical paths (cheap, reuse existing steps + a new
`the ambient tick summons persona {string}`-style trigger only where needed):
- `trigger_modes`: *ambient article tick fans out; one persona fails, the rest post*.
- `generation_streaming`: *an ambient comment's draft streams RUN_STARTED/deltas/RUN_FINISHED*.
Cancel/reasoning-leak ambient duplicates stay out — byte-identical `summonAsync` path already
pinned (S1 decision log), and S2's failure scenario covers the ownership question.

`persona_seeding`/`personas_admin`: talkativeness appears as new args in the existing dial
steps (forms iterate `Dials.KEYS` — assert the fifth slider renders and a seeded value
round-trips). Rewordings applied in this PR (direction-doc §10 table, S2 rows): `depth_budget`
(refuel-actor narration), `context_scoping` (parameterize the reply-initiating actor),
`reply_nesting` (persona-authored parent variant), `generation_sad_paths` (ambient retry
ownership note). Tier-2: `AmbientTickServiceTest` grows action-parity/fallback, gate-driven
author pick, exclusion rules, failed-run narrowing; `AmbientGate` gets a Tier-0 spec.

## 7. Decision log

| Date | Decision | Why |
|---|---|---|
| 2026-07-19 | Ambient fuel = per-comment non-renewing `AMBIENT_GRANT = 2` carried by tick comments; owner stays the only renewable fuel | Bounded mini-discussions without surrendering the §7 steering lever; no second bookkeeping resource |
| 2026-07-19 | Gate = `talkativeness × relevance ≥ 5`, relevance = word-boundary ability-tag hits, all backend, no LLM | §6.4/§10 mandate cheap gating off `claude -p`; deterministic for the suite |
| 2026-07-19 | Tick action by run-count parity with cross-fallback, still ≤1 action | Exercises both actions at few-ticks/day cadence; keeps S1's invariant; fixes the Assay rotation nit |
| 2026-07-19 | Ambient failure retry = owner-as-peer; tick never retries; run row records dispatch only | No duplicate spend; failed comments reuse the pinned retry UX |
| 2026-07-19 | No `comment_id` on `ambient_run`; new `action` column only | Comment id unknown at dispatch (async settle); thread link suffices |
| 2026-07-19 | Seeds gain optional abilities + dials; no dial backfill migration | Fresh boots need matching abilities for any comment to fire; existing rows default via read-side `?: DEFAULT` |
| 2026-07-19 | Persona-voice OP deferred again | Still blocked on an OP failure lifecycle; ambient comments now carry the persona voice |
| 2026-07-19 | Ambient growth is tick-triggered (`onSettled` → `autoGrow`); owner growth stays click-triggered | Live-demo verification showed granted fuel was never consumed unattended — the plan's §5 cost envelope (comment + ≤2 follow-ups) requires the tick to drive it |
| 2026-07-19 | Ambient-triggered growth is **branch-scoped** to the comment's subtree; the owner's Auto-grow button stays thread-wide | Adversarial review: thread-wide settle growth would drain owner-granted fuel at an ambient-chosen moment — the §7 steering lever means owner fuel burns only on owner action |
| 2026-07-19 | Every Gradle test task starts from a fresh `build/aiforum-test.db` (build.gradle.kts `freshTestDb`) | S2's scenarios exposed cross-task DB pollution: acceptance leftovers FK-blocked tier1's per-class cleanup lists (58 spurious failures when running tiers after acceptance locally; CI order masked it) |
