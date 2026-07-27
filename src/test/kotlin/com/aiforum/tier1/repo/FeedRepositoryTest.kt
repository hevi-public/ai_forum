package com.aiforum.tier1.repo

import com.aiforum.acceptance.support.TestData
import com.aiforum.repo.FeedRepository
import com.aiforum.repo.FeedThread
import com.aiforum.repo.ThreadReadRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Clock

/**
 * Tier-1: [FeedRepository] against the real test SQLite DB (plan_docs/ambient-slice-6.md §2.2). These two
 * queries are the whole front page — one per view — so what they have to be pinned on is not "do they
 * return rows" but the four properties the design rests on:
 *
 * - **Activity order beats creation order**, and the excerpt is the newest SETTLED reply. Both collapse
 *   [com.aiforum.repo.ThreadRepository.findActive]'s `MAX(POSTED)`-else-creation fallback into a picked
 *   row, so the reply-less thread previewing its own opening post is the same behaviour, not a second one.
 * - **Unread means one thing in both views** (I5). The grouped expression here and
 *   [ThreadReadRepository.unreadCount] must agree with and without a read marker, so the equivalence test
 *   calls the REAL method rather than re-deriving its SQL — a second copy of the query would agree with
 *   itself while both were wrong.
 * - **The stream's order is total.** The test clock is fixed and a whole fixture can share one instant, so
 *   `created_at DESC` alone leaves the order to whatever the planner feels like; `is_post DESC, id DESC` is
 *   what makes two calls return the same page.
 * - **The stream cannot show what is not there.** Unsettled comments and a deleted thread's orphans are
 *   both filtered in SQL, one by state and one by the comment leg's INNER JOIN.
 *
 * Every fixture that asserts an order seeds explicit ages via `TestData`'s `agoSeconds` (§8): the clock is
 * fixed, so rows seeded without one share an instant and an "ordering" assertion would really be reading
 * an arbitrary UUID tie-break. The one test that WANTS that collision says so in its name.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class FeedRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var data: TestData
    @Autowired lateinit var feed: FeedRepository
    @Autowired lateinit var threadReads: ThreadReadRepository
    @Autowired lateinit var clock: Clock

    @BeforeEach @AfterEach
    fun clean() {
        listOf("vote", "event_log", "comment", "thread_read", "thread", "persona").forEach { jdbc.update("DELETE FROM $it") }
    }

    /** The stamp a row seeded [agoSeconds] ago carries — the expected-value side of every age assertion. */
    private fun stamp(agoSeconds: Long): String = clock.instant().minusSeconds(agoSeconds).toString()

    /** The owner's read marker, planted at a chosen age. Written directly rather than through
     *  [ThreadReadRepository.markRead], which stamps the fixed clock's *now* — leaving no way to seed a
     *  reply on the unread side of it. */
    private fun markReadAgo(threadId: String, agoSeconds: Long) {
        jdbc.update(
            "INSERT INTO thread_read(thread_id, last_read_at) VALUES (?,?)",
            threadId, stamp(agoSeconds),
        )
    }

    private fun cardFor(threadId: String): FeedThread = feed.feedThreads().single { it.id == threadId }

    @Test
    fun `thread cards are ordered by last activity, not by creation`() {
        // Both directions in one fixture: the older thread must come FIRST because of its reply, and the
        // newer one must come second despite being newer. Ordered by t.created_at this is exactly reversed.
        val older = data.insertThread("io_uring vs epoll", agoSeconds = 3600)
        val newer = data.insertThread("Rust in the kernel", agoSeconds = 600)
        data.insertComment(older, "sol", "measure first", agoSeconds = 60)

        assertEquals(listOf("io_uring vs epoll", "Rust in the kernel"), feed.feedThreads().map { it.title })
        assertEquals(stamp(60), cardFor(older).lastActivity, "the reply, not the thread's own creation")
        assertEquals(stamp(600), cardFor(newer).lastActivity, "no replies — the thread's own creation")
    }

    @Test
    fun `a thread whose only reply is unsettled falls back to its own creation and its own opening post`() {
        val id = data.insertThread("has a draft", body = "The article, summarised", agoSeconds = 3600)
        data.insertComment(id, "sol", "thinking…", state = "DRAFTING", agoSeconds = 10)

        val card = cardFor(id)
        assertEquals(stamp(3600), card.lastActivity, "a draft is not activity")
        assertEquals("The article, summarised", card.excerptBody)
        assertFalse(card.excerptIsReply, "the preview came from the thread itself")
    }

    @Test
    fun `every settled reply is unread when the thread has never been read`() {
        val id = data.insertThread("Unread from birth", agoSeconds = 3600)
        data.insertComment(id, "sol", "one", agoSeconds = 300)
        data.insertComment(id, "vex", "two", agoSeconds = 200)

        assertEquals(2, cardFor(id).unreadCount)
    }

    @Test
    fun `only replies newer than the read marker are unread`() {
        val id = data.insertThread("Partly read", agoSeconds = 3600)
        data.insertComment(id, "sol", "before the marker", agoSeconds = 300)
        data.insertComment(id, "vex", "after the marker", agoSeconds = 50)
        markReadAgo(id, 100)

        assertEquals(1, cardFor(id).unreadCount)
    }

    @Test
    fun `unread never counts a drafting, failed or cancelled reply`() {
        val id = data.insertThread("Three ways to not be a reply", agoSeconds = 3600)
        data.insertComment(id, "sol", "settled", agoSeconds = 300)
        data.insertComment(id, "vex", "still writing", state = "DRAFTING", agoSeconds = 200)
        data.insertComment(id, "mira", "the model fell over", state = "FAILED", agoSeconds = 100)
        data.insertComment(id, "sol", "the owner stopped it", state = "CANCELLED", agoSeconds = 50)

        assertEquals(1, cardFor(id).unreadCount, "one POSTED reply — the other three are not replies yet")
    }

    @Test
    fun `the card's unread count agrees with ThreadReadRepository, with and without a marker`() {
        // The coherence pin (I5). The comparison is against the REAL unreadCount — the method the thread
        // page and every pre-S6 scenario already trust — rather than a second copy of this class's SQL,
        // which would agree with itself while both were wrong. The literal expectations are there so the
        // pair cannot pass by both returning zero.
        val id = data.insertThread("The same number, two ways", agoSeconds = 3600)
        data.insertComment(id, "sol", "one", agoSeconds = 300)
        data.insertComment(id, "vex", "two", agoSeconds = 200)
        data.insertComment(id, "mira", "three", agoSeconds = 50)
        data.insertComment(id, "sol", "still writing", state = "DRAFTING", agoSeconds = 10)

        assertEquals(3, threadReads.unreadCount(id), "three settled replies and no marker")
        assertEquals(threadReads.unreadCount(id), cardFor(id).unreadCount, "no marker: both branches count everything")

        markReadAgo(id, 100)

        assertEquals(1, threadReads.unreadCount(id), "one reply lands after the marker")
        assertEquals(threadReads.unreadCount(id), cardFor(id).unreadCount, "with a marker: both branches count the tail")
    }

    @Test
    fun `the excerpt is the newest settled reply, with the voice that wrote it`() {
        val id = data.insertThread("Which reply gets previewed", body = "the opening post", agoSeconds = 3600)
        data.insertComment(id, "sol", "the older reply", agoSeconds = 300)
        data.insertComment(id, "vex", "the newest settled reply", agoSeconds = 100)
        data.insertComment(id, "mira", "an unsettled draft", state = "DRAFTING", agoSeconds = 10)

        val card = cardFor(id)
        assertEquals("the newest settled reply", card.excerptBody)
        assertEquals("vex", card.excerptAuthor)
        assertTrue(card.excerptIsReply)
    }

    @Test
    fun `a thread with no replies previews its own opening post, crediting the thread's own author`() {
        // The small-forum behaviour the design values most: a fresh ambient article thread shows its
        // summary rather than an empty slot. The owner twin is on the same fixture, because "no byline"
        // must come from an owner-authored thread rather than from the query losing the column.
        val ambient = data.insertThread("An article Sol posted", authorId = "sol", body = "The summary", agoSeconds = 600)
        val owned = data.insertThread("Something the owner asked", body = "What do we think?", agoSeconds = 300)

        cardFor(ambient).let {
            assertEquals("The summary", it.excerptBody)
            assertEquals("sol", it.excerptAuthor)
            assertFalse(it.excerptIsReply)
            assertEquals("sol", it.authorId)
        }
        cardFor(owned).let {
            assertEquals("What do we think?", it.excerptBody)
            assertNull(it.excerptAuthor, "an owner thread previewing its own OP has no persona byline")
            assertNull(it.authorId)
        }
    }

    @Test
    fun `the activity stream interleaves thread openings and settled comments, newest first`() {
        val first = data.insertThread("The older thread", agoSeconds = 300)
        val replyToFirst = data.insertComment(first, "sol", "a reply to the older thread", agoSeconds = 200)
        val second = data.insertThread("The newer thread", agoSeconds = 100)
        val replyToSecond = data.insertComment(second, "vex", "a reply to the newer thread", agoSeconds = 50)

        val events = feed.recentActivity(50)

        assertEquals(listOf(replyToSecond, second, replyToFirst, first), events.map { it.id })
        assertEquals(listOf(false, true, false, true), events.map { it.isPost }, "both legs are present, alternating here")
        assertEquals("The newer thread", events[1].threadTitle)
        assertEquals("a reply to the older thread", events[2].body)
    }

    @Test
    fun `a thread opening's event id is its own thread id`() {
        // What makes a post card's link land on the opening post's existing anchor rather than needing a
        // second href shape (§2.2).
        val id = data.insertThread("Its own event", agoSeconds = 100)

        val event = feed.recentActivity(50).single()
        assertEquals(id, event.id)
        assertEquals(id, event.threadId)
    }

    @Test
    fun `a thread nobody is credited with reads as owner in the stream`() {
        val owned = data.insertThread("The owner opened this", agoSeconds = 200)
        val ambient = data.insertThread("Sol opened this", authorId = "sol", agoSeconds = 100)

        val byId = feed.recentActivity(50).associateBy { it.id }
        assertEquals("owner", byId.getValue(owned).authorId, "a NULL author_id is the owner, and the card needs a voice")
        assertEquals("sol", byId.getValue(ambient).authorId, "a credited thread keeps its byline")
    }

    @Test
    fun `unsettled comments never reach the stream`() {
        val id = data.insertThread("A thread with three non-replies", agoSeconds = 600)
        val settled = data.insertComment(id, "sol", "settled", agoSeconds = 300)
        data.insertComment(id, "vex", "still writing", state = "DRAFTING", agoSeconds = 200)
        data.insertComment(id, "mira", "the model fell over", state = "FAILED", agoSeconds = 100)
        data.insertComment(id, "sol", "the owner stopped it", state = "CANCELLED", agoSeconds = 50)

        val commentEvents = feed.recentActivity(50).filterNot { it.isPost }
        assertEquals(listOf(settled), commentEvents.map { it.id })
    }

    @Test
    fun `a comment newer than the read marker is flagged unread, and a thread opening never is`() {
        val id = data.insertThread("Read to a point", agoSeconds = 600)
        val read = data.insertComment(id, "sol", "before the marker", agoSeconds = 300)
        val unread = data.insertComment(id, "vex", "after the marker", agoSeconds = 50)
        markReadAgo(id, 100)

        val byId = feed.recentActivity(50).associateBy { it.id }
        assertTrue(byId.getValue(unread).unread)
        assertFalse(byId.getValue(read).unread)
        assertFalse(byId.getValue(id).unread, "an opening post is not an unread reply (I5)")
    }

    @Test
    fun `a deleted thread's comments vanish from the stream`() {
        // The comment leg's INNER JOIN. The orphan is FORGED — `comment.thread_id` references `thread(id)`
        // and the test datasource runs with foreign_keys=on, so the app cannot write this state and the
        // pragma has to come off for one statement to build it (the forged-cycle pattern from the testing
        // skill). What it stands for is real: a card linking into a thread that is no longer there.
        val kept = data.insertThread("Still here", agoSeconds = 600)
        data.insertComment(kept, "sol", "a surviving comment", agoSeconds = 300)
        val doomed = data.insertThread("About to be deleted", agoSeconds = 500)
        data.insertComment(doomed, "vex", "an orphaned comment", agoSeconds = 200)

        jdbc.execute("PRAGMA foreign_keys = OFF")
        try {
            jdbc.update("DELETE FROM thread WHERE id = ?", doomed)
        } finally {
            jdbc.execute("PRAGMA foreign_keys = ON")
        }

        val events = feed.recentActivity(50)
        assertEquals(
            listOf("a surviving comment", "Still here"), events.map { if (it.isPost) it.threadTitle else it.body },
            "the orphan is gone and the healthy thread is still rendered — which is what stops this passing empty",
        )
    }

    @Test
    fun `the stream is totally ordered under identical stamps, and stable across two calls`() {
        // The one fixture that deliberately collides: five events on one instant, which is what the fixed
        // clock produces for anything seeded without an age. `created_at DESC` alone leaves the order to
        // the planner; `is_post DESC, id DESC` is what makes it a total one — thread openings ahead of
        // comments at the same instant, then descending id within each.
        val a = data.insertThread("One")
        val b = data.insertThread("Two")
        val comments = listOf(
            data.insertComment(a, "sol", "one"),
            data.insertComment(a, "vex", "two"),
            data.insertComment(b, "mira", "three"),
        )
        val expected = listOf(a, b).sortedDescending() + comments.sortedDescending()

        val first = feed.recentActivity(50)
        val second = feed.recentActivity(50)

        assertEquals(expected, first.map { it.id })
        assertEquals(first.map { it.id }, second.map { it.id }, "two calls on unchanged data must return one page")
    }

    @Test
    fun `the stream is capped at the limit`() {
        val id = data.insertThread("A busy thread", agoSeconds = 600)
        data.insertComment(id, "sol", "oldest", agoSeconds = 300)
        data.insertComment(id, "vex", "middle", agoSeconds = 200)
        data.insertComment(id, "mira", "newest", agoSeconds = 100)

        assertEquals(listOf("newest", "middle"), feed.recentActivity(2).map { it.body })
        assertEquals(4, feed.recentActivity(50).size, "the cap is the only thing that was hiding the rest")
    }
}
