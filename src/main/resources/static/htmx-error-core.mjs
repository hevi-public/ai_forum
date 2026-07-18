/*
 * htmx-error-core — pure decision + persistence layer for honest htmx failure handling (T1.4).
 *
 * NO DOM, NO globals, NO clock/random: the toast wording (`noticeFor`), the relative-age label
 * (`ageLabel`), and the sticky toast STORE (add/dismiss/list over an injectable storage) live here and
 * are unit-tested. The DOM glue (htmx-error.js, loaded directly from layout.kte) wires real localStorage
 * + the DOM + the ✕ button + load-time rehydration, and supplies real ids + the clock. See
 * src/test/js/htmx-error-core.test.mjs.
 *
 * The toast-only design rests on htmx-2.0.6 behaviour verified against the vendored dist/htmx.js:
 *   - On a non-2xx htmx does NOT swap the body (default responseHandling: [45].. → swap:false), so the
 *     server returns the real error status and NOTHING lands in the compose field.
 *   - It still processes `HX-Trigger` (at the top of handleAjaxResponse, before swap/error branches), so
 *     the server fires `app:error` regardless of status — the client toasts off it.
 *   - It re-enables `hx-disabled-elt` controls and clears spinners itself (removeRequestIndicators in
 *     xhr.onload/onerror/ontimeout), so there is no stuck control to fix here.
 * The one thing htmx gives no default for is user-visible failure feedback — that is this module's job.
 */

// The htmx event for a request that never reached the server (network failure). No response, no swap.
export const SEND_ERROR = "htmx:sendError";
// Our server's out-of-band failure signal, dispatched by htmx from the advice's HX-Trigger header.
export const ERROR_EVENT = "app:error";

// Most recent N toasts are kept; older ones are dropped so storage can't grow unbounded.
export const MAX_TOASTS = 5;

// A documented backstop: persisted toasts older than this are pruned (on load and on write), so a stale
// error can't linger forever and localStorage usage stays bounded (with the cap-of-5 above).
export const TOAST_TTL_MS = 24 * 60 * 60 * 1000; // 24h

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;

/**
 * A relative "time elapsed" label for a toast, e.g. "just now", "3 minutes ago", "2 hours ago" — so a
 * rehydrated toast reads as `Server error · 3 minutes ago` instead of a contextless message. Uses native
 * `Intl.RelativeTimeFormat` (zero-dep, matches the no-CDN ethos). [now] is injected so the core stays
 * deterministic/Tier-0 (no argless Date.now()).
 *
 * @param {number} createdAt epoch-ms the toast was raised
 * @param {number} now       epoch-ms "now"
 * @returns {string} a short relative label
 */
export function ageLabel(createdAt, now) {
  const elapsed = Math.max(0, now - createdAt);
  if (elapsed < MINUTE) return "just now";
  const rtf = new Intl.RelativeTimeFormat("en", { numeric: "always" });
  if (elapsed < HOUR) return rtf.format(-Math.floor(elapsed / MINUTE), "minute");
  return rtf.format(-Math.floor(elapsed / HOUR), "hour");
}

/**
 * The owner-facing notice for a failed htmx interaction.
 *
 * @param {string} eventType   the event name (SEND_ERROR | ERROR_EVENT)
 * @param {number|null} status the mapped HTTP status carried by an app:error event, else null
 * @returns {string} a short, non-blocking message
 */
export function noticeFor(eventType, status) {
  if (eventType === SEND_ERROR) {
    return "Couldn't reach the server — check your connection and try again.";
  }
  // app:error (or anything else with a status): word it from the mapped status the server sent.
  if (status === 429 || status === 503) {
    return "The model is busy right now — try again in a moment.";
  }
  if (typeof status === "number" && status >= 500) {
    return "Something went wrong on the server — please try again.";
  }
  if (typeof status === "number" && status >= 400) {
    return "That request couldn't be completed — please try again.";
  }
  return "Something went wrong — please try again.";
}

/*
 * The sticky toast STORE. `storage` is an injectable interface — `{ read(): Toast[], write(toasts) }` —
 * so the glue can back it with localStorage while tests back it with a plain in-memory object. A Toast is
 * `{ id, kind, status, message, createdAt }`; the glue mints `id` + `createdAt` (the core never calls
 * Date.now()/Math.random()). Rehydration is implicit: a fresh store over the same storage `read()`s it.
 *
 * Persistence is BEST-EFFORT and TTL-BOUNDED: toasts older than TOAST_TTL_MS (24h) are pruned on read and
 * write, and `localStorage.setItem` is wrapped so a throw never breaks the toast path (the in-session
 * toast still renders, it just won't persist that session). Deliberately NOT handled — acceptable for a
 * single-user PoC, revisit if it bites: Safari private mode (setItem always throws → no persistence that
 * session) and hard quota exhaustion. The cap-of-5 + TTL keep localStorage usage hard-bounded.
 */

/** The active toasts, oldest-first. Tolerates a missing/corrupt store by treating it as empty. */
export function listToasts(storage) {
  const raw = storage.read();
  return Array.isArray(raw) ? raw : [];
}

/**
 * Add [toast] (append), then enforce de-dupe + cap, and persist. Returns the new active list.
 *   - De-dupe: if the most recent existing toast has the same kind+status, replace it rather than stack
 *     (a retried-and-still-failing call shouldn't pile identical toasts). The newcomer's id wins.
 *   - Cap: keep only the most recent MAX_TOASTS, dropping the oldest.
 */
export function addToast(storage, toast) {
  let toasts = listToasts(storage).slice();
  const last = toasts[toasts.length - 1];
  if (last && last.kind === toast.kind && last.status === toast.status) {
    toasts[toasts.length - 1] = toast; // collapse the consecutive duplicate
  } else {
    toasts.push(toast);
  }
  if (toasts.length > MAX_TOASTS) {
    toasts = toasts.slice(toasts.length - MAX_TOASTS);
  }
  storage.write(toasts);
  return toasts;
}

/** Remove the toast with [id] and persist. Returns the new active list (unchanged if id is absent). */
export function dismissToast(storage, id) {
  const toasts = listToasts(storage).filter((t) => t.id !== id);
  storage.write(toasts);
  return toasts;
}

// The key under which toasts persist.
export const STORAGE_KEY = "ai_forum.errorToasts";

/** Drop toasts whose createdAt is older than TOAST_TTL_MS relative to [now]. Missing createdAt = kept. */
export function pruneExpired(toasts, now) {
  if (!Array.isArray(toasts)) return [];
  return toasts.filter((t) => typeof t.createdAt !== "number" || now - t.createdAt < TOAST_TTL_MS);
}

/**
 * Build a `{ read, write }` storage backed by a Web-Storage-like object [ls] (real `localStorage` in the
 * glue, a fake in tests), with TTL pruning + best-effort persistence:
 *   - read(): load from localStorage (→ [] on any read fault), then prune entries older than TOAST_TTL_MS
 *     using the injected [now] — so a stale error never rehydrates after 24h.
 *   - write(toasts): prune, keep an in-session memory copy (so a toast still lists even if persistence
 *     fails), then best-effort `setItem` wrapped in try/catch — a throw (Safari private mode / quota)
 *     NEVER breaks the toast path; the toast just won't persist that session.
 * [now] is a `() => epoch-ms` clock, injected so pruning is deterministic/testable.
 */
export function toastStorage(ls, now, key = STORAGE_KEY) {
  let memory = null; // the in-session copy; non-null once we've written this session
  return {
    read() {
      // Prefer the in-session copy (it reflects writes that may not have persisted); else load+parse.
      let toasts = memory;
      if (toasts == null) {
        try {
          const raw = ls.getItem(key);
          toasts = raw ? JSON.parse(raw) : [];
        } catch (e) {
          toasts = [];
        }
      }
      return pruneExpired(Array.isArray(toasts) ? toasts : [], now());
    },
    write(toasts) {
      const pruned = pruneExpired(Array.isArray(toasts) ? toasts : [], now());
      memory = pruned; // authoritative for this session regardless of whether persistence succeeds
      try {
        ls.setItem(key, JSON.stringify(pruned)); // best-effort — a throw must not break the toast path
      } catch (e) {
        /* private mode / quota: keep the in-session copy, skip persistence */
      }
    },
  };
}
