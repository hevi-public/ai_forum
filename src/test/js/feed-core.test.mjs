import { test } from "node:test";
import assert from "node:assert/strict";
import { shouldOpenCard, DRAG_SLOP } from "../../main/resources/static/feed-core.mjs";

/*
 * An activity card is one big click target wrapped around one piece of genuinely readable text. The
 * core's whole job is telling apart "the owner clicked this card" from the four things that look like
 * a click and are not: a drag that selected the preview, a click on the thread title (which has its
 * OWN destination), a modified click meant to open a new tab, and a non-primary button.
 *
 * pathTags is the walk from the clicked node up to (and including) the card.
 */

/** A plain primary click on card chrome, with each guard at its passing value. */
const plainClick = (over) => ({
  pathTags: ["li"], hasSelection: false, movedPx: 0, button: 0, modified: false, ...over,
});

test("a plain click on card chrome opens the card", () => {
  assert.equal(shouldOpenCard(plainClick()), true);
});

test("a click on the preview text opens the card — the reason this module exists", () => {
  // span.activity-card__excerpt -> li.activity-card. CSS alone could only make this region clickable
  // by turning the text into a link, which is what stopped it being selectable.
  assert.equal(shouldOpenCard(plainClick({ pathTags: ["span", "li"] })), true);
});

test("a drag that selected text is NOT a click", () => {
  // The failure this guards is silent and maddening: highlight a quote, release, and the page leaves.
  assert.equal(shouldOpenCard(plainClick({ hasSelection: true, movedPx: 120 })), false);
});

test("a selection still counts even when the pointer barely moved", () => {
  // Double-click word-select: the browser has a selection, the pointer never travelled.
  assert.equal(shouldOpenCard(plainClick({ hasSelection: true, movedPx: 0 })), false);
});

test("pointer travel alone blocks the click, before any selection exists", () => {
  // A drag can end with an empty selection (started on padding, crossed no text) and must still not
  // navigate — releasing the mouse somewhere you dragged to is not a click on where you started.
  assert.equal(shouldOpenCard(plainClick({ movedPx: DRAG_SLOP + 1 })), false);
  assert.equal(shouldOpenCard(plainClick({ movedPx: DRAG_SLOP })), true, "exactly at the slop still counts");
});

test("a click on the thread title is left alone, because it has its own destination", () => {
  // a.activity-card__thread -> li.activity-card. Swallowing this would collapse the card's two
  // destinations into one: the title opens the CONVERSATION, the card opens THIS EVENT.
  assert.equal(shouldOpenCard(plainClick({ pathTags: ["a", "li"] })), false);
});

test("the stretched open-link is left alone too — the browser already follows it", () => {
  assert.equal(shouldOpenCard(plainClick({ pathTags: ["a", "li"] })), false);
});

test("form controls inside a card keep their own click", () => {
  for (const tag of ["button", "input", "select", "textarea", "label", "summary", "details"]) {
    assert.equal(shouldOpenCard(plainClick({ pathTags: [tag, "li"] })), false, tag);
  }
});

test("tag matching is case-insensitive", () => {
  assert.equal(shouldOpenCard(plainClick({ pathTags: ["A", "LI"] })), false);
});

test("modified clicks belong to the browser, not to us", () => {
  // ctrl/cmd/shift/alt-click is "open in a new tab/window" — re-routing it silently breaks that, and
  // on a feed opening things in background tabs is a normal way to read.
  assert.equal(shouldOpenCard(plainClick({ modified: true })), false);
});

test("middle and right clicks are not ours either", () => {
  assert.equal(shouldOpenCard(plainClick({ button: 1 })), false, "middle: new tab");
  assert.equal(shouldOpenCard(plainClick({ button: 2 })), false, "right: context menu");
});

test("a malformed call is refused rather than guessed at", () => {
  assert.equal(shouldOpenCard(undefined), false);
  assert.equal(shouldOpenCard({}), false);
  assert.equal(shouldOpenCard({ pathTags: "li" }), false);
});
