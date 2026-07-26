---
name: jte-spring-kotlin
description: Server-side rendering with JTE templates in the AI Forum Spring Boot + Kotlin app — the gg.jte Gradle plugin and precompile config, jte-spring-boot-starter-4 wiring, .kte Kotlin templates taking typed DTO params, calling sub-template fragments, and the stable data-* semantic-hook convention the acceptance tests assert against. Use this whenever creating or editing .kte templates, wiring a controller to a view, configuring JTE in build.gradle.kts, or debugging a template/DTO mismatch. Reach for it before touching anything under src/main/jte so templates compile at build time and stay assertable.
---

# JTE + Spring Boot + Kotlin (SSR) for AI Forum

The UI is server-rendered with JTE (Java Template Engine). The decisive reason we chose JTE over a
runtime template engine: **JTE compiles templates to typed classes against the view-model DTOs**, so a
wrong field name, a missing param, or a type mismatch fails *the build* — no browser needed. That
matters because the dev jail can't run one, and because it turns a class of view bugs into compile
errors the acceptance suite never even has to catch.

Templates render the surfaces the acceptance tests assert against, so they emit **stable `data-*`
hooks** rather than relying on visual CSS classes — see the convention below and
[[cucumber-spring-bdd]].

## Dependencies + Gradle plugin

```kotlin
plugins {
    id("gg.jte.gradle") version "3.2.4"
}

dependencies {
    implementation("gg.jte:jte:3.2.4")
    implementation("gg.jte:jte-spring-boot-starter-4:3.2.4")  // ← starter-4 for Spring Boot 4.x
    implementation("gg.jte:jte-kotlin:3.2.4")                 // enables .kte (Kotlin) templates
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}
```

Note the starter is named for the Spring Boot major: `jte-spring-boot-starter-4` for Spring Boot 4,
`-starter-3` for Spring Boot 3. Mismatching it against the wrong Spring Boot major is the usual
"no view resolver" / startup failure.

## Precompile wiring (the load-bearing part)

Generate template sources/classes at build time and make Kotlin compilation depend on it, so template
errors break the build before any test runs:

```kotlin
jte {
    generate()                                  // generate template sources at build time
    contentType.set(gg.jte.ContentType.Html)    // HTML-aware output escaping
}
```

The plugin already wires `compileKotlin` to depend on `generateJte` and adds the generated sources to
the source set — do NOT add `generateJte.dependsOn("compileKotlin")` yourself (that's circular: the
generated sources are compiled *by* compileKotlin). If CI ever shows the generated sources aren't on
the compile path, add the dir explicitly:

```kotlin
sourceSets.main { java.srcDir(layout.buildDirectory.dir("generated-sources/jte")) }
```

**Runtime property (the one that bites first):** the JTE Spring Boot starter refuses to create its
`TemplateEngine` unless you tell it where templates come from. With build-time `generate()`, set
**`gg.jte.use-precompiled-templates: true`** so it loads the compiled template classes. Without it the
context fails to start with *"You need to either set gg.jte.usePrecompiledTemplates or
gg.jte.developmentMode to true."* In `application.yml`:

```yaml
gg:
  jte:
    use-precompiled-templates: true   # load build-time compiled templates; no in-process compiler
```

Under `dev` you can instead set `gg.jte.development-mode: true` for hot reload (compiles from
`src/main/jte` at runtime). In the Docker pipeline, run `generateJte` as an explicit early stage so a
template/DTO mismatch fails fast.

## Templates take typed params

Templates live in `src/main/jte/`. Use `.kte` for Kotlin. Each declares its params and imports up
top — this is what gives compile-time safety:

`src/main/jte/thread.kte`:
```kotlin
@import com.aiforum.dto.ThreadViewDTO
@import com.aiforum.dto.ReplyViewDTO
@param page: ThreadViewDTO

<!DOCTYPE html>
<html>
<head><title>${page.title}</title></head>
<body>
  <h1 data-thread-id="${page.id}">${page.title}</h1>
  <section class="tree">
    @for(reply in page.root.children)
      @template.fragments.replyNode(reply = reply)
    @endfor
  </section>
  @template.fragments.composer(composer = page.composer)
</body>
</html>
```

## Calling sub-template fragments

Any template is callable as `@template.<path>.<name>(param = value)`. Keep reusable pieces in
`src/main/jte/fragments/`:

`src/main/jte/fragments/replyNode.kte`:
```kotlin
@import com.aiforum.dto.ReplyViewDTO
@param reply: ReplyViewDTO

<article
    data-reply-id="${reply.id}"
    data-state="${reply.state.name.lowercase()}"
    data-failure-category="${reply.failureCategory?.name ?: ""}"
    data-retryable="${reply.retryable}">

  @if(reply.state.name == "FAILED")
    @template.fragments.errorState(reply = reply)
  @else
    <div class="body">${reply.body}</div>
  @endif

  <span data-vote-count="${reply.voteCount}">+${reply.voteCount}</span>

  @for(child in reply.children)
    @template.fragments.replyNode(reply = child)   <%-- recursion renders the tree --%>
  @endfor
</article>
```

## Shared page shell + htmx (`layout.kte`)

Full pages share one document shell instead of each `.kte` re-inlining `<!DOCTYPE html>`. `layout.kte`
takes the body as a **content block** (`gg.jte.Content`) and renders it inside `<head>`/`<body>`:

```kotlin
@param title: String
@param content: gg.jte.Content
<!DOCTYPE html>
<html lang="en">
<head>
  <title>${title}</title>
  <script src="/webjars/htmx.org/dist/htmx.min.js"></script>   <%-- htmx, see below --%>
</head>
<body>${content}</body>
</html>
```

A page wraps its markup by passing an `@`…`` content block:

```kotlin
@template.layout(title = title, content = @`
  <div class="thread" data-thread-id="${threadId}">…</div>
`)
```

Only **full pages** wrap in the layout. **Fragment** templates (`fragments/replyList`,
`fragments/composer`, a single re-rendered node) stay bare — htmx swaps them into an already-loaded
page, so a `<head>` would be wrong.

**htmx is delivered by webjar, not a CDN** (hermetic, offline, version-pinned like everything else):
`org.webjars.npm:htmx.org` + `org.webjars:webjars-locator-lite` in `build.gradle.kts`; the locator
serves it version-agnostically at `/webjars/htmx.org/dist/htmx.min.js`, so an htmx bump doesn't churn
the `<script src>`.

**The encoding gotcha when htmx drives an existing JSON endpoint.** An htmx form POSTs
`application/x-www-form-urlencoded` by default, but the acceptance suite POSTs JSON to the same
endpoint — one handler can't bind both. Keep the JSON contract green and add a second handler beside
it, discriminated by `consumes`, both delegating to one private method:

```kotlin
@PostMapping("/threads/{id}/generate", consumes = [MediaType.APPLICATION_JSON_VALUE])
fun json(@PathVariable id: String, @RequestBody req: GenerateRequest, model: Model) = respond(id, req, model)

@PostMapping("/threads/{id}/generate", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
fun form(@PathVariable id: String, req: GenerateRequest, model: Model) = respond(id, req, model)  // model-attribute binding
```

A fragment whose composer must keep working *after* a swap needs whatever its composer params require
(here `threadId` + the persona list) added to the model by **every** endpoint that returns it. Thread
those through `fragments/replyList` → `fragments/replyNode` as **nullable-default** params so a render
path that lacks them (e.g. retry) still compiles and just omits the composer.

**Failed htmx requests are a TOAST, NOT a rendered view (no error fragment).** Worth calling out *here*
precisely because the intuitive fix — render a small error `.kte` and let htmx swap it in — is wrong on
this stack, so don't reach for one. An *uncaught* exception on an htmx request must not return Boot's
Whitelabel error **page** (htmx would swap a whole `<html>` into the request's target — e.g. the compose
`<textarea>` — and corrupt the view). The shipped fix (`web/HtmxErrorAdvice`, a `@ControllerAdvice`)
returns **no body at all**: a `ResponseEntity<Void>` with the **mapped non-2xx status** (RateLimited →
503, Timeout → 504, other `LlmException` → 502, else 500) plus an `HX-Trigger: {"app:error":{"status":<code>}}`
header. The client raises a toast off that event. **There is no `errorNotice.kte`, no
`data-error-fragment`/`data-error-status` hook — that whole fragment design was removed.**

Why a non-2xx + empty body rather than a swapped fragment (all verified against the vendored htmx 2.0.6
`dist/htmx.js`):

- htmx's default `responseHandling` maps `[45]..` to `{ swap: false, error: true }`, so on a non-2xx the
  body is **fetched then discarded — htmx swaps nothing**. Returning the *real* error status is
  therefore exactly what guarantees nothing lands in the compose field. (This is the inverse of the
  intuition that you must return 200 to make htmx render something — here you *want* it to render
  nothing.)
- `HX-Trigger` is processed at the **top** of `handleAjaxResponse`, before the swap/error branches, so
  the `app:error` event fires regardless of the non-2xx status. The failure travels out-of-band on that
  header, not in a body.
- htmx already re-enables `hx-disabled-elt` controls and clears `hx-indicator` spinners on every
  terminal request path, so there's no stuck control to fix. The only gap it leaves is user-visible
  failure feedback — which the toast supplies.

> **`HX-Trigger` header values must be ASCII (ISO-8859-1).** HTTP header values are Latin-1, and the
> owner-facing copy contains non-Latin1 punctuation (em dashes) Tomcat strips as invalid. So the header
> payload carries **only the numeric status** — never the human message. The client words the toast from
> that ASCII/numeric signal. General rule: keep human prose out of HTTP headers; signal with an ASCII
> code and word it client-side.

The toast itself lives entirely in **static JS, not a template**: `static/htmx-error-core.mjs` is a pure
decision + persistence layer (toast wording + a sticky toast STORE — add/dismiss/list with a cap and
consecutive-duplicate de-dupe, over an *injectable* storage so it's jsTest-unit-testable), and
`static/htmx-error.js` is the DOM glue loaded as an ES module from `layout.kte` — it backs the store
with `localStorage`, renders **sticky, dismissible (✕)** toasts, and **rehydrates them on page load**.
It listens for `app:error` (the server signal) and `htmx:sendError` (request never left). The advice
only fires for `HX-Request` calls; a non-htmx request **rethrows**, so Boot's default page renders at
its real status and emits **no `HX-Trigger`**. See [[cucumber-spring-bdd]] for the failure-path
acceptance wiring (mapped **non-2xx** + empty body + the `HX-Trigger` `app:error` assertion).

Persistence is **best-effort and TTL-bounded**, deliberately — it's a single-user PoC, not a durable
queue:

- **TTL prune.** Each toast carries a `createdAt`; the store drops any toast older than `TOAST_TTL_MS`
  (24h) **on load and on every write**, so a stale error from days ago never resurfaces and storage
  can't accumulate forever (the per-`MAX_TOASTS` cap bounds count; the TTL bounds age).
- **Best-effort writes.** `localStorage.setItem` is wrapped so a failure is swallowed, not thrown — the
  toast still shows this session, it just may not survive a reload. **Documented limitation:** Safari
  private mode (`setItem` *always* throws → no cross-load persistence that session) and hard quota
  exhaustion are **not** handled, on purpose. Acceptable for a single-user PoC; revisit if it bites.
  (This TTL + best-effort approach *replaced* an earlier in-memory-fallback latch — don't reintroduce
  one.)
- **Relative age label.** A pure-core `ageLabel(createdAt, now)` (native `Intl.RelativeTimeFormat`)
  renders a rehydrated toast as e.g. "Server error · 3 minutes ago" rather than a contextless message,
  refreshed on a single ~60s tick. `now` is **injected** (the core never calls `Date.now()`), so the
  label is deterministic under test — the same Tier-0 purity the store and `noticeFor` already keep.

## Static assets & styling (`static/`, `app.css`)

The visual layer is hand-written CSS + a little vanilla JS served as **static resources** — no build
step, no framework. Spring Boot serves `src/main/resources/static/**` at the web root with zero config:

- `src/main/resources/static/app.css` → `/app.css`; `app.js` → `/app.js`.
- `layout.kte` links them in `<head>` (`<link rel="stylesheet" href="/app.css">`,
  `<script src="/app.js" defer>`); every full page inherits them through the shell.
- `app.js` re-binds on `htmx:afterSwap` so behaviour (composer auto-grow) survives htmx swaps.
- `stream.js` (live AG-UI generation streaming — see `plan_docs/streaming-agui.md`) likewise keys off the
  `data-*` hooks: it opens an `EventSource` on each `article.reply[data-state="drafting"]` node, keyed by
  `data-reply-id`, appends text live, then lets the server-rendered fragment swap in. So those two hooks are
  load-bearing for the SSE client as well as the acceptance probe — another reason not to rename them.

**Style with `class=`, never with `data-*`.** The probe (`Html.kt`) reads attributes by **regex off the
single opening tag** and substring-matches text, so when styling templates:

- *Safe:* add `class=`, add child elements/wrappers, change visible chrome text, wrap a title in `<a>`.
- *Unsafe:* move/rename a `data-*` attribute, split the composer `<form>` (its `data-*` + `hx-*` must
  stay on **one** opening tag), or drop a field the steps assert (`name="text"`, `name="personaIds"`,
  `value="SUMMON"`).
- Drive state/error/empty visuals off the **existing** hooks — `article.reply[data-state="failed"]`,
  `[data-failure-category="RATE_LIMITED"]`, `[data-empty-state="waiting"]` — so the six error states
  need no new markup.

**Design source of truth:** the six full-screen comps in `HAIP_design/*.dc.html` (the sage `#b3bca3`
HUP-lineage system, Verdana prose + mono chrome) — **not** the `Style Tile`'s olive exploration. The
palette/type/spacing tokens live as CSS custom properties at the top of `app.css`.

## The data-* semantic-hook convention

Acceptance assertions must target stable, behavioural attributes — not CSS classes, which churn with
styling. Standardize on:

| Hook | Meaning |
|------|---------|
| `data-reply-id`, `data-thread-id` | entity identity |
| `data-state` | `drafting` / `posted` / `failed` / `cancelled` |
| `data-failure-category` | `FAILED_RETRY` / `RATE_LIMITED` / `COULDNT_SAVE` / … |
| `data-retryable` | `true` / `false` |
| `data-retry-after` | seconds, present only for rate-limit |
| `data-vote-count` | the firewalled `+1` tally (visible to owner) |
| `data-scope` | `BRANCH_ONLY` / `WHOLE_THREAD` on the composer (the `ScopeMode` enum name) |

This keeps the same `.feature` files re-pointable at a Playwright layer later — the hooks survive a
visual redesign.

**Row-slicer contract: a hooked `<li>` row must stay FLAT.** The acceptance helpers that read one
audit row as a unit (`Html.latestInterestChangeRow` / `latestMemoryChangeRow` / `roomMapRow`, all over
the shared `liBlock` slicer) cut from the row's opening `<li data-…>` tag to the **first `</li>`** in
document order — so a nested `<li>` inside the row truncates the sliced block at the inner close tag,
and assertions then fail against half a row: loudly, but confusingly. Any template whose rows a slicer
reads (`admin_memory.kte`'s `data-memory-change` rows and their cited lines, the interest/stance logs)
renders row internals as flat `<span>`/`<div>` children — one `<li>` per row, never a nested list.

## JTE syntax cheat-sheet

```kotlin
@param x: Type                 <%-- declare a param --%>
@import some.Type              <%-- import --%>
${expr}                        <%-- HTML-escaped output --%>
$unsafe{expr}                  <%-- raw, only for trusted HTML --%>
!{val y = expr;}               <%-- run code --%>
@if(c) … @elseif(c) … @else … @endif
@for(i in list) … @endfor
@template.path.name(p = v)     <%-- call another template --%>
<%-- comment --%>
```

Null-safety is Kotlin's: use `?.`, `?:`, and the elvis fallback inside `${}`.

### `$unsafe{}` and untrusted bodies — the two-half firewall

The only untrusted HTML that may reach `$unsafe{}` is `bodyHtml` (and `captionHtml`), and only because
`MarkdownRenderer` enforces **both** halves of the XSS firewall: `escapeHtml(true)` (raw HTML inert) **and**
`sanitizeUrls(true)` with a custom `http/https/mailto` allowlist (hostile `javascript:`/`data:` link/image
destinations emptied). Traps if you touch this:

- `escapeHtml` alone is NOT enough — it never touches link *destinations* (that hole shipped for 3 weeks).
- commonmark's *default* URL-sanitizer allowlist includes `data:` — keep the custom allowlist, `data:text/html`
  is script execution.
- `sanitizeUrls` stamps `rel="nofollow"` on every anchor — tests pinning exact anchor markup must expect it.
- Never route new untrusted content through `$unsafe{}` directly; render it through `MarkdownRenderer` (or
  `${}`-escape it). Details: `plan_docs/markdown-rendering.md` §Security; code: `markdown/MarkdownRenderer.kt`.

## Wiring a controller to a view

With `jte-spring-boot-starter-4`, return the template name (path under `src/main/jte`, no extension)
and put the DTO on the model under the param name:

```kotlin
@Controller
class ThreadController(private val threads: ThreadService) {
    @GetMapping("/threads/{id}")
    fun thread(@PathVariable id: String, model: Model): String {
        model.addAttribute("page", threads.view(id))   // matches @param page
        return "thread"                                 // → src/main/jte/thread.kte
    }
}
```

For HTMX-style fragment responses (e.g. a single re-rendered reply node after retry), render a
fragment template directly and return the HTML.

## Common failure points

- **Param name mismatch** between `model.addAttribute("page", …)` and `@param page` → render error.
  The names must match exactly.
- **`.kte` not recognized** → missing `gg.jte:jte-kotlin`.
- **Generated sources not compiled in CI** → add the generated dir to the source set (above) and run
  `generateJte` before `compileKotlin`/tests.
- **Asserting on CSS classes** → brittle; switch to `data-*` hooks.

## Verify

- `./gradlew generateJte compileKotlin` succeeds and fails loudly on a deliberate bad field
  reference (that failure *is* the feature working).
- Hitting a controller route renders the `.kte` with the DTO; acceptance steps find the expected
  `data-*` hooks in the HTML.
