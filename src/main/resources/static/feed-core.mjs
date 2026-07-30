/*
 * feed-core — pure decision for "click an activity card to open it".
 *
 * NO DOM, NO globals: the one function takes a plain description of the click and decides whether it
 * should open the card's event. This is the unit-tested heart; the DOM glue (feed.js) reads the real
 * event and performs the navigation. See src/test/js/feed-core.test.mjs.
 *
 * WHY THIS EXISTS AT ALL. The card is a big click target with one piece of genuinely readable text in
 * it — the preview — and those two wants fight. CSS alone can satisfy either but not both: make the
 * text part of a link and it stops being drag-selectable; leave it out and clicking the most natural
 * spot on the card does nothing. This function is the arbiter, and it is small precisely because the
 * hard part is knowing WHICH clicks are not clicks.
 */

// Controls that own their own click — a real link or form control inside the card must act normally,
// never be re-routed to the card's own destination. The thread title (<a>) is the case that matters:
// it opens the CONVERSATION while the card opens THIS EVENT, and swallowing it would collapse the two
// destinations the card exists to offer.
const INTERACTIVE = new Set(["a", "button", "input", "select", "textarea", "label", "summary", "details"]);

/** How far the pointer may travel between press and release and still count as a click, in px. */
export const DRAG_SLOP = 4;

/**
 * How long to hold a click on selectable text before acting on it, in ms.
 *
 * A double-click cannot be recognised from its first click: the browser fires mousedown, mouseup,
 * click, and only THEN the second mousedown that turns it into a word-select. At that first click
 * there is no selection yet and the pointer has not moved, so every other guard here says "open" —
 * and the page leaves before the word is ever highlighted. Measured, not theorised: double-clicking a
 * preview navigated away with an empty selection.
 *
 * So a click on text waits long enough for a second press to cancel it. 350ms is above the interval
 * a deliberate double-click takes and below the point the delay reads as lag.
 */
export const DOUBLE_CLICK_GRACE_MS = 350;

/**
 * How long the glue should wait before opening, given where the click landed.
 *
 * Only clicks on selectable text pay the wait. Card chrome — padding, the byline, the stamp — has
 * nothing to select, so a double-click there means nothing and instant navigation is correct. This
 * keeps the cost exactly where the benefit is.
 *
 * @param {boolean} onSelectableText whether the click landed on the card's preview text.
 * @returns {number} milliseconds to wait before navigating.
 */
export function openDelayMs(onSelectableText) {
  return onSelectableText ? DOUBLE_CLICK_GRACE_MS : 0;
}

/**
 * Decide whether a click inside an activity card should open that card's event.
 *
 * @param {object} click
 * @param {string[]} click.pathTags lowercased tag names from the clicked node up to (incl.) the card.
 * @param {boolean} click.hasSelection whether the document currently holds a non-empty text selection.
 * @param {number}  click.movedPx how far the pointer travelled between mousedown and mouseup.
 * @param {number}  click.button which mouse button (0 = primary).
 * @param {boolean} click.modified whether ctrl/meta/shift/alt was held.
 * @returns {boolean} true only for a plain, stationary primary click on non-interactive card chrome.
 */
export function shouldOpenCard(click) {
  if (!click || !Array.isArray(click.pathTags)) return false;

  // A real control inside the card keeps its own behaviour.
  if (click.pathTags.some((tag) => INTERACTIVE.has(String(tag).toLowerCase()))) return false;

  // THE POINT OF THE WHOLE MODULE: a drag that selected text is not a click. Checked two ways,
  // because either alone leaks — `hasSelection` misses the instant before the browser has committed
  // one, and `movedPx` catches a drag whose selection came out empty (started on padding, crossed no
  // text). Neither catches a DOUBLE-click word-select, whose first click has no selection and no
  // travel; that one is handled by delaying the open instead — see [openDelayMs].
  if (click.hasSelection) return false;
  if (Number(click.movedPx) > DRAG_SLOP) return false;

  // Middle/right click and ctrl/cmd/shift-click belong to the browser: new tab, new window, context
  // menu. Re-routing them through location assignment would silently break "open in new tab", which
  // on a feed is a thing people actually do.
  if (Number(click.button) !== 0) return false;
  if (click.modified) return false;

  return true;
}
