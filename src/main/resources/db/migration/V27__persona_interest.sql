-- V27__persona_interest.sql
--
-- S4b (plan_docs/ambient-slice-4b.md): mutable interests, the per-interest provenance that makes each
-- member's immutable core PER-PERSONA, and the append-only audit trail for every drift.
--
-- THERE IS NO NUMBER IN EITHER TABLE AND NONE MAY EVER BE ADDED. V24's header stated this rule for
-- relations, V25's restated it for their audit trail, V26's restated it again for a new column — and this
-- is the fourth restatement because S4b is the slice most tempted to break it: it is the convergence-risk
-- mechanism, and "measuring convergence" is precisely the thing that wants a count. There is no
-- drift_count, no confidence, no affinity, no how-strongly. `cited` records WHICH words a judgment read,
-- as comment ids and snapshotted prose; it must never record HOW MANY. The rule is not squeamishness
-- about arithmetic: a magnitude attached to what a member is into is comparable, therefore rankable,
-- therefore optimisable, and the cut reward economy is back wearing a new column name (direction doc §2,
-- §11.7 Stays-Cut). The only numbers this slice tolerates are the AUTHORED 0-10 dials it does not touch
-- (V10) and the readout on /admin/interests, whose subject is the POPULATION — a phrase and the members
-- who hold it, rendered as NAMES, never as a score attached to a member (§2.12).
--
-- Why a TABLE rather than a column on `persona`:
--   1. SQLite's ALTER TABLE ADD COLUMN cannot carry the CHECK constraints below, and those CHECKs are the
--      first time the no-numbers guardrail is enforced by the database rather than by a parser a future
--      writer could bypass.
--   2. Provenance is PER INTEREST. source='owner' is what makes the immutable set genuinely not-global
--      (requirements §6.2): Sol may pin a phrase Mira leaves open. One JSON column has nowhere to put it.
--   3. A row read inside GenerationService's per-persona context seam is LIVE at settle time (the seam
--      D7 renames withStances -> withPersonaContext); a column on the Persona
--      row that GenPlan captured at plan-mint is a snapshot (D7).
CREATE TABLE persona_interest (
    -- Live state, not history: once the member is gone the phrase has nothing left to mean, so this is a
    -- real FK with CASCADE (the persona_stance posture, V24), declared here because SQLite cannot add a
    -- foreign key by ALTER TABLE later.
    persona_id TEXT NOT NULL REFERENCES persona(id) ON DELETE CASCADE,
    -- A short prose phrase ("boring technology choices"), never a tag: tags are what `abilities` are, and
    -- AmbientGate.relevance COUNTS ability tags. COLLATE NOCASE so storage agrees with the
    -- case-insensitive already-held refusal in InterestDrift.parse — otherwise "Storage engines" and
    -- "storage engines" become two rows and the count invariant (I3) leaks.
    interest   TEXT NOT NULL COLLATE NOCASE,
    -- 'seeded' (config) | 'owner' (edit form) | 'drifted' (this pass). persona_stance.source's contract
    -- one object over: an 'owner' row is skipped BEFORE the judgment, for good, and skipping it is free.
    source     TEXT NOT NULL DEFAULT 'seeded',
    updated_at TEXT NOT NULL,                     -- injected Clock, ISO-8601 (house repository pattern)
    PRIMARY KEY (persona_id, interest),
    CHECK (source IN ('seeded', 'owner', 'drifted')),
    -- I2, in SQL. Scoped to the rows the PASS may write: the rule exists to stop a MODEL smuggling a
    -- score in, and an owner typing "web3" or "http/3" is not that. Unscoped, this CHECK would throw a
    -- DataAccessException inside PersonaController.edit — where interest writes run BEFORE the prompt
    -- logic, so the owner would silently lose their descriptor and dial edits too. That is the exact
    -- failure applyStanceEdits' three no-op guards were written to prevent (PersonaController.kt:226-245),
    -- and D11 keeps the owner path away from this CHECK with a shared pure validator besides.
    CHECK (source = 'owner' OR interest NOT GLOB '*[0-9]*'),
    -- Two chars admits "Go" and "AI"; eighty is the same bound the parse enforces, so the two agree.
    CHECK (length(trim(interest)) BETWEEN 2 AND 80)
);
-- No second index: the PK's leading (persona_id, …) answers the only hot read — "this member's
-- interests" — which runs once per generation.

-- The audit trail, modelled on stance_change (V25) including its split FK posture.
CREATE TABLE interest_change (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    -- Persona endpoint CASCADEs: an audit row for a departed member has nothing left to revert onto.
    persona_id     TEXT NOT NULL REFERENCES persona(id) ON DELETE CASCADE,
    dropped        TEXT NOT NULL,
    -- What a revert RESTORES, not decoration. Without it, reverting a seeded row silently relabels it
    -- 'drifted' and the next pass reads a lie about who wrote that phrase.
    dropped_source TEXT NOT NULL,
    taken_up       TEXT NOT NULL,
    -- One line per cited engagement: commentId \t threadId \t snippet. Snapshotted PROSE plus bare ids
    -- with NO foreign key — the comment_quote.quoted_text precedent — because comment.body is mutable in
    -- place, so citing by id alone would let the evidence change under the record the owner is judging.
    cited          TEXT NOT NULL,
    changed_at     TEXT NOT NULL,
    -- NULL until reverted. This column IS the double-revert guard, enforced in SQL, not by convention.
    reverted_at    TEXT
);
CREATE INDEX idx_interest_change_persona    ON interest_change(persona_id);   -- lastStandingChangeAt
CREATE INDEX idx_interest_change_changed_at ON interest_change(changed_at);   -- newest-first admin log

-- The per-member window: WHEN DID THE PASS LAST LOOK, which is a different question from when the text
-- last changed (updated_at above). V26 exists because the audit table only gets a row when something
-- moved, and "nothing moved" is the designed steady state — worse here than for stances, because most
-- members most weeks will have written nothing that moves them.
-- Nullable with no default is also the only shape ALTER TABLE reliably supports: a NOT NULL add needs a
-- non-null literal default, a UNIQUE/PK add is refused, and an FK add is impossible. NULL means "never
-- looked at" -> read this member's whole history once. It is a MOMENT, and there is nothing in it to
-- rank, compare or optimise: no times_judged, no runs_since_change, no verdict score.
ALTER TABLE persona ADD COLUMN interests_judged_at TEXT;
