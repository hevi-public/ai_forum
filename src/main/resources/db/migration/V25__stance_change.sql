-- The audit trail for automatically evolved relation stances (plan_docs/ambient-slice-4a.md D8): one
-- append-only row per rewritten persona_stance edge, modelled on ambient_run (V21) — INTEGER PRIMARY KEY
-- AUTOINCREMENT, a Clock-stamped ISO-8601 timestamp, and an index per hot read (two of them here — see
-- the bottom of this file).
--
-- This table is not decoration, it is the ONLY control on the evolution pass. S4a auto-applies with no
-- approval queue (direction doc §11.5, owner call), so the owner's whole lever is reading old->new on
-- /admin/stances afterwards and reverting what they disagree with. That forces two things into the row:
--
--   * `old_stance` AND `old_source` are captured BEFORE the persona_stance upsert. persona_stance has no
--     history and its upsert overwrites stance + source + updated_at in a single statement, so a change
--     not captured first is UNREVERTABLE — the old text exists nowhere else in the system. And without
--     old_source, reverting a seeded row would silently relabel it 'evolved', quietly making it a
--     candidate for re-evolution on a different footing than it started on.
--   * `reverted_at` stays NULL until the owner reverts, which is also what blocks a double revert (a
--     second revert would otherwise re-apply a text that is already live and stamp a fresh time on it).
--
-- TWO DIFFERENT FOREIGN-KEY POSTURES, ON PURPOSE. This is the one place the "deleting a thread/persona
-- leaves no dangling stance-audit rows" rule needs interpreting, and the two halves land differently:
--
--   * The PERSONA endpoints CASCADE, exactly like persona_stance itself. An audit row for a departed
--     persona has nothing left to revert ONTO — the stance row it describes cascaded away on the same
--     delete, and attempting the revert would only fail on that same foreign key. So the audit row goes
--     with the persona and no orphan can exist.
--   * The CITED INTERACTIONS are SNAPSHOTTED TEXT plus plain comment ids, with NO foreign key — the
--     comment_quote.quoted_text precedent. comment.body is mutable in place (edit, revision select), so
--     citing by id alone would let the evidence silently change under the audit record that justifies it,
--     and the owner would be judging the judgment against text the judge never saw. Deleting a thread
--     therefore cannot orphan a row here either: there is nothing to orphan, and the rendered permalinks
--     are defensive. (SQLite cannot add a foreign key by ALTER TABLE, so both calls are made here or never.)
--
-- THE HARD GUARDRAIL, inherited from V24 and restated because an audit table is exactly where it would
-- break first: there is NO numeric column here and none may ever be added — no confidence, no delta, no
-- interaction_count, nothing. `cited` records WHICH exchanges were judged, as ids and prose; it must never
-- record HOW MANY. The moment an audit row carries a magnitude, changes become comparable, then rankable,
-- then optimisable, and the reward economy this design cut is back wearing an auditor's badge.
CREATE TABLE stance_change (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    from_persona  TEXT NOT NULL REFERENCES persona(id) ON DELETE CASCADE,
    to_persona    TEXT NOT NULL REFERENCES persona(id) ON DELETE CASCADE,
    old_stance    TEXT NOT NULL,
    new_stance    TEXT NOT NULL,
    old_source    TEXT NOT NULL,   -- what to restore on revert, not decoration
    cited         TEXT NOT NULL,   -- comment ids + snapshotted prose, never a count
    changed_at    TEXT NOT NULL,   -- injected Clock, ISO-8601
    reverted_at   TEXT             -- NULL until the owner reverts; blocks double-revert
);
-- TWO INDEXES, because the two hot reads are not the same shape and neither index can answer the other's
-- question:
--
--   * `idx_stance_change_edge` serves the evolution window, which is PER EDGE rather than one global
--     watermark (see StanceChangeRepository.lastStandingChangeAt on why one boundary for the whole table
--     lets a single pair's success disinherit every other pair in the run). The read is
--     `WHERE from_persona = ? AND to_persona = ? AND reverted_at IS NULL`, executed once per candidate
--     edge on every run — the pass's most-repeated query, and a table scan without this. `reverted_at`
--     stays out of the index on purpose: it discriminates a handful of rows for one pair, so it is a
--     cheap residual filter, not a third index column.
--   * `idx_stance_change_changed_at` serves /admin/stances, which is still ORDER BY changed_at DESC —
--     a different column in a different direction, which the (from, to) prefix cannot supply.
CREATE INDEX idx_stance_change_edge ON stance_change(from_persona, to_persona);
CREATE INDEX idx_stance_change_changed_at ON stance_change(changed_at);
