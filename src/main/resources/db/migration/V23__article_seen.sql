-- The ambient feed source's dedupe registry (plan_docs/ambient-slice-5.md §2 "Dedupe"): every article
-- URL FeedArticleSource has ever yielded, so a later tick against unchanged feed content never re-posts
-- the same article. A brand-new standalone table — no foreign keys (a feed URL is not a forum entity),
-- nothing to retrofit. `url` is the natural PRIMARY KEY (the dedupe identity, so INSERT OR IGNORE makes a
-- re-record idempotent); `first_seen` is the injected-Clock ISO-8601 stamp of when it was first recorded,
-- kept for a future retention/prune pass (read by nothing yet, the ambient_run.cost_usd spirit).
CREATE TABLE article_seen (
    url        TEXT PRIMARY KEY,
    first_seen TEXT NOT NULL              -- injected Clock, ISO-8601
);
