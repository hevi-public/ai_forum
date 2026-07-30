-- V29__front_page_feed.sql
--
-- S6 (plan_docs/ambient-slice-6.md): the front page stops being one list and becomes two views over
-- the same forum — activity-sorted thread cards and a reverse-chronological activity stream — with the
-- owner's choice between them persisted. This migration ships that persistence plus the two read
-- indexes the feed's queries want; the queries themselves live in FeedRepository.
--
-- WHY A COLUMN PER SETTING AND NOT A KEY/VALUE BAG. A `(key, value)` table structurally cannot CHECK a
-- value against its key — one CHECK would have to admit every legal value of every setting at once — so
-- the enum guardrail below would evaporate the moment a second preference landed, which is exactly when
-- nobody would be looking at it. A future setting here is a nullable ADD COLUMN, which is the one shape
-- SQLite's ALTER TABLE reliably supports.
--
-- WHY "PER OWNER" IS ONE GLOBAL ROW. Single-user PoC, no auth by design (CLAUDE.md), so there is no
-- user id to key on and inventing one would be a column holding the same value forever. `CHECK (id = 1)`
-- makes "two preferences" unrepresentable rather than merely never-written — the `thread_read` posture
-- (V2), keyed by the thing rather than by a user.
--
-- WHY BOTH CHECKS SHIP AT BIRTH. Not because a CHECK cannot be added later — that blunt claim sat in
-- V28's header and in the sqlite skill, and the V28 review close-out proved it FALSE (`ADD COLUMN c TEXT
-- CHECK (…)` is accepted and enforced, cross-column predicates included, and on the SQLite we actually
-- ship, xerial 3.53.2, even table-level `ALTER TABLE … ADD CHECK` works). It is stated correctly here
-- because a migration is immutable once applied, so a false comment written today is unfixable tomorrow.
-- The real argument is that a CHECK is FREE at birth and CONDITIONAL afterwards: the retrofit validates
-- the whole table and aborts on the first violator, so it is only addable while the data already happens
-- to obey it — the one thing a live file cannot promise — and the table-level form is newer-SQLite
-- syntax that the system 3.51 rejects outright. "We'll add the CHECK when the feature lands" is a hope,
-- not a plan. They are also the LAST of three layers rather than the only one: `OwnerPrefRepository.setFeedView` takes the
-- `FeedView` enum, so nothing above the DB has a String door; `FeedView.of` answers null for a slug that
-- names no view, which is what lets the endpoint refuse it with a 400; and this DDL refuses it below both.
-- The Tier-1 test writes RAW SQL at this table precisely so the bottom layer is proven standing on its
-- own rather than shadowed by the enum above it.
--
-- NO SEED ROW — ABSENCE IS THE DEFAULT. An empty table means `FeedView.DEFAULT` (threads), which is what
-- lets every pre-S6 front-page scenario keep its Gherkin untouched, and what makes the acceptance reset
-- hook's `DELETE FROM owner_pref` restore the default instead of some stored value.
CREATE TABLE owner_pref (
    id         INTEGER PRIMARY KEY CHECK (id = 1),
    feed_view  TEXT    NOT NULL CHECK (feed_view IN ('threads','activity')),
    updated_at TEXT    NOT NULL                  -- injected Clock, ISO-8601 (house repository pattern)
);

-- The two indexes the feed reads want. Measured against a scratch DB built from all 28 prior migrations
-- (plan doc §2.3), not reasoned about — and neither is served by what already exists: V17's
-- `idx_comment_thread_order` is `(thread_id, depth, created_at)`, and `depth` sits between the equality
-- column and the sort column, so the planner cannot walk it in created_at order for one thread; its
-- leading `thread_id` makes it useless to a forum-wide read besides.
--
-- Both are PARTIAL on `state = 'POSTED'`, which is not decoration: every feed read carries that exact
-- predicate (the excerpt subquery, the unread COUNT(*), the stream's comment leg), and a partial index
-- is only usable when the query's WHERE contains its condition. Unsettled drafts are also the rows most
-- likely to churn, and they stay out of both indexes entirely.
--
-- idx_comment_thread_posted answers the per-thread halves of feedThreads() — the newest-POSTED-comment
-- excerpt subquery and the unread count — as a range scan over just that thread's POSTED comments,
-- rather than a walk of every comment in the thread applying the state filter row by row.
CREATE INDEX idx_comment_thread_posted ON comment(thread_id, created_at DESC) WHERE state = 'POSTED';
-- idx_comment_posted_recent answers the activity stream's comment leg (forum-wide, newest first) and
-- also the PRE-EXISTING CommentRepository.recentPosted behind the recent-comments rail box, which runs
-- on every home AND every thread page load — so this index pays on pages this slice never touches.
CREATE INDEX idx_comment_posted_recent ON comment(created_at DESC) WHERE state = 'POSTED';
