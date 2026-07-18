# Keyboard navigation — vim-style ("AI Forum vim mode")

Status: **first cut = thread page only** (2026-06-21). Other pages roll out later via the same engine.

Goal: drive the forum from the keyboard with mostly single-letter, vim-like commands. Pure
progressive enhancement — every action still has a clickable affordance; nav.js only adds shortcuts.
No framework, no bundler, no runtime dependency (matches the project's vanilla-JS / data-* ethos).

## Architecture: pure model vs DOM glue

Two layers, split so the navigation logic is unit-testable without a browser:

- **`static/nav-core.mjs`** — *pure* tree model + traversal + search. No DOM, no globals. Operates on a
  plain `model` built from `{id, parentId, author, body}` records in pre-order. **This is the JS-unit-
  tested layer** (`src/test/js/nav-core.test.mjs`, run with `node --test`).
- **`static/nav.js`** — DOM glue. Reads `[data-nav-item]` elements (pre-order = document order),
  derives the tree from DOM nesting, owns the cursor (`.is-current`), scroll, the command/search
  overlay, and re-acquires the cursor after every `htmx:afterSwap`. **Manual / browser integration
  testing for now** (the HTTP acceptance suite can't drive keyboard JS — same bucket as the composer
  DOM swap).

The engine is **markup-driven and page-agnostic**: a page becomes navigable by emitting
`[data-nav-item]` on its rows/nodes. Today only the thread page does. Tree motions (h/l/J/K) use
`data-depth`; flat lists (index, members) will simply leave those inert.

## Key map

j/k are fixed: **pre-order reading order** (the flattened top-to-bottom sequence, i.e. what scrolling
does). Everything else:

> **The post (OP) is the root of the tree.** `.thread__op` carries `data-nav-item="post"`, so it's the
> first node in pre-order — `k`/`gg` land on it, and clicking the title selects it. Top-level replies
> aren't DOM-nested inside the OP, but they *are* replies to it, so `rebuild()` reparents every
> otherwise-rootless node under the post: `h` from a top-level reply goes to the post, `l` descends back
> into the thread. Its search text is the title for now (posts get a body later). Permalink:
> `#reply-<threadId>`, same scheme as replies.

| Key            | Action                                                                 |
|----------------|------------------------------------------------------------------------|
| `j` / `k`      | next / previous node in pre-order (reading order)                       |
| `h`            | ascend to **parent** (the "in reply to" node). Records on the parent which child we came from. |
| `l`            | descend: return to the **remembered child** if any (set by a prior `h` or descent), else first child. *(ranger model — convenient + accidental-`h` recovery)* |
| `L`            | **raw** descend to first child, ignoring descent memory                 |
| `J` / `K`      | next / previous **sibling** (skips over the current subtree)            |
| `gg` / `G`     | first node (the **post / OP**) / last reply                             |
| `/` … `Enter`  | search **forward** (matches author + body, case-insensitive)           |
| `?` … `Enter`  | search **backward**                                                     |
| `n` / `N`      | repeat last search, same / opposite direction                          |
| `i`            | reply **as child** of the current node (opens its inline composer)      |
| `a`            | reply **as sibling** (composer targets the current node's *parent*)     |
| `o`            | new **top-level** reply to the thread (focus the bottom composer)       |
| `v`            | +1 the current node                                                     |
| `Esc`          | close composer / cancel search / blur                                  |
| `:`            | command palette + help (escape hatch for rare actions; `?` is taken by reverse-search) |

History (back/forward between pages/threads) is deliberately **not** on h/l — those are spatial tree
motions. History rides vim's jumplist keys `Ctrl-o` / `Ctrl-i` (future).

### Descent memory (the `h` ↔ `l` round-trip)

Per the ranger file-manager model. The DOM layer keeps a `Map<parentId, lastChildId>`:
- on `h` (ascend), record `memory[parent] = currentId`;
- on plain `l`, descend to `memory[current]` if that id is still a child of `current`, else first child;
- `L` always ignores the map.

This makes an accidental `h` free to undo (`l` lands you back exactly where you were) and makes `h`/`l`
behave like columns you can walk in and out of. Pure helper: `nav-core.resolveDescend(model, id, memory)`.

## Compose semantics (a / i / o) — affordances required

The backend already distinguishes target + scope, so the vim analogy maps onto existing endpoints —
**with one new affordance**:

- **`i` (child reply)** — opens the current node's existing inline `<details>` composer
  (`parentId = currentId`, `scope = BRANCH_ONLY`). No backend change; already wired in `replyNode.kte`.
- **`a` (sibling reply)** — must open a composer whose `parentId = parent(currentId)`. Today every
  inline composer is **child-scoped** (parentId = the node it sits under), so there is no existing
  composer that targets the parent from a child node. **First cut:** `a` opens the *parent's* inline
  composer (walk one `[data-nav-item]` up, open its `<details>`). At a **top-level** node the parent is
  the thread, so **`a` and `o` collapse to the same action** (focus the bottom composer). A dedicated
  "reply as sibling from here" composer affordance can come later; for now reuse the parent's box.
- **`o` (new top-level)** — focuses the persistent bottom composer
  (`parentId = threadId`, `scope = WHOLE_THREAD`).

After any compose submit htmx swaps/reloads; nav.js re-acquires the cursor by `data-reply-id` on
`htmx:afterSwap` (same hook app.js already uses to rebind auto-grow).

## Search — MVP and stretch

- **MVP (this cut):** `/`/`?` open a command-line overlay pinned to the bottom; on `Enter` the cursor
  jumps to the next/previous matching node (wraps), and the match term is highlighted in that node.
  `n`/`N` repeat.
- **Stretch (TBD):** a results-list mode — typing filters to a list of matching comments; `Enter`
  leaves the search input and `j`/`k` select among the results, `Enter` again jumps to the chosen one.
  Pure support already exists: `nav-core.matches(model, query)` returns all hits in pre-order.

## Pointer / touch

A **single click or tap** on a comment moves the cursor there (highlight only, no scroll — the target
is already in view). One `click` handler covers both mouse and touch: browsers fire a `click` on tap,
and the `width=device-width` viewport removes the legacy 300ms tap delay. This is the editor model —
tap to place the caret, then drive with the keyboard — which is exactly the touch-with-keyboard case
(e.g. iPad + Magic Keyboard) where vim-nav earns its keep. On a pure touchscreen with no keyboard the
highlight is simply inert (harmless). **Double-tap is deliberately not used** — browsers reserve it for
zoom, it adds detection latency, and it's an unintuitive "select" gesture. Clicks don't `preventDefault`,
so buttons/links inside a comment still work; a manual click also clears any pending Esc-return target.

## Permalink (URL sync)

The current selection is reflected in the URL as `#reply-<id>` — the anchor `replyNode.kte` already
emits (`id="reply-<id>"`), the same target the branch index links to. So the cursor doubles as a
shareable permalink: copy the URL, reload, or send it, and the page reopens with that comment selected.

- Written with `history.replaceState`, **not** `location.hash = …`: replaceState avoids piling up a
  history entry on every `j`/`k` and avoids the browser's native scroll-to-anchor jump (which would
  fight `scrollCommentIntoView`).
- **On load**, the selection is restored from `#reply-<id>` if present (highlight + scroll), else it
  defaults to the first comment *without* writing a hash — clean URLs stay clean until you interact.
- A genuine `hashchange` (clicking a branch-index entry or an "in reply to" link, or back/forward) moves
  the cursor to match. setCursor uses replaceState, so it never re-triggers `hashchange` (no loop).

## Reconciliation with the composer affordances branch (PR #29)

PR #29 adds composer-level keyboard handling (slash `/` palette, `@mention` menu, Single/Roomful, chips)
in `app.js`. No file conflict — that work is in `app.js`; this is in `nav.js`/`nav-core.mjs` (only
`app.css` is shared, additively). The agreed behavioral contract:

- **Escape is tiered.** PR #29's composer `keydown` calls `e.stopPropagation()` *only when it dismissed
  an open palette*, so this module's document-level Escape never fires in that case. Net: 1st Esc
  dismisses the open palette (focus stays in the field), 2nd Esc (no palette) bubbles to nav.js and exits
  the composer + restores the cursor. **No change needed here** — the existing Escape handler is correct.
- **Composer focus keeps thread-nav inert** via the generic `TEXTAREA/INPUT/SELECT` guard — chosen over
  `[data-composer-text]` because it also covers the chip checkboxes and the `routingScope` select.
- **Clicks inside `.composer` don't move the reading cursor** (`onClick` early-returns on
  `closest(".composer")`), so chip/toggle/palette interaction doesn't shift the selection.

## Guards & accessibility

- When focus is in a `<textarea>`/`<input>`/`[contenteditable]` (a composer is open), the keymap is
  inert except `Esc` — otherwise the user can't type "j".
- The cursor is a persistent highlight (`.is-current`), not a native focus ring, so it survives and
  reads cleanly; `scrollIntoView({block:'nearest'})` keeps it visible.

## Testing

- **Automated:** `nav-core.mjs` via `node --test src/test/js/` (also `npm test`). Covers traversal
  (pre-order next/prev, parent/child, siblings, first/last, boundaries), descent memory, and search
  (forward/back/wrap, author+body match, `matches()`).
- **Manual for now:** the DOM glue (cursor, scroll, overlay, htmx re-hook, compose wiring) is verified
  by driving the running app in a browser. The stable hooks a future browser-level test would assert
  on: `[data-nav-item]`, `.is-current` / `[data-cursor]`.

## Deferred follow-ups

Shipped in the first cut (PR #31). These were consciously left out — collected here so they're not lost:

- **Cross-page rollout (index / members / profile).** The engine is markup-driven, so each page becomes
  navigable just by emitting `[data-nav-item]` on its rows/cards (flat lists leave h/l/J/K inert). Only
  the thread page emits the hook today. See *Cross-page consistency* above.
- **Dedicated "reply as sibling from here" composer.** `a` currently reuses the *parent's* inline
  composer (and collapses to `o` at top level). A composer that targets the parent directly from a child
  node would make sibling replies first-class. See the `a` note under *Compose semantics*.
- **Search results-list mode.** The stretch UI described under *Search* — filter to a list of matches,
  `Enter` exits the input, `j`/`k` pick among them. `nav-core.matches(model, query)` already backs it.
- **`.nav-cmdline` dark-surface contrast.** The command-line bar hardcodes `--dark` / `--dark-ink`, which
  reads flat on the dark theme (landed in PR #26). Give it a dark-mode variant so it stays legible. The
  cursor highlight, search `mark`, and help panel already use semantic tokens and re-skin automatically.
