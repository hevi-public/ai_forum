# Ambient Slice 6 — the feed front page: thread cards, the activity stream, and the persisted view

> **Status:** ✅ built 2026-07-27 (V29) — `./gradlew verifyAll` green, suite 263 → 283 scenarios,
> tier 0/1/2: 439/265/156, jsTest 97 · designed 2026-07-27 · owner calls answered 2026-07-27
> (§10, §10b.6, §10b.7) ·
> **Owner:** Hevi · **Created:** 2026-07-27 ·
> Parent: `ai-driven-forum-direction.md` §9 (S6 row — the last slice on the map) / §10 (the pre-authored
> `home_rail` / `empty_and_unread` pair) / §11.7 Stays-Cut · Spec: `ai-forum-ux-brief.md` §57,
> `ai-forum-ux-feedback-v2.md` ① · Predecessor: `persona-memory.md` (V28)

## 1. What this slice delivers

The front page stops being one list and becomes **two views over the same forum**, chosen by a control
that remembers:

- **Thread cards** (the default) — the existing thread index, restyled from a bare title row into a card
  carrying the persona attribution badge, the relative time of last activity, the `N new` unread delta,
  and a one-line preview of the newest comment in that thread. **Activity-sorted**, which the front page
  is not today.
- **The activity stream** (behind the toggle) — one reverse-chronological list in which every thread
  opening and every settled comment is its own card, with its author, an excerpt, and a link **into the
  thread at that comment**.
- **The view persists**, so the owner's choice survives leaving and coming back.

The organising constraint, which buys almost everything else: **the thread card keeps its element, its
class and its four existing `data-*` hooks in their existing order**, so the delta is `+20` scenarios
and **zero rewritten `.feature` lines**.

### The owner call this slice implements, and the one word it cannot honour

Recorded 2026-07-27: ship **both** readings as one surface, thread cards by default, the toggle
persisting per owner. §9's scope line and that call both say the stream shows **ambient** events.

**The schema cannot express "ambient", and this design does not pretend otherwise (D1, I6).**
`AmbientTickService.tryComment` calls the same `GenerationService.summonAsync` an owner summon does,
with no marker of any kind: there is no `comment.source`, no `ambient_run_id`, no trigger mode.
`depth_budget` is not a discriminator either — an ambient top-level grant is `AMBIENT_GRANT`, but so is
an auto-grow grandchild of an owner grant. `ambient_run` cannot be joined: V22's own header records that
it carries no `comment_id` **on purpose**, because the run row is written at dispatch and the comment
settles afterwards. A V29 `comment.origin` column would be NULL for all history and would read as a lie
for every existing row — the argument that killed the `core` column in S4b.

So the view ships as the honest superset — **all settled activity, author-agnostic, named "Activity"
and never "Ambient"**, in the toggle, the `<h1>`, the empty state and this document. This was a
re-decision of the owner call's own wording, put to the owner rather than buried in an implementation
note, and **answered 2026-07-27: ship Activity now, revisit later** (§10.1). An ambient-only stream is
**deferred, not refused** — it is a later slice whose §2 must first make provenance representable.

## 2. Design

### 2.1 How the page is assembled

`HomeController` reads the persisted view, issues **only that view's query**, and hands the template one
typed object.

```kotlin
@Controller class HomeController(
    private val threads: ThreadRepository,   // now only for count()
    private val feed: FeedRepository,        // NEW
    private val prefs: OwnerPrefRepository,  // NEW
    private val personas: PersonaRepository,
    private val railFeeds: RailFeeds,
    private val shortcut: ShortcutService,
    private val clock: Clock,                // NEW — RelativeTime needs `now`
)
```

**Note what leaves: `ThreadReadRepository`.** Today's front page is a 2N+1 —
`threads.findAll().map { threadReads.unreadCount(it.id) }`, two queries per row over an unbounded
`findAll()`. Both collapse into one grouped read. Dropping the dependency is the point (I4): a per-row
unread call now costs a visible constructor change rather than a line slipped into a `.map {}`.
**Absent parameter beats guard beats test.**

```kotlin
enum class FeedView(val slug: String, val title: String, val emptyStateKey: String) {
    THREADS("threads", "Threads", "no-threads"),
    ACTIVITY("activity", "Activity", "no-activity");
    companion object { val DEFAULT = THREADS; fun of(slug: String?) = entries.firstOrNull { it.slug == slug } }
}

data class FeedPage(val view: FeedView, val threadCards: List<ThreadRow>, val events: List<ActivityRow>)
```

`FeedPage` holds **two typed lists, never one shared card type**: JTE's typed `@param` then makes handing
an `ActivityRow` to the thread-card fragment a *build* failure rather than a review question. `ThreadRow`
is **extended, not replaced** — three defaulted fields (`ago`, `excerpt`, `excerptBy`) appended to the
existing four, so its name and shape survive.

The projection from repository rows to card rows is `object FeedCards` — an object, not a `@Component`,
which puts it in Tier 0 by the testing skill's own definition.

### 2.2 The two reads (`FeedRepository`)

One class owns the feed's SQL so the two views cannot drift in their state filter, tie-break or unread
expression — the `RailFeeds` argument, one level down. **Both queries below were measured against a
scratch DB built from all 28 migrations, not reasoned about.**

**Thread cards — uncapped, activity-sorted, one row per card:**

```sql
SELECT t.id, t.title, t.author_id,
       COALESCE(n.created_at, t.created_at) AS last_activity,
       COALESCE(n.body,       t.body)       AS excerpt_body,
       COALESCE(n.author_id,  t.author_id)  AS excerpt_author,
       (n.id IS NOT NULL)                   AS excerpt_is_reply,
       (SELECT COUNT(*) FROM comment u
         WHERE u.thread_id = t.id AND u.state = 'POSTED'
           AND u.created_at > COALESCE(r.last_read_at, '')) AS unread_count
  FROM thread t
  LEFT JOIN thread_read r ON r.thread_id = t.id
  LEFT JOIN comment n ON n.id = (SELECT c.id FROM comment c
                                  WHERE c.thread_id = t.id AND c.state = 'POSTED'
                                  ORDER BY c.created_at DESC, c.id DESC LIMIT 1)
 ORDER BY last_activity DESC, t.id DESC
```

`COALESCE(n.created_at, t.created_at)` is exactly `ThreadRepository.findActive`'s
`COALESCE(MAX(CASE WHEN state='POSTED' …), t.created_at)` — `n` **is** the max — so the draft-only
fallback `ThreadRepositoryTest` already pins is subsumed rather than duplicated.
`u.created_at > COALESCE(r.last_read_at,'')` is `ThreadReadRepository.unreadCount`'s two branches
collapsed into one (verified: `'2026-01-01T12:00:00Z' > ''` is 1, so an absent marker counts everything).

A **reply-less thread previews its own opening post** — so a fresh ambient article thread's card shows
the article summary instead of an empty slot. This is the single most valuable small-forum behaviour in
the design.

**Uncapped is deliberate (I10):** there is no `GET /threads` index route — only `/threads/{id}` and
`/threads/{threadId}/room` — so a cap would make thread 51 unreachable from anywhere in the app.

**The activity stream — one UNION ALL, capped at 50:**

```sql
SELECT 0 AS is_post, c.id, c.thread_id, t.title, c.author_id, c.body, c.created_at,
       CASE WHEN c.created_at > COALESCE(r.last_read_at,'') THEN 1 ELSE 0 END AS unread
  FROM comment c
  JOIN thread t ON t.id = c.thread_id
  LEFT JOIN thread_read r ON r.thread_id = c.thread_id
 WHERE c.state = 'POSTED'
UNION ALL
SELECT 1, t.id, t.id, t.title, COALESCE(t.author_id, 'owner'), t.body, t.created_at, 0
  FROM thread t
 ORDER BY created_at DESC, is_post DESC, id DESC
 LIMIT ?
```

The comment leg is an **INNER JOIN** to `thread`, so a deleted thread's events can never surface.
There is **no `WHERE t.author_id IS NOT NULL`** on the thread leg, deliberately: excluding owner posts
would make a brand-new forum with three owner threads and no settled replies render *"Nothing has
happened yet"* with three threads one click away, and in steady state would show replies to a post it
refuses to show.

**The tie-break is load-bearing, not decorative.** Measured: with `is_post DESC, id DESC` a fixture of
three comments and two threads all stamped `12:00:00Z` returns a stable order; without it the order
differs. Because the mutation is *observable*, it earns a §7 ledger entry rather than a §10.4 confession.

### 2.3 The persisted view (V29)

```sql
CREATE TABLE owner_pref (
    id         INTEGER PRIMARY KEY CHECK (id = 1),
    feed_view  TEXT    NOT NULL CHECK (feed_view IN ('threads','activity')),
    updated_at TEXT    NOT NULL
);
CREATE INDEX idx_comment_thread_posted ON comment(thread_id, created_at DESC) WHERE state = 'POSTED';
CREATE INDEX idx_comment_posted_recent ON comment(created_at DESC)            WHERE state = 'POSTED';
```

Single-user PoC, no auth by design, so **"per owner" is one global row** and the owner is implicit — the
`thread_read` (V2) precedent, keyed by the thing rather than by a user. **No seed row: absence IS the
default**, which is exactly what lets every existing front-page scenario keep its Gherkin untouched and
what makes the reset hook's `DELETE` restore the default rather than a stored value.

One column per setting, **not** a key/value bag: a KV table structurally cannot `CHECK` a value against
its key, so the enum guardrail would evaporate the moment a second setting landed. A future setting is a
nullable `ADD COLUMN`.

Both indexes are measured wins, not guesses. V17's `idx_comment_thread_order` is
`(thread_id, depth, created_at)` — `depth` sits between the equality column and the sort column, so it
serves neither feed read. With `idx_comment_thread_posted` the unread count becomes a **covering range
scan** instead of a scan of every comment in the thread; `idx_comment_posted_recent` also speeds the
**pre-existing** `CommentRepository.recentPosted`, which runs on every home *and* thread page load.

**Three layers guard the value, weakest last:** the Kotlin enum (`setFeedView` takes `FeedView`, so the
repository has no `String` door), `FeedView.of`'s 400 refusal at the endpoint, and the DDL CHECK. All
three verified to refuse `id = 2` and `feed_view = 'chronological'` with the stored row unchanged.

### 2.4 Why the toggle is a server-side form and not the three obvious alternatives

| Rejected | Why it cannot work here |
|---|---|
| `localStorage` | The only existing toggle precedent (theme, rail collapse) is client-only and **invisible to the acceptance suite**, which drives no browser. "The toggle persists" would be unpinnable above jsTest. |
| A cookie | `HttpClient` is a bare `RestClient.create()` with no cookie jar and a fresh exchange per call, so `Set-Cookie` is dropped between two `http.get("/")` calls — the persistence scenario would fail against a **correct** implementation. |
| htmx | htmx caches a form's request path; mutating `hx-post` via `setAttribute` is ignored. It shipped broken once (PR #52) and **no test tier catches it**. A control whose target URL flips with state is exactly that shape. |
| A config knob | `FeedView.DEFAULT` is a Kotlin constant pinned by one scenario; a property would drag in `@ConfigurationProperties`, both yml files, a `/__diag` rail and a `config_guardrails` scenario for a value the owner flips in one click. |

Two plain PRG forms with **fixed** actions and fixed hidden values. Accepted consequence, stated:
switching views is a full page load. **The view toggle still ships zero JavaScript** — but the slice as
a whole no longer does; see §10b.7.

### 2.5 Markup rules that are not style preferences

**I2 — no free-form body text may reach a `data-*` attribute value.** Measured against gg.jte 3.2.4's
`OwaspHtmlTemplateOutput`: in **attribute** context it does **not** escape `>`; in body context it does.
`Html.threadRowAttr`'s `<[^>]*…[^>]*>` tag regex cannot cross a literal `>`, so a 120-char prose excerpt
in a hook would truncate the tag and make **every hook after it unreadable** — silently, on a page four
feature files probe. So `data-thread-excerpt` carries the **thread id**; the prose is child text.

**Booleans must be rendered as explicit strings.** JTE *drops* a Boolean-valued attribute when false —
that is precisely how `open="${threads.isEmpty()}"` produces the collapsed composer `home_rail` asserts.
`data-feed-unread="${e.unread.toString()}"` and `aria-pressed="${if (o == view) "true" else "false"}"`,
or the read case and the unselected case become unassertable.

**The flat contract, at two levels** (I7): one `<li>` per activity card with flat `<span>`/`<a>`
internals, because `Html.liBlock` cuts to the first `</li>`; and **no nested `<span>` inside a hooked
`<span>`**, because the excerpt is read with the `Html.dialText` idiom — which is why the excerpt's
byline is a `<b>`.

**`.thread-row` is not renamed and `rowDelete` stays its direct child** (I8). This is structural CSS no
test reaches: `app.css` hides the trash at rest and reveals it only on `.thread-row:hover`; makes
`.row-delete__confirm` a sibling with `flex-basis:100%` inside the `.thread-row` flex-wrap container; and
`rowDelete.kte` feeds `rowSelector` into both `hx-target` and a raw `onclick`. `thread_deletion` asserts
only the `hx-post` string, so a rename would break hover-reveal, the confirm line-drop, the swap target
and Cancel — **all silently**.

The toggle's active modifier is `--on`, **never** `--open`: `HomeRailSteps.composerIsOpen()`
word-boundary-matches `\bopen\b` inside the ask-card's opening tag.

## 3. Cost shape, stated plainly

**Zero LLM calls.** This slice adds no seam call, no prompt and no scheduler. It reads the DB and renders.

Per front-page load: **one** feed query (only the active view's) plus the unchanged rail reads — down
from `1 + 2N`. The `+18` scenarios cost no LLM calls either: the ambient-attribution scenario seeds by
direct INSERT rather than buying two generations the way its `ambient_tick` ancestor does.

## 4. Constraints and guardrails

**Stays-Cut (direction §11.7) — re-checked explicitly and CLEAN.** The feed's only two orderings are
time-only: reverse-chronological for the stream, last-POSTED-comment for the thread cards. No engagement
weight, no "hot" ranking, no score column, no per-member count on any card, no aggregate keyed to a
member. The one number on the surface — the unread delta — is keyed to a **thread**, already ships (V2),
and is the owner's own read state. Any future "sort by" control or per-persona card count needs its own
argued D-number.

**This slice touches no prompt.** Nothing here can reach a model, which is why the no-numbers rule that
dominates S4a/S4b/persona-memory has nothing to bind on beyond the Stays-Cut check above.

The invariants I1–I11 are listed in §7 beside the mutation that proves each one.

## 5. What this slice does NOT do

- **No pagination and no cursor**, refused explicitly rather than forgotten. The stream is a flat
  `LIMIT 50` that **discloses its own truncation** (`data-feed-more`). The moment anyone adds "load
  more" with a `created_at` cursor this becomes a real dropped-row bug, because of the whole-second
  anomaly in §10.4 — that slice's §2 must name which of the repo's three handling patterns it picks.
- **No live updates.** No SSE, no polling; the feed is what the DB held at render time.
- **No ambient-only filter** — see §1 and I6; it is not expressible.
- **No `GET /threads` index route.** Out of scope, and its absence is why the thread list is uncapped.
- **No retrofit of the four existing unguarded `Instant.parse` sites** (`RailFeeds` ×3,
  `AmbientController`). `agoOrNull` is added and used on the **new feed path only**; retrofitting is a
  behaviour change to every thread page and belongs in its own PR.
- **No `Snippet.kt` modification.** `FeedExcerpt` is a new object; `Snippet` feeds every rail box, branch
  index and in-reply-to line, and refactoring it for a preview nicety is real risk for no gain. A Tier-0
  test asserts the two agree on a URL-free body so they cannot drift silently.

## 6. Acceptance scenarios, RED-first

`+20`, **zero rewritten `.feature` lines**, one existing feature file edited (a stale header comment plus
two appended scenarios). Verify the acceptance task's printed count rises by exactly 20 — the **delta**
is the robust check; absolutes drift with sibling merges. *(18 at design time; +1 for the rail-suppression
scenario D11 bought; +1 for scenario 9b, the double-naming fix in §10b.6. Built figure: 263 → 283.)*

**Why nothing re-scopes:** a fresh DB has no `owner_pref` row, absence is THREADS, and the reset hook
wipes the table before every scenario. Adding a view `Given` to the nine untouched front-page scenarios
would be nine steps that can never fail; instead **one** scenario pins the default, so those nine have a
named guard.

New file `front_page_feed.feature` — 17 scenarios, every one behaviourally RED today:

1. The front page opens on the thread-card view *(the guard for the nine untouched scenarios)*
2. The front page offers a control for each view, marks the one it is showing, and still offers both on an empty stream
3. Switching to the activity view redraws the front page — **and shows no thread cards**
4. Switching back to threads restores the thread cards *(the other direction, so a one-way mutation cannot pass)*
5. The chosen view survives leaving and coming back *(a second independent GET, unwritable against localStorage)*
6. An unknown view name is refused and the stored view is unchanged *(400)*
7. A thread card previews the newest comment in its thread *(newer present **and** older absent)*
8. A thread with no replies previews its own opening post *(twin: a title-only thread shows no preview)*
9. A thread card names the voice its preview came from *(twin: an owner thread previewing its own OP shows no byline)*
10. A thread card shows how long ago the thread was last active
11. **Thread cards are ordered by last activity, not by creation** *(both directions asserted)*
12. The activity view interleaves posts and comments, newest first
13. A thread the owner opened is its own card in the activity view too *(the no-author-predicate pin)*
14. Unsettled replies never reach the activity stream *(POSTED present, FAILED and CANCELLED absent, one page)*
15. An activity card links into its thread at that comment
16. **Unread means the same thing in both views** *(the coherence pin)*
17. The activity view hides the recent-comments box and still shows the other three rails *(D11; the positive twin is what stops it passing by failing to render the rail at all)*

Appended to `empty_and_unread.feature`, discharging the direction doc's pre-authored pair. **These two are
CHARACTERISATION pins, not RED-first ones — and that is not a compromise, it is the honest reading:**

18. An ambient thread's card carries a persona attribution badge *(twin: an owner thread carries none)*
19. Owner-unread ambient comments increment the badge

**Why they are green the day they are written.** Both ride hooks D2 keeps *deliberately unchanged* —
`data-thread-author`, which `index.kte` already emits as a JTE smart attribute, and `data-unread-count`,
which the front page already computes. Making them red would have meant asserting behaviour §6 never
asked for (a byline resolved through the persona roster to a display name — a change to what
`data-thread-author` *means*, which `AmbientSteps` asserts as the raw id). So their job is to stay green
**across** the slice: they are what catches attribution or the unread badge breaking when the 2N+1 read is
replaced by the grouped query. `persona_memory.feature` set the precedent of naming its RED-first
exemptions in its own preamble; these are named in theirs. *(The "every one behaviourally RED today"
sentence above governs the seventeen in the new file, and only those.)*

**Every absence-shaped assertion carries a positive twin on the same page**, so "shows no X" can never
pass because the page failed to render.

**Three step-definition fixes, no `.feature` edits — and the first is a sequencing requirement.**
`HomeRailSteps.railShows` is a **page-wide** `Html.contains` justified by its own KDoc ("the home page
renders comment bodies only inside the recent-comments box") — which the card excerpt **falsifies**. In
both affected fixtures the asserted text *is* the thread's newest comment, so after S6 they would pass
**with the rail boxes deleted**. The box-scoping fix must land **in the same commit as the excerpt**.
The other two tighten page-wide probes to card-scoped ones and must land **before** any markup moves,
mutation-verified *negatively* (reorder the card's attributes; those three stay green).

## 7. Tier inventory and the mutation ledger

**Tier 2: none, deliberately.** S6 introduces no IO port, so there is nothing new to fake, and faking a
repository to test a controller is what doctrine forbids. **jsTest: `feed-core.test.mjs`** — 13 cases over the one pure decision `feed.js` makes (§10b.7). The DOM glue is manually verified, the `nav.js` precedent.

**Tier 0** — `FeedViewTest` (slug round-trip; unknown/empty/null → null; `DEFAULT == THREADS`; every slug
appears in V29's CHECK list) · `FeedCardsTest` (href per kind; a post card's id **is** its threadId, so it
lands on the existing OP anchor; `excerptBy` null iff the excerpt is the card's own owner OP; byline and
hue survive an author id with **no persona row**; `gh:octocat` → `@octocat`; unparsable stamp → `""`, no
throw) · `FeedExcerptTest` (an ambient OP body `summary\n\nhttps://…` previews the summary **alone**; a
markdown link's label survives; truncation still ellipsises; **agrees with `Snippet.oneLine` on a
URL-free body**) · `RelativeTimeTest` extended.

**Tier 1** — `OwnerPrefRepositoryTest` (fresh DB → THREADS; set→read; set twice → one row, latest wins;
raw `INSERT id=2` throws; raw `UPDATE` to an unknown view throws — **the DDL is the enforcement, not the
enum**; a hand-corrupted value still reads as DEFAULT) · `FeedRepositoryTest` (activity order beats
creation order; draft-only fallback; unread with/without a marker and never counting unsettled states;
**the equivalence test** — the grouped unread expression equals `ThreadReadRepository.unreadCount` on the
same fixture both with and without a marker, *not* a re-derivation of the same SQL; excerpt is the newest
POSTED body and falls back to `t.body`; the UNION interleaves both legs; NULL `author_id` reads `owner`; a
deleted thread's comments vanish; **total order under three identical stamps, stable across two calls**;
LIMIT respected) · `MigrationPipelineTest` → 29.

**The ledger — break it, watch the named test redden, restore, and record the result.** Any entry that
fails to redden moves to §10.4 as an unpinned gap, never stays here as a claim. *(Persona memory shipped
two unverified ledger entries and both were wrong; that is why this sentence exists.)*

| Mutation | Reddens |
|---|---|
| `ORDER BY last_activity` → `t.created_at DESC` | #11's second read |
| Excerpt subquery `DESC` → `ASC` | #7 |
| Drop the excerpt's `COALESCE` to `t.body` | #8 |
| Drop `COALESCE(r.last_read_at,'')`'s empty branch | #19 — **only because it was written to carry both branches on one page**: a never-read thread whose two comments all count, *and* a thread read 300s ago where only the later one does. The obvious one-thread version exercises the marker branch alone and would stay green under this mutation |
| Drop `state='POSTED'` | #14 |
| Drop the UNION's thread leg | #12 |
| Add `WHERE t.author_id IS NOT NULL` | #13 |
| Drop `, is_post DESC, id DESC` | the Tier-1 total-order test *(verified observable)* |
| `FeedView.DEFAULT = ACTIVITY` | #1 — **and twenty more. MEASURED, and the "only" was false.** 21 scenarios redden, including the nine untouched front-page ones the row claimed #1 was *protecting*. The named scenario does redden, so the pin is real; the exclusivity claim never was. The nine self-guard — flipping the default makes the front page render the wrong view *for them too* — which is a stronger result than the doc argued for, just not the one it wrote down |
| `setFeedView` a no-op | #3 **and** #5. *(The row originally claimed "#5 but not #3, which is why both exist". It cannot: the switch step POSTs the control's own action and then **re-GETs `/`**, so a preference that never stored reddens the switch too. Keeping the distinction would mean asserting on the POST's own response and not re-reading — which would stop pinning that the preference was **stored**, the one thing #5 exists for. Measured during the RED phase; the shape was kept and the row corrected.)* #5 still earns its place: it is the only one that re-reads in a **separate** visit, so it is what a cookie or `localStorage` implementation would fail |
| Remove the 400 refusal | #6 |
| Delete `feedToggle.kte` | #2 and #3 |
| Emit `data-thread-title` on an activity card | #3's second clause |
| Flag post cards unread | #16 |
| Remove `owner_pref` from the reset hook | run the suite **twice in different orders** and confirm the leak surfaces |
| ~~Delete the `recentCommentsBox` call from `index.kte`~~ **→ replaced: render the thread page's starred box EMPTY** (`starredComments.take(0)` in `thread.kte`) | `starred_sidebar`'s thread-page scenario — **GREEN before the box-scoping fix, RED after. Both verified 2026-07-27.** *The original row was wrong and proved nothing: `home_rail`'s recent-comments scenario asserts `data-rail-box` and counts `data-recent-comment` BEFORE its `rail shows` step, so deleting the box reddens it with or without the fix. The discriminating mutation must keep the box and empty its rows, on the page where `rail shows` is the ONLY Then.* |
| Reorder the card's `data-*` attributes | `new_thread` ×2 and `shortcut` must stay **GREEN** |

**Invariants:** I1 a stream card can never satisfy a thread-card assertion (disjoint hook vocabularies +
JTE typing + #3) · I2 no free-form text in any `data-*` value · I3 two preferences or an unknown view are
unrepresentable · I4 the 2N+1 cannot return without a constructor change · I5 "N new" means one thing in
both views · I6 the stream claims no provenance the schema lacks · I7 flat rows at both levels · I8 the
delete control survives the restyle · I9 no scenario leaks its view · I10 the front page cannot hide a
thread · I11 Stays-Cut clean.

## 8. Implementation order

1. **Step-def tightening first** (the three page-wide probes), mutation-verified negatively — before any
   markup moves.
2. V29 + `OwnerPrefRepository` + `FeedRepository` + their Tier-1 tests; `MigrationPipelineTest` → 29;
   `owner_pref` into the reset hook; `TestData`'s four **defaulted** parameters.
3. The pure objects and their Tier-0 tests (`FeedView`, `FeedCards`, `FeedExcerpt`, `agoOrNull`).
4. The 18 scenarios, RED, verified red for the right reason.
5. Controller, fragments, CSS — green. **`--no-build-cache` if a JTE change "doesn't take".**
6. Close-out audit: read shipped code against this doc, run the ledger, write §10.

**Test-clock discipline, binding on every ordering scenario.** The test `Clock` is fixed, and `TestData`
stamps it verbatim, so **every seeded row currently shares one `created_at`**. An ordering scenario
written naively would pass while asserting an arbitrary UUID order that means nothing, then break later
on an unrelated id change and be reported as an S6 regression. Every ordering and every "newest"
assertion **must seed explicit ages** via the new defaulted `agoSeconds`. A **global monotonic stagger is
refused**: it would move the unread boundary under every scenario that seeds a comment and then reads,
and would have to land as its own PR ahead of this one.

## 9. Decision log

| # | Decision | Why |
|---|---|---|
| D1 | The stream is **Activity**, not Ambient — author-agnostic, owner posts included | Provenance is not representable (I6); excluding owner posts produces a false empty state on a fresh forum. **A re-decision of the owner call's wording — flagged for the owner, §1** |
| D2 | Thread cards keep their element, class and four hooks in order | Buys "zero rewritten `.feature` lines"; the whole scope argument rests on it |
| D3 | The view persists in a one-row `owner_pref` table | The only mechanism the acceptance suite can see (§2.4) |
| D4 | Absence of the row **is** the default | Lets every existing scenario keep its Gherkin, and makes the reset hook restore the default |
| D5 | Two typed lists, not one shared card type | Makes a mis-render a build failure instead of a review question |
| D6 | The excerpt is child text; hooks carry ids only | JTE does not escape `>` in attribute context (I2) |
| D7 | Thread list uncapped; stream capped at 50 **and says so** | No `GET /threads` route exists to reach an overflow (I10) |
| D8 | `FeedExcerpt` is new; `Snippet` is untouched | `Snippet` feeds six other surfaces; a preview nicety is not worth the risk |
| D9 | Per-call `agoSeconds`, not a global stagger | Same determinism, zero blast radius, one PR instead of two |
| D10 | No pagination | Refused with its reason (§5), because the cursor version is a real bug waiting on the whole-second anomaly |
| D11 | The **recent-comments rail box is suppressed in the Activity view only** | It is a strict subset of the stream — the same five comments twice on one screen. Owner call, 2026-07-27 |
| D12 | Cards **do** carry `data-nav-item` — j/k navigation extends to the front page | Owner call, 2026-07-27, made deliberately rather than inherited. The argument against is recorded below, not discarded |
| D13 | `FeedView` gets its **own file** (`web/FeedView.kt`), not a declaration inside `HomeController.kt` | §2.1 sketched it in the controller, but `OwnerPrefRepository` must name the type — and a repository importing a *controller file's* type is worse layering than both importing a two-constant vocabulary. Changed during the build |

## 10. Owner calls — all three answered 2026-07-27

The three questions this design opened are settled. Recorded here with the reasoning that lost, because
the losing argument is what a later reader needs when they wonder why.

**1. Activity vs Ambient (D1) — ship Activity now, revisit later.** The honest superset ships, named
Activity and never Ambient. An **ambient-only stream is not closed, it is deferred**: recorded here as a
named follow-up whose §2 must first make provenance representable, and which must reckon with the fact
that any `comment.origin` column is NULL for all history and would read as a lie about every existing
row (the argument that killed the `core` column in S4b). Nothing in this slice forecloses it — the two
feed queries are the only readers, and adding a predicate to one of them is the whole change.

**2. The right rail (D11) — suppress the recent-comments box in the Activity view.** It touches
`index.kte` **only**; the fragment itself and `RailFeeds` stay shared byte-for-byte with every thread
page, which is the property their KDoc exists to protect. The other three boxes (starred, active threads,
shortcut) render in both views.
*Binding constraint either way:* **no scenario may assert that all four rail boxes render in the Activity
view** — that converts an accident into a contract. `home_rail`'s recent-comments scenarios stay valid
untouched because they run in the default THREADS view; that is why this costs no `.feature` edit.
*Adds one scenario* (now 17 in `front_page_feed.feature`, delta **+19**): *the activity view hides the
recent-comments box and still shows the other three rails* — with the positive twin, so it cannot pass by
failing to render the rail at all.

**3. Keyboard navigation (D12) — yes, cards get `data-nav-item`.**
*The argument that lost, recorded at the owner's request:* turning j/k on for the front page is a
**product change, and it would have arrived wearing a restyle's clothes** — `nav.js` activates wherever
the hook appears, so adding it as part of a styling slice is exactly the kind of side effect that later
reads as accidental. It is taken **deliberately**, which is what makes it fine.
*What this obliges the build to do:* the hook goes on **both** card types, or j/k works in one view and
silently dies in the other. `data-nav-item` is a **hook, never a styling selector** (the jte skill's
rule, and the same breach `data-unread-count` is being cleaned up for in this very slice — see I2's
neighbourhood). No scenario asserts *navigation behaviour* — the acceptance suite drives no browser, so
this is pinned only as far as "the attribute is present on both card types", and the behaviour itself is
a **new §10.4 entry**: no tier drives `nav.js`, exactly as no tier drives the htmx delete swap.

## 10b. As built — where the implementation departed from this design

Six departures, none of them silent. Each was found by running something, not by reading.

1. **D13 — `FeedView` got its own file** rather than living in `HomeController.kt` as §2.1 sketched.
   `OwnerPrefRepository` must name the type, and a repository importing a *controller file's* type is
   worse layering than both importing a two-constant vocabulary.
2. **The `FeedView.DEFAULT` ledger row was false** — see §7. Corrected against a measurement.
3. **The `setFeedView` no-op row was false** — it reddens #3 *and* #5, because the switch step re-GETs.
   Keeping the claimed distinction would have meant not re-reading, which would stop pinning that the
   preference was *stored*. Shape kept, row corrected (§7).
4. **The rail-suppression mutation in §7 proved nothing as written** and was replaced with one that
   discriminates — empty the *thread page's* starred box, where `rail shows` is the only Then. Green
   before the box-scoping fix, red after; both measured.
5. **Scenarios 18–19 are characterisation pins, not RED-first ones** (§6) — they ride hooks D2 keeps
   deliberately unchanged, and forcing them red would have meant inventing behaviour.
6. **`FeedThread.excerptIsReply` shipped with no production consumer — owner-resolved 2026-07-27, and it
   now has one.** The first cut derived the byline from a null `excerptAuthor` alone, which coincides with
   the flag for owner threads but not for a persona's reply-less one: an ambient article card named its
   persona in the badge *and* again in the excerpt byline. §7's fuller rule — **don't name the same voice
   twice** — is now implemented against the flag, so it is being the thread's **own opening post** that
   suppresses the byline, whoever wrote it. Owner and persona OPs behave alike; a *reply* is still
   credited even when it is the same voice as the badge.

   **Why the flag and not `excerptAuthor != authorId`**, which is the obvious cheaper rule: a persona
   replying to its own article thread has `excerptAuthor == authorId` while the preview is genuinely new
   speech, so the comparison suppresses the byline in the one case that most needs it — the card then
   looks untouched since it was opened. Both readings are pinned, and **both mutations were run**: the
   comparison rule reddens `FeedCardsTest`'s discriminator test *and* the acceptance scenario's third Then
   (`FeedSteps.kt:245`, the "preview to name" half); reverting the fix entirely reddens the scenario's
   second Then. Scenario 9b asserts the badge too, so the rule pins **named once, not zero times**.

7. **"Zero new JavaScript" did not survive, and the owner is the one who ended it — correctly.** §2.4's
   line was about the view toggle, where it still holds; but it hardened into a slice-wide rule that
   then cost a working interaction. The activity card wants two things CSS cannot both give: a big
   click target, and a preview that drag-selects. Three CSS-only attempts each bought one at the price
   of the other — text-as-link (unselectable), overlay-in-front (byline/verb/stamp went dead),
   text-as-span (clicking the words does nothing). The owner pointed out I had already named the
   solution and declined it.

   `feed-core.mjs` + `feed.js` follow the house split — pure decision unit-tested (13 cases), thin DOM
   glue manually verified, the `nav-core`/`nav.js` precedent. **It is progressive, not load-bearing:**
   the stretched `<a>` still opens the card with scripts off, and `pointer-events: none` on the inert
   labels is kept for exactly that path. What the script adds is the preview text as a click target,
   which is the one region CSS could only have bought by making it a link.

   The rule worth carrying forward is not "no JavaScript" but **"no JavaScript the page depends on"**.
   The lesson about the process is sharper: I held a self-imposed constraint through three attempts
   that each failed the user's actual request, and did not re-examine it until told to. A constraint
   that starts costing the thing it was meant to protect has stopped being a constraint.

Also corrected before it became permanent: **V29's first draft explained its CHECKs with "SQLite cannot
add a CHECK by ALTER TABLE"** — the exact folk claim the V28 review close-out disproved and the sqlite
skill now brands false, regressing into new code one slice later. A migration is immutable once applied,
so the honest argument (free at birth, *conditional* afterwards, because the retrofit validates the whole
table and aborts on the first violator) went in before it shipped.

## 11. Known gaps this design pre-books for §10.4

Written now so the close-out cannot quietly discover them: **`data-feed-more` — the stream's own
truncation disclosure (§5, D7) — is in the hook vocabulary but no scenario of the 19 asserts it**, found
during the RED phase; it ships pinned at no tier, and either earns a scenario in a later delta or stays a
recorded gap · the card's visual layout in either theme
(nothing in `verifyAll` reads CSS) · the htmx delete swap, hover-reveal and Cancel actually working (no
tier drives htmx) · "the front page issues no per-row repository call" is an absence claim held by the
constructor's shape and by review, not by a query counter · the measured query plans are a judgement
about *this* data volume, not an invariant · **the whole-second lexicographic anomaly** at the stream's
LIMIT boundary — a one-row display swap, inherited unchanged from `CommentRepository.recentPosted`, and
the reason `findActive`'s "sorts chronologically" comment is **false as written** (verified:
`MAX()` over `12:00:00Z` and `12:00:00.500Z` returns the *older* whole-second stamp, because `'Z' > '.'`)
· `agoOrNull`'s null branch is pinned directly at Tier 0, but **no fixture and no Tier-1 or acceptance
path drives a corrupt stamp end-to-end through the feed** — the narrow claim, because the blunt version
("the null branch is never exercised") would be a lie about the suite · the pre-existing
`>`-in-a-title exposure of `Html.threadRowAttr` · the persona-memory `homeFingerprint` helper becomes
view-dependent (its KDoc needs a sentence; no code change) · **j/k navigation actually working on the
new cards** (D12): the acceptance suite drives no browser and no tier drives `nav.js`, so the pin reaches
"the `data-nav-item` attribute is present on **both** card types" and no further — the same standing
limitation as the htmx delete swap.
