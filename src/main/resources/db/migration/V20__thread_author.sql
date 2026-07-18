-- Attribution for a thread's opening post (plan_docs/ambient-slice-1.md).
--
-- author_id is a PLAIN attribution string, NOT a foreign key — deliberately matching comment.author_id
-- (V1 schema): the ambient loop authors threads AS a persona, but a persona-authored thread must keep its
-- byline after the persona is deleted, and a persona delete must stay a clean single-row delete with no
-- cascade. A foreign key would force one or the other; the plain string keeps both (the comment.author_id
-- precedent, spelled out in PersonaRepository.delete). NULL = owner-authored — which every existing thread
-- is, automatically: a nullable ADD COLUMN backfills existing rows to NULL, so no data migration is needed.
ALTER TABLE thread ADD COLUMN author_id TEXT;
CREATE INDEX idx_thread_author ON thread(author_id);
