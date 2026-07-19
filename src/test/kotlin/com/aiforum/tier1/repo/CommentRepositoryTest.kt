package com.aiforum.tier1.repo

import com.aiforum.acceptance.support.TestData
import com.aiforum.domain.Comment
import com.aiforum.dto.GenerationState
import com.aiforum.repo.CommentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Tier-1: the repository against the real test SQLite DB (see the bdd-tiered-testing skill). Pins the
 * `childrenOf` query that backs the branch+siblings scope — it must return only direct children.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class CommentRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var data: TestData
    @Autowired lateinit var comments: CommentRepository

    @BeforeEach
    fun clean() {
        listOf("vote", "comment_revision", "event_log", "comment", "thread", "persona").forEach { jdbc.update("DELETE FROM $it") }
    }

    @Test
    fun `childrenOf returns only the direct children of a node`() {
        // tree:  R ─┬─ A ── A1
        //          └─ B
        val thread = data.insertThread("Scaling SQLite")
        val r = data.insertComment(thread, authorId = "owner", body = "R", parentId = null, depth = 0)
        val a = data.insertComment(thread, authorId = "vex", body = "A", parentId = r)
        data.insertComment(thread, authorId = "pike", body = "B", parentId = r)
        data.insertComment(thread, authorId = "sol", body = "A1", parentId = a)

        assertEquals(setOf("A", "B"), comments.childrenOf(r).map { it.body }.toSet())
        assertEquals(setOf("A1"), comments.childrenOf(a).map { it.body }.toSet())
        assertEquals(setOf("R"), comments.childrenOf(null).map { it.body }.toSet())
    }

    @Test
    fun `growableLeaves returns only POSTED childless nodes that still have budget`() {
        val thread = data.insertThread("Scaling SQLite")
        // fuelled root with budget, but it has a child → not a leaf, must be excluded
        val root = data.insertComment(thread, authorId = "owner", body = "R", parentId = null, depth = 0, depthBudget = 4)
        // a leaf that still has budget → the growth frontier
        data.insertComment(thread, authorId = "sol", body = "fuelled-leaf", parentId = root, depth = 1, depthBudget = 3)
        // an exhausted leaf (budget 0) → excluded
        data.insertComment(thread, authorId = "sol", body = "spent-leaf", parentId = root, depth = 1, depthBudget = 0)
        // a leaf with budget but not POSTED → never grown under
        data.insertComment(thread, authorId = "sol", body = "failed-leaf", parentId = root, depth = 1, state = "FAILED", depthBudget = 3)

        assertEquals(setOf("fuelled-leaf"), comments.growableLeaves(thread).map { it.body }.toSet())
    }

    @Test
    fun `growableLeaves scoped to a subtree excludes fuelled leaves on other branches`() {
        // Two independent top-level branches:  R1 ── L1 (fuelled)   and   R2 (fuelled leaf itself).
        // The owner's thread-wide /auto-grow sees both; the ambient settle-hook growth (S2, scoped to the
        // settled comment's subtree) must see ONLY its own branch — the other branch's owner-granted fuel
        // stays unspent.
        val thread = data.insertThread("Scaling SQLite")
        val r1 = data.insertComment(thread, authorId = "owner", body = "R1", parentId = null, depth = 0, depthBudget = 4)
        data.insertComment(thread, authorId = "sol", body = "L1", parentId = r1, depth = 1, depthBudget = 3)
        val r2 = data.insertComment(thread, authorId = "vex", body = "R2", parentId = null, depth = 0, depthBudget = 2)

        // Thread-wide (withinSubtreeOf = null, the owner endpoint's semantics): every fuelled leaf.
        assertEquals(setOf("L1", "R2"), comments.growableLeaves(thread).map { it.body }.toSet())
        // Scoped: the subtree root itself counts when it is a growable leaf (the fresh ambient comment)…
        assertEquals(setOf("R2"), comments.growableLeaves(thread, r2).map { it.body }.toSet())
        // …and a scoped walk from the other root never crosses into the sibling branch.
        assertEquals(setOf("L1"), comments.growableLeaves(thread, r1).map { it.body }.toSet())
    }

    @Test
    fun `descendantCount counts the whole subtree under a node, excluding itself`() {
        // tree:  R ─┬─ A ── A1
        //          └─ B
        val thread = data.insertThread("Scaling SQLite")
        val r = data.insertComment(thread, authorId = "owner", body = "R", parentId = null, depth = 0)
        val a = data.insertComment(thread, authorId = "vex", body = "A", parentId = r)
        data.insertComment(thread, authorId = "pike", body = "B", parentId = r)
        data.insertComment(thread, authorId = "sol", body = "A1", parentId = a)

        assertEquals(3, comments.descendantCount(r))
        assertEquals(1, comments.descendantCount(a))
        assertEquals(0, comments.descendantCount(comments.childrenOf(r).first { it.body == "B" }.id))
    }

    @Test
    fun `deleteSubtree removes the node, its whole subtree, and their votes — siblings survive`() {
        // tree:  R ─┬─ A ── A1
        //          └─ B          ← B (and R) must survive a delete of the A subtree
        val thread = data.insertThread("Scaling SQLite")
        val r = data.insertComment(thread, authorId = "owner", body = "R", parentId = null, depth = 0)
        val a = data.insertComment(thread, authorId = "vex", body = "A", parentId = r)
        val b = data.insertComment(thread, authorId = "pike", body = "B", parentId = r)
        val a1 = data.insertComment(thread, authorId = "sol", body = "A1", parentId = a, depth = 2)
        // votes on a doomed node, a doomed descendant, and a survivor
        listOf(a, a1, b).forEach { jdbc.update("INSERT INTO vote(node_id, voter_id) VALUES (?, 'owner')", it) }

        val removed = comments.deleteSubtree(a)

        assertEquals(setOf(a, a1), removed.toSet())
        // A and its descendant A1 are gone; R and B survive
        assertEquals(setOf("R", "B"), comments.threadComments(thread).map { it.body }.toSet())
        // votes for the deleted nodes are gone; B's vote survives (no FK violation deleting deepest-first)
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vote WHERE node_id IN (?, ?)", Int::class.java, a, a1))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vote WHERE node_id = ?", Int::class.java, b))
    }

    @Test
    fun `deleteSubtree on an unknown id is a no-op`() {
        val thread = data.insertThread("Scaling SQLite")
        data.insertComment(thread, authorId = "owner", body = "R", parentId = null, depth = 0)

        assertEquals(emptyList<String>(), comments.deleteSubtree("does-not-exist"))
        assertEquals(setOf("R"), comments.threadComments(thread).map { it.body }.toSet())
    }

    @Test
    fun `deleteByThread removes every comment and vote in the thread — other threads are untouched`() {
        // doomed thread:  R ─┬─ A ── A1
        //                    └─ B
        val doomed = data.insertThread("Scaling SQLite")
        val r = data.insertComment(doomed, authorId = "owner", body = "R", parentId = null, depth = 0)
        val a = data.insertComment(doomed, authorId = "vex", body = "A", parentId = r)
        val b = data.insertComment(doomed, authorId = "pike", body = "B", parentId = r)
        val a1 = data.insertComment(doomed, authorId = "sol", body = "A1", parentId = a, depth = 2)
        listOf(r, a, b, a1).forEach { jdbc.update("INSERT INTO vote(node_id, voter_id) VALUES (?, 'owner')", it) }
        // a separate thread that must survive intact
        val keep = data.insertThread("io_uring vs epoll")
        val k = data.insertComment(keep, authorId = "owner", body = "K", parentId = null, depth = 0)
        jdbc.update("INSERT INTO vote(node_id, voter_id) VALUES (?, 'owner')", k)

        val removed = comments.deleteByThread(doomed)

        assertEquals(setOf(r, a, b, a1), removed.toSet())
        // every comment + vote in the doomed thread is gone, no FK violation (deepest-first)
        assertEquals(emptyList<com.aiforum.domain.Comment>(), comments.threadComments(doomed))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vote v JOIN comment c ON c.id = v.node_id WHERE c.thread_id = ?", Int::class.java, doomed))
        // the other thread and its vote survive
        assertEquals(setOf("K"), comments.threadComments(keep).map { it.body }.toSet())
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vote WHERE node_id = ?", Int::class.java, k))
    }

    @Test
    fun `deleteByThread on a thread with no comments is a no-op`() {
        val thread = data.insertThread("empty")
        assertEquals(emptyList<String>(), comments.deleteByThread(thread))
    }

    @Test
    fun `recentPosted returns the newest POSTED comments across threads, newest first, capped`() {
        // Two threads; insert in a deliberately jumbled time order, then assert the query re-sorts.
        val t1 = data.insertThread("io_uring vs epoll")
        val t2 = data.insertThread("Rust in the kernel")
        // created_at is controlled directly so ordering is deterministic (the test Clock is fixed).
        fun post(thread: String, author: String, body: String, at: String) =
            data.insertComment(thread, authorId = author, body = body).also { id ->
                jdbc.update("UPDATE comment SET created_at = ? WHERE id = ?", at, id)
            }

        post(t1, "vex", "oldest", "2026-06-21T10:00:00Z")
        post(t2, "pike", "middle", "2026-06-21T11:00:00Z")
        val newest = post(t1, "sol", "newest", "2026-06-21T12:00:00Z")

        val recent = comments.recentPosted(limit = 2)
        assertEquals(listOf("newest", "middle"), recent.map { it.body })   // newest first, capped at 2
        assertEquals(newest, recent.first().id)
        assertEquals(t1, recent.first().threadId)
        assertEquals("sol", recent.first().authorId)
    }

    @Test
    fun `insert persists the starred flag and toggleStar flips it both ways`() {
        val thread = data.insertThread("Scaling SQLite")
        fun node(author: String, body: String, starred: Boolean) = Comment(
            id = data.newId(), threadId = thread, parentId = null, authorId = author, body = body,
            state = GenerationState.POSTED, failureCategory = null, depth = 0, starred = starred,
        )
        val pinned = node("sol", "pinned", starred = true).also(comments::insert)
        val loose = node("vex", "loose", starred = false).also(comments::insert)

        // insert round-trips the flag
        assertEquals(true, comments.findById(pinned.id)!!.starred)
        assertEquals(false, comments.findById(loose.id)!!.starred)

        // toggle flips and persists, both directions, returning the new state
        assertEquals(true, comments.toggleStar(loose.id))
        assertEquals(true, comments.findById(loose.id)!!.starred)
        assertEquals(false, comments.toggleStar(loose.id))
        assertEquals(false, comments.findById(loose.id)!!.starred)
    }

    @Test
    fun `update keeps a star set on the node — a generation settle never clears it`() {
        val thread = data.insertThread("Scaling SQLite")
        val draft = Comment(
            id = data.newId(), threadId = thread, parentId = null, authorId = "sol", body = "draft",
            state = GenerationState.DRAFTING, failureCategory = null, depth = 0,
        )
        comments.insert(draft)
        comments.toggleStar(draft.id)

        comments.update(draft.copy(state = GenerationState.POSTED, body = "settled"))

        val after = comments.findById(draft.id)!!
        assertEquals(GenerationState.POSTED, after.state)
        assertEquals("settled", after.body)
        assertEquals(true, after.starred) // update() leaves starred alone
    }

    @Test
    fun `toggleStar on an unknown id is a no-op returning false`() {
        assertEquals(false, comments.toggleStar("does-not-exist"))
    }

    @Test
    fun `editBody rewrites the body and stamps updated_at — a generation settle does not`() {
        val thread = data.insertThread("Scaling SQLite")
        val draft = Comment(
            id = data.newId(), threadId = thread, parentId = null, authorId = "sol", body = "first take",
            state = GenerationState.DRAFTING, failureCategory = null, depth = 0,
        )
        comments.insert(draft)

        // A generation settle (update) rewrites the body but is NOT an owner edit — updated_at stays null.
        comments.update(draft.copy(state = GenerationState.POSTED, body = "settled"))
        comments.findById(draft.id)!!.let {
            assertEquals("settled", it.body)
            assertEquals(null, it.updatedAt)
        }

        // An owner edit rewrites the body and stamps updated_at, so the node renders the "(edited)" marker.
        assertEquals(true, comments.editBody(draft.id, "corrected"))
        comments.findById(draft.id)!!.let {
            assertEquals("corrected", it.body)
            assertNotNull(it.updatedAt)
        }
    }

    @Test
    fun `editBody on an unknown id is a no-op returning false`() {
        assertEquals(false, comments.editBody("does-not-exist", "whatever"))
    }

    @Test
    fun `editBody appends an edit revision — the original is kept and the marker tracks the shown version`() {
        val thread = data.insertThread("Scaling SQLite")
        val id = data.insertComment(thread, authorId = "sol", body = "first take")

        assertEquals(true, comments.editBody(id, "corrected"))
        // Two revisions now: idx 0 = the original generation, idx 1 = the owner's edit (shown).
        assertEquals(2, comments.revisionCount(id))
        comments.findById(id)!!.let {
            assertEquals("corrected", it.body)
            assertNotNull(it.updatedAt, "the shown revision is the edit → marked edited")
            assertEquals(1, it.revisionIndex)
        }

        // Stepping back to the original generation restores it AND clears the edited marker.
        assertEquals(true, comments.selectRevision(id, 0))
        comments.findById(id)!!.let {
            assertEquals("first take", it.body)
            assertEquals(null, it.updatedAt, "the original generation is not an edit → no marker")
        }
    }

    @Test
    fun `revisions are append-only and selectRevision swaps the chosen take into the live body`() {
        val thread = data.insertThread("Scaling SQLite")
        val id = data.insertComment(thread, authorId = "sol", body = "first take")

        // No revisions yet → implicit 1-of-1, body untouched, revision_index 0.
        assertEquals(0, comments.revisionCount(id))
        assertEquals(0, comments.findById(id)!!.revisionIndex)

        // First regenerate seeds idx 0 (the body being replaced) then idx 1 (the new take), and shows idx 1.
        comments.addRevision(id, 0, "first take", null)
        comments.addRevision(id, 1, "second take", null)
        assertEquals(true, comments.selectRevision(id, 1))
        comments.findById(id)!!.let {
            assertEquals("second take", it.body, "the live body follows the selected revision")
            assertEquals(1, it.revisionIndex)
        }
        assertEquals(2, comments.revisionCount(id))

        // Stepping back to idx 0 restores the original take in place.
        assertEquals(true, comments.selectRevision(id, 0))
        comments.findById(id)!!.let {
            assertEquals("first take", it.body)
            assertEquals(0, it.revisionIndex)
        }

        // An out-of-range index is a no-op leaving the comment untouched.
        assertEquals(false, comments.selectRevision(id, 9))
        assertEquals("first take", comments.findById(id)!!.body)
    }

    @Test
    fun `revisionCountsByComment reports counts per comment for the whole thread`() {
        val thread = data.insertThread("Scaling SQLite")
        val regened = data.insertComment(thread, authorId = "sol", body = "live")
        data.insertComment(thread, authorId = "vex", body = "never regened")
        comments.addRevision(regened, 0, "v0", null)
        comments.addRevision(regened, 1, "v1", null)

        val counts = comments.revisionCountsByComment(thread)
        assertEquals(2, counts[regened])
        assertEquals(null, counts[data.newId()], "a comment with no revisions has no entry (implicit 1-of-1)")
    }

    @Test
    fun `deleteSubtree clears a node's revisions so the FK never blocks the delete`() {
        val thread = data.insertThread("Scaling SQLite")
        val id = data.insertComment(thread, authorId = "sol", body = "live", parentId = null, depth = 0)
        comments.addRevision(id, 0, "v0", null)
        comments.addRevision(id, 1, "v1", null)

        assertEquals(setOf(id), comments.deleteSubtree(id).toSet())
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM comment_revision WHERE comment_id = ?", Int::class.java, id))
    }

    @Test
    fun `starredPosted returns only starred POSTED comments, newest first, capped at limit`() {
        val t1 = data.insertThread("io_uring vs epoll")
        val t2 = data.insertThread("Rust in the kernel")
        fun post(thread: String, author: String, body: String, at: String, star: Boolean = false): String {
            val id = data.insertComment(thread, authorId = author, body = body)
            jdbc.update("UPDATE comment SET created_at = ? WHERE id = ?", at, id)
            if (star) comments.toggleStar(id)
            return id
        }

        post(t1, "vex", "not-starred", "2026-06-21T10:00:00Z")
        val oldest = post(t2, "pike", "oldest-star", "2026-06-21T11:00:00Z", star = true)
        val newest = post(t1, "sol", "newest-star", "2026-06-21T12:00:00Z", star = true)

        val results = comments.starredPosted(limit = 5)
        assertEquals(listOf("newest-star", "oldest-star"), results.map { it.body })
        assertEquals(newest, results.first().id)
        assertEquals(t1, results.first().threadId)
        assertEquals("io_uring vs epoll", results.first().threadTitle)
        assertEquals("sol", results.first().authorId)
    }

    @Test
    fun `starredPosted is capped at the given limit`() {
        val t = data.insertThread("Scaling SQLite")
        repeat(3) { i ->
            val id = data.insertComment(t, authorId = "sol", body = "reply-$i")
            comments.toggleStar(id)
        }
        assertEquals(2, comments.starredPosted(limit = 2).size)
    }

    @Test
    fun `starredPosted excludes drafts and failures even when starred`() {
        val t = data.insertThread("Scaling SQLite")
        val posted = data.insertComment(t, authorId = "sol", body = "posted", state = "POSTED")
        val drafting = data.insertComment(t, authorId = "sol", body = "drafting", state = "DRAFTING")
        val failed = data.insertComment(t, authorId = "sol", body = "failed", state = "FAILED")
        comments.toggleStar(posted)
        comments.toggleStar(drafting)
        comments.toggleStar(failed)

        assertEquals(listOf("posted"), comments.starredPosted(limit = 10).map { it.body })
    }

    @Test
    fun `allStarredPosted returns all starred POSTED comments without a cap`() {
        val t = data.insertThread("Scaling SQLite")
        repeat(6) { i ->
            val id = data.insertComment(t, authorId = "sol", body = "reply-$i")
            comments.toggleStar(id)
        }
        assertEquals(6, comments.allStarredPosted().size)
    }

    @Test
    fun `recentPosted excludes drafts, failures and cancelled nodes`() {
        val thread = data.insertThread("Scaling SQLite")
        data.insertComment(thread, authorId = "sol", body = "posted", state = "POSTED")
        data.insertComment(thread, authorId = "sol", body = "drafting", state = "DRAFTING")
        data.insertComment(thread, authorId = "sol", body = "failed", state = "FAILED")
        data.insertComment(thread, authorId = "sol", body = "cancelled", state = "CANCELLED")

        assertEquals(listOf("posted"), comments.recentPosted(limit = 10).map { it.body })
    }

    /**
     * T1.3 cycle/depth guard. A corrupt `parent_id` write (one the app's acyclic invariant would never
     * make) turns each recursive-CTE tree walk into an infinite loop — a hang, not a graceful error.
     * The depth bound (`lvl < 10000`) on every recursive CTE makes the walk terminate instead. We prove
     * it by forging a 2-cycle directly in the DB (A.parent = B, B.parent = A) — bypassing the app — and
     * asserting each CTE method returns within a short timeout rather than spinning forever.
     */
    private fun forge2Cycle(): Pair<String, String> {
        val thread = data.insertThread("Scaling SQLite")
        val a = data.insertComment(thread, authorId = "vex", body = "A", parentId = null, depth = 0)
        val b = data.insertComment(thread, authorId = "pike", body = "B", parentId = a, depth = 1)
        // Forge the cycle the app would never create: A↔B point at each other.
        jdbc.update("UPDATE comment SET parent_id = ? WHERE id = ?", b, a)
        jdbc.update("UPDATE comment SET parent_id = ? WHERE id = ?", a, b)
        return a to b
    }

    @Test
    fun `ancestorPath terminates on a cyclic parent_id graph instead of hanging`() {
        val (a, _) = forge2Cycle()
        // Without the depth bound this loops forever; the guard makes it return (or throw) promptly.
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            runCatching { comments.ancestorPath(a) }
        }
    }

    @Test
    fun `descendantCount terminates on a cyclic parent_id graph instead of hanging`() {
        val (a, _) = forge2Cycle()
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            runCatching { comments.descendantCount(a) }
        }
    }

    @Test
    fun `deleteSubtree (subtreeIdsDeepestFirst) terminates on a cyclic parent_id graph instead of hanging`() {
        val (a, _) = forge2Cycle()
        // deleteSubtree is the only caller of the private subtreeIdsDeepestFirst CTE — drive it through that.
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            runCatching { comments.deleteSubtree(a) }
        }
    }
}
