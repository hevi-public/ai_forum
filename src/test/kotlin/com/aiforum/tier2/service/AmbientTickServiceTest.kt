package com.aiforum.tier2.service

import com.aiforum.ambient.Article
import com.aiforum.ambient.ArticleSource
import com.aiforum.ambient.TickSource
import com.aiforum.dto.ScopeMode
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import com.aiforum.repo.AmbientRunRepository
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.ThreadRepository
import com.aiforum.service.AmbientTickService
import com.aiforum.service.GenerationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock

/**
 * Tier-2: [AmbientTickService] running its real orchestration over in-memory subclass fakes of the repos +
 * generation (the all-open plugin makes @Service/@Repository methods overridable — the same shape as
 * [GitHubPrIngestionServiceTest]). Pins exactly what the acceptance suite leaves unpinned, since it drives
 * only ONE tick: the round-robin author arithmetic across SEVERAL ticks, the failure-recording path (a
 * throwing source records 'failed' and never propagates), and the two no-op paths (null article / empty
 * roster). No IO seam is touched — the LLM never runs (the tick makes no call of its own; the summon is a
 * spy).
 */
@Tag("tier2")
class AmbientTickServiceTest {

    /** A scripted article queue; a per-call throw models a source fault. */
    private class FakeSource(articles: List<Article> = emptyList(), private val throws: Boolean = false) : ArticleSource {
        private val queue = ArrayDeque(articles)
        override fun next(): Article? {
            if (throws) throw RuntimeException("source exploded")
            return queue.removeFirstOrNull()
        }
    }

    private class RosterPersonas(private val roster: List<PersonaRepository.Persona>) :
        PersonaRepository(JdbcTemplate()) {
        override fun findAllByRowid() = roster
    }

    private class RecordingThreads : ThreadRepository(JdbcTemplate(), Clock.systemUTC()) {
        val inserted = mutableListOf<Triple<String, String, String?>>() // id, title, authorId
        override fun insert(id: String, title: String, body: String, authorId: String?) {
            inserted += Triple(id, title, authorId)
        }
    }

    private class RecordingRuns : AmbientRunRepository(JdbcTemplate(), Clock.systemUTC()) {
        val runs = mutableListOf<AmbientRun>()
        override fun count() = runs.size
        override fun record(
            source: TickSource,
            outcome: String,
            detail: String?,
            articleTitle: String?,
            articleUrl: String?,
            personaId: String?,
            threadId: String?,
            costUsd: Double?,
        ) {
            runs += AmbientRun(
                runs.size + 1L, "t", source.name.lowercase(), outcome, detail,
                articleTitle, articleUrl, personaId, threadId, costUsd,
            )
        }
    }

    private class SpyGeneration : GenerationService(
        object : LlmClient {
            override fun generate(request: LlmRequest, cancellation: CancellationToken) = LlmResponse("x")
        },
        CommentRepository(JdbcTemplate(), Clock.systemUTC()),
        PersonaRepository(JdbcTemplate()),
    ) {
        val summons = mutableListOf<String>()
        override fun summonAsync(
            threadId: String,
            parentId: String?,
            personaIds: List<String>,
            text: String,
            scope: ScopeMode,
            includeSiblings: Boolean,
            postAsOwner: Boolean,
            routingScope: ScopeMode,
        ) {
            summons += threadId
        }
    }

    private fun persona(id: String) = PersonaRepository.Persona(id, id, "", "")

    private fun article(title: String) = Article(title, "https://example.org/$title", "summary of $title")

    private fun service(source: ArticleSource, personas: PersonaRepository, threads: RecordingThreads, runs: RecordingRuns, gen: SpyGeneration) =
        AmbientTickService(source, personas, threads, runs, gen)

    @Test
    fun `the author rotates round-robin over the rowid roster, keyed by prior run count`() {
        val personas = RosterPersonas(listOf(persona("sol"), persona("vex")))
        val threads = RecordingThreads()
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        val source = FakeSource(listOf(article("A"), article("B"), article("C")))
        val svc = service(source, personas, threads, runs, gen)

        svc.tick(TickSource.MANUAL)   // count 0 -> roster[0] = sol
        svc.tick(TickSource.MANUAL)   // count 1 -> roster[1] = vex
        svc.tick(TickSource.MANUAL)   // count 2 -> roster[0] = sol (wraps)

        // Each thread carries its persona byline in round-robin order — never the owner.
        assertEquals(listOf("sol", "vex", "sol"), threads.inserted.map { it.third })
        // Three posted runs, each attributed to the same persona that authored the thread.
        assertEquals(listOf("posted", "posted", "posted"), runs.runs.map { it.outcome })
        assertEquals(listOf("sol", "vex", "sol"), runs.runs.map { it.personaId })
        // The room was summoned on each opened thread, in order.
        assertEquals(threads.inserted.map { it.first }, gen.summons)
    }

    @Test
    fun `a throwing article source records a failed run and never propagates`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val threads = RecordingThreads()
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        val svc = service(FakeSource(throws = true), personas, threads, runs, gen)

        svc.tick(TickSource.SCHEDULED)   // must NOT throw

        assertEquals(1, runs.runs.size)
        assertEquals("failed", runs.runs.single().outcome)
        assertEquals("source exploded", runs.runs.single().detail)
        assertTrue(threads.inserted.isEmpty(), "a failed tick opens no thread")
        assertTrue(gen.summons.isEmpty(), "a failed tick summons no one")
    }

    @Test
    fun `an empty roster records a no-op and opens no thread`() {
        val threads = RecordingThreads()
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        val svc = service(FakeSource(listOf(article("A"))), RosterPersonas(emptyList()), threads, runs, gen)

        svc.tick(TickSource.MANUAL)

        assertEquals("no-op", runs.runs.single().outcome)
        assertTrue(threads.inserted.isEmpty(), "no persona to author as → no thread")
        assertTrue(gen.summons.isEmpty(), "no-op summons no one")
    }

    @Test
    fun `a null article records a no-op and makes no summon`() {
        val threads = RecordingThreads()
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        val svc = service(FakeSource(emptyList()), RosterPersonas(listOf(persona("sol"))), threads, runs, gen)

        svc.tick(TickSource.MANUAL)

        val run = runs.runs.single()
        assertEquals("no-op", run.outcome)
        assertNull(run.personaId, "a no-op has no author")
        assertTrue(threads.inserted.isEmpty(), "nothing to post → no thread")
        assertTrue(gen.summons.isEmpty(), "nothing to post → no summon (no LLM call)")
    }
}
