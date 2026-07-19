package com.aiforum.repo

import com.aiforum.domain.Comment
import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReasoningLeak
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * JdbcTemplate persistence for the comment tree (see the sqlite-spring-jdbc skill — deliberately not
 * Hibernate). Branch context is read with the recursive-CTE ancestorPath; whole-thread context uses a
 * flat select.
 */
/** A recently-posted comment for the front-page rail; createdAt is the stored UTC ISO instant. */
data class RecentComment(
    val id: String,
    val threadId: String,
    val authorId: String,
    val body: String,
    val createdAt: String,
)

/** A starred POSTED comment with its thread title, for the starred rail box and the /starred page. */
data class StarredComment(
    val id: String,
    val threadId: String,
    val threadTitle: String,
    val authorId: String,
    val body: String,
    val createdAt: String,
)

/** One stored content revision of a comment (V14): its 0-based position, body, leak verdict, and — when
 *  this revision is an owner edit rather than a generated take — the edit timestamp (null otherwise). */
data class Revision(
    val idx: Int,
    val body: String,
    val reasoningLeak: ReasoningLeak?,
    val editedAt: Instant? = null,
)

@Repository
class CommentRepository(private val jdbc: JdbcTemplate, private val clock: Clock) {

    private val mapper = RowMapper { rs, _ ->
        Comment(
            id = rs.getString("id"),
            threadId = rs.getString("thread_id"),
            parentId = rs.getString("parent_id"),
            authorId = rs.getString("author_id"),
            body = rs.getString("body"),
            state = GenerationState.valueOf(rs.getString("state")),
            failureCategory = rs.getString("failure_category")?.let { FailureCategory.valueOf(it) },
            depth = rs.getInt("depth"),
            reason = rs.getString("reason"),
            retryAfterSeconds = rs.getObject("retry_after_seconds") as? Long
                ?: rs.getString("retry_after_seconds")?.toLongOrNull(),
            depthBudget = rs.getInt("depth_budget"),
            starred = rs.getInt("starred") != 0,
            reasoningLeak = rs.getString("reasoning_leak")?.let { ReasoningLeak.valueOf(it) },
            updatedAt = rs.getString("updated_at")?.let { Instant.parse(it) },
            revisionIndex = rs.getInt("revision_index"),
        )
    }

    private val revisionMapper = RowMapper { rs, _ ->
        Revision(
            idx = rs.getInt("idx"),
            body = rs.getString("body"),
            reasoningLeak = rs.getString("reasoning_leak")?.let { ReasoningLeak.valueOf(it) },
            editedAt = rs.getString("edited_at")?.let { Instant.parse(it) },
        )
    }

    fun insert(c: Comment) = insertAt(c, clock.instant())

    /**
     * Insert with an explicit `created_at`, instead of stamping "now". Lets a batch ingest (a PR's
     * discussion, plan_docs/github-pr-threads.md) preserve each comment's real timestamp so the nodes order
     * chronologically — distinct times even when inserted within one clock tick. [insert] is just this with
     * the current instant.
     */
    fun insertAt(c: Comment, createdAt: Instant) {
        jdbc.update(
            """INSERT INTO comment(id, thread_id, parent_id, author_id, body, state, failure_category,
                                   reason, retry_after_seconds, depth, depth_budget, starred,
                                   reasoning_leak, created_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            c.id, c.threadId, c.parentId, c.authorId, c.body, c.state.name, c.failureCategory?.name,
            c.reason, c.retryAfterSeconds, c.depth, c.depthBudget, if (c.starred) 1 else 0,
            c.reasoningLeak?.name, createdAt.toString(),
        )
    }

    // Note: update() deliberately leaves `starred` (and depth_budget) alone, so a generation settle
    // (DRAFTING → POSTED) never clears a star the owner set. The star is toggled only via toggleStar.
    fun update(c: Comment) {
        // reasoning_leak tracks the body, so it's refreshed here (a retry regenerates the body and can
        // clear or re-raise the flag) — unlike starred/depth_budget, which a settle deliberately leaves.
        jdbc.update(
            """UPDATE comment SET body=?, state=?, failure_category=?, reason=?, retry_after_seconds=?,
                                  reasoning_leak=?
               WHERE id=?""",
            c.body, c.state.name, c.failureCategory?.name, c.reason, c.retryAfterSeconds,
            c.reasoningLeak?.name, c.id,
        )
    }

    /**
     * Edit a comment's body (§7) — the owner revising their own note or correcting an AI persona's
     * reply. Stamps updated_at so the node renders the "(edited)" marker; deliberately separate from
     * [update] (the generation lifecycle) so a settle/retry never looks like an owner edit. Returns true
     * if a row was updated. The corrected body flows into future generation context automatically: branch
     * context is reassembled from the stored bodies at summon time.
     */
    /**
     * Edit a comment body (§7) as a NEW REVISION (V14), not an in-place overwrite — so the owner's
     * correction is kept in the version history beside the generated take(s) and can be stepped back to.
     * The new revision is flagged as an edit (edited_at set), which [selectRevision] copies into
     * `comment.updated_at`, so the "(edited)" marker tracks whichever version is currently shown. The
     * FIRST edit/regenerate also seeds idx 0 with the body being replaced (carrying its original
     * edited-ness), so nothing is lost. Returns false for an unknown id (nothing to edit).
     *
     * @Transactional: the seed-idx0 + addRevision + selectRevision are one atomic unit — a crash or
     * SQLITE_BUSY mid-way must not leave a half-seeded revision history. Called through the Spring proxy
     * (external callers); the internal self-calls to addRevision/selectRevision run inside this tx.
     */
    @Transactional
    fun editBody(id: String, body: String): Boolean {
        val existing = findById(id) ?: return false
        val count = revisionCount(id)
        if (count == 0) addRevision(id, 0, existing.body, existing.reasoningLeak, editedAt = existing.updatedAt)
        val newIdx = if (count == 0) 1 else count
        // An owner edit is the owner's own words: no model reasoning leak, and edited_at stamps it as an edit.
        addRevision(id, newIdx, body, reasoningLeak = null, editedAt = clock.instant())
        return selectRevision(id, newIdx)
    }

    /**
     * Append a content revision (V14) — a regenerated take or an owner edit of a reply. Revisions are
     * stored append-only and never overwrite the live body; [selectRevision] is what swaps a stored take
     * into `comment.body` for display. [editedAt] is non-null only for an owner edit (it drives the
     * "(edited)" marker via [selectRevision]); a generated take leaves it null. The FIRST regenerate/edit
     * also stores idx 0 (the body being replaced) so the original take is kept.
     */
    fun addRevision(commentId: String, idx: Int, body: String, reasoningLeak: ReasoningLeak?, editedAt: Instant? = null) {
        jdbc.update(
            "INSERT INTO comment_revision(comment_id, idx, body, reasoning_leak, edited_at, created_at) VALUES (?,?,?,?,?,?)",
            commentId, idx, body, reasoningLeak?.name, editedAt?.toString(), clock.instant().toString(),
        )
    }

    /** How many revisions are stored for [commentId]. 0 means "never regenerated" — an implicit 1-of-1. */
    fun revisionCount(commentId: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM comment_revision WHERE comment_id = ?", Int::class.java, commentId) ?: 0

    /** Revision counts for every regenerated comment in [threadId], so the assembler can label nodes in
     *  one read instead of per-node. Absent comments (no rows) are an implicit 1-of-1. */
    fun revisionCountsByComment(threadId: String): Map<String, Int> =
        jdbc.query(
            """SELECT r.comment_id AS cid, COUNT(*) AS n
               FROM comment_revision r JOIN comment c ON c.id = r.comment_id
               WHERE c.thread_id = ? GROUP BY r.comment_id""",
            { rs, _ -> rs.getString("cid") to rs.getInt("n") }, threadId,
        ).toMap()

    /**
     * Show revision [idx] of [commentId]: copy that stored take's body + leak into the live `comment` row
     * and point `revision_index` at it. This is the navigation primitive behind the ‹ › switcher — the
     * body the rest of the app reads (context, rail, markdown) follows the selected revision. Returns
     * false if no such revision exists (an out-of-range index), leaving the comment untouched.
     */
    fun selectRevision(commentId: String, idx: Int): Boolean {
        val rev = jdbc.query(
            "SELECT idx, body, reasoning_leak, edited_at FROM comment_revision WHERE comment_id = ? AND idx = ?",
            revisionMapper, commentId, idx,
        ).firstOrNull() ?: return false
        // updated_at follows the selected revision's edit flag: set on an owner edit, cleared on a generated
        // take — so the "(edited)" marker reflects whichever version is on screen.
        return jdbc.update(
            "UPDATE comment SET body=?, reasoning_leak=?, updated_at=?, revision_index=? WHERE id=?",
            rev.body, rev.reasoningLeak?.name, rev.editedAt?.toString(), idx, commentId,
        ) > 0
    }

    /**
     * Flip a comment's star and return the new state. Toggled atomically in SQL (CASE) so concurrent
     * clicks can't read-modify-write a stale value. Returns false for an unknown id (nothing updated).
     */
    fun toggleStar(id: String): Boolean {
        val changed = jdbc.update(
            "UPDATE comment SET starred = CASE WHEN starred = 0 THEN 1 ELSE 0 END WHERE id = ?", id,
        )
        if (changed == 0) return false
        return jdbc.queryForObject("SELECT starred FROM comment WHERE id = ?", Int::class.java, id) != 0
    }

    fun findById(id: String): Comment? =
        jdbc.query("SELECT * FROM comment WHERE id = ?", mapper, id).firstOrNull()

    fun threadComments(threadId: String): List<Comment> =
        jdbc.query("SELECT * FROM comment WHERE thread_id = ? ORDER BY depth, created_at", mapper, threadId)

    /**
     * The distinct author ids that already have a POSTED comment in [threadId] (plan_docs/ambient-slice-2.md
     * §5 step 4, exclusion rule b): the ambient comment action rules these personas out so no one comments
     * twice in the same thread. Only POSTED counts — a persona whose earlier draft failed/cancelled is free
     * to try again. Owner/system authors appear here too, harmlessly (the roster it filters holds persona ids).
     */
    fun postedAuthors(threadId: String): Set<String> =
        jdbc.query(
            "SELECT DISTINCT author_id FROM comment WHERE thread_id = ? AND state = 'POSTED'",
            { rs, _ -> rs.getString("author_id") }, threadId,
        ).toSet()

    /**
     * The newest POSTED comments across all threads, for the front-page "Recent comments" rail. Drafts,
     * failures and cancelled nodes are excluded (only settled, visible replies). created_at is a UTC
     * ISO instant ('…Z'), so ORDER BY on the text column sorts chronologically.
     */
    fun recentPosted(limit: Int): List<RecentComment> =
        jdbc.query(
            """SELECT id, thread_id, author_id, body, created_at FROM comment
               WHERE state = 'POSTED' ORDER BY created_at DESC LIMIT ?""",
            { rs, _ ->
                RecentComment(
                    rs.getString("id"), rs.getString("thread_id"), rs.getString("author_id"),
                    rs.getString("body"), rs.getString("created_at"),
                )
            },
            limit,
        )

    /** Direct children of a node (its replies' siblings) — null parent = the thread's top-level nodes. */
    fun childrenOf(parentId: String?): List<Comment> =
        if (parentId == null)
            jdbc.query("SELECT * FROM comment WHERE parent_id IS NULL ORDER BY depth, created_at", mapper)
        else
            jdbc.query("SELECT * FROM comment WHERE parent_id = ? ORDER BY depth, created_at", mapper, parentId)

    /** Root → node ancestor path (branch-only scope) via recursive CTE. The `lvl` counter bounds the
     *  recursion (< 10000, far above any real thread depth) so a corrupt parent_id cycle terminates
     *  instead of looping forever — see the T1.3 cycle/depth guard. */
    fun ancestorPath(nodeId: String): List<Comment> =
        jdbc.query(
            """WITH RECURSIVE ancestors(id, lvl) AS (
                   SELECT id, 0 FROM comment WHERE id = ?
                   UNION ALL
                   SELECT c.parent_id, a.lvl + 1 FROM comment c JOIN ancestors a ON c.id = a.id
                   WHERE c.parent_id IS NOT NULL AND a.lvl < 10000
               )
               SELECT cm.* FROM comment cm JOIN ancestors an ON cm.id = an.id ORDER BY cm.depth""",
            mapper, nodeId,
        )

    /**
     * The autonomous-growth frontier (§4): POSTED leaf nodes that still have depth budget. A node can
     * sprout an auto-reply only if it has no children yet and budget > 0, so exhausted branches and
     * non-leaf nodes are excluded. FAILED/DRAFTING nodes are never grown under.
     *
     * [withinSubtreeOf] (plan_docs/ambient-slice-2.md §2, the branch-scoped ambient growth) narrows the
     * frontier to the SUBTREE rooted at that comment id — the node itself plus all its descendants, the
     * same recursive-CTE walk as [descendantCount]/[subtreeIdsDeepestFirst] (lvl-bounded against parent_id
     * cycles, T1.3). Null (the owner's thread-wide /auto-grow) keeps the whole-thread frontier unchanged.
     */
    fun growableLeaves(threadId: String, withinSubtreeOf: String? = null): List<Comment> =
        if (withinSubtreeOf == null)
            jdbc.query(
                """SELECT * FROM comment c
                   WHERE c.thread_id = ?
                     AND c.state = 'POSTED'
                     AND c.depth_budget > 0
                     AND NOT EXISTS (SELECT 1 FROM comment k WHERE k.parent_id = c.id)
                   ORDER BY c.depth, c.created_at""",
                mapper, threadId,
            )
        else
            jdbc.query(
                """WITH RECURSIVE sub(id, lvl) AS (
                       SELECT id, 0 FROM comment WHERE id = ?
                       UNION ALL
                       SELECT c.id, s.lvl + 1 FROM comment c JOIN sub s ON c.parent_id = s.id WHERE s.lvl < 10000
                   )
                   SELECT c.* FROM comment c JOIN sub ON c.id = sub.id
                   WHERE c.thread_id = ?
                     AND c.state = 'POSTED'
                     AND c.depth_budget > 0
                     AND NOT EXISTS (SELECT 1 FROM comment k WHERE k.parent_id = c.id)
                   ORDER BY c.depth, c.created_at""",
                mapper, withinSubtreeOf, threadId,
            )

    /** Number of descendants under [nodeId] (excluding the node itself) via recursive CTE. The `lvl`
     *  counter bounds the recursion (< 10000) so a corrupt parent_id cycle terminates — see T1.3. */
    fun descendantCount(nodeId: String): Int =
        jdbc.queryForObject(
            """WITH RECURSIVE sub(id, lvl) AS (
                   SELECT id, 0 FROM comment WHERE id = ?
                   UNION ALL
                   SELECT c.id, s.lvl + 1 FROM comment c JOIN sub s ON c.parent_id = s.id WHERE s.lvl < 10000
               )
               SELECT COUNT(*) - 1 FROM sub""",
            Int::class.java, nodeId,
        ) ?: 0

    /**
     * Starred POSTED comments joined with their thread title, newest first. Pass a limit for the
     * rail box; null for the /starred page (all of them).  The JOIN is safe here: every comment
     * must have a thread_id that references an existing thread row.
     */
    fun starredPosted(limit: Int): List<StarredComment> = starredQuery(limit)

    fun allStarredPosted(): List<StarredComment> = starredQuery(null)

    private fun starredQuery(limit: Int?): List<StarredComment> {
        val sql = buildString {
            append(
                """SELECT c.id, c.thread_id, t.title AS thread_title, c.author_id, c.body, c.created_at
                   FROM comment c JOIN thread t ON c.thread_id = t.id
                   WHERE c.starred = 1 AND c.state = 'POSTED'
                   ORDER BY c.created_at DESC""",
            )
            if (limit != null) append(" LIMIT $limit")
        }
        return jdbc.query(sql) { rs, _ ->
            StarredComment(
                rs.getString("id"), rs.getString("thread_id"), rs.getString("thread_title"),
                rs.getString("author_id"), rs.getString("body"), rs.getString("created_at"),
            )
        }
    }

    /**
     * Delete [nodeId] and its entire subtree (§8) — a deleted parent takes its replies with it, so no
     * child is ever left pointing at a gone parent. foreign_keys=on (every profile, see the datasource
     * URLs) means dependents must go first: `vote.node_id` and `comment.parent_id` both reference
     * `comment(id)`, so we clear the subtree's votes, then delete the comments deepest-first (a child is
     * always removed before its parent). Returns the ids removed; empty if [nodeId] doesn't exist.
     *
     * @Transactional: the 3 batch DELETEs + the per-id comment DELETE loop are one atomic unit — a crash
     * or SQLITE_BUSY mid-loop must not leave votes/revisions/attachments orphaned or a subtree half-cut.
     */
    @Transactional
    fun deleteSubtree(nodeId: String): List<String> {
        val ids = subtreeIdsDeepestFirst(nodeId)
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        jdbc.update("DELETE FROM vote WHERE node_id IN ($placeholders)", *ids.toTypedArray())
        // Revisions reference comment(id) too (foreign_keys=on) — clear them before the comments they hang off.
        jdbc.update("DELETE FROM comment_revision WHERE comment_id IN ($placeholders)", *ids.toTypedArray())
        // Attachments reference comment(id) (foreign_keys=on), so they must go before the comments —
        // the blob on disk is content-addressed and left for a future dedup-aware GC.
        jdbc.update("DELETE FROM attachment WHERE comment_id IN ($placeholders)", *ids.toTypedArray())
        // Deepest-first so the self-referential parent_id FK is never momentarily violated.
        ids.forEach { jdbc.update("DELETE FROM comment WHERE id = ?", it) }
        return ids
    }

    /**
     * Delete every comment in [threadId] (and their votes) — used when a whole thread is removed. Same
     * FK ordering as [deleteSubtree]: votes first (`vote.node_id` references `comment`), then comments
     * deepest-first so a child is always gone before the parent it points at (`comment.parent_id`).
     * Returns the ids removed; empty if the thread has no comments.
     *
     * @Transactional: same shape as [deleteSubtree] — the votes/revisions/attachments DELETEs plus the
     * per-id comment DELETE loop are one atomic unit, so a mid-loop failure never half-removes the thread.
     */
    @Transactional
    fun deleteByThread(threadId: String): List<String> {
        val ids = jdbc.query(
            "SELECT id FROM comment WHERE thread_id = ? ORDER BY depth DESC",
            { rs, _ -> rs.getString("id") }, threadId,
        )
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        jdbc.update("DELETE FROM vote WHERE node_id IN ($placeholders)", *ids.toTypedArray())
        jdbc.update("DELETE FROM comment_revision WHERE comment_id IN ($placeholders)", *ids.toTypedArray())
        // Comment-scoped attachments reference comment(id), so clear them before the comments.
        jdbc.update("DELETE FROM attachment WHERE comment_id IN ($placeholders)", *ids.toTypedArray())
        ids.forEach { jdbc.update("DELETE FROM comment WHERE id = ?", it) }
        return ids
    }

    /** Ids of [nodeId]'s subtree (itself + all descendants) ordered deepest depth first, for FK-safe
     *  delete. The `lvl` recursion counter (distinct from the stored `depth`) bounds the walk (< 10000)
     *  so a corrupt parent_id cycle terminates instead of looping forever — see T1.3. */
    private fun subtreeIdsDeepestFirst(nodeId: String): List<String> =
        jdbc.query(
            """WITH RECURSIVE sub(id, depth, lvl) AS (
                   SELECT id, depth, 0 FROM comment WHERE id = ?
                   UNION ALL
                   SELECT c.id, c.depth, s.lvl + 1 FROM comment c JOIN sub s ON c.parent_id = s.id
                   WHERE s.lvl < 10000
               )
               SELECT id FROM sub ORDER BY depth DESC""",
            { rs, _ -> rs.getString("id") }, nodeId,
        )
}
