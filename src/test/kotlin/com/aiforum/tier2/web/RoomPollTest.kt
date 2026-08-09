package com.aiforum.tier2.web

import com.aiforum.domain.Comment
import com.aiforum.dto.BranchIndexEntry
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReplyView
import com.aiforum.images.DescribeRequest
import com.aiforum.images.ImageDescriber
import com.aiforum.images.ImageStore
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import com.aiforum.repo.AttachmentRepository
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.QuoteRepository
import com.aiforum.repo.VoteRepository
import com.aiforum.service.AttachmentService
import com.aiforum.service.GenerationService
import com.aiforum.service.InFlightGenerations
import com.aiforum.web.BranchIndexBuilder
import com.aiforum.web.GenerationController
import com.aiforum.web.ReplyTreeAssembler
import com.aiforum.web.ThreadReplies
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.ui.ExtendedModelMap
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tier-2: the three answers `GET /threads/{id}/room` can give, and the htmx swap headers that go with
 * each — deterministically, with no worker, no browser and no waiting.
 *
 * This exists because both halves of the poll's contract were bought by a failure. Answering from the
 * registry alone lost a room whose drafts had already settled; then answering on CONTENT alone lost it
 * again, because an owner note posted mid-routing makes the thread non-empty while the room has produced
 * nothing — and a terminal response there swaps the poller away before the drafts land. **The routing
 * window decides, not the emptiness of the thread**, and the header that makes the terminal response
 * terminal is the one thing no acceptance scenario can watch land (nothing in `verifyAll` drives a
 * browser), so it is pinned here instead.
 */
@Tag("tier2")
class RoomPollTest {

    private val clock = Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)
    private val registry = InFlightGenerations()

    private fun note(id: String) =
        Comment(id, THREAD, null, "owner", "meanwhile, my own hunch", GenerationState.POSTED, null, depth = 1)

    private fun draftView(id: String) =
        ReplyView(id, "sol", "", GenerationState.DRAFTING, null, null, retryable = false, retryAfterSeconds = null, voteCount = 0, depth = 1)

    /** The real controller; every collaborator the room path doesn't touch is bare. */
    private fun controllerOver(rows: List<Comment>): GenerationController {
        val comments = object : CommentRepository(JdbcTemplate(), clock) {
            override fun threadComments(threadId: String): List<Comment> = rows
        }
        val personas = object : PersonaRepository(JdbcTemplate()) {
            override fun findAll() = emptyList<PersonaRepository.Persona>()
        }
        val assembler = object : ReplyTreeAssembler(
            comments, VoteRepository(JdbcTemplate()), personas,
            AttachmentRepository(JdbcTemplate(), clock), QuoteRepository(JdbcTemplate(), clock),
        ) {
            override fun assemble(all: List<Comment>): List<ReplyView> = all.map { it.toReplyView() }
        }
        val generation = GenerationService(
            object : LlmClient {
                override fun generate(request: LlmRequest, cancellation: CancellationToken) = LlmResponse("unused")
            },
            comments, personas, inFlight = registry,
        )
        return GenerationController(
            generation, personas, comments,
            AttachmentService(
                ImageStore(Files.createTempDirectory("aiforum-room-poll").toString(), 1_000_000),
                AttachmentRepository(JdbcTemplate(), clock),
                object : ImageDescriber {
                    override fun describe(request: DescribeRequest) = "unused"
                },
                "",
            ),
            BranchIndexBuilder(comments, personas, assembler),
            assembler,
            QuoteRepository(JdbcTemplate(), clock),
            ThreadReplies(generation, comments, assembler),
            ObjectMapper(),
        )
    }

    private fun poll(rows: List<Comment>): Pair<String, MockHttpServletResponse> {
        val response = MockHttpServletResponse()
        val model = ExtendedModelMap()
        val view = controllerOver(rows).room(THREAD, response, model)
        lastModel = model
        return view to response
    }

    private lateinit var lastModel: ExtendedModelMap

    @Test
    fun `while a summon is routing the answer is the poller, even on a thread that already has rows`() {
        // THE BLOCKER, pinned: the owner posted a note mid-routing, so the thread is non-empty while the
        // room has produced nothing. Answering terminally here retargets the whole reply list and takes
        // the poller with it — routing then concludes into a page that never asks again.
        registry.beginSummon(THREAD)
        val (view, response) = poll(listOf(note("n1")))

        assertEquals("fragments/roomPoller", view)
        assertEquals(true, lastModel.getAttribute("summoning"))
        assertNull(response.getHeader("HX-Retarget"), "a poller that retargets the reply list swaps ITSELF away")
        assertNull(response.getHeader("HX-Reswap"))
    }

    @Test
    fun `once routing has concluded the answer is terminal and retargets the whole reply list`() {
        registry.register("d1", THREAD, draftView("d1"))
        val (view, response) = poll(listOf(note("n1")))

        assertEquals("fragments/replyList", view)
        // The persisted note and the still-drafting node arrive together — the union, not either read.
        assertEquals(listOf("n1", "d1"), replies().map { it.id })
        // Retarget, so the response replaces the whole list rather than the poller: it carries rows the
        // page already holds (that note), and an in-place swap would leave the browser holding them twice.
        assertEquals(".reply-list", response.getHeader("HX-Retarget"))
        // Stated, not inherited from the poller's own hx-swap, so the two can't drift apart.
        assertEquals("outerHTML", response.getHeader("HX-Reswap"))
        // TERMINAL MEANS TERMINAL: replyList renders a poller when handed summoning=true, and this response
        // replaces the whole list — so a poller inside it would retarget and replace the list again every
        // second, forever. The param defaults false; this is what holds the default to its job.
        assertNotEquals(true, lastModel.getAttribute("summoning"), "a terminal response must carry no poller")
        // The rail rides along out of band, because this response is what puts the settled rows on the page.
        // Nothing else asserts it: the reply-body probe is deliberately scoped away from the rail, so
        // without this an emptied branch index would silently blank the thread's TOC on every room poll.
        assertEquals(listOf("n1"), branchIndex().map { it.id }, "posted rows only — the draft is not in the rail")
    }

    @Test
    fun `a summon that ended having produced nothing drops the poller and retargets nothing`() {
        val (view, response) = poll(emptyList())

        assertEquals("fragments/roomPoller", view)
        assertEquals(false, lastModel.getAttribute("summoning"), "renders empty, so htmx stops polling")
        assertNull(response.getHeader("HX-Retarget"), "there is no list to replace")
    }

    @Suppress("UNCHECKED_CAST")
    private fun replies() = lastModel.getAttribute("replies") as List<ReplyView>

    @Suppress("UNCHECKED_CAST")
    private fun branchIndex() = lastModel.getAttribute("branchIndex") as List<BranchIndexEntry>

    private companion object {
        const val THREAD = "t1"
    }
}
