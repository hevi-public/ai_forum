/*
 * nav.js — DOM glue for AI Forum vim-style keyboard navigation (see plan_docs/keyboard-navigation.md).
 *
 * Pure progressive enhancement: builds a navigation model from the rendered comment tree, owns the
 * cursor (.is-current), and maps keystrokes onto moves + existing affordances (composers, +1). All the
 * navigation LOGIC lives in nav-core.mjs and is unit-tested; this file is the (manually verified) glue.
 *
 * First cut: thread page only. The engine is markup-driven — it activates wherever [data-nav-item]
 * elements exist and no-ops elsewhere, so rolling it out to other pages is just emitting the hook.
 */
import {
  buildModel,
  preorderNext,
  preorderPrev,
  parentOf,
  firstChildOf,
  nextSibling,
  prevSibling,
  firstNode,
  lastNode,
  resolveDescend,
  search,
} from "./nav-core.mjs";

const NAV = "[data-nav-item]";

let model = null;
const elById = new Map();
let currentId = null;
let cursorHidden = false; // selection cancelled via click-away: highlight is gone but currentId remembers
                          // where it was, so keyboard nav resumes from there instead of jumping to the top
const descendMemory = new Map(); // parentId -> last child we ascended from (ranger model)
let lastSearch = { query: "", dir: 1 };
let awaitingG = false;
let composeReturnId = null; // where the cursor was when a/i/o opened a composer — Esc returns here

const idOf = (el) => el.dataset.replyId || el.dataset.navId || el.id;

// The selection's permalink is the anchor replyNode already emits: id="reply-<id>".
const hashFor = (id) => "#reply-" + id;
function idFromHash() {
  const m = /^#reply-(.+)$/.exec(location.hash);
  return m ? m[1] : null;
}

/** (Re)build the model from the DOM. Pre-order = document order of [data-nav-item]. */
function rebuild() {
  elById.clear();
  let postId = null; // the thread OP, if present — the logical root of the tree
  const nodes = Array.from(document.querySelectorAll(NAV));
  const records = nodes.map((el) => {
    const id = idOf(el);
    elById.set(id, el);
    if (el.dataset.navItem === "post") postId = id;
    const parentEl = el.parentElement && el.parentElement.closest(NAV);
    // a reply's text is its .body; the OP has no body yet, so fall back to its title for search.
    const bodyEl = el.querySelector(":scope > .body") || el.querySelector(":scope > .thread__title");
    return {
      id,
      parentId: parentEl ? idOf(parentEl) : null,
      author: el.dataset.author || "",
      body: bodyEl ? bodyEl.textContent : "",
    };
  });
  // The OP is the root: top-level replies aren't nested in it in the DOM, but they ARE replies to the
  // post — so reparent every otherwise-rootless node under it. h from a top-level reply then reaches
  // the post, l descends back into the thread, and gg/k land on the post (it's first in document order).
  if (postId != null) {
    for (const r of records) if (r.id !== postId && r.parentId == null) r.parentId = postId;
  }
  model = buildModel(records);

  if (!model.ids.length) {
    currentId = null;
    return;
  }
  if (currentId == null || !elById.has(currentId)) {
    // First selection: restore from the permalink (#reply-<id>) if present, else default to the top.
    const fromHash = idFromHash();
    currentId = fromHash && elById.has(fromHash) ? fromHash : firstNode(model);
  }
  // re-apply the highlight: an htmx swap replaces element objects, dropping the class. Skip while the
  // cursor is hidden (cancelled via click-away) so a swap doesn't resurrect the highlight we just cleared.
  if (!cursorHidden) {
    const el = elById.get(currentId);
    if (el) el.classList.add("is-current");
    highlightRailEntry(currentId);
  }
  // Branch-index rail mirrors two things the cursor/swaps change: which entry is selected, and which
  // comments are starred. A star toggle swaps in a fresh .reply__star-area (with the new data-starred)
  // but never touches the rail, so re-sync both here — rebuild runs on load and on every htmx:afterSwap.
  syncRailMarkers();
}

// --- branch-index rail mirroring --------------------------------------------------------------------

const cssId = (id) => (window.CSS && CSS.escape ? CSS.escape(id) : id);
const railEntry = (id) => (id == null ? null : document.querySelector('[data-branch-index-entry="' + cssId(id) + '"]'));

/** Reflect the reading cursor in the rail: the selected comment's entry gets .is-current. */
function highlightRailEntry(id) {
  document.querySelectorAll(".branch-index__row.is-current").forEach((r) => r.classList.remove("is-current"));
  const row = railEntry(id)?.closest(".branch-index__row");
  if (row) row.classList.add("is-current");
}

/** Mirror each comment's star state onto its rail entry (data-starred drives the dot↔star swap in CSS). */
function syncRailMarkers() {
  document.querySelectorAll(".reply__star-area[data-reply-id]").forEach((area) => {
    const a = railEntry(area.dataset.replyId);
    if (a) a.dataset.starred = area.dataset.starred === "true" ? "true" : "false";
  });
}

function setCursor(id, scroll = true) {
  if (id == null || !elById.has(id)) return;
  if (currentId != null && elById.has(currentId)) elById.get(currentId).classList.remove("is-current");
  currentId = id;
  cursorHidden = false; // any placement re-engages the cursor (re-shows the highlight)
  // Reflect the selection in the URL so it acts as a permalink. replaceState (not location.hash = …) so
  // it neither piles up a history entry on every j/k nor triggers a native scroll jump that would fight
  // scrollCommentIntoView. Genuine hash changes (link clicks, back/forward) still fire hashchange below.
  try { history.replaceState(history.state, "", hashFor(id)); } catch (e) { /* non-fatal */ }
  const el = elById.get(id);
  el.classList.add("is-current");
  highlightRailEntry(id); // keep the rail's selected entry in step with the reading cursor
  if (scroll) scrollCommentIntoView(el);
}

// Cancel the selection: drop .is-current from the comment + its rail entry and clear the permalink so
// the URL no longer points at a now-deselected node. We keep currentId (just hide it via cursorHidden)
// so the next keyboard move resumes from the last selected position rather than jumping to the top.
// No-op when there's nothing to cancel.
function clearCursor() {
  if (currentId == null || cursorHidden) return;
  if (elById.has(currentId)) elById.get(currentId).classList.remove("is-current");
  cursorHidden = true;
  highlightRailEntry(null); // clears the rail's selected entry (null matches no entry → removes all)
  try { history.replaceState(history.state, "", location.pathname + location.search); } catch (e) { /* non-fatal */ }
}

/*
 * Scroll so the comment's OWN content is visible — its header + body + actions, but NOT its nested
 * children (a parent article spans its whole subtree, so we can't "show the whole box"). The own-content
 * range runs from the article top to the top of its first nested comment (or the article bottom if it's
 * a leaf). We only scroll when needed: off the top → bring the header to the margin (this is the `h`/`k`
 * ancestor case); off the bottom → bring the bottom into view without pushing the header above the top
 * margin (tall comments pin to the top). Fully visible → no scroll. The page scrolls on the window and
 * the site header is static, so plain window.scrollBy is correct.
 */
function scrollCommentIntoView(el) {
  const margin = 16;
  const vh = window.innerHeight || document.documentElement.clientHeight;
  const rect = el.getBoundingClientRect();
  const firstChild = el.querySelector(":scope > " + NAV);
  const bottom = firstChild ? firstChild.getBoundingClientRect().top : rect.bottom;

  if (rect.top < margin) {
    window.scrollBy({ top: rect.top - margin });
  } else if (bottom > vh - margin) {
    window.scrollBy({ top: Math.min(bottom - (vh - margin), rect.top - margin) });
  }
}

// --- compose / vote: drive the existing affordances on the current node -----------------------------

function openInlineComposer(id) {
  const el = elById.get(id);
  // The composer lives inside this node's own action bar (article > .reply__actionbar > details).
  // Scope through .reply__actionbar so we open THIS node's reply box, never a nested child's.
  const det = el && el.querySelector(":scope > .reply__actionbar > details.reply__compose");
  if (!det) return false;
  det.open = true;
  const ta = det.querySelector("textarea");
  if (ta) {
    ta.focus();
    ta.scrollIntoView({ block: "nearest" });
  }
  return true;
}

function focusBottomComposer() {
  const ta = document.querySelector('.composer--bottom textarea, [data-composer="bottom"] textarea');
  if (!ta) return false;
  ta.focus();
  ta.scrollIntoView({ block: "nearest" });
  return true;
}

function replyAsChild() {
  if (!openInlineComposer(currentId)) focusBottomComposer();
}

function replyAsSibling() {
  // sibling == same level == target the parent. At top level (no parent) this collapses to `o`.
  const p = parentOf(model, currentId);
  if (p != null) {
    setCursor(p); // focus the parent first (mirrors `h`): the composer is above the current node, so
    openInlineComposer(p); // move the cursor + scroll there before opening its reply box.
  } else {
    focusBottomComposer();
  }
}

function votePlusOne() {
  const el = elById.get(currentId);
  const btn = el && el.querySelector(":scope > .reply__vote-area .reply__vote-btn");
  if (btn) btn.click();
}

// --- search + term highlight ------------------------------------------------------------------------

let hitRestore = null; // { el, html } to undo the last inline highlight

function clearHighlight() {
  if (hitRestore && hitRestore.el && hitRestore.el.isConnected) hitRestore.el.innerHTML = hitRestore.html;
  hitRestore = null;
}

function highlightTerm(id, query) {
  clearHighlight();
  const el = elById.get(id);
  const body = el && el.querySelector(":scope > .body");
  if (!body || !query) return;
  hitRestore = { el: body, html: body.innerHTML };
  const re = new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "ig");
  body.innerHTML = body.innerHTML.replace(re, (m) => `<mark class="nav-hit">${m}</mark>`);
}

function runSearch(query, dir) {
  const hit = search(model, query, currentId, dir);
  if (hit == null) return false;
  setCursor(hit);
  highlightTerm(hit, query);
  return true;
}

function repeatSearch(dir) {
  if (lastSearch.query) runSearch(lastSearch.query, dir);
}

// --- command-line overlay (search `/` `?` and command palette `:`) ----------------------------------

let overlay = null;

function ensureOverlay() {
  if (overlay) return overlay;
  const wrap = document.createElement("div");
  wrap.className = "nav-cmdline";
  wrap.setAttribute("data-nav-cmdline", "");
  wrap.hidden = true;
  wrap.innerHTML = '<span class="nav-cmdline__prompt"></span><input class="nav-cmdline__input" type="text" autocomplete="off" spellcheck="false">';
  document.body.appendChild(wrap);
  const input = wrap.querySelector("input");
  const prompt = wrap.querySelector(".nav-cmdline__prompt");
  overlay = { wrap, input, prompt, mode: null, dir: 1 };

  input.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      e.preventDefault();
      closeOverlay();
    } else if (e.key === "Enter") {
      e.preventDefault();
      const value = input.value;
      const mode = overlay.mode;
      const dir = overlay.dir;
      closeOverlay();
      if (mode === "search") {
        if (value.trim()) {
          lastSearch = { query: value.trim(), dir };
          runSearch(lastSearch.query, dir);
        }
      } else if (mode === "command") {
        runCommand(value.trim());
      }
    }
    e.stopPropagation(); // keep the global keymap out of the input
  });
  return overlay;
}

function openOverlay(mode, dir) {
  ensureOverlay();
  overlay.mode = mode;
  overlay.dir = dir;
  overlay.prompt.textContent = mode === "search" ? (dir > 0 ? "/" : "?") : ":";
  overlay.input.value = "";
  overlay.wrap.hidden = false;
  overlay.input.focus();
}

function closeOverlay() {
  if (overlay) overlay.wrap.hidden = true;
}

const overlayOpen = () => overlay && !overlay.wrap.hidden;

function runCommand(cmd) {
  const c = cmd.toLowerCase();
  if (c === "help" || c === "h" || c === "?") return showHelp();
  if (c === "threads" || c === "home") return void (window.location.href = "/");
  if (c === "members" || c === "personas") return void (window.location.href = "/personas");
  if (c === "top" || c === "first") return setCursor(firstNode(model));
  if (c === "bottom" || c === "last") return setCursor(lastNode(model));
  if (c === "grow" || c === "autogrow") {
    const btn = document.querySelector('.thread__controls [hx-post$="/auto-grow"]');
    if (btn) btn.click();
  }
}

// --- help cheat sheet -------------------------------------------------------------------------------

const KEYMAP = [
  ["j / k", "next / previous comment (reading order)"],
  ["h", "go to parent (in-reply-to)"],
  ["l", "into the thread (remembered child, else first)"],
  ["L", "into the thread (always first child)"],
  ["J / K", "next / previous sibling"],
  ["gg / G", "first / last comment"],
  ["/  ?", "search forward / backward"],
  ["n / N", "repeat search same / opposite"],
  ["i", "reply as child"],
  ["a", "reply as sibling"],
  ["o", "new top-level reply"],
  ["v", "+1 this comment"],
  [":", "command palette (help, threads, members, grow…)"],
  ["Esc", "close composer / search / help"],
];

let helpEl = null;

function showHelp() {
  if (!helpEl) {
    helpEl = document.createElement("div");
    helpEl.className = "nav-help";
    helpEl.setAttribute("data-nav-help", "");
    const rows = KEYMAP.map(([k, d]) => `<tr><th>${k}</th><td>${d}</td></tr>`).join("");
    helpEl.innerHTML = `<div class="nav-help__panel"><h2>Keyboard navigation</h2><table>${rows}</table><p class="nav-help__hint">Esc to close</p></div>`;
    helpEl.addEventListener("click", () => (helpEl.hidden = true));
    document.body.appendChild(helpEl);
  }
  helpEl.hidden = false;
}

function hideHelp() {
  if (helpEl) helpEl.hidden = true;
}

const helpOpen = () => helpEl && !helpEl.hidden;

// --- key dispatch -----------------------------------------------------------------------------------

function isEditable(t) {
  if (!t) return false;
  return t.tagName === "TEXTAREA" || t.tagName === "INPUT" || t.tagName === "SELECT" || t.isContentEditable;
}

// Keys that move the reading cursor — the first one after a click-away cancel resumes the cursor at its
// remembered position instead of moving (see the cursorHidden handling in onKey).
const MOVE_KEYS = new Set(["j", "k", "h", "l", "L", "J", "K", "g", "G", "n", "N"]);

function onKey(e) {
  if (overlayOpen()) return; // the overlay input owns keys while open
  if (helpOpen()) {
    if (e.key === "Escape") hideHelp();
    return;
  }
  if (isEditable(e.target)) {
    if (e.key === "Escape") {
      e.target.blur();
      e.target.closest("details.reply__compose")?.removeAttribute("open");
      // Return to the comment we were on when a/i/o opened the composer (a moves the cursor to the parent).
      if (composeReturnId != null) { setCursor(composeReturnId); composeReturnId = null; }
    }
    return;
  }
  if (e.ctrlKey || e.metaKey || e.altKey) return;
  if (!model || !model.ids.length) return;

  // A click-away cancel (clearCursor) hides the cursor but remembers where it was. The first movement
  // key re-engages there — resume reading from the last selected position rather than the top. The move
  // itself is consumed; press again to actually move. (If the remembered node is gone, fall through.)
  if (cursorHidden && MOVE_KEYS.has(e.key)) {
    if (currentId != null && elById.has(currentId)) {
      setCursor(currentId); // un-hide: re-highlight, restore the permalink, scroll back into view
      e.preventDefault();
      return;
    }
    cursorHidden = false;
  }

  const wasAwaitingG = awaitingG;
  if (e.key !== "g") awaitingG = false;

  switch (e.key) {
    case "j": setCursor(preorderNext(model, currentId)); break;
    case "k": setCursor(preorderPrev(model, currentId)); break;
    case "h": {
      const p = parentOf(model, currentId);
      if (p != null) { descendMemory.set(p, currentId); setCursor(p); }
      break;
    }
    case "l": setCursor(resolveDescend(model, currentId, descendMemory)); break;
    case "L": setCursor(firstChildOf(model, currentId)); break;
    case "J": setCursor(nextSibling(model, currentId)); break;
    case "K": setCursor(prevSibling(model, currentId)); break;
    case "g":
      if (wasAwaitingG) { setCursor(firstNode(model)); awaitingG = false; }
      else { awaitingG = true; setTimeout(() => { awaitingG = false; }, 700); }
      break;
    case "G": setCursor(lastNode(model)); break;
    case "/": openOverlay("search", 1); break;
    case "?": openOverlay("search", -1); break;
    case "n": repeatSearch(lastSearch.dir); break;
    case "N": repeatSearch(-lastSearch.dir); break;
    case "i": composeReturnId = currentId; replyAsChild(); break;
    case "a": composeReturnId = currentId; replyAsSibling(); break;
    case "o": composeReturnId = currentId; focusBottomComposer(); break;
    case "v": votePlusOne(); break;
    case ":": openOverlay("command", 1); break;
    case "Escape": clearHighlight(); break;
    default: return; // unhandled — don't preventDefault
  }
  e.preventDefault();
}

// Click / tap on a comment moves the cursor there. One handler covers mouse AND touch — browsers fire
// a click on tap (no 300ms delay thanks to the width=device-width viewport). Single tap = place the
// cursor, mirroring an editor's tap-to-place-caret; double-tap is deliberately NOT used (browsers
// reserve it for zoom). We don't preventDefault, so buttons/links inside the comment still work.
function onClick(e) {
  // Clicks inside a composer (chips, slash/@mention palette — PR #29) must NOT
  // move the reading cursor or clear the Esc-return target. Agreed contract with the composer branch.
  if (e.target.closest(".composer")) return;
  const el = e.target.closest(NAV);
  if (el) {
    const id = idOf(el); // closest() returns the innermost comment, so nested replies pick correctly
    if (!elById.has(id)) return;
    composeReturnId = null; // a manual reposition invalidates any pending Esc-return target
    setCursor(id, false); // no scroll: the user clicked something already in view
    return;
  }
  // Click landed outside the comment tree (overlay, header, rail, empty space). A branch-index entry is
  // itself a selection — its href="#reply-<id>" fires hashchange, which moves the cursor — so leave that
  // alone. Everything else cancels the selection: clicking off the thread is the way to deselect.
  if (e.target.closest("[data-branch-index-entry]")) return;
  composeReturnId = null;
  clearCursor();
}

// Follow genuine hash changes — clicking a branch-index entry or an "in reply to" link (both href to
// #reply-<id>) should move the cursor too. setCursor uses replaceState, so it never re-fires this.
function onHashChange() {
  const h = idFromHash();
  if (h && elById.has(h) && h !== currentId) setCursor(h);
}

function init() {
  rebuild();
  // Restore the selection from a permalink on load: highlight + scroll to the linked comment.
  const fromHash = idFromHash();
  if (fromHash && elById.has(fromHash)) setCursor(fromHash);
  document.addEventListener("keydown", onKey);
  document.addEventListener("click", onClick);
  window.addEventListener("hashchange", onHashChange);
  document.body.addEventListener("htmx:afterSwap", rebuild);
}

if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
else init();
