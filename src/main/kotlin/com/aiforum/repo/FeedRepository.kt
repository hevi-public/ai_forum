package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

/**
 * One thread's worth of front-page card (plan_docs/ambient-slice-6.md §2.2), read in a single row.
 *
 * [lastActivity] is the newest POSTED comment's instant, falling back to the thread's own creation, and
 * [excerptBody] / [excerptAuthor] / [excerptIsReply] describe that same comment — or, when the thread has
 * no settled reply at all, the thread's own opening post, which is what makes a fresh ambient article
 * thread's card show the article summary instead of an empty slot.
 *
 * [excerptIsReply] answers a question no other column can: "the excerpt came from a reply" is NOT the
 * same as "the excerpt's author differs from the thread's", because a persona replying to its own article
 * thread gives the same author for both. **It has no production consumer today** — `FeedCards` derives
 * the byline from a null [excerptAuthor] alone, which coincides with this flag for owner threads and
 * differs for a persona's reply-less one. Kept, and pinned at Tier 1, because it is the only expression
 * of §7's "don't name the same voice twice" rule; said plainly here rather than claimed as load-bearing,
 * because an unused field defended by a sentence about a caller that does not exist is the exact defect
 * class this repo polices (persona-memory §10.6).
 *
 * [authorId] and [excerptAuthor] are both nullable and both mean *owner* when null — `thread.author_id` is
 * NULL for every hand-created thread (V20), and a reply-less owner thread coalesces its excerpt author to
 * that same NULL. [lastActivity] stays a raw ISO-8601 [String] for the reason [Interest.updatedAt] does:
 * the column is TEXT under SQLite's dynamic typing, and the caller either formats it or compares it.
 */
data class FeedThread(
    val id: String,
    val title: String,
    val authorId: String?,
    val lastActivity: String,
    val excerptBody: String,
    val excerptAuthor: String?,
    val excerptIsReply: Boolean,
    val unreadCount: Int,
)

/**
 * One card in the activity stream (plan_docs/ambient-slice-6.md §2.2): either a settled comment or a
 * thread opening, distinguished by [isPost].
 *
 * For a comment, [id] is the comment's id and [threadId] its thread — the pair that builds a link *into*
 * the thread at that comment. For a thread opening the two are deliberately **the same value**, so the
 * link lands on the opening post's existing anchor rather than needing a second href shape.
 *
 * [authorId] is never null here even though `thread.author_id` is: the thread leg coalesces to `owner`, so
 * a stream card always has a voice to name. [unread] is always false on a post card — "N new" is about
 * replies the owner has not read (V2 `thread_read`), and a thread opening is not one of them (I5).
 */
data class ActivityEvent(
    val isPost: Boolean,
    val id: String,
    val threadId: String,
    val threadTitle: String,
    val authorId: String,
    val body: String,
    val createdAt: String,
    val unread: Boolean,
)

/**
 * The front page's two reads (plan_docs/ambient-slice-6.md §2.2), in one class so the thread-card view and
 * the activity view cannot drift in their state filter, their tie-break or their unread expression — the
 * [com.aiforum.web.RailFeeds] argument, one level down.
 *
 * **What this class replaces is as much the point as what it does.** The front page used to be
 * `threads.findAll().map { threadReads.unreadCount(it.id) }` — a 2N+1 over an unbounded `findAll()`. Both
 * views here are ONE query, which is what lets the front page's controller drop its
 * `ThreadReadRepository` dependency outright: with no per-row door injected, bringing the 2N+1 back costs
 * a visible constructor change rather than a line slipped into a `.map {}` (I4).
 *
 * No injected `Clock`: nothing here stamps a row, and both reads compare stored ISO-8601 text against
 * stored ISO-8601 text. "Now" belongs to the caller that renders "3 minutes ago".
 */
@Repository
class FeedRepository(private val jdbc: JdbcTemplate) {

    private val threadMapper = RowMapper { rs, _ ->
        FeedThread(
            id = rs.getString("id"),
            title = rs.getString("title"),
            authorId = rs.getString("author_id"),
            lastActivity = rs.getString("last_activity"),
            excerptBody = rs.getString("excerpt_body"),
            excerptAuthor = rs.getString("excerpt_author"),
            // SQLite hands back 1/0 for a boolean expression; read it as the integer it is rather than
            // trusting the driver's getBoolean coercion (the explicit-getter rule for a dynamically
            // typed column).
            excerptIsReply = rs.getInt("excerpt_is_reply") == 1,
            unreadCount = rs.getInt("unread_count"),
        )
    }

    private val eventMapper = RowMapper { rs, _ ->
        ActivityEvent(
            isPost = rs.getInt("is_post") == 1,
            id = rs.getString("id"),
            threadId = rs.getString("thread_id"),
            threadTitle = rs.getString("title"),
            authorId = rs.getString("author_id"),
            body = rs.getString("body"),
            createdAt = rs.getString("created_at"),
            unread = rs.getInt("unread") == 1,
        )
    }

    /**
     * Every thread as a card, most recently active first.
     *
     * **Uncapped, and it takes no limit parameter at all** (I10, D7). There is no `GET /threads` index
     * route in this app — only `/threads/{id}` and `/threads/{threadId}/room` — so a thread that fell off
     * a capped front page would be unreachable from anywhere in the UI. An absent parameter is a stronger
     * guarantee than a guard, and a much stronger one than a test.
     *
     * `COALESCE(n.created_at, t.created_at)` is exactly [ThreadRepository.findActive]'s
     * `COALESCE(MAX(CASE WHEN state='POSTED' …), t.created_at)` — `n` *is* that max, picked as a row so its
     * body and author come along — so the draft-only fallback is the same behaviour reached a second way,
     * not a second behaviour.
     *
     * `u.created_at > COALESCE(r.last_read_at, '')` is [ThreadReadRepository.unreadCount]'s two branches
     * collapsed into one expression: every ISO-8601 stamp sorts above the empty string, so an absent
     * marker counts everything, which is the never-read meaning V2 gave a missing row. The Tier-1
     * equivalence test asserts the two agree by calling the real method, both with and without a marker —
     * the coherence "N new means one thing in both views" rests on it (I5).
     *
     * The excerpt subquery picks the newest POSTED comment by `created_at DESC, id DESC`; the id tie-break
     * is what keeps the preview stable when a fixed clock (or a busy second) gives two comments one stamp.
     * The outer `ORDER BY last_activity DESC, t.id DESC` does the same job for the cards themselves.
     */
    fun feedThreads(): List<FeedThread> =
        jdbc.query(
            """SELECT t.id, t.title, t.author_id,
                      COALESCE(n.created_at, t.created_at) AS last_activity,
                      COALESCE(n.body,       t.body)       AS excerpt_body,
                      COALESCE(n.author_id,  t.author_id)  AS excerpt_author,
                      (n.id IS NOT NULL)                   AS excerpt_is_reply,
                      (SELECT COUNT(*) FROM comment u
                        WHERE u.thread_id = t.id AND u.state = 'POSTED'
                          AND u.created_at > COALESCE(r.last_read_at, '')) AS unread_count
                 FROM thread t
                 LEFT JOIN thread_read r ON r.thread_id = t.id
                 LEFT JOIN comment n ON n.id = (SELECT c.id FROM comment c
                                                 WHERE c.thread_id = t.id AND c.state = 'POSTED'
                                                 ORDER BY c.created_at DESC, c.id DESC LIMIT 1)
                ORDER BY last_activity DESC, t.id DESC""",
            threadMapper,
        )

    /**
     * The activity stream: every settled comment and every thread opening as its own card, newest first,
     * capped at [limit].
     *
     * **The comment leg is an INNER JOIN to `thread`**, so a deleted thread's comments can never surface —
     * the one row shape that would render a card linking into nothing.
     *
     * **There is deliberately no `WHERE t.author_id IS NOT NULL` on the thread leg.** Excluding owner posts
     * would make a brand-new forum with three owner threads and no settled replies render "nothing has
     * happened yet" with three threads one click away, and in steady state would show replies to a post it
     * refuses to show. The stream is author-agnostic, which is also why it is called Activity (D1).
     *
     * **The tie-break is load-bearing, not tidiness.** `is_post DESC, id DESC` after `created_at DESC` is
     * what makes the order a total one; the fixed test clock stamps a whole fixture with one instant, and
     * measured, the order differs without it. The Tier-1 total-order test is the pin, and it asserts
     * stability across two calls as well as the order itself.
     *
     * Post cards are flagged `0` for unread unconditionally — see [ActivityEvent.unread].
     */
    fun recentActivity(limit: Int): List<ActivityEvent> =
        jdbc.query(
            """SELECT 0 AS is_post, c.id, c.thread_id, t.title, c.author_id, c.body, c.created_at,
                      CASE WHEN c.created_at > COALESCE(r.last_read_at,'') THEN 1 ELSE 0 END AS unread
                 FROM comment c
                 JOIN thread t ON t.id = c.thread_id
                 LEFT JOIN thread_read r ON r.thread_id = c.thread_id
                WHERE c.state = 'POSTED'
               UNION ALL
               SELECT 1, t.id, t.id, t.title, COALESCE(t.author_id, 'owner'), t.body, t.created_at, 0
                 FROM thread t
                ORDER BY created_at DESC, is_post DESC, id DESC
                LIMIT ?""",
            eventMapper,
            limit,
        )
}
