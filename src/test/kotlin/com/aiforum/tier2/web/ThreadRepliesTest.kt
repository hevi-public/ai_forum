package com.aiforum.tier2.web

import com.aiforum.domain.Comment
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReplyView
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import com.aiforum.repo.AttachmentRepository
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.QuoteRepository
import com.aiforum.repo.VoteRepository
import com.aiforum.service.GenerationService
import com.aiforum.service.InFlightGenerations
import com.aiforum.web.ReplyTreeAssembler
import com.aiforum.web.ThreadReplies
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tier-2: [ThreadReplies] — the union both the thread page and the room poll read through — against a
 * real [InFlightGenerations] registry and a stubbed comment read.
 *
 * The read ORDER (registry before DB) used to be marked as unpinnable here: a race between a settling
 * worker and a rendering request that no test could observe by waiting on real timing. It IS pinned now
 * (`a settle landing in the gap between the two reads…` below), not by racing a real thread but by
 * controlling CAUSALITY directly, not a raw call count: the registry accessor
 * ([GenerationService.inFlightViews]) always returns the pre-settle draft but also records that it ran;
 * the DB accessor ([CommentRepository.threadComments]) returns the persisted row only if that flag is
 * already set at the time it is called — otherwise the pre-settle empty list, the real worker's
 * "not-yet-persisted" window. The fixture is sensitive to exactly the invariant under test — whether the
 * registry read causally precedes the tree-feeding DB read — and to nothing else: an earlier, ordinal
 * version of this fixture keyed the two reads off a call counter shared between them, which only pinned
 * the order for an implementation making EXACTLY two collaborator calls; one extra stubbed-accessor call
 * ahead of the real DB read could absorb the "first read" slot on the counter and rescue a DB-first
 * implementation without reddening (see the dated addendum in how-we-work/context.md). Verified by
 * mutation (issue #17 part 1, 2026-08-09; re-verified 2026-08-10 after the causal rework): swapping the
 * two reads inside [ThreadReplies.read] turns that test red 3/3 runs; the pre-existing tests below and in
 * [com.aiforum.tier2.web.RoomPollTest] do not move under the same swap, 3/3 runs — their fixtures are
 * static snapshots, so no implementation order could make them red (see how-we-work/context.md).
 *
 * Everything else below was already deterministic without needing that trick: a node caught in BOTH reads
 * renders once with the settled row winning, a draft with no row still shows, and the two flags the callers
 * branch on say what they mean.
 */
@Tag("tier2")
class ThreadRepliesTest {

    private val clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)
    private val registry = InFlightGenerations()

    private fun comment(id: String, state: GenerationState = GenerationState.POSTED, body: String = "settled $id") =
        Comment(id, THREAD, null, "sol", body, state, null, depth = 1)

    private fun draftView(id: String) =
        ReplyView(id, "sol", "", GenerationState.DRAFTING, null, null, retryable = false, retryAfterSeconds = null, voteCount = 0, depth = 1)

    /**
     * The real assembler (nesting, votes, quotes, attachments) is exercised by the acceptance suite and by
     * the page it renders; here it only has to turn the rows into views, so the assertions are about the
     * union rather than about tree shape. Its own collaborators never run.
     */
    private fun flatAssembler() = object : ReplyTreeAssembler(
        CommentRepository(JdbcTemplate(), clock),
        VoteRepository(JdbcTemplate()),
        PersonaRepository(JdbcTemplate()),
        AttachmentRepository(JdbcTemplate(), clock),
        QuoteRepository(JdbcTemplate(), clock),
    ) {
        override fun assemble(all: List<Comment>): List<ReplyView> = all.map { it.toReplyView() }
    }

    /** The union as a caller sees it: build the component over [rows] and read the thread once. */
    private fun repliesOver(rows: List<Comment>): ThreadReplies.Assembled {
        val comments = object : CommentRepository(JdbcTemplate(), clock) {
            override fun threadComments(threadId: String): List<Comment> = rows
        }
        val generation = GenerationService(
            object : LlmClient {
                override fun generate(request: LlmRequest, cancellation: CancellationToken) = LlmResponse("unused")
            },
            comments,
            PersonaRepository(JdbcTemplate()),
            inFlight = registry,
        )
        return ThreadReplies(generation, comments, flatAssembler()).read(THREAD)
    }

    @Test
    fun `a node caught in both reads renders once, and the settled row wins`() {
        // The window the read order exists for: the worker has persisted the row but not yet evicted the
        // registry entry, so the same id is in BOTH. It must render once, as the settled node.
        registry.register("n1", THREAD, draftView("n1"))
        val replies = repliesOver(listOf(comment("n1", body = "the posted body")))

        assertEquals(listOf("n1"), replies.all.map { it.id }, "the id must not be rendered twice")
        assertEquals(GenerationState.POSTED, replies.all.single().state, "the settled row wins over the draft view")
        assertEquals("the posted body", replies.all.single().body)
        assertTrue(replies.drafting.isEmpty(), "a draft already in the tree is not surfaced again")
    }

    @Test
    fun `a draft with no row yet is surfaced alongside the persisted tree`() {
        registry.register("draft", THREAD, draftView("draft"))
        val replies = repliesOver(listOf(comment("posted")))

        assertEquals(listOf("posted", "draft"), replies.all.map { it.id }, "tree first, then the drafts")
        assertEquals(listOf("draft"), replies.drafting.map { it.id })
    }

    @Test
    fun `anyPosted reports the state of the rows, not their existence`() {
        // waitingOnRoom hangs off this: a thread whose only rows FAILED has still never had anyone speak.
        assertFalse(repliesOver(listOf(comment("f", state = GenerationState.FAILED))).anyPosted)
        assertTrue(repliesOver(listOf(comment("p"))).anyPosted)
        assertFalse(repliesOver(emptyList()).anyPosted)
    }

    @Test
    fun `a settle landing in the gap between the two reads is never lost, regardless of read order`() {
        // The race ThreadReplies.read's KDoc describes: the worker's persist-then-evict can land in the
        // gap between the two reads. repliesOver()'s fixtures above are static — nothing changes between
        // the two reads — so no implementation order could turn them red. This fixture makes the world
        // CHANGE between the registry read and the DB read, deterministically, keyed on CAUSALITY rather
        // than a raw call count: the registry accessor always returns the pre-settle draft but flags that
        // it ran; the DB accessor returns the persisted row only if that flag is already set — otherwise
        // the pre-settle empty list. That holds regardless of how many times either is called (an earlier
        // ordinal version of this fixture, keyed on a shared counter's ">1" cutoff, only pinned the order
        // for an implementation making exactly two collaborator calls: one extra stubbed-accessor call
        // ahead of the real DB read could absorb the "first read" slot on the counter and rescue a
        // DB-first implementation without reddening — see how-we-work/context.md).
        var registryRan = false
        val comments = object : CommentRepository(JdbcTemplate(), clock) {
            override fun threadComments(threadId: String): List<Comment> =
                if (registryRan) listOf(comment("n1", body = "the posted body")) else emptyList()
        }
        val generation = object : GenerationService(
            object : LlmClient {
                override fun generate(request: LlmRequest, cancellation: CancellationToken) = LlmResponse("unused")
            },
            comments,
            PersonaRepository(JdbcTemplate()),
            inFlight = registry,
        ) {
            override fun inFlightViews(threadId: String): List<ReplyView> {
                registryRan = true
                return listOf(draftView("n1"))
            }
        }

        val replies = ThreadReplies(generation, comments, flatAssembler()).read(THREAD)

        // Registry-first (real) order: the registry read runs before the DB read, so the DB read already
        // sees the persisted row by the time it runs — the union renders "n1" once, as posted, nothing
        // left drafting. DB-first (buggy) order: the DB read runs before the registry read has set the
        // flag, so it sees the pre-settle empty list — "n1" only shows up via the stale draft, still
        // DRAFTING even though the row exists. The id alone does not tell the orders apart (the draft
        // keeps the id present either way) — the state and the drafting list do.
        assertEquals(listOf("n1"), replies.all.map { it.id }, "a node settling between the two reads must not vanish from both")
        assertEquals(
            GenerationState.POSTED, replies.all.single().state,
            "registry-first sees the persisted row by the time the DB is read — a DB-first read would leave this DRAFTING",
        )
        assertTrue(
            replies.drafting.isEmpty(),
            "the settled node must not still be surfaced as a draft once the registry read has causally preceded the DB read",
        )
    }

    @Test
    fun `isEmpty is true only when neither read produced anything`() {
        // The room poll's terminal-vs-poller branch reads this, so "empty" must mean BOTH are empty.
        assertTrue(repliesOver(emptyList()).isEmpty())

        registry.register("draft", THREAD, draftView("draft"))
        assertFalse(repliesOver(emptyList()).isEmpty(), "a draft with no row is still something to show")
        assertFalse(repliesOver(listOf(comment("p"))).isEmpty())
    }

    private companion object {
        const val THREAD = "t1"
    }
}
