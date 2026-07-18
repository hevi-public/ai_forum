/*
 * nav-core — pure tree-navigation model for AI Forum vim-style keyboard nav.
 *
 * NO DOM, NO globals: every function takes a `model` and returns ids (or null). This is the
 * unit-tested heart of the keyboard navigation (see src/test/js/nav-core.test.mjs). The DOM glue
 * (nav.js) builds the model from the rendered comment tree and turns ids into cursor moves.
 *
 * A `model` is built from records in PRE-ORDER (document / reading order):
 *   buildModel([{ id, parentId, author, body }, ...])
 * parentId is null/undefined for top-level nodes. depth is derived from the parent chain.
 */

/** Build the immutable navigation model from pre-ordered records. */
export function buildModel(records) {
  const ids = records.map((r) => r.id);
  const index = new Map(ids.map((id, i) => [id, i]));
  const parent = new Map();
  const children = new Map(ids.map((id) => [id, []]));
  const text = new Map();

  for (const r of records) {
    const p = r.parentId == null ? null : r.parentId;
    parent.set(r.id, p);
    if (p != null && children.has(p)) children.get(p).push(r.id);
    text.set(r.id, `${r.author ?? ""} ${r.body ?? ""}`.toLowerCase());
  }

  const depth = new Map();
  for (const id of ids) {
    let d = 0;
    let cur = parent.get(id);
    while (cur != null) {
      d += 1;
      cur = parent.get(cur);
    }
    depth.set(id, d);
  }

  return { ids, index, parent, children, depth, text };
}

const has = (model, id) => id != null && model.index.has(id);

/** Next node in pre-order (reading order). null at the end. */
export function preorderNext(model, id) {
  if (!has(model, id)) return model.ids[0] ?? null;
  const i = model.index.get(id);
  return i + 1 < model.ids.length ? model.ids[i + 1] : null;
}

/** Previous node in pre-order. null at the start. */
export function preorderPrev(model, id) {
  if (!has(model, id)) return null;
  const i = model.index.get(id);
  return i > 0 ? model.ids[i - 1] : null;
}

/** The "in reply to" parent. null for top-level nodes. */
export function parentOf(model, id) {
  return has(model, id) ? model.parent.get(id) ?? null : null;
}

/** First child, or null if the node is a leaf. */
export function firstChildOf(model, id) {
  if (!has(model, id)) return null;
  const kids = model.children.get(id);
  return kids.length ? kids[0] : null;
}

function siblings(model, id) {
  const p = parentOf(model, id);
  return p == null ? model.ids.filter((x) => model.parent.get(x) == null) : model.children.get(p);
}

/** Next sibling at the same level (skips the current subtree). null if last. */
export function nextSibling(model, id) {
  if (!has(model, id)) return null;
  const sibs = siblings(model, id);
  const i = sibs.indexOf(id);
  return i >= 0 && i + 1 < sibs.length ? sibs[i + 1] : null;
}

/** Previous sibling at the same level. null if first. */
export function prevSibling(model, id) {
  if (!has(model, id)) return null;
  const sibs = siblings(model, id);
  const i = sibs.indexOf(id);
  return i > 0 ? sibs[i - 1] : null;
}

export function firstNode(model) {
  return model.ids[0] ?? null;
}

/** Last node in pre-order (the deepest-last reply on the page). */
export function lastNode(model) {
  return model.ids.length ? model.ids[model.ids.length - 1] : null;
}

/**
 * Descend with ranger-style memory. `memory` is a Map<parentId, lastChildId>. Returns the remembered
 * child if it is still a child of `id`, otherwise the first child (or null for a leaf). Pass an empty
 * Map (or omit) for a raw "first child" descent.
 */
export function resolveDescend(model, id, memory) {
  const first = firstChildOf(model, id);
  if (first == null) return null;
  const remembered = memory && memory.get(id);
  if (remembered != null && model.parent.get(remembered) === id) return remembered;
  return first;
}

/**
 * Search by direction from `fromId`. dir is +1 (forward) or -1 (backward). Matches the lowercased
 * "author body" text, substring. Wraps around the whole list. `fromId` itself is excluded so n/N
 * advance. Returns the matching id or null when nothing matches.
 */
export function search(model, query, fromId, dir = 1) {
  const q = (query ?? "").trim().toLowerCase();
  const n = model.ids.length;
  if (!q || n === 0) return null;
  const start = has(model, fromId) ? model.index.get(fromId) : dir > 0 ? -1 : n;
  for (let step = 1; step <= n; step += 1) {
    const pos = (((start + dir * step) % n) + n) % n;
    const id = model.ids[pos];
    if (id === fromId) continue; // wrap landed back on the current node — exclude it so n/N advance
    if (model.text.get(id).includes(q)) return id;
  }
  return null;
}

/** All matching ids in pre-order (for the stretch results-list search mode). */
export function matches(model, query) {
  const q = (query ?? "").trim().toLowerCase();
  if (!q) return [];
  return model.ids.filter((id) => model.text.get(id).includes(q));
}
