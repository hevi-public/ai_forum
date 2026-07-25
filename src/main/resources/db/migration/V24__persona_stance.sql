-- Qualitative persona relations (plan_docs/ambient-slice-3.md): one row per DIRECTED persona->persona
-- edge carrying a short FREE-TEXT stance, rendered as prose into the generation prompt. Directed
-- because the relation is asymmetric by design — Vex may find Sol's rigour bracing while Sol finds Vex
-- exhausting — so (a→b) and (b→a) are two independent rows, not one symmetric edge.
--
-- THE HARD GUARDRAIL: there is NO number anywhere in this table, and none may ever be added. A stance is
-- prose an LLM reads, never a magnitude. The moment a stance becomes a score (affinity 0.7, trust 3/5)
-- the deliberately-cut reward economy is back: rows become comparable, then rankable, then optimisable,
-- and personas start playing a points game instead of holding a view. Free text has no gradient to climb.
--
-- Why real FKs + ON DELETE CASCADE here, when comment bylines elsewhere are plain attribution strings
-- (V1 schema — deleting a persona leaves past comments' bylines intact): a byline is HISTORY, which must
-- survive its subject, whereas a stance is LIVE RELATIONAL STATE that is meaningless once either endpoint
-- is gone. A dangling stance would be injected into a prompt naming a persona that no longer posts. So
-- the delete must cascade — and since SQLite cannot add a foreign key by ALTER TABLE (no ADD CONSTRAINT;
-- retrofitting one costs a full table rebuild), it has to be declared here at CREATE TABLE or never.
--
-- `source` records provenance and is read by nothing in this slice. It exists now precisely because it
-- CANNOT be backfilled: once seeded, owner-authored and auto-evolved rows are indistinguishable after the
-- fact. A later slice that evolves stances from observed conversation must be able to leave the owner's
-- own wording alone (and to tell a seed it may overwrite from an edit it may not), and that decision is
-- only recordable at write time. Same spirit as ambient_run.cost_usd — capture the unrecoverable field.
CREATE TABLE persona_stance (
    from_persona TEXT NOT NULL REFERENCES persona(id) ON DELETE CASCADE,  -- the persona HOLDING the view
    to_persona   TEXT NOT NULL REFERENCES persona(id) ON DELETE CASCADE,  -- the persona it is held ABOUT
    stance       TEXT NOT NULL,   -- free text, prose only — never a score, never parsed for a number
    source       TEXT NOT NULL DEFAULT 'seeded',
    updated_at   TEXT NOT NULL,   -- injected Clock, ISO-8601
    PRIMARY KEY (from_persona, to_persona),
    -- A persona holding a stance about itself has no meaning in the prompt ("Sol on Sol") and would be a
    -- symptom of a caller looping over the roster without excluding self. Reject it at the storage layer
    -- rather than trusting every future caller to filter.
    CHECK (from_persona <> to_persona),
    CHECK (source IN ('seeded', 'owner', 'evolved'))
);
