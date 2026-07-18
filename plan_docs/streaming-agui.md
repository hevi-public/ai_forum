# Live generation streaming — AG-UI-shaped events over a hybrid SSR + SSE layer

Status: **shipped** (2026-06). Adds token-by-token streaming of persona replies, replacing the batch-only
model (requirements §4 "Batch is fine"). It is **purely additive** over the existing `every 1s` htmx poll —
the poll stays as the backbone and the progressive-enhancement fallback, so nothing already working changes.

Goal: show reply text appear live while it generates (plus tool-call status), without moving rendering to
the client and without coupling the codebase to a churning external protocol.

## The protocol decision (why AG-UI, why mirror not depend)

The streaming layer is modelled on **[AG-UI](https://github.com/ag-ui-protocol/ag-ui)**'s stable-core event
vocabulary (the agent↔UI counterpart to MCP's agent↔tools). We **mirror** the vocabulary rather than take
the `com.agui` dependency: AG-UI is pre-1.0 and its only JVM SDK is a *consumer* client, while we're an event
*producer*. The on-the-wire coupling is isolated to **one file** (`AguiWire`), so a spec bump — or a later
decision to adopt the dependency — is a contained change pinned by a single Tier-0 golden test.

(The protocol called "HAIP" at haiprotocol.com — despite this repo's original name — was evaluated and rejected: a
dormant solo project with no adoption. The name collision is coincidental.)

## Architecture

```
backend native stream ──normalize──▶ AguiEvent (internal sealed vocab)
  (stream-json / SSE)                     │
                                          ├─▶ AguiWire.encode ▶ SSE /replies/{id}/stream ▶ stream.js (append text live)
                                          └─▶ aggregate text ▶ settleOne persists ONE row (unchanged)
                                                                   ▶ on terminal, htmx swaps the server-rendered fragment in
```

- **`com.aiforum.agui`** — `AguiEvent` is our internal sealed vocabulary: `RunStarted`, `TextDelta`,
  `ToolCallStart`/`ToolCallEnd` (status-only), `RunFinished`, `RunError`. A deliberately minimal subset of
  AG-UI's core (a forum reply is one message per run — no TextMessageStart/End, args, or state snapshots).
  **`AguiWire`** is the only file that knows AG-UI's wire JSON (SCREAMING_SNAKE types, camelCase fields;
  `TextDelta` → `TEXT_MESSAGE_CONTENT` with `runId` as `messageId`). Pinned by `tier0/AguiWireTest`.
- **The seam** — `LlmClient.generate(req, cancel, sink)`, a streaming overload alongside the blocking one,
  with a **default that degrades** a non-streaming backend to a single `TextDelta` framed by
  RunStarted/RunFinished. So the test double and any future backend satisfy streaming for free, and the
  **single Tier-1 IO seam is preserved** (see [bdd-tiered-testing]). `LlmRequest.runId` = the in-flight node
  id (the comment being drafted), used as the AG-UI `runId`/`messageId` so events route to the right node.
- **Normalisation (the provider-agnostic point)** — `ProcessLlmClient` overrides the streaming seam to spawn
  `--output-format stream-json --verbose --include-partial-messages` and map NDJSON via the pure Tier-0
  `ClaudeStreamParser`; `OpenAiLlmClient` sends `stream:true` and maps SSE chunks via `OpenAiStreamParser`.
  **Both still classify the FINAL text through the existing `LlmResponseParser`/`OpenAiResponseParser`** (the
  OpenAI path folds the streamed pieces into a synthetic envelope), so the persisted reply is byte-identical
  to the non-streaming path and the failure taxonomy is unchanged. `OpenCodeLlmClient` does the same for the
  `opencode run --format json` agent CLI via `OpenCodeStreamParser` — opencode `text` parts are *cumulative*
  per `part.id`, so the parser emits each new suffix as a delta and classifies the final text + `step_finish`
  reason. (opencode has no inline system-prompt flag, so the persona prompt is folded into the message.)
  `retry`/`regenerate`/`autoGrow` stay synchronous.
- **Transport** — `InFlightGenerations` gains a per-run event channel: every event buffered (replies are
  short) plus live subscribers, with replay for a late joiner and completion on the terminal event. An
  unknown/evicted run returns `null` from `subscribe` so the caller falls back to the poll (the settled row
  exists). The generation worker is the sole publisher per run, so events stay ordered. SSE endpoint:
  `GET /replies/{id}/stream` (`@ResponseBody SseEmitter` on `GenerationController`).
- **Client (hybrid)** — `static/stream.js` (loaded in `layout.kte`) opens an `EventSource` on each
  `article.reply[data-state="drafting"]` node (the same `data-*` hooks the acceptance probe reads; see
  [jte-spring-kotlin]), appends `TextDelta` text raw into `.body`, shows a tool-call status line, and on the
  terminal event closes the stream and triggers an immediate `htmx.ajax` GET of `/replies/{id}` to swap in
  the **server-rendered** fragment (markdown, highlighting, voting, revisions). `onerror` closes the stream
  (no EventSource reconnect loop) and leans on the poll. So the DB + server rendering stay the source of
  truth and **there is no client-side markdown engine**.

## Why hybrid (the key constraint)

Reply bodies are rendered server-side (commonmark + highlight.js via GraalJS → `bodyHtml`, escaped — see
[markdown-rendering]). That pipeline is JVM-coupled, so token streaming does **not** move rendering to the
client. Instead the client shows raw text while drafting and swaps in the authoritative server-rendered HTML
on completion. This preserves the SSR architecture and the XSS firewall.

## Backends & running it

Streaming is **automatic and additive — there is no UI toggle**. Pick a backend at startup
(`aiforum.llm.provider`, **global**, not per-persona); then just use the app and drafting nodes stream.

| Backend | `aiforum.llm.provider` | Streaming status |
|---|---|---|
| `claude -p` (default) | `cli` | ✅ **live-verified** end-to-end (real token deltas → hybrid swap) |
| OpenAI-compatible HTTP (LM Studio, vLLM, …) | `openai` | ✅ implemented; Tier-1 tested against canned SSE — **not yet** smoke-tested against a live server |
| opencode agent CLI | `opencode` | ✅ implemented (`OpenCodeLlmClient`); parser matched to **real** `opencode run --format json` output (schema captured live); Tier-0/1 tested. ⚠ **heavy + slow** — a full app reply wasn't live-smoked (a local 9B under opencode's agent prompt exceeds the practical time budget) |

Backend choice is **global** — one provider for the whole app. A persona may still pin a *model* within that
backend via `persona.model`; per-persona/per-request *backend* routing is deferred.

**Run it:**
- `./gradlew bootRun` → dev profile, <http://localhost:8081> (throwaway project-local DB). `claude` must be
  on PATH and authenticated.
- `./gradlew bootRunProd` → prod profile, <http://localhost:8080> (persistent DB at `~/.ai_forum`).

**Switch to an OpenAI-compatible server** (config, not a UI choice):

```
./gradlew bootRun --args='--aiforum.llm.provider=openai --aiforum.llm.openai.base-url=http://localhost:1234/v1'
```

**Switch to opencode** (heavy — use sparingly). The model must be in opencode's `provider/model` form and
opencode must have that provider configured/authed (its own config). Point `working-dir` at a dir holding an
`opencode.json` if the provider is configured project-locally (e.g. an `lmstudio` provider for LM Studio):

```
./gradlew bootRun --args='--aiforum.llm.provider=opencode \
  --aiforum.llm.default-model=lmstudio/qwen/qwen3.5-9b \
  --aiforum.llm.working-dir=/path/to/dir-with-opencode.json'
```

Note: opencode's `--format json` appears to flush events near completion rather than token-by-token, and a
full agent run is slow — so opencode streaming is functionally correct but coarser/heavier than the other two
backends. The hybrid swap still lands the server-rendered reply on settle.

(`aiforum.llm.openai.base-url` defaults to LM Studio's `http://localhost:1234/v1`; set
`aiforum.llm.openai.api-key` if the server needs one. See [local-model-reasoning-leak.md] for model choice.)

**Use it (no special UI):** on the home page use **Ask the room**, or open a thread and **Reply → summon a
persona**. The drafting node fills in token-by-token, then swaps to the server-rendered reply on settle.
Devtools → Network shows `GET /replies/{id}/stream` as a `text/event-stream` while a node is drafting. If
`EventSource` is unavailable the node still settles via the existing `every 1s` poll — nothing breaks.

**Claude stream flags:** the `cli` backend spawns `--output-format stream-json --verbose
--include-partial-messages`. If a `claude` version rejects partial messages, set
`aiforum.llm.stream-partial-messages=false` to fall back to whole-message streaming (still normalised by
`ClaudeStreamParser`).

## Does it break the batch runaway-brake? No.

§4/§9 rely on **batch generation** as a structural runaway brake (the autonomous loop advances per tick, and
each summon settles exactly one DB row). This change streams the *UI presentation* of a single generation; it
does not make generation continuous or change settlement. One run still produces one persisted row; the
autonomous loop is untouched.

## Known wrinkle (PoC-acceptable)

The live stream carries the model's **raw** tokens, including any `<think>…</think>` reasoning. The final
settle strips them and flags `data-reasoning-leak` (the [reasoning-leak pipeline](local-model-reasoning-leak.md)),
so reasoning briefly shows during streaming, then the swap cleans it. Acceptable for a single-user PoC; future
fix = hide think-spans client-side mid-stream, or stream-sanitise.

## Deferred (seams are ready)

- **Persisting events to `event_log`** (V1 table, still unused). The in-memory buffer covers reconnect within
  the in-flight window; after settle the DB row + poll serve the final state. Wire it only if cross-restart
  replay is needed.
- **Per-persona backend routing** — backend choice stays global (`aiforum.llm.provider`); a persona pins only
  a *model* within the chosen backend. (The `opencode` backend itself is now implemented — see above.)
- **Taking the `com.agui` dependency** — revisit if we become an AG-UI *consumer* or it stabilises at 1.0;
  `AguiWire` + its test are the one place that changes.

## Tests

Mirrors the tiered paradigm ([bdd-tiered-testing]); the only spec-coupled test is the wire golden:

- `tier0/AguiWireTest` — the AG-UI wire contract (the **one** file that changes on a spec bump).
- `tier0/StreamingSeamDefaultTest` — the seam default degrades a non-streaming reply to one delta; failure → RunError.
- `tier0/ClaudeStreamParserTest`, `tier0/OpenAiStreamParserTest`, `tier0/OpenCodeStreamParserTest` — pure
  NDJSON / SSE-chunk / opencode-NDJSON normalisation (incl. opencode's cumulative→suffix delta extraction).
- `tier1/ProcessLlmClientStreamTest`, `tier1/OpenAiLlmClientStreamTest`, `tier1/OpenCodeLlmClientStreamTest` —
  the streaming overloads end-to-end against canned native streams (`/bin/sh` NDJSON; `MockRestServiceServer` SSE).
- `tier2/InFlightChannelTest` — channel replay / terminal-complete / unknown-fallback / cancel.
- `features/generation_streaming.feature` — HTTP-level SSE: a run's buffered events replay as real frames;
  an unknown run completes empty (poll fallback). `ScriptableLlmClient` gains `Behavior.Stream`.

Verified end-to-end in the browser against the real `claude -p` backend (token-by-token deltas over the wire,
hybrid swap to the sanitised fragment on settle). The opencode parser's fixtures are taken from a **real**
`opencode run --format json` capture; a full app reply through opencode wasn't live-smoked because a local 9B
under opencode's agent prompt exceeds the practical time budget (opencode is heavy — use sparingly).
