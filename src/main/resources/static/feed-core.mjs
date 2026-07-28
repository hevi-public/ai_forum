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
  // because either alone lets a real selection through — `hasSelection` misses the moment before the
  // browser has committed one, and `movedPx` misses a double-click word-select that never moved.
  if (click.hasSelection) return false;
  if (Number(click.movedPx) > DRAG_SLOP) return false;

  // Middle/right click and ctrl/cmd/shift-click belong to the browser: new tab, new window, context
  // menu. Re-routing them through location assignment would silently break "open in new tab", which
  // on a feed is a thing people actually do.
  if (Number(click.button) !== 0) return false;
  if (click.modified) return false;

  return true;
}
