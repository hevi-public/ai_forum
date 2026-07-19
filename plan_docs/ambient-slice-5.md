# Ambient Slice 5 — the real article source: allowlist feeds, dedupe, security posture

> **Status:** built 2026-07-19 — 184/184 acceptance scenarios green under `verifyAll` ·
> **Owner:** Hevi · **Created:** 2026-07-19 ·
> Parent: `ai-driven-forum-direction.md` §4/§9 (S5 row) · Spec anchor: `ai-forum-requirements.md` §12

## 1. What this slice delivers

The gap between "runs itself on canned fixtures" and "runs itself on the live web":
`FeedArticleSource` pulls real articles from an **owner-curated allowlist of RSS/Atom feeds**,
dedupes against a stored URL registry, and hands the tick the same `Article(title, url, summary)`
it gets from the stub today. The stub stays the default — the owner opts into the live web with
one config key. This PR also owns the **explicit untrusted-web posture** (§3 below), which is why
it's its own reviewable slice.

Settles direction-doc open question 4: **allowlist-only feeds** — not model-side WebSearch (that
stays a §4 stage-3 maybe), not open fetch. The owner's feed list is the trust anchor; everything
fetched through it is still treated as adversarial data.

## 2. Design

**Source selection** — replicate the `aiforum.llm.provider` pattern verbatim:
`aiforum.ambient.source` with `stub` as `matchIfMissing = true` default on `StubArticleSource`,
`feed` activating `FeedArticleSource`. Both `@Profile("!test")`; `ScriptableArticleSource` stays
`@Primary @Profile("test")` — **the real source can never wire under test** (the existing
config_guardrails rail already pins the wired class name).

**Fetch** — Spring `RestClient` with the house constructor seam (`RestClient.Builder` primary
ctor + `@Autowired @Value` secondary), absolute URLs by hand, and the `OpenAiLlmClient.awaitWithin`
daemon-FutureTask deadline pattern (~10s/feed) — the manual tick runs on the request thread and
must not hang on a dead feed. Response body capped (`ImageStore.max-bytes` precedent, 1 MiB
default) **before** parsing.

**Parse** — no feed library (nothing on the classpath; a dependency is supply-chain surface this
posture would have to defend). A pure `FeedParser` object (Tier-0): hardened
`DocumentBuilderFactory` — `disallow-doctype-decl` (kills XXE *and* billion-laughs in one flag),
`FEATURE_SECURE_PROCESSING`, external general/parameter entities off, XInclude off, entity
expansion off. RSS 2.0 (`channel/item`: title, link, description) + Atom (`feed/entry`: title,
`link@href`, summary|content). Per the §4 content decision: **link + short excerpt, never
bodies** — summaries are HTML-stripped, whitespace-collapsed, hard-truncated (~400 chars; titles
~200). Item links must be http(s) or the item is skipped; feed URLs themselves must be https
(invalid entries skipped with a boot-time warn, count surfaced in `/__diag`).

**Dedupe** — `V23__article_seen.sql`: `article_seen(url TEXT PRIMARY KEY, first_seen TEXT NOT
NULL)` — no FKs, brand-new table (skill rules: scan for V23 collisions before claiming; nothing
to retrofit). `ArticleSeenRepository` on the `GitHubPrThreadRepository` shape (jdbc + Clock).
`FeedArticleSource.next()`: iterate feeds round-robin (in-memory cursor, stub's `AtomicInteger`
precedent), fetch+parse, first item whose URL is unseen wins → mark seen **on yield** → return.
All feeds errored → throw `FeedUnavailableException` (aggregated per-feed messages) → the S2
attribution machinery records a `failed` run, `action="post"`, detail = the message. Some feeds
down but another yields → still posts (degraded, logged). No unseen items anywhere → null.

**Distinguishable no-ops** — the port gains one defaulted method: `fun emptyReason(): String? =
null`. When the tick records its generic no-op it appends the source's reason if present
(`"nothing to post or comment — all 30 feed items already seen"` vs `"— feeds returned no
items"`). `admin_ambient.kte` adds `data-detail="${run.detail}"` on the run row (the text is
already rendered; the hook makes it assertable — house convention). `ScriptableArticleSource`
gets a programmable `emptyReason` so acceptance pins the plumbing end-to-end; the real strings
pin at Tier-1.

## 3. Security posture (the slice's reason to exist as its own PR)

| Threat | Mitigation here |
|---|---|
| XXE / entity-expansion bombs in feed XML | DTDs rejected outright (`disallow-doctype-decl`) + secure processing; pinned by Tier-0 tests feeding hostile XML |
| Memory abuse via huge responses | Byte cap before parse (1 MiB default) |
| Hung/slow feeds blocking the tick | Per-feed deadline via daemon FutureTask; tick degrades to remaining feeds |
| Malicious URLs in items (`javascript:`, `data:`) | Scheme allowlist http/https at ingestion; the existing `sanitizeUrls` render firewall stays the second layer |
| Stored/rendered hostile content | §4 content decision: link + short stripped excerpt only — fetched bodies never enter the DB |
| Prompt injection via feed text into persona context | **Documented, accepted residual risk** pre-jail (§12 posture unchanged): excerpts are minimal, the owner curates the allowlist, and the Docker jail remains the real fix (urgency already raised in context.md). This slice narrows ambient ingestion to owner-trusted hosts — it does not widen persona WebFetch. |
| Network under test | Structurally impossible: real sources are `@Profile("!test")`; rail pins the wired class |

## 4. Acceptance plan (RED-first at the port; source internals pin at tiers)

Deviation from the §10-S5 sketch, with rationale: `FeedArticleSource` cannot load under the test
profile (by design — that IS the security rail), so its internals (parsing, XXE, caps, dedupe)
are pinned at Tier-0/Tier-1, while acceptance pins the tick-level contract through the port fake:

New `article_source.feature` (sibling of `generation_sad_paths`, scripted at the
`ScriptableArticleSource` seam):
1. *A failing article source records a failed run with its message* — `failWith "feed
   unreachable"` → failed run, `action="post"`, `data-detail` contains "feed unreachable".
2. *An empty source records a no-op with the source's reason* — programmable `emptyReason`
   ("feeds returned no items") → no-op run, `data-detail` carries it.
3. *A dedupe-exhausted source records a distinguishable no-op* — `emptyReason` ("all 12 feed
   items already seen") → no-op run, `data-detail` distinguishes it from scenario 2.

`config_guardrails.feature` additions: *the ambient source selection defaults to the stub* and
*no feeds are configured under test* (`/__diag` gains `ambientSource` + `ambientFeedCount`;
`AmbientFeedProperties` is enabled from a non-profiled `@Configuration` so the bean exists —
empty — under test).

Tier-0 (`FeedParserTest`): RSS + Atom happy paths, DOCTYPE/XXE payload rejected, entity bomb
rejected, HTML-stripped + truncated summaries, bad-scheme items skipped, malformed XML → clean
failure. Tier-1 (`FeedArticleSourceTest`, `MockRestServiceServer` on the builder seam): yields
first unseen item and marks it seen; second call skips seen (real `ArticleSeenRepository`);
all-feeds-error throws aggregated; one-feed-down degrades; oversized body rejected; empty feeds →
null + emptyReason. Tier-1 (`ArticleSeenRepositoryTest`) + `MigrationPipelineTest` 22→23.
`DatabaseResetHooks`: `article_seen` joins the delete list next to `ambient_run`;
`ScriptableArticleSource.reset()` clears the new `emptyReason`.

## 5. Live-web demo (verification)

Boot `dev,stub` (stub LLM — the subscription envelope is not this slice's concern) with
`--aiforum.ambient.source=feed --aiforum.ambient.feeds[0]=https://hnrss.org/frontpage`; two
ticks → two real front-page articles as persona threads with stub room discussion; third tick
against unchanged feed content → no-op "already seen" (dedupe proof, visible on
`/admin/ambient`).

## 6. Decision log

| Date | Decision | Why |
|---|---|---|
| 2026-07-19 | Allowlist RSS/Atom feeds only; WebSearch stays deferred; open fetch rejected | Owner-curated trust anchor; no new egress class pre-jail (settles direction-doc open question 4) |
| 2026-07-19 | Hand-rolled hardened parser, no feed library | Zero new supply-chain surface; the needed subset (title/link/summary ×2 formats) is small; hostile-input tests pin it |
| 2026-07-19 | Source selection = `aiforum.ambient.source` (`stub` default), `@ConditionalOnProperty` per the llm.provider template | Zero behavior change for existing setups; test profile structurally untouched |
| 2026-07-19 | Dedupe inside `FeedArticleSource` via `article_seen`, marked on yield; stub bypasses dedupe | The stub's rotating fixtures are its demo value; a failed tick may re-offer a seen article only if it never yielded (accepted) |
| 2026-07-19 | Port gains defaulted `emptyReason()`; no-op details distinguish empty vs all-seen; `data-detail` hook on admin rows | §10-S5 asks for recorded, assertable no-op reasons; defaulted method keeps S1/S2 impls source-compatible |
| 2026-07-19 | Acceptance pins the port contract only; source internals pin at Tier-0/1 | The real source never wires under test — that profile wall IS the security rail, so acceptance cannot (and should not) exercise it |
