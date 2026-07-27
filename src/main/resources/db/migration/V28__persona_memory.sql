-- V28__persona_memory.sql
--
-- Persona memory (plan_docs/persona-memory.md): a private, per-persona tree of prose memory
-- records plus an optional owner-only root, the append-only audit trail for the pass that writes
-- records, and the per-member consolidation watermark.
--
-- THERE IS NO NUMBER IN EITHER TABLE AND NONE MAY EVER BE ADDED (fifth slice running). No
-- salience, no recall count, no importance, no strength. Anything score-shaped is the cut reward
-- economy wearing a new column name (direction doc §11.7). Retrieval "relevance" is a binary
-- decision computed in memory from the record's own words and discarded (plan doc §2.7).
--
-- NOTE on digits: unlike V27 there is NO body-level GLOB digit ban. A memory like "we argued
-- about WAL mode in V27" is honest prose, and this forum's own subject matter is digit-saturated;
-- a body ban plus rejected-never-stamps would re-buy the same judgment weekly (the V26/PR#6 cost
-- shape, judged a fatal flaw in design C). The Stays-Cut line is a number that is model-written
-- AND machine-read into selection as a magnitude — and word-overlap matching never parses a
-- number out of a body (plan doc §2.8). Rating-shaped lines are refused at parse as hygiene.

CREATE TABLE persona_memory (
    id         TEXT NOT NULL PRIMARY KEY,
    -- Live state, not history: a memory without its member is noise no surface can show, so a
    -- real FK with CASCADE (the persona_stance/persona_interest posture), declared here because
    -- SQLite cannot add a foreign key by ALTER TABLE later.
    persona_id TEXT NOT NULL REFERENCES persona(id) ON DELETE CASCADE,
    -- The associative link: this record extends one earlier record OF THE SAME PERSONA. NULL =
    -- top-level (directly under the member's root, present or not). See the composite FK below.
    parent_id  TEXT,
    -- 'root' = the §6.3 root post: motivation, background, identity. At most one per member
    -- (partial unique index below), owner-only (CHECK below), NOT injected into any prompt this
    -- slice. 'record' = a memory record.
    kind       TEXT NOT NULL DEFAULT 'record' CHECK (kind IN ('root','record')),
    body       TEXT NOT NULL,
    -- Provenance at birth, unbackfillable (the third occurrence of the rule). The scribe's
    -- insert path hard-codes 'scribe'; no code path can write 'owner' from the pass.
    source     TEXT NOT NULL CHECK (source IN ('owner','scribe')),
    created_at TEXT NOT NULL,                    -- injected Clock, ISO-8601
    -- The root is owner-only IN DDL: no pass can ever write identity. This CHECK is only
    -- available at table birth — SQLite cannot add a CHECK by ALTER — which is exactly why the
    -- root row ships in this migration even though injection is deferred (§2.3).
    CHECK (kind = 'record' OR source = 'owner'),
    -- The root has no parent; records with NULL parent sit directly under it.
    CHECK (kind = 'record' OR parent_id IS NULL),
    CHECK (length(body) > 0),
    -- Scoped to what the PASS may write (the V27 scoping precedent): the owner path must never
    -- trip a model-aimed constraint. length() counts code points; MemoryText counts
    -- codePointCount — the two sides agree by construction (I5).
    CHECK (source = 'owner' OR length(body) BETWEEN 1 AND 300),
    -- The same-persona association, unrepresentable to violate (design C's graft, mandated by
    -- the guardrails verdict): the parent FK binds persona_id too, so a cross-persona link is
    -- refused by SQLite, not by a repository check someone could bypass. The repository's own
    -- parent check demotes to belt. ON DELETE CASCADE because a composite SET NULL would null
    -- persona_id as well, which NOT NULL forbids — so CASCADE is the only representable action;
    -- it fires only under persona-cascade ordering, because single-row deletes reparent children
    -- first (§2.10).
    UNIQUE (persona_id, id),
    FOREIGN KEY (persona_id, parent_id)
        REFERENCES persona_memory(persona_id, id) ON DELETE CASCADE
);
-- At most one root per member, in DDL.
CREATE UNIQUE INDEX idx_persona_memory_root ON persona_memory(persona_id) WHERE kind = 'root';

-- The audit trail, modelled on interest_change (V27) including its split FK posture.
CREATE TABLE memory_change (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    -- Persona endpoint CASCADEs: an audit row for a departed member has nothing left to revert.
    persona_id  TEXT NOT NULL REFERENCES persona(id) ON DELETE CASCADE,
    -- Bare id, NO FK: the record may be reverted or owner-deleted; the audit row must survive
    -- holding its snapshot (the cited/quoted_text pattern, V25/V27).
    memory_id   TEXT NOT NULL,
    body        TEXT NOT NULL,     -- snapshot of the record as written
    parent_body TEXT,              -- snapshot of the linked antecedent — prose, not FK
    -- One line per cited engagement: commentId \t threadId \t snippet. Snapshotted prose plus
    -- bare ids, because comment.body is mutable in place.
    cited       TEXT NOT NULL,
    -- The run's PRE-QUERY evidence-read instant (design A's graft): the value the watermark was
    -- stamped with, carried onto every audit row so the read-instant contract (bed019fe) is
    -- auditable per row rather than trusted.
    read_at     TEXT NOT NULL,
    changed_at  TEXT NOT NULL,
    reverted_at TEXT               -- NULL until reverted; this column IS the double-revert guard
);
CREATE INDEX idx_memory_change_persona    ON memory_change(persona_id);
CREATE INDEX idx_memory_change_changed_at ON memory_change(changed_at);

-- The per-member window: when did the pass last LOOK. Nullable-no-default is the only shape
-- ALTER TABLE reliably supports. NULL = never judged — bounded by the 90-day evidence horizon
-- (plan doc §2.6), never an all-time read. Read/written ONLY by
-- PersonaMemoryRepository.judgedAt/markJudged; a malformed stamp reads as NULL with a warning.
ALTER TABLE persona ADD COLUMN memory_judged_at TEXT;
