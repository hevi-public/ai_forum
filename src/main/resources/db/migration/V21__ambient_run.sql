-- The ambient loop's append-only run log (plan_docs/ambient-slice-1.md §Migrations), modelled on
-- routing_event (V15): every tick — posted, no-op, or failed — lands exactly one row, surfaced on the
-- admin drill-down at /admin/ambient.
--
-- thread_id is a FOREIGN KEY with ON DELETE SET NULL, declared HERE in CREATE TABLE so it is fully
-- enforced (SQLite cannot add a foreign key via a later ALTER, so retrofitting one is impossible). The
-- run/cost history therefore OUTLIVES the thread it opened — the spend happened either way — and
-- ThreadRepository.delete needs NO new clear line for ambient_run (the SET NULL does it). persona_id stays
-- a plain attribution string (comment.author_id / thread.author_id spirit), so a persona delete stays a
-- single-row delete. cost_usd is NULL until per-run cost capture lands (that needs an LlmClient contract
-- change to surface total_cost_usd — its own slice).
CREATE TABLE ambient_run (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    tick_time     TEXT NOT NULL,              -- injected Clock, ISO-8601
    source        TEXT NOT NULL,              -- 'manual' | 'scheduled'  (not "trigger": SQLite keyword)
    outcome       TEXT NOT NULL,              -- 'posted' | 'no-op' | 'failed'
    detail        TEXT,                       -- skip reason / failure message
    article_title TEXT,
    article_url   TEXT,
    persona_id    TEXT,                       -- attribution string, comment.author_id spirit
    thread_id     TEXT REFERENCES thread(id) ON DELETE SET NULL,  -- run history survives thread deletion
    cost_usd      REAL                        -- NULL until per-run cost capture lands
);
CREATE INDEX idx_ambient_run_tick_time ON ambient_run(tick_time);
