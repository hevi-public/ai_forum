-- V30__generation_tool_call.sql
--
-- Issue #15: the audit trail for the tools a generation actually reached for. Stance changes (V25),
-- interest drifts (V27) and memory writes (V28) each have a full audit table; the tool FETCH that
-- informed a reply — the PR diff a reviewer pulled, the page a persona read — has had none, while the
-- data has been sitting in the CLI's stream-json all along and being thrown away. This is that table.
-- The sibling half of the same slice needs no DDL: `ambient_run.cost_usd` has been REAL-and-NULL since
-- V21, whose header said it awaited an LlmClient contract change. This is that change.
--
-- WHAT A ROW IS. One observed tool invocation of ONE generation, written at settle — after the reply is
-- persisted, never during the turn — so a row exists only for a turn that finished reaching the settle
-- path. Rows are STREAMING-CLI ONLY, and that is a structural fact rather than a coverage gap: the
-- streaming NDJSON carries the assistant's `tool_use` parts and the following `tool_result`s, while the
-- plain-json `--output-format json` envelope carries no content array at all, and the openai / opencode
-- / stub providers run no tool loop. For every one of those, EMPTY IS THE CORRECT ACCOUNT of the turn —
-- not a missing trace. Nothing here is ever read back into a prompt; it is an operator surface.
--
-- WHY run_id IS TEXT AND NOT A FOREIGN KEY TO ambient_run. Tool calls belong to a GENERATION, not to a
-- tick. `run_id` is the in-flight node id the generation settles into — which is also the settled
-- comment's id — because that is the identity the AG-UI stream, the in-flight registry and the settled
-- row already share. One tick fans out N generations plus, on the comment action, a whole growth round;
-- an owner summon has no tick behind it at all. So a FK to ambient_run would be wrong for most rows and
-- impossible for the rest. There is deliberately NO foreign key here in either direction, and the row
-- that freedom buys is the UNSAVEABLE one: a turn whose model call SUCCEEDED but whose reply could not
-- be persisted (COULDNT_SAVE, UX state E) still leaves its trace, hanging on run_id alone with a NULL
-- comment_id — there being no comment row for a foreign key to point at.
--
-- WHAT THAT DOES NOT COVER — spelled out because the opposite is the natural thing to assume, and a
-- header is the worst place to leave an assumption uncorrected. A turn that dies AT THE SEAM (timeout,
-- rate limit, process death, a malformed envelope) records NOTHING AT ALL. The response never returns,
-- so the calls that turn made exist only inside a parser owned by the seam that threw, and smuggling
-- them out through the exception would put audit plumbing into the failure taxonomy the whole app reads.
-- That is issue #15's one documented limitation rather than an oversight — but it means "a failed run
-- still leaves its trace" is an OVERCLAIM: what survives is specifically the generation that FINISHED
-- and could not be stored, never the generation that never finished.
--
-- WHY comment_id IS NULLABLE, AND WHY IT CASCADES. It is linked when the reply POSTED, and left NULL
-- otherwise — which, per the paragraph above, means the unsaveable turn: its trace is the one nobody has
-- to go looking for in a log. ON DELETE CASCADE because a deleted reply's trace has nothing left to
-- explain — the reply it described is gone, and an orphan row about invisible text is noise. CONTRAST
-- V21's ambient_run thread_id, which is ON DELETE SET NULL for the opposite reason: the SPEND happened
-- either way, so cost history must outlive the thread it opened. Spend survives; explanation does not.
-- Note that DatabaseResetHooks still wipes this table EXPLICITLY per house discipline, and here that is
-- load-bearing rather than merely tidy: the CASCADE only reaches comment-linked rows, so the
-- NULL-comment traces — the unsaveable turns — cascade from nothing and would survive into the next
-- scenario.
--
-- TRUNCATION CAPS ARE ENFORCED IN KOTLIN, NOT HERE. A tool input is a JSON blob a model wrote and an
-- output is whatever the tool printed — a bash run, a diff, a fetched page — all unbounded, and an audit
-- trail that stores megabytes per turn stops being an audit trail and becomes the largest table in the
-- file. A SQLite `CHECK (length(x) <= 4000)` would be decorative: it counts code points only up to the
-- first NUL (the V28 lesson), and the repository is the only door, so the constraint would restate the
-- writer rather than constrain it. The caps live in ToolSummaries (2000 input / 4000 output) and are
-- applied TWICE — at the parser, and defensively again at the repository, because a future second
-- writer will not have read this comment. Every clipped value ends in a marker INSIDE its budget, so
-- "was this truncated?" is answerable from the stored string alone.
--
-- WHY NUMERIC COLUMNS ARE LEGITIMATE HERE (the sixth restatement of the guardrail — V24 stated it for
-- relations, V25 for their audit trail, V26 for a watermark column, V27 called itself the fourth, V28
-- the fifth). The rule V27's CHECK enforces is about MEMBER-ATTACHED MAGNITUDES: a number hung on what
-- a member is, thinks or is into is comparable, therefore rankable, therefore optimisable, and the cut
-- reward economy is back wearing a new column name (direction doc §11.7 Stays-Cut). `seq`, `is_error`
-- and the two timestamps attach to a TOOL INVOCATION. They rank no persona, they enter no prompt, and
-- nothing above them selects a member by them — they answer "which call, in what order, did it fail,
-- how long did it take", which is operator accounting in exactly the class `ambient_run.cost_usd REAL`
-- established at V21. TO THE NEXT MIGRATION WRITER: that distinction is the whole rule, and it is
-- narrower than it looks. A column counting how often a MEMBER used tools, or how expensive a MEMBER's
-- replies are, would be the banned shape landing in the permitted table — do not add it here on the
-- strength of this paragraph.
CREATE TABLE generation_tool_call (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    -- The generation's id: the in-flight node id == the settled comment's id. No FK, deliberately.
    run_id         TEXT    NOT NULL,
    -- Linked when the reply POSTED; NULL keeps an unsaveable turn's trace. CASCADE: see the header.
    comment_id     TEXT    REFERENCES comment(id) ON DELETE CASCADE,
    -- 1-based call order within the generation, as observed. Ordering, not a magnitude.
    seq            INTEGER NOT NULL,
    tool_name      TEXT    NOT NULL,
    -- Compact JSON of the tool's input / the text of its result, clipped in Kotlin (see the header).
    input_summary  TEXT,
    output_summary TEXT,
    is_error       INTEGER NOT NULL DEFAULT 0,
    -- ISO-8601 from the injected Clock, like every other timestamp in this schema. started_at is
    -- backfilled by the repository when the parser saw none, so a time-window read never drops a row;
    -- ended_at stays NULL for a call whose result never arrived (the turn died mid-tool).
    started_at     TEXT,
    ended_at       TEXT
);
-- The two reads issue #16 will make: "what did this reply reach for" and the time-window aggregate.
CREATE INDEX idx_generation_tool_call_comment ON generation_tool_call(comment_id);
CREATE INDEX idx_generation_tool_call_started ON generation_tool_call(started_at);
