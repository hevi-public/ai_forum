package com.aiforum.tier2.service

import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import com.aiforum.domain.Comment
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ScopeMode
import com.aiforum.repo.PersonaRepository.Persona
import com.aiforum.repo.RelationStanceRepository
import com.aiforum.repo.Stance
import com.aiforum.service.PersonaRouter
import com.aiforum.service.RoutingMetrics
import com.aiforum.service.RoutingOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock

/**
 * Tier-2: the "Anyone" dispatcher running real routing logic over a faked LlmClient (the single IO
 * seam). Pins that the model's free-text pick is turned into the right personas, and that every
 * failure mode falls back to the whole room so "Anyone" never picks no one.
 */
@Tag("tier2")
class PersonaRouterTest {

    private fun persona(name: String) = Persona(name, name, "$name's specialty", "You are $name.")
    private val roster = listOf(persona("Sol"), persona("Saul"), persona("Paul"), persona("Mira"))

    /** A seam that returns canned text and records whether it was called at all, plus what it was sent. */
    private class CannedLlm(private val text: String) : LlmClient {
        var calls = 0
        var lastRequest: LlmRequest? = null
        override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
            calls++
            lastRequest = request
            return LlmResponse(text)
        }
    }

    @Test
    fun `routes a prose answer to the named personas, most-relevant first`() {
        val router = PersonaRouter(CannedLlm("I'd let Paul and Sol take this one."))

        val chosen = router.pick(roster, emptyList()).map { it.name }

        assertEquals(listOf("Paul", "Sol"), chosen, "ordered by where each name appears in the reply")
    }

    @Test
    fun `word-boundary matching ignores names buried inside other words`() {
        // "solve"/"Paulson" must NOT match Sol/Paul — only the standalone name Mira should.
        val router = PersonaRouter(CannedLlm("Let's solve this; ask Paulson's neighbour Mira."))

        val chosen = router.pick(roster, emptyList()).map { it.name }

        assertEquals(listOf("Mira"), chosen)
    }

    @Test
    fun `caps the fan-out even when the model names everyone`() {
        val router = PersonaRouter(CannedLlm("Sol, Saul, Paul, Mira — all of them."))

        assertEquals(PersonaRouter.MAX_PICKS, router.pick(roster, emptyList()).size)
    }

    @Test
    fun `a roomful fan-out spans the agreeableness axis instead of three alike`() {
        // Four named — the cap is three. Without diversity we'd take the first three (Lead, Echo, Bui);
        // with it the redundant neutral (Echo) yields to the contrarian (Vex) so the room has friction.
        fun dialed(name: String, agreeableness: Int) =
            Persona(name, name, "$name's specialty", "You are $name.", dials = mapOf("agreeableness" to agreeableness))
        val diverseRoster = listOf(
            dialed("Lead", 5),
            dialed("Echo", 5),
            dialed("Bui", 9),
            dialed("Vex", 1),
        )
        val router = PersonaRouter(CannedLlm("Lead, Echo, Bui, Vex"))

        val chosen = router.pick(diverseRoster, emptyList()).map { it.name }

        assertEquals(listOf("Lead", "Bui", "Vex"), chosen)
    }

    @Test
    fun `falls back to the whole room when nothing parses`() {
        val router = PersonaRouter(CannedLlm("Hmm, hard to say."))

        assertEquals(roster, router.pick(roster, emptyList()), "an unparseable pick must not drop to no one")
    }

    @Test
    fun `falls back to the whole room when the seam fails`() {
        val router = PersonaRouter(object : LlmClient {
            override fun generate(request: LlmRequest, cancellation: CancellationToken) =
                throw LlmException.Timeout()
        })

        assertEquals(roster, router.pick(roster, emptyList()))
    }

    @Test
    fun `a lone persona is chosen without spending an LLM call`() {
        val llm = CannedLlm("unused")
        val router = PersonaRouter(llm)

        val only = listOf(persona("Sol"))
        assertEquals(only, router.pick(only, emptyList()))
        assertTrue(llm.calls == 0, "no point asking the model to choose the only candidate")
    }

    // --- Observability: each pick() records exactly one outcome (plan_docs/persona-routing-observability) ---

    /** Captures the events the router records so we can pin the four outcome buckets directly. */
    private class RecordingMetrics : RoutingMetrics {
        data class Event(
            val outcome: RoutingOutcome,
            val rosterSize: Int,
            val pickedCount: Int,
            val scope: ScopeMode,
            val rawReply: String?,
        )

        val events = mutableListOf<Event>()
        override fun record(
            outcome: RoutingOutcome,
            rosterSize: Int,
            pickedCount: Int,
            routingScope: ScopeMode,
            rawReply: String?,
        ) {
            events += Event(outcome, rosterSize, pickedCount, routingScope, rawReply)
        }
    }

    @Test
    fun `a clean pick records MATCHED with the picked count and no raw reply`() {
        val metrics = RecordingMetrics()
        PersonaRouter(CannedLlm("Paul and Sol"), metrics).pick(roster, emptyList())

        val event = metrics.events.single()
        assertEquals(RoutingOutcome.MATCHED, event.outcome)
        assertEquals(4, event.rosterSize)
        assertEquals(2, event.pickedCount, "two names were matched")
        assertNull(event.rawReply, "raw reply is kept only for parse misses")
    }

    @Test
    fun `an unparseable answer records WIDENED_NO_MATCH and keeps the raw reply`() {
        val metrics = RecordingMetrics()
        PersonaRouter(CannedLlm("Hmm, hard to say."), metrics).pick(roster, emptyList())

        val event = metrics.events.single()
        assertEquals(RoutingOutcome.WIDENED_NO_MATCH, event.outcome)
        assertEquals(roster.size, event.pickedCount, "widening routes to the whole room")
        assertEquals("Hmm, hard to say.", event.rawReply, "the miss is captured for eyeballing")
    }

    @Test
    fun `a seam failure records FAILED_GENERATION, not a parse miss`() {
        val metrics = RecordingMetrics()
        val router = PersonaRouter(object : LlmClient {
            override fun generate(request: LlmRequest, cancellation: CancellationToken) =
                throw LlmException.Timeout()
        }, metrics)

        router.pick(roster, emptyList())

        assertEquals(RoutingOutcome.FAILED_GENERATION, metrics.events.single().outcome)
    }

    @Test
    fun `a lone roster records SINGLE_PERSONA without calling the seam`() {
        val metrics = RecordingMetrics()
        val llm = CannedLlm("unused")
        PersonaRouter(llm, metrics).pick(listOf(persona("Sol")), emptyList())

        assertEquals(RoutingOutcome.SINGLE_PERSONA, metrics.events.single().outcome)
        assertEquals(0, llm.calls, "no LLM call means no routing decision to weigh in the rate")
    }

    @Test
    fun `the routing scope is recorded alongside the outcome`() {
        val metrics = RecordingMetrics()
        PersonaRouter(CannedLlm("Sol"), metrics).pick(roster, emptyList(), ScopeMode.BRANCH_ONLY)

        assertEquals(ScopeMode.BRANCH_ONLY, metrics.events.single().scope)
    }

    // --- Relations: the stances aimed at whoever is already talking reach the brief (ambient-slice-3) ---

    /**
     * The relation graph faked at the repository, not at a JdbcTemplate: the router only ever calls
     * [RelationStanceRepository.findAll], so overriding that one method keeps the fake honest about the
     * seam it stands in for. The `JdbcTemplate()` / [Clock] arguments are never touched — no query runs —
     * they only satisfy the constructor. Subclassing works because kotlin-spring's allopen plugin already
     * opens `@Repository` classes.
     */
    private fun graph(vararg rows: Stance) =
        object : RelationStanceRepository(JdbcTemplate(), Clock.systemUTC()) {
            override fun findAll(): List<Stance> = rows.toList()
        }

    private fun stance(from: String, to: String, text: String) =
        Stance(fromPersona = from, toPersona = to, stance = text, source = "seeded", updatedAt = "2026-07-25T00:00:00Z")

    private fun posted(author: String, body: String) =
        Comment("c-$author", "t1", null, author, body, GenerationState.POSTED, null, 0)

    @Test
    fun `a stance toward someone who has posted is folded into the dispatcher's brief`() {
        val llm = CannedLlm("Paul")
        val router = PersonaRouter(llm, stances = graph(stance("Paul", "Sol", "needles him about hype")))

        router.pick(roster, listOf(posted("Sol", "Indexes help here")))

        val prompt = llm.lastRequest!!.context.personaSystemPrompt
        assertTrue(
            prompt.contains("- Paul -> Sol: needles him about hype"),
            "expected the stance in the dispatcher's brief, which was:\n$prompt",
        )
    }

    @Test
    fun `with no relation repository wired the brief is the plain roster`() {
        val llm = CannedLlm("Sol")
        PersonaRouter(llm).pick(roster, listOf(posted("Sol", "Indexes help here")))

        assertFalse(
            llm.lastRequest!!.context.personaSystemPrompt.contains("Relations between participants"),
            "a null repository must degrade to exactly the pre-relations prompt",
        )
    }
}
