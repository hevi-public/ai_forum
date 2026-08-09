package com.aiforum.tier2.service

import com.aiforum.domain.Comment
import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReasoningLeak
import com.aiforum.dto.ReplyView
import com.aiforum.dto.ScopeMode
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import com.aiforum.llm.LlmUsage
import com.aiforum.llm.ToolCall
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.GenerationToolCallRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.RelationStanceRepository
import com.aiforum.repo.Revision
import com.aiforum.repo.Stance
import com.aiforum.service.GenerationService
import com.aiforum.service.InFlightGenerations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tier-2: the service runs real Tier-0 logic over fakes at the single IO seam (see the
 * bdd-tiered-testing skill). Here we pin the couldn't-save path (UX state E): a write failure must
 * keep the drafted body rather than lose it.
 */
@Tag("tier2")
class GenerationServiceTest {

    private val okLlm = object : LlmClient {
        override fun generate(request: LlmRequest, cancellation: CancellationToken) =
            LlmResponse("Indexes help here")
    }

    private val personas = object : PersonaRepository(JdbcTemplate()) {
        override fun find(id: String) = Persona(id, id, "", "You are $id.")
    }

    /** A repository whose first write throws (a one-shot transient blip), the rest delegate to memory. */
    private class FailingOnceComments : CommentRepository(JdbcTemplate(), Clock.systemUTC()) {
        val saved = mutableListOf<Comment>()
        var failNext = true
        override fun insert(c: Comment) {
            if (failNext) {
                failNext = false
                throw IllegalStateException("simulated write failure")
            }
            saved += c
        }
        override fun threadComments(threadId: String): List<Comment> = emptyList()
        override fun ancestorPath(nodeId: String): List<Comment> = emptyList()
    }

    @Test
    fun `a save failure keeps the drafted body and surfaces COULDNT_SAVE`() {
        val comments = FailingOnceComments()
        val service = GenerationService(okLlm, comments, personas)

        val view = service.generate("t1", null, listOf("sol"), "q?", ScopeMode.WHOLE_THREAD).single()

        assertEquals(GenerationState.FAILED, view.state)
        assertEquals(FailureCategory.COULDNT_SAVE, view.failureCategory)
        assertEquals("Indexes help here", view.body, "the drafted text must survive the save failure")
        assertTrue(view.retryable)
        assertEquals(1, comments.saved.size, "the failure marker is persisted so retry has a real row")
    }

    @Test
    fun `a reasoning-leak flag flows from the response through to the persisted comment and view`() {
        // The parsers set LlmResponse.reasoningLeak; here we pin that the service persists it as-is and
        // surfaces it on the view (so the node renders a badge), without touching the POSTED state.
        val leakyLlm = object : LlmClient {
            override fun generate(request: LlmRequest, cancellation: CancellationToken) =
                LlmResponse("The real reply.", ReasoningLeak.ACTUAL)
        }
        val comments = RecordingComments()
        val service = GenerationService(leakyLlm, comments, personas)

        val view = service.generate("t1", null, listOf("sol"), "q?", ScopeMode.WHOLE_THREAD).single()

        assertEquals(GenerationState.POSTED, view.state, "a leak is flagged, not failed")
        assertEquals(ReasoningLeak.ACTUAL, view.reasoningLeak)
        assertEquals(ReasoningLeak.ACTUAL, comments.saved.single().reasoningLeak, "the flag is persisted")
    }

    /** An LlmClient that blocks until its token is tripped, then reports cancellation (mirrors the
     *  acceptance HangUntilCancelled behaviour) — lets us drive the real async cancel path. */
    private val hangingLlm = object : LlmClient {
        override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
            while (!cancellation.isCancelled) Thread.sleep(5)
            throw LlmException.Cancelled()
        }
    }

    /**
     * An in-memory repository that just records writes, so we can assert on what was persisted. [onSettle]
     * fires after each insert (default no-op) — the async test uses it to release a latch the instant the
     * settle WRITE lands, so it can await that exact event instead of wall-clock polling. Inserts are
     * synchronized: a worker thread does the write while the test thread reads [saved], and the latch the
     * hook trips establishes the happens-before the assertions rely on.
     */
    private class RecordingComments(
        private val onSettle: (Comment) -> Unit = {},
    ) : CommentRepository(JdbcTemplate(), Clock.systemUTC()) {
        val saved = mutableListOf<Comment>()
        @Synchronized override fun insert(c: Comment) { saved += c; onSettle(c) }
        @Synchronized override fun findById(id: String): Comment? = saved.lastOrNull { it.id == id }
        override fun threadComments(threadId: String): List<Comment> = emptyList()
        override fun ancestorPath(nodeId: String): List<Comment> = emptyList()
    }

    @Test
    fun `startGeneration drafts immediately and a cancel trips the in-flight token to CANCELLED`() {
        val comments = RecordingComments()
        val registry = InFlightGenerations()
        val service = GenerationService(hangingLlm, comments, personas, registry)

        val draft = service.startGeneration("t1", null, listOf("sol"), "q?", ScopeMode.WHOLE_THREAD).single()
        assertEquals(GenerationState.DRAFTING, draft.state, "the summon returns a DRAFTING node at once")
        assertEquals(0, comments.saved.size, "a draft is not persisted until it settles")

        service.cancel(draft.id) // trips the shared token and waits for the worker to settle the node

        val settled = comments.findById(draft.id)!!
        assertEquals(GenerationState.CANCELLED, settled.state)
        assertEquals(FailureCategory.CANCELLED, settled.failureCategory)
        assertEquals(1, comments.saved.size, "the cancelled node is persisted exactly once")
        assertNull(service.inFlightView(draft.id), "the in-flight entry is evicted once settled")
    }

    /** A roster of more than one persona so the "Anyone" dispatcher actually runs (it short-circuits a
     *  single-member roster). [find]/[findAll] are all the service needs of the repo here. */
    private fun roster(vararg ids: String) = object : PersonaRepository(JdbcTemplate()) {
        private val all = ids.map { Persona(it, it, "", "You are $it.") }
        override fun find(id: String) = all.firstOrNull { it.id == id }
        override fun findAll() = all
    }

    /** Replays scripted bodies through the single seam and records every request, so a test can assert
     *  both the outputs and HOW MANY calls were made (e.g. that the dispatcher was skipped). */
    private class ScriptedLlm(responses: List<String>) : LlmClient {
        private val deque = ArrayDeque(responses)
        val requests = mutableListOf<LlmRequest>()
        override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
            requests += request
            return LlmResponse(deque.removeFirst())
        }
    }

    @Test
    fun `the Anyone dispatcher summons everyone it names — breadth follows the pick, not a toggle`() {
        val llm = ScriptedLlm(listOf("Sol, Paul", "Sol's take", "Paul's take"))
        val service = GenerationService(llm, RecordingComments(), roster("Sol", "Paul"))

        val replies = service.generate("t1", null, listOf("auto"), "make these faster?")

        assertEquals(listOf("Sol", "Paul"), replies.map { it.authorId }, "Anyone keeps everyone the dispatcher named")
    }

    /** An in-memory repo with a live tree, so threadComments/childrenOf/ancestorPath reflect inserts —
     *  enough to exercise the round-aware, settle-time context re-read. */
    private class InMemoryComments : CommentRepository(JdbcTemplate(), Clock.systemUTC()) {
        val saved = mutableListOf<Comment>()
        override fun insert(c: Comment) { saved += c }
        override fun findById(id: String): Comment? = saved.lastOrNull { it.id == id }
        override fun threadComments(threadId: String): List<Comment> =
            saved.filter { it.threadId == threadId }.sortedBy { it.depth }
        override fun childrenOf(parentId: String?): List<Comment> = saved.filter { it.parentId == parentId }
        override fun ancestorPath(nodeId: String): List<Comment> {
            val path = mutableListOf<Comment>()
            var cur = findById(nodeId)
            while (cur != null) { path += cur; cur = cur.parentId?.let { findById(it) } }
            return path.reversed()
        }
    }

    @Test
    fun `a later persona in the round sees an earlier persona's posted reply`() {
        // Sequential fan-out: Sol settles, then Paul. Because context is re-read at settle time, Paul's
        // request must carry Sol's just-posted reply — the room reads as a conversation, not N blind takes.
        val llm = ScriptedLlm(listOf("Sol, Paul", "Sol's take", "Paul's take"))
        val service = GenerationService(llm, InMemoryComments(), roster("Sol", "Paul"))

        service.generate("t1", null, listOf("auto"), "make these faster?", postAsOwner = true)

        // requests[0] = dispatcher, [1] = Sol, [2] = Paul
        val solCtx = llm.requests[1].context
        assertFalse(solCtx.comments.any { it.authorId == "Paul" }, "the first persona can't see a reply not yet written")
        val paulCtx = llm.requests[2].context
        assertTrue(
            paulCtx.comments.any { it.authorId == "Sol" && it.body == "Sol's take" },
            "the second persona in the round sees the first's reply",
        )
    }

    @Test
    fun `branch-only scope still injects the round's posted siblings`() {
        // Branch-only is the ancestor path only, so a sibling reply isn't on it. Round-awareness folds the
        // round's posted siblings in anyway, so even a narrowed view reads as an exchange.
        val llm = ScriptedLlm(listOf("Sol, Paul", "Sol's take", "Paul's take"))
        val service = GenerationService(llm, InMemoryComments(), roster("Sol", "Paul"))

        service.generate("t1", null, listOf("auto"), "make these faster?", ScopeMode.BRANCH_ONLY, postAsOwner = true)

        val paulCtx = llm.requests[2].context
        assertTrue(
            paulCtx.comments.any { it.authorId == "Sol" && it.body == "Sol's take" },
            "branch-only still shows the round's earlier sibling reply",
        )
    }

    /**
     * A relation graph read from memory. Only [from] is overridden because the generation path asks the
     * graph exactly one question — "what does the persona about to speak think of everyone?" — and
     * deliberately never reads the incoming edges (a persona's prompt carries its own views, never the
     * room's views of it). The list is returned in `to_persona` order, matching the real repository's
     * explicit ORDER BY, so a test that asserted on rendered order would not be lying to itself.
     */
    private class ScriptedStances(private val edges: List<Stance>) :
        RelationStanceRepository(JdbcTemplate(), Clock.systemUTC()) {
        override fun from(fromId: String) = edges.filter { it.fromPersona == fromId }.sortedBy { it.toPersona }
    }

    private fun stance(from: String, to: String, text: String) =
        Stance(from, to, text, RelationStanceRepository.SOURCE_SEEDED, "2026-01-01T00:00:00Z")

    @Test
    fun `only stances toward personas present in the context reach the system prompt`() {
        // The point of the relation model is colouring how a persona speaks TO SOMEONE IN THE ROOM. Vex
        // holds views of both Sol (who has posted here) and Paul (who has not); pasting Paul's edge in
        // would be bulk noise about someone the model can't see — and, at 42 seeded edges, the failure
        // mode is a prompt of opinions about absent people. This filter is also what makes BRANCH_ONLY
        // narrow the stance set for free: a narrower context simply carries fewer authors.
        val comments = InMemoryComments().apply { insert(postedReply("c1", "Sol", "Indexes help here")) }
        val llm = ScriptedLlm(listOf("Hype aside, an index is cheap"))
        val service = GenerationService(
            llm, comments, roster("Vex", "Sol", "Paul"),
            stances = ScriptedStances(
                listOf(
                    stance("Vex", "Sol", "needles him about hype"),
                    stance("Vex", "Paul", "defers to him on frontends"),
                ),
            ),
        )

        service.generate("t1", null, listOf("Vex"), "", ScopeMode.WHOLE_THREAD)

        val prompt = llm.requests.single().context.personaSystemPrompt
        assertTrue(prompt.contains("needles him about hype"), "the stance toward the persona in the room is injected:\n$prompt")
        assertFalse(prompt.contains("defers to him on frontends"), "a stance toward an absent persona is noise:\n$prompt")
    }

    @Test
    fun `without a relation graph the system prompt is the persona's stored prompt, unchanged`() {
        // The regression guard for every construction that predates relations (and for a roster with no
        // edges): "no stances" must be indistinguishable from "no relation model at all" — not a header
        // dangling over zero bullets, not a stray blank line. Byte equality is the assertion precisely
        // because anything softer would let a formatting change slip into every prompt in the app.
        val comments = InMemoryComments().apply { insert(postedReply("c1", "Sol", "Indexes help here")) }
        val llm = ScriptedLlm(listOf("Hype aside, an index is cheap"))
        val service = GenerationService(llm, comments, roster("Vex", "Sol"))

        service.generate("t1", null, listOf("Vex"), "", ScopeMode.WHOLE_THREAD)

        assertEquals("You are Vex.", llm.requests.single().context.personaSystemPrompt)
    }

    /** The member's private memory store, in memory (persona-memory §2.9). Only [recordsOf] is
     *  overridden because injection asks exactly one question — "what does the persona about to
     *  speak remember?" — records-only, newest-first, the real repository's order. Mutable so the
     *  settle-time test can write a record WHILE a round is in flight. */
    private class ScriptedMemories(
        val rows: MutableList<com.aiforum.repo.PersonaMemory> = mutableListOf(),
    ) : com.aiforum.repo.PersonaMemoryRepository(JdbcTemplate(), Clock.systemUTC()) {
        override fun recordsOf(personaId: String) =
            rows.filter { it.personaId == personaId && it.kind == KIND_RECORD }
                .sortedWith(compareByDescending<com.aiforum.repo.PersonaMemory> { it.createdAt }.thenBy { it.id })
    }

    private fun memory(id: String, personaId: String, body: String, createdAt: String = "2026-01-01T00:00:00Z") =
        com.aiforum.repo.PersonaMemory(id, personaId, null, "record", body, "owner", createdAt)

    /** What a member is into, scripted — only [phrasesOf] is read on the injection path. */
    private class ScriptedInterests(private val phrases: Map<String, List<String>>) :
        com.aiforum.repo.PersonaInterestRepository(JdbcTemplate(), Clock.systemUTC()) {
        override fun phrasesOf(personaId: String) = phrases[personaId].orEmpty()
    }

    /** Replays scripted bodies with a side effect per call — how "the world moves mid-round" is
     *  driven: the Nth persona's own LLM call IS the instant an owner edit lands. */
    private class EffectLlm(responses: List<() -> String>) : LlmClient {
        private val deque = ArrayDeque(responses)
        val requests = mutableListOf<LlmRequest>()
        override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
            requests += request
            return LlmResponse(deque.removeFirst()())
        }
    }

    @Test
    fun `with a memory store wired but nothing matching, the system prompt is byte-identical`() {
        // The §2.9 parity pin in its sharper form: not merely "unwired repo changes nothing" (the
        // byte-equality test above already covers every unwired construction) but "a WIRED store
        // whose records share no words with the scoped context injects nothing" — zero when
        // irrelevant, no header, no blank line, or a formatting change slips into every prompt.
        val comments = InMemoryComments().apply { insert(postedReply("c1", "Sol", "Indexes help here")) }
        val llm = ScriptedLlm(listOf("Hype aside, an index is cheap"))
        val service = GenerationService(
            llm, comments, roster("Vex", "Sol"),
            memories = ScriptedMemories(mutableListOf(memory("m1", "Vex", "Prefers boring rollout habits"))),
        )

        service.generate("t1", null, listOf("Vex"), "", ScopeMode.WHOLE_THREAD)

        assertEquals("You are Vex.", llm.requests.single().context.personaSystemPrompt)
    }

    @Test
    fun `the persona-context blocks land in fixed order - system prompt, stances, interests, memories`() {
        // The fourth-block ordering (persona-memory §2.9), pinned as a prompt-string assertion: the
        // order is fixed by the listOfNotNull in withPersonaContext, not by which repository
        // happens to be wired, and an unrelated refactor that reorders it must redden here.
        val comments = InMemoryComments().apply { insert(postedReply("c1", "Sol", "Checkpoint stalls again")) }
        val llm = ScriptedLlm(listOf("That stall is familiar"))
        val service = GenerationService(
            llm, comments, roster("Vex", "Sol"),
            stances = ScriptedStances(listOf(stance("Vex", "Sol", "needles him about hype"))),
            interests = ScriptedInterests(mapOf("Vex" to listOf("kernel scheduling"))),
            memories = ScriptedMemories(
                mutableListOf(memory("m1", "Vex", "Watched checkpoint tuning eat a whole weekend")),
            ),
        )

        service.generate("t1", null, listOf("Vex"), "", ScopeMode.WHOLE_THREAD)

        val prompt = llm.requests.single().context.personaSystemPrompt
        assertTrue(prompt.startsWith("You are Vex."), "the stored prompt leads:\n$prompt")
        val stanceAt = prompt.indexOf("needles him about hype")
        val interestAt = prompt.indexOf("kernel scheduling")
        val memoryAt = prompt.indexOf("Watched checkpoint tuning eat a whole weekend")
        assertTrue(stanceAt in 1 until interestAt, "stances before interests:\n$prompt")
        assertTrue(interestAt < memoryAt, "interests before memories — the memory block is the fourth:\n$prompt")
        assertTrue(
            prompt.contains("Things you remember from past discussions here:"),
            "the memory frame opens the fourth block:\n$prompt",
        )
    }

    @Test
    fun `a record written between plan mint and settle reaches the later persona's prompt`() {
        // Recall is LIVE at settle time (persona-memory §2.9): context assembles under
        // GenPlan.contextOf when the reply settles, never when the plan was minted. Sol's own LLM
        // call is the instant a record lands in Paul's store — a plan-mint snapshot would miss it,
        // and Paul settles after Sol in the same round.
        val comments = InMemoryComments().apply {
            insert(postedReply("c1", "owner", "My checkpoint config keeps misbehaving"))
        }
        val store = ScriptedMemories()
        val llm = EffectLlm(
            listOf(
                {
                    store.rows += memory("m1", "Paul", "Spent a weekend chasing checkpoint stalls")
                    "Sol's take"
                },
                { "Paul's take" },
            ),
        )
        val service = GenerationService(llm, comments, roster("Sol", "Paul"), memories = store)

        service.generate("t1", null, listOf("Sol", "Paul"), "")

        val solPrompt = llm.requests[0].context.personaSystemPrompt
        assertFalse(
            solPrompt.contains("Spent a weekend chasing checkpoint stalls"),
            "the record did not exist when Sol settled",
        )
        val paulPrompt = llm.requests[1].context.personaSystemPrompt
        assertTrue(
            paulPrompt.contains("Spent a weekend chasing checkpoint stalls"),
            "the record written mid-round reaches the member that settles after it:\n$paulPrompt",
        )
    }

    @Test
    fun `an at-mention on the Anyone path summons that persona and skips the dispatcher`() {
        // Only one scripted body: if the dispatcher were consulted the deque would underflow.
        val llm = ScriptedLlm(listOf("Paul's take"))
        val service = GenerationService(llm, RecordingComments(), roster("Sol", "Paul"))

        val replies = service.generate("t1", null, listOf("auto"), "@Paul what do you think?")

        assertEquals(1, llm.requests.size, "an @mention pre-empts the dispatcher — no routing call")
        assertEquals("Paul", replies.single().authorId)
        assertEquals("Paul's take", replies.single().body)
    }

    /** An in-memory repo that backs the revision round-trip: holds comments + their revisions so the
     *  regenerate path runs end-to-end against fakes at the single LLM seam. */
    private class RevisioningComments : CommentRepository(JdbcTemplate(), Clock.systemUTC()) {
        val store = mutableMapOf<String, Comment>()
        private val revs = mutableMapOf<String, MutableList<Revision>>()
        override fun insert(c: Comment) { store[c.id] = c }
        override fun findById(id: String): Comment? = store[id]
        override fun threadComments(threadId: String): List<Comment> = store.values.filter { it.threadId == threadId }
        override fun ancestorPath(nodeId: String): List<Comment> = emptyList()
        override fun revisionCount(commentId: String): Int = revs[commentId]?.size ?: 0
        override fun addRevision(commentId: String, idx: Int, body: String, reasoningLeak: com.aiforum.dto.ReasoningLeak?, editedAt: java.time.Instant?) {
            revs.getOrPut(commentId) { mutableListOf() }.add(Revision(idx, body, reasoningLeak, editedAt))
        }
        override fun selectRevision(commentId: String, idx: Int): Boolean {
            val rev = revs[commentId]?.firstOrNull { it.idx == idx } ?: return false
            store[commentId] = store.getValue(commentId).copy(body = rev.body, reasoningLeak = rev.reasoningLeak, updatedAt = rev.editedAt, revisionIndex = idx)
            return true
        }
    }

    private fun postedReply(id: String, author: String, body: String) =
        Comment(id, "t1", null, author, body, GenerationState.POSTED, null, 0)

    @Test
    fun `regenerate appends a new revision, keeps the original, and shows the new take`() {
        val comments = RevisioningComments().apply { insert(postedReply("c1", "sol", "first take")) }
        val service = GenerationService(okLlm, comments, personas)   // okLlm answers "Indexes help here"

        val view = service.regenerate("c1")

        assertEquals("Indexes help here", view.body, "the node shows the regenerated take")
        assertEquals(2, view.revisionIndex, "the new take is the one shown (idx 1, 1-based 2)")
        assertEquals(2, comments.revisionCount("c1"), "the original (idx 0) and the new take (idx 1) are both stored")

        // The original is preserved — stepping back to revision 0 restores it in place.
        comments.selectRevision("c1", 0)
        assertEquals("first take", comments.findById("c1")!!.body)
    }

    @Test
    fun `a second regenerate appends a third revision without re-seeding the original`() {
        val comments = RevisioningComments().apply { insert(postedReply("c1", "sol", "first take")) }
        val service = GenerationService(okLlm, comments, personas)

        service.regenerate("c1")
        val second = service.regenerate("c1")

        assertEquals(3, comments.revisionCount("c1"), "first(0) + two regenerations(1,2) = 3 takes")
        assertEquals(3, second.revisionIndex, "the latest take is shown (idx 2, 1-based 3)")
    }

    @Test
    fun `a regeneration failure keeps the current take and appends no revision`() {
        val failing = object : LlmClient {
            override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse =
                throw LlmException.RateLimited(retryAfter = java.time.Duration.ZERO)
        }
        val comments = RevisioningComments().apply { insert(postedReply("c1", "sol", "keep me")) }
        val service = GenerationService(failing, comments, personas)

        val view = service.regenerate("c1")

        assertEquals("keep me", view.body, "a flaky model never destroys the live take")
        assertEquals(0, comments.revisionCount("c1"), "no revision is appended on failure")
    }

    @Test
    fun `regenerate rejects a non-posted reply`() {
        val draft = Comment("c1", "t1", null, "sol", "", GenerationState.DRAFTING, null, 0)
        val comments = RevisioningComments().apply { insert(draft) }
        val service = GenerationService(okLlm, comments, personas)

        assertThrows(IllegalArgumentException::class.java) { service.regenerate("c1") }
    }

    @Test
    fun `summonAsync routes the dispatcher on the worker, then settles the chosen persona`() {
        // The dispatcher's routing call (first scripted body) AND the persona's reply both run on the
        // worker — summonAsync returns at once without touching the LLM, which is the whole point: the
        // create request never blocks on the model.
        // Released the instant Sol's reply settles (the persist WRITE), so the await below is event-driven
        // rather than a wall-clock poll: the timeout is only a failsafe against a hung worker, never a
        // sampling interval.
        val solSettled = CountDownLatch(1)
        val llm = ScriptedLlm(listOf("Sol", "Sol's take"))
        val comments = RecordingComments { c ->
            if (c.authorId == "Sol" && c.state == GenerationState.POSTED) solSettled.countDown()
        }
        val registry = InFlightGenerations()
        val service = GenerationService(llm, comments, roster("Sol", "Paul"), registry)

        service.summonAsync("t1", null, listOf(GenerationService.AUTO_PERSONA), "")

        assertTrue(solSettled.await(5, TimeUnit.SECONDS), "Sol's reply should settle on the worker")

        assertTrue(llm.requests.any { it.persona.name == "Moderator" }, "the dispatcher routed on the worker")
        val sol = comments.saved.singleOrNull { it.authorId == "Sol" }
        assertEquals("Sol's take", sol?.body, "the persona the dispatcher picked drafted and settled")
        assertFalse(service.isSummoning("t1"), "the summon clears once routing + draft registration finish")
    }

    // --- issue #15: the settle writes the trace and carries the cost out -----------------------------

    /** One captured trace write, so the tier can assert WHAT was recorded and against WHICH node. */
    private data class TraceWrite(val runId: String, val commentId: String?, val calls: List<ToolCall>)

    /** The audit repository in memory; [throws] models an INSERT that fails at exactly the wrong moment. */
    private class RecordingTraces(private val throws: Boolean = false) :
        GenerationToolCallRepository(JdbcTemplate(), Clock.systemUTC()) {
        val writes = mutableListOf<TraceWrite>()
        override fun record(runId: String, commentId: String?, toolCalls: List<ToolCall>) {
            if (throws) throw IllegalStateException("simulated trace write failure")
            writes += TraceWrite(runId, commentId, toolCalls)
        }
    }

    /** A seam that reports a priced turn with a tool trace — what the real streaming CLI now returns. */
    private fun toolingLlm(costUsd: Double? = 0.12, vararg calls: ToolCall) = object : LlmClient {
        override fun generate(request: LlmRequest, cancellation: CancellationToken) =
            LlmResponse("Indexes help here", null, costUsd?.let { LlmUsage(costUsd = it) }, calls.toList())
    }

    private fun call(id: String, name: String) = ToolCall(id, name)

    @Test
    fun `a settled reply records its trace under the generation id and links the posted comment`() {
        val traces = RecordingTraces()
        val service = GenerationService(
            toolingLlm(calls = arrayOf(call("t1", "Read"), call("t2", "WebFetch"))),
            RecordingComments(), personas, toolCalls = traces,
        )

        val view = service.generate("t1", null, listOf("sol"), "q?", ScopeMode.WHOLE_THREAD).single()

        val write = traces.writes.single()
        assertEquals(view.id, write.runId, "the run id IS the settled node's id")
        assertEquals(view.id, write.commentId, "a POSTED reply links its trace")
        assertEquals(listOf("Read", "WebFetch"), write.calls.map { it.name }, "order is preserved")
    }

    @Test
    fun `the settled view carries the generation's cost out to the caller`() {
        val service = GenerationService(toolingLlm(costUsd = 0.12), RecordingComments(), personas)

        val view = service.generate("t1", null, listOf("sol"), "q?", ScopeMode.WHOLE_THREAD).single()

        assertEquals(0.12, view.costUsd)
    }

    @Test
    fun `a turn the provider did not price leaves the view's cost null, never zero`() {
        val service = GenerationService(toolingLlm(costUsd = null), RecordingComments(), personas)

        val view = service.generate("t1", null, listOf("sol"), "q?", ScopeMode.WHOLE_THREAD).single()

        assertNull(view.costUsd, "unknown must stay unknown all the way to the run row")
    }

    @Test
    fun `a failed settle records no trace at all`() {
        // The documented limitation: tool calls a turn made before it timed out are lost, because the only
        // place they exist is inside the parser owned by the seam that threw. Smuggling parser state out
        // through the exception would put audit plumbing into the failure taxonomy.
        val failing = object : LlmClient {
            override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse =
                throw LlmException.Timeout()
        }
        val traces = RecordingTraces()
        val service = GenerationService(failing, RecordingComments(), personas, toolCalls = traces)

        val view = service.generate("t1", null, listOf("sol"), "q?", ScopeMode.WHOLE_THREAD).single()

        assertEquals(GenerationState.FAILED, view.state)
        assertTrue(traces.writes.isEmpty())
        assertNull(view.costUsd)
    }

    @Test
    fun `a couldn't-save settle still records the trace, with a NULL comment id`() {
        // The generation SUCCEEDED and the model really did fetch things; only the write failed. The trace
        // is exactly what explains a reply the owner can see the draft of but has no row for — so it is
        // recorded, unlinked, rather than dropped along with the failed insert.
        val traces = RecordingTraces()
        val service = GenerationService(
            toolingLlm(calls = arrayOf(call("t1", "Read"))),
            FailingOnceComments(), personas, toolCalls = traces,
        )

        val view = service.generate("t1", null, listOf("sol"), "q?", ScopeMode.WHOLE_THREAD).single()

        assertEquals(FailureCategory.COULDNT_SAVE, view.failureCategory)
        val write = traces.writes.single()
        assertEquals(view.id, write.runId)
        assertNull(write.commentId, "an unsaveable reply's trace is kept unlinked, not thrown away")
    }

    @Test
    fun `a trace write that throws never fails the settle`() {
        // The reply is the product; the audit row is commentary on it. Losing a persona's answer because
        // an accounting INSERT tripped would be the tail wagging the dog.
        val comments = RecordingComments()
        val service = GenerationService(
            toolingLlm(calls = arrayOf(call("t1", "Read"))),
            comments, personas, toolCalls = RecordingTraces(throws = true),
        )

        val view = service.generate("t1", null, listOf("sol"), "q?", ScopeMode.WHOLE_THREAD).single()

        assertEquals(GenerationState.POSTED, view.state)
        assertEquals("Indexes help here", view.body)
        assertEquals(1, comments.saved.size, "the reply landed despite the trace failure")
    }

    @Test
    fun `a turn with no tool calls writes nothing — empty is an account, not a row`() {
        val traces = RecordingTraces()
        val service = GenerationService(okLlm, RecordingComments(), personas, toolCalls = traces)

        service.generate("t1", null, listOf("sol"), "q?", ScopeMode.WHOLE_THREAD)

        assertTrue(traces.writes.isEmpty(), "no INSERT at all for a turn that used no tools")
    }

    @Test
    fun `summonAsync hands the post-settle hook the settled views, cost included`() {
        val settled = CountDownLatch(1)
        val handed = java.util.concurrent.CopyOnWriteArrayList<ReplyView>()
        val hookRan = CountDownLatch(1)
        val service = GenerationService(
            toolingLlm(costUsd = 0.07),
            RecordingComments { if (it.state == GenerationState.POSTED) settled.countDown() },
            personas, InFlightGenerations(),
        )

        service.summonAsync("t1", null, listOf("sol"), "", onSettled = { handed += it; hookRan.countDown() })

        assertTrue(settled.await(5, TimeUnit.SECONDS), "the reply should settle on the worker")
        assertTrue(hookRan.await(5, TimeUnit.SECONDS), "the post-settle hook runs after the settle")
        assertEquals(listOf(0.07), handed.map { it.costUsd }, "the hook is handed priced views, not bare ids")
    }
}
