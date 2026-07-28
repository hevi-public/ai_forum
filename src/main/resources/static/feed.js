/*
 * feed.js — DOM glue for "click an activity card to open it" (see plan_docs/ambient-slice-6.md §2.5).
 *
 * Pure progressive enhancement, and that word is load-bearing here. WITHOUT this file the card still
 * works: an empty stretched <a class="activity-card__open"> behind the content makes the padding, the
 * monogram, the byline, the verb and the stamp all open the event, and it is focusable so the keyboard
 * reaches it. What this file adds is the last region CSS could not have without cost — the PREVIEW
 * TEXT — which stays a plain <span> so it drag-selects like any other text on the page.
 *
 * That is the trade S6 originally refused ("zero new JavaScript") and the owner reversed once the
 * CSS-only version proved it could not have both. The refusal was mine, not a rule.
 *
 * The decision (is this actually a click, and is it mine to take?) lives in feed-core.mjs and is
 * unit-tested. This file only measures what the core cannot see: the pointer travel between press and
 * release. Activates wherever [data-activity-stream] exists, no-ops elsewhere.
 */
import { shouldOpenCard } from "./feed-core.mjs";

const STREAM = "[data-activity-stream]";
const CARD = ".activity-card";
const OPEN = ".activity-card__open";

// Where the pointer went down, so the core can tell a click from the tail end of a drag. Tracked on
// the document rather than per-card: a selection often STARTS inside one card and ends outside it.
let downX = 0;
let downY = 0;

document.addEventListener("mousedown", function (e) {
  downX = e.clientX;
  downY = e.clientY;
});

document.addEventListener("click", function (e) {
  const stream = e.target.closest(STREAM);
  if (!stream) return;
  const card = e.target.closest(CARD);
  if (!card) return;

  // The card's own destination, read from the link that already carries it — never rebuilt here, so
  // there is one source of truth for the href and no second URL shape to keep in step.
  const open = card.querySelector(OPEN);
  if (!open) return;

  // Walk from the clicked node up to (and including) the card, collecting tag names for the core.
  const tags = [];
  for (let el = e.target; el; el = el.parentElement) {
    tags.push(el.tagName.toLowerCase());
    if (el === card) break;
  }

  const moved = Math.hypot(e.clientX - downX, e.clientY - downY);
  const selection = String(window.getSelection() || "");

  if (!shouldOpenCard({
    pathTags: tags,
    hasSelection: selection.length > 0,
    movedPx: moved,
    button: e.button,
    modified: e.ctrlKey || e.metaKey || e.shiftKey || e.altKey,
  })) return;

  // assign(), not href=, so the visit is a normal history entry and Back returns to the feed.
  window.location.assign(open.href);
});
