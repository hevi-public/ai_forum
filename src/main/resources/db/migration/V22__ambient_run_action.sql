-- The ambient loop's second action (plan_docs/ambient-slice-2.md §5): a tick now either opens an article
-- thread ('post', the S1 action) or drops a persona comment into a live thread ('comment'). Record WHICH
-- action a run dispatched so the /admin/ambient drill-down can label it (data-action).
--
-- DEFAULT 'post' backfills the S1 rows correctly (they were all article posts) and lets the append-only
-- record() keep working without every insert naming the column. A comment run reuses thread_id/persona_id
-- (article_* stay NULL); there is no comment_id column — the comment settles asynchronously AFTER the run
-- row is written (the summon dispatch is what's recorded), so the drill-down links to the thread, not the
-- as-yet-unsettled comment.
ALTER TABLE ambient_run ADD COLUMN action TEXT NOT NULL DEFAULT 'post';
