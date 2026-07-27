package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant

@Repository
class ThreadRepository(private val jdbc: JdbcTemplate, private val clock: Clock) {

    // body is the opening post's content (§2, V7) — may be blank for title-only / legacy threads.
    // updatedAt is when the owner last edited the OP (title/body), or null if never (V11) — drives the
    // "(edited)" marker on the post, same as a comment's.
    // authorId is the OP's attribution string (V20): a persona id when the ambient loop authored the
    // thread, NULL when the owner did (every hand-created / legacy thread). Plain string, not an FK — the
    // comment.author_id precedent, so a byline survives its persona's deletion.
    data class Thread(val id: String, val title: String, val body: String, val updatedAt: Instant? = null, val authorId: String? = null) {
        val edited: Boolean get() = updatedAt != null
    }

    // A thread ranked by recent activity for the front-page rail; lastActivity is the ISO instant of
    // the newest POSTED comment, falling back to the thread's own creation when it has no replies yet.
    data class ActiveThread(val id: String, val title: String, val lastActivity: String)

    // Owner-authored create (author_id NULL). Kept as its own 3-arg overload — NOT a 4-arg with a default —
    // so every existing caller AND its test subclass (which overrides this exact signature) stay unchanged;
    // a defaulted 4th param would leave no 3-arg signature to override.
    fun insert(id: String, title: String, body: String) = insert(id, title, body, null)

    // authorId is the OP attribution (V20): the ambient loop passes the authoring persona's id here.
    fun insert(id: String, title: String, body: String, authorId: String?) {
        jdbc.update(
            "INSERT INTO thread(id, title, body, author_id, created_at) VALUES (?,?,?,?,?)",
            id, title, body, authorId, clock.instant().toString(),
        )
    }

    /**
     * Remove the thread row itself (§8). Dependents must already be gone: `comment.thread_id` and
     * `thread_read.thread_id` both reference `thread(id)` with foreign_keys=on, so callers clear the
     * comments ([CommentRepository.deleteByThread]) and read marker ([ThreadReadRepository.delete]) first.
     */
    fun delete(id: String) {
        // Opening-post images reference thread(id) (foreign_keys=on), so clear them before the row. The
        // caller already removed the comments (and their attachments); the content-addressed blobs on disk
        // are left for a future dedup-aware GC.
        jdbc.update("DELETE FROM attachment WHERE thread_id = ?", id)
        // A GitHub-PR thread carries a github_pr_thread mapping row that also references thread(id); clear it
        // too (no-op for ordinary threads) so the delete doesn't trip the foreign key.
        jdbc.update("DELETE FROM github_pr_thread WHERE thread_id = ?", id)
        jdbc.update("DELETE FROM thread WHERE id = ?", id)
    }

    /**
     * Edit the opening post (§7): the owner revises the thread title and/or body. Stamps updated_at so
     * the post renders the "(edited)" marker. Returns true if a row was updated. created_at is untouched
     * (the OP keeps its place in the activity ranking).
     */
    fun updateOp(id: String, title: String, body: String): Boolean =
        jdbc.update(
            "UPDATE thread SET title=?, body=?, updated_at=? WHERE id=?",
            title, body, clock.instant().toString(), id,
        ) > 0

    fun find(id: String): Thread? =
        jdbc.query("SELECT id, title, body, updated_at, author_id FROM thread WHERE id = ?", ::mapThread, id).firstOrNull()

    /**
     * How many threads the forum holds — the left rail's "~/forum" count, which both front-page views
     * need. Its own query rather than `feedThreads().size`: the activity view renders no thread cards at
     * all, so counting them would mean issuing the thread-card read the stream exists without (S6 §2.1).
     *
     * This replaces `findAll()`, whose only caller was the front page's 2N+1.
     */
    fun count(): Int = jdbc.queryForObject("SELECT COUNT(*) FROM thread", Int::class.java) ?: 0

    /**
     * Threads most recently active first, capped at [limit]. Activity = newest POSTED comment, or the
     * thread's own creation if it has none. created_at is stored as a UTC ISO instant ('…Z'), so
     * MAX()/ORDER BY on the text column sorts chronologically.
     */
    fun findActive(limit: Int): List<ActiveThread> =
        jdbc.query(
            """SELECT t.id, t.title,
                      COALESCE(MAX(CASE WHEN c.state = 'POSTED' THEN c.created_at END), t.created_at) AS last_activity
                 FROM thread t
                 LEFT JOIN comment c ON c.thread_id = t.id
                GROUP BY t.id, t.title, t.created_at
                ORDER BY last_activity DESC
                LIMIT ?""",
            { rs, _ -> ActiveThread(rs.getString("id"), rs.getString("title"), rs.getString("last_activity")) },
            limit,
        )

    private fun mapThread(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) =
        Thread(
            rs.getString("id"), rs.getString("title"), rs.getString("body"),
            rs.getString("updated_at")?.let { Instant.parse(it) },
            rs.getString("author_id"),
        )
}
