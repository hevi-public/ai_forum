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
 * What is NOT pinned here, stated so the coverage isn't overread: the read ORDER (registry before DB) is a
 * race between a settling worker and a rendering request, and no test at any tier can observe the instant
 * it protects. What IS deterministic — and is what the order exists to make safe — is everything below:
 * a node caught in BOTH reads renders once with the settled row winning, a draft with no row still shows,
 * and the two flags the callers branch on say what they mean.
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
