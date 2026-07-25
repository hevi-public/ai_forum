import { test } from "node:test";
import assert from "node:assert/strict";
import {
  classifyField,
  reduceStale,
  shouldGate,
} from "../../main/resources/static/persona-form-core.mjs";

/*
 * The frontend "Tier 0" for the prompt/dials divergence guard (see plan_docs/persona-prompt-edit-ux.md):
 * pure staleness logic, no DOM. The DOM glue (persona-form.js) is the manually-verified wiring.
 */

test("classifyField sorts controls into prompt / composer-input / other", () => {
  assert.equal(classifyField("systemPrompt"), "prompt");
  assert.equal(classifyField("dial_agreeableness"), "composer-input");
  assert.equal(classifyField("dial_verbosity"), "composer-input");
  assert.equal(classifyField("abilities"), "composer-input");
  assert.equal(classifyField("descriptor"), "composer-input");
  // name + model feed neither the composed prompt nor staleness.
  assert.equal(classifyField("name"), "other");
  assert.equal(classifyField("model"), "other");
  assert.equal(classifyField(""), "other");
  // A relation stance (S3) is read fresh at reply time, never baked into the stored prompt, so it must
  // NOT count as a composer input — otherwise editing one would flag the prompt stale and gate Save
  // behind a paid Regenerate for a change the prompt doesn't carry.
  assert.equal(classifyField("stance_sol"), "other");
});

test("changing a composer input makes the prompt stale", () => {
  assert.equal(reduceStale(false, { type: "field", name: "dial_agreeableness" }), true);
  assert.equal(reduceStale(false, { type: "field", name: "abilities" }), true);
  assert.equal(reduceStale(false, { type: "field", name: "descriptor" }), true);
});

test("hand-editing the prompt clears stale — the owner has taken it over", () => {
  assert.equal(reduceStale(true, { type: "field", name: "systemPrompt" }), false);
});

test("a completed regenerate clears stale — freshly in sync", () => {
  assert.equal(reduceStale(true, { type: "regenerated" }), false);
});

test("name / model changes leave staleness untouched", () => {
  assert.equal(reduceStale(true, { type: "field", name: "model" }), true);
  assert.equal(reduceStale(false, { type: "field", name: "name" }), false);
});

test("the gate engages only when a prompt is actually shown", () => {
  // Stale but no prompt yet (fresh create) → don't disable Save; the server composes on submit.
  assert.equal(shouldGate(true, false), false);
  // Stale with a prompt shown → engage (disable Save, flag Regenerate).
  assert.equal(shouldGate(true, true), true);
  // Not stale → never gated, prompt or not.
  assert.equal(shouldGate(false, true), false);
});

test("an edit's full cycle: change a dial → stale, regenerate → clear", () => {
  let stale = false;
  stale = reduceStale(stale, { type: "field", name: "dial_verbosity" });
  assert.equal(shouldGate(stale, true), true, "Save disabled after a dial move");
  stale = reduceStale(stale, { type: "regenerated" });
  assert.equal(shouldGate(stale, true), false, "Save re-enabled once regenerated");
});
