package com.aiforum.tier2.service

import com.aiforum.config.StanceEvolutionProperties
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import com.aiforum.persona.PersonaPromptRefresher
import com.aiforum.persona.PersonaSpec
import com.aiforum.persona.PriorComposition
import com.aiforum.persona.PromptComposer
import com.aiforum.persona.StanceJudgePrompts
import com.aiforum.persona.StanceProse
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaExchange
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.RelationStanceRepository
import com.aiforum.repo.Stance
import com.aiforum.repo.StanceChange
import com.aiforum.repo.StanceChangeRepository
import com.aiforum.service.EvolutionSource
import com.aiforum.service.StanceEvolutionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock

/**
 * Tier-2: [StanceEvolutionService] running its real orchestration over in-memory subclass fakes of the
 * repositories plus a scripted [LlmClient] (the all-open plugin makes @Repository/@Service methods
 * overridable — the same shape as [AmbientTickServiceTest]). No mocking library, and the ONE IO seam is
 * the scripted client, so "how many judgments did this run buy?" is directly assertable.
 *
 * What this pins that the acceptance suite cannot: the acceptance scenarios drive one pass over one
 * pair, so the multi-pair behaviour — deterministic ordering, the cap, the recompose fan-out collapsing
 * to one call per holder — plus the two free skips (owner-authored, no stance row) costing ZERO seam
 * calls, and the window arithmetic that decides what a second run even looks at.
 */
@Tag("tier2")
class StanceEvolutionServiceTest {

    // --- fakes -------------------------------------------------------------------------------------

    private class RosterPersonas(private val roster: List<PersonaRepository.Persona>) :
        PersonaRepository(JdbcTemplate()) {
        override fun findAll() = roster
        override fun find(id: String) = roster.firstOrNull { it.id == id }
    }

    /** Serves a programmed exchange list and records the window each run asked for. */
    private class FakeComments(private val all: List<PersonaExchange> = emptyList()) :
        CommentRepository(JdbcTemplate(), Clock.systemUTC()) {
        val windowsAsked = mutableListOf<String?>()
        override fun exchangesSince(since: String?): List<PersonaExchange> {
            windowsAsked += since
            // Mirrors the repository's `created_at > ?` (a null window is all time), so a test can pin
            // the boundary arithmetic without a database.
            return if (since == null) all else all.filter { it.createdAt > since }
        }
    }

    private class FakeStances : RelationStanceRepository(JdbcTemplate(), Clock.systemUTC()) {
        val rows = LinkedHashMap<Pair<String, String>, Stance>()

        fun seed(from: String, to: String, stance: String, source: String = RelationStanceRepository.SOURCE_SEEDED) {
            rows[from to to] = Stance(from, to, stance, source, STAMP)
        }

        override fun find(from: String, to: String) = rows[from to to]
        override fun from(fromId: String) = rows.values.filter { it.fromPersona == fromId }
        override fun upsert(from: String, to: String, stance: String, source: String) {
            rows[from to to] = Stance(from, to, stance, source, STAMP)
        }
    }

    private class FakeChanges : StanceChangeRepository(JdbcTemplate(), Clock.systemUTC()) {
        val rows = mutableListOf<StanceChange>()

        fun seed(from: String, to: String, changedAt: String, revertedAt: String? = null) {
            rows += StanceChange(
                rows.size + 1L, from, to, "was", "now",
                RelationStanceRepository.SOURCE_SEEDED, "", changedAt, revertedAt,
            )
        }

        override fun record(
            fromPersona: String,
            toPersona: String,
            oldStance: String,
            newStance: String,
            oldSource: String,
            cited: String,
        ): Long {
            val id = rows.size + 1L
            rows += StanceChange(id, fromPersona, toPersona, oldStance, newStance, oldSource, cited, STAMP, null)
            return id
        }

        override fun recent(limit: Int) =
            rows.sortedWith(compareByDescending<StanceChange> { it.changedAt }.thenByDescending { it.id }).take(limit)

        override fun find(id: Long) = rows.firstOrNull { it.id == id }

        override fun markReverted(id: Long) {
            val idx = rows.indexOfFirst { it.id == id && it.revertedAt == null }
            if (idx >= 0) rows[idx] = rows[idx].copy(revertedAt = REVERT_STAMP)
        }

        override fun lastChangeAt() = rows.maxOfOrNull { it.changedAt }
    }

    /** A FIFO of scripted answers; an entry that throws models a seam fault (rate limit, timeout). */
    private class ScriptedLlm(answers: List<() -> String> = emptyList()) : LlmClient {
        private val queue = ArrayDeque(answers)
        val received = mutableListOf<LlmRequest>()
        override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
            received += request
            val next = queue.removeFirstOrNull()
            return LlmResponse(if (next != null) next() else "unscripted reply")
        }
    }

    private object InertComposer : PromptComposer {
        override fun compose(spec: PersonaSpec, prior: PriorComposition?, stances: List<StanceProse.NamedStance>) =
            "composed"
    }

    /**
     * Records the fan-out instead of composing. It also snapshots the graph AS SEEN at refresh time, so a
     * test can prove the recompose runs after every stance write of the run — recomposing a member off a
     * half-written graph would bake a stance the pass was about to replace into their stored prompt.
     */
    private class SpyRefresher(personas: PersonaRepository, stances: RelationStanceRepository) :
        PersonaPromptRefresher(personas, InertComposer, stances) {
        val refreshed = mutableListOf<String>()
        val seenAtRefresh = mutableListOf<List<String>>()
        override fun refresh(personaId: String): Boolean {
            refreshed += personaId
            seenAtRefresh += storedStances(personaId).map { it.text }
            return true
        }
    }

    // --- fixture helpers ---------------------------------------------------------------------------

    private fun persona(id: String) = PersonaRepository.Persona(id, id.replaceFirstChar { it.uppercase() }, "", "")

    private fun exchange(
        from: String,
        to: String,
        body: String = "$from wrote to $to",
        commentId: String = "c-$from-$to-$body".take(60),
        threadId: String = "t-1",
        createdAt: String = "2026-01-01T12:00:00Z",
    ) = PersonaExchange(commentId, threadId, "Rust in the kernel", from, to, body, "$to said something", createdAt)

    private fun says(vararg texts: String): List<() -> String> = texts.map { text -> { text } }

    private fun service(
        comments: FakeComments,
        personas: RosterPersonas,
        stances: FakeStances,
        changes: FakeChanges,
        llm: ScriptedLlm,
        refresher: SpyRefresher,
        props: StanceEvolutionProperties = StanceEvolutionProperties(),
    ) = StanceEvolutionService(comments, personas, stances, changes, llm, refresher, props)

    // --- the write path ----------------------------------------------------------------------------

    @Test
    fun `a qualifying pair is judged, audited old to new, and re-stamped evolved`() {
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply { seed("paul", "sol", "kindred pessimist") }
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("reads him now with an eyebrow already raised"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol", "This benchmark measures the wrong thing")))

        val changed = service(comments, personas, stances, changes, llm, refresher).evolve(EvolutionSource.MANUAL)

        assertEquals(1, changed)
        // The stance moved AND its provenance did: `evolved` is what tells a later pass this row is
        // system-derived rather than the owner's, and the seeder that it must not be refilled.
        val row = stances.rows.getValue("paul" to "sol")
        assertEquals("reads him now with an eyebrow already raised", row.stance)
        assertEquals(RelationStanceRepository.SOURCE_EVOLVED, row.source)
        // The audit captured BOTH the old text and the old provenance — without the latter, reverting
        // this seeded row would relabel it `evolved` and it would look system-authored forever.
        val audit = changes.rows.single()
        assertEquals("kindred pessimist", audit.oldStance)
        assertEquals("reads him now with an eyebrow already raised", audit.newStance)
        assertEquals(RelationStanceRepository.SOURCE_SEEDED, audit.oldSource)
        assertNull(audit.revertedAt, "a fresh audit row is not reverted")
        // One judgment, carrying the synthetic judge identity so a spy can tell it from a reply, and the
        // judge's own SYSTEM prompt rather than a persona's.
        val request = llm.received.single()
        assertEquals(StanceJudgePrompts.JUDGE_ID, request.persona.id)
        assertEquals(StanceJudgePrompts.JUDGE_NAME, request.persona.name)
        assertEquals(StanceJudgePrompts.SYSTEM, request.context.personaSystemPrompt)
        assertTrue(
            request.context.comments.single().body.contains("This benchmark measures the wrong thing"),
            "the judged exchange must reach the model: ${request.context.comments.single().body}",
        )
    }

    @Test
    fun `the audit cites the exchange by snapshotted prose plus its ids`() {
        // The audit IS the owner's control here, so it has to carry enough to judge the judgment: the
        // evidence as it read at the time (bodies are editable in place) AND the ids the page needs to
        // build a /threads/{thread}#reply-{comment} permalink back to it.
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply { seed("paul", "sol", "kindred pessimist") }
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("has started checking his claims"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(
            listOf(exchange("paul", "sol", "This benchmark measures the wrong thing", commentId = "c1", threadId = "th1")),
        )

        service(comments, personas, stances, changes, llm, refresher).evolve(EvolutionSource.MANUAL)

        val cited = StanceEvolutionService.parseCited(changes.rows.single().cited).single()
        assertEquals("c1", cited.commentId)
        assertEquals("th1", cited.threadId)
        assertEquals("This benchmark measures the wrong thing", cited.snippet)
    }

    // --- the two free skips ------------------------------------------------------------------------

    @Test
    fun `an owner-authored stance is skipped without a judgment call`() {
        // The never-clobber contract, enforced BEFORE the judgment: a room whose relations the owner has
        // taken over by hand is also a room this pass stops spending money on.
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply {
            seed(
                "paul", "sol", "Owner's note: Paul has decided Sol is worth listening to.",
                RelationStanceRepository.SOURCE_OWNER,
            )
        }
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("should never be asked for"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol")))

        val changed = service(comments, personas, stances, changes, llm, refresher).evolve(EvolutionSource.MANUAL)

        assertEquals(0, changed)
        assertTrue(llm.received.isEmpty(), "an owner-authored stance must cost no judgment at all")
        assertEquals(
            "Owner's note: Paul has decided Sol is worth listening to.",
            stances.rows.getValue("paul" to "sol").stance,
        )
        assertTrue(changes.rows.isEmpty(), "nothing changed, so nothing is audited")
        assertTrue(refresher.refreshed.isEmpty(), "no stance moved, so no prompt goes stale")
    }

    @Test
    fun `a pair with no stance row is skipped without a judgment call`() {
        // S4a evolves relationships; it does not invent edges the seed never authored.
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances()
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("should never be asked for"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol")))

        val changed = service(comments, personas, stances, changes, llm, refresher).evolve(EvolutionSource.MANUAL)

        assertEquals(0, changed)
        assertTrue(llm.received.isEmpty(), "no edge to move means no judgment to buy")
        assertTrue(stances.rows.isEmpty(), "the pass must not create the edge it was about to judge")
    }

    @Test
    fun `an exchange with a non-roster author never forms a pair`() {
        // Persona-ness is decided against the roster, not by the shape of an id: the owner and ingested
        // GitHub authors are excluded by simply not being on it. A string heuristic would admit `gh:`.
        val personas = RosterPersonas(listOf(persona("paul")))
        val stances = FakeStances().apply {
            seed("paul", "owner", "finds the owner hard to read")
            seed("gh:octocat", "paul", "seeded by accident")
        }
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("should never be asked for"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "owner"), exchange("gh:octocat", "paul")))

        val changed = service(comments, personas, stances, changes, llm, refresher).evolve(EvolutionSource.MANUAL)

        assertEquals(0, changed)
        assertTrue(llm.received.isEmpty(), "relations are persona-to-persona; the owner is a peer, not a node")
    }

    @Test
    fun `a pair below min-exchanges is never judged`() {
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply { seed("paul", "sol", "kindred pessimist") }
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("should never be asked for"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol")))

        val changed = service(
            comments, personas, stances, changes, llm, refresher,
            props = StanceEvolutionProperties(minExchanges = 2),
        ).evolve(EvolutionSource.MANUAL)

        assertEquals(0, changed)
        assertTrue(llm.received.isEmpty(), "one exchange under a two-exchange threshold buys no judgment")
    }

    // --- the guardrail and the failure posture -----------------------------------------------------

    @Test
    fun `a judgment carrying a number is refused and nothing is written`() {
        // The one place a number could enter the relation model is the judge's answer, so a digit-bearing
        // answer leaves the stance exactly where it was — and writes no audit row, because nothing
        // happened for the owner to review.
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply { seed("paul", "sol", "kindred pessimist") }
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("trust level 4 out of 5, down from 5"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol")))

        val changed = service(comments, personas, stances, changes, llm, refresher).evolve(EvolutionSource.MANUAL)

        assertEquals(0, changed)
        assertEquals("kindred pessimist", stances.rows.getValue("paul" to "sol").stance)
        assertTrue(changes.rows.isEmpty(), "a refused judgment is not a change, so it is not audited")
        assertTrue(refresher.refreshed.isEmpty(), "nothing moved, so no prompt is recomposed")
    }

    @Test
    fun `a judge failure leaves the graph untouched and the run returns normally`() {
        // The 04:00 rate-limit case: an unattended pass that dies and takes the run with it is worse than
        // one that records the failure and leaves every relationship standing.
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply { seed("paul", "sol", "kindred pessimist") }
        val changes = FakeChanges()
        val llm = ScriptedLlm(listOf({ throw RuntimeException("rate-limited") }))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol")))

        val changed = service(comments, personas, stances, changes, llm, refresher).evolve(EvolutionSource.SCHEDULED)

        assertEquals(0, changed, "the run must return, not throw")
        assertEquals("kindred pessimist", stances.rows.getValue("paul" to "sol").stance)
        assertTrue(changes.rows.isEmpty())
    }

    @Test
    fun `one pair's seam failure does not cost the next pair its judgment`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("paul"), persona("sol")))
        val stances = FakeStances().apply {
            seed("dana", "sol", "treats him as weather")
            seed("paul", "sol", "kindred pessimist")
        }
        val changes = FakeChanges()
        // Pairs are judged in (from, to) order, so dana's judgment is the one that explodes.
        val llm = ScriptedLlm(listOf({ throw RuntimeException("rate-limited") }, { "reads him with an eyebrow raised" }))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("dana", "sol"), exchange("paul", "sol")))

        val changed = service(comments, personas, stances, changes, llm, refresher).evolve(EvolutionSource.SCHEDULED)

        assertEquals(1, changed)
        assertEquals("treats him as weather", stances.rows.getValue("dana" to "sol").stance)
        assertEquals("reads him with an eyebrow raised", stances.rows.getValue("paul" to "sol").stance)
        assertEquals(listOf("paul"), refresher.refreshed, "only the member whose edge actually moved")
    }

    // --- cadence and fan-out -----------------------------------------------------------------------

    @Test
    fun `the edge cap clamps how many pairs a run judges`() {
        // The cap has to bound SPEND, not just successful writes: a cap that only counted the rows it
        // wrote would let a run make unlimited judgment calls, which is the cost the knob exists to hold.
        val personas = RosterPersonas(listOf(persona("dana"), persona("paul"), persona("sol")))
        val stances = FakeStances().apply {
            seed("dana", "sol", "treats him as weather")
            seed("paul", "sol", "kindred pessimist")
        }
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("has started listening properly", "reads him with an eyebrow raised"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("dana", "sol"), exchange("paul", "sol")))

        val changed = service(
            comments, personas, stances, changes, llm, refresher,
            props = StanceEvolutionProperties(maxEdgesPerRun = 1),
        ).evolve(EvolutionSource.SCHEDULED)

        assertEquals(1, changed)
        assertEquals(1, llm.received.size, "the cap bounds judgments, not just writes")
        // Deterministic (from, to) ordering, so a capped run always spends its budget on the same edge
        // rather than on whatever the query happened to return first.
        assertEquals("has started listening properly", stances.rows.getValue("dana" to "sol").stance)
        assertEquals("kindred pessimist", stances.rows.getValue("paul" to "sol").stance)
    }

    @Test
    fun `zero means unlimited, so every qualifying pair is judged`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("paul"), persona("sol")))
        val stances = FakeStances().apply {
            seed("dana", "sol", "treats him as weather")
            seed("paul", "sol", "kindred pessimist")
        }
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("has started listening properly", "reads him with an eyebrow raised"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("dana", "sol"), exchange("paul", "sol")))

        val changed = service(
            comments, personas, stances, changes, llm, refresher,
            props = StanceEvolutionProperties(maxEdgesPerRun = 0),
        ).evolve(EvolutionSource.SCHEDULED)

        assertEquals(2, changed)
        assertEquals(listOf("dana", "paul"), refresher.refreshed)
    }

    @Test
    fun `a holder whose two relations moved is recomposed once, after every write`() {
        val personas = RosterPersonas(listOf(persona("paul"), persona("saul"), persona("sol")))
        val stances = FakeStances().apply {
            seed("paul", "saul", "considers his prototypes evidence of nothing")
            seed("paul", "sol", "kindred pessimist")
        }
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("has warmed to his prototypes", "reads him with an eyebrow raised"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "saul"), exchange("paul", "sol")))

        val changed = service(comments, personas, stances, changes, llm, refresher).evolve(EvolutionSource.MANUAL)

        assertEquals(2, changed)
        // ONE recompose for the member, not one per moved edge: two calls would spend twice and the first
        // would compose against a graph the run was still rewriting.
        assertEquals(listOf("paul"), refresher.refreshed)
        assertEquals(
            listOf(listOf("has warmed to his prototypes", "reads him with an eyebrow raised")),
            refresher.seenAtRefresh,
            "the refresh must see the finished graph, not a half-written one",
        )
    }

    // --- the window --------------------------------------------------------------------------------

    @Test
    fun `the first run reads all time, and the next starts at the newest standing change`() {
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply { seed("paul", "sol", "kindred pessimist") }
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("reads him with an eyebrow raised"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol")))
        val svc = service(comments, personas, stances, changes, llm, refresher)

        svc.evolve(EvolutionSource.MANUAL)
        svc.evolve(EvolutionSource.MANUAL)

        // Nothing has ever evolved => all time; afterwards the pass only looks at what happened since it
        // last moved something, so a quiet forum re-judges nothing and costs nothing.
        assertEquals(listOf(null, STAMP), comments.windowsAsked)
        assertEquals(1, llm.received.size, "the second run had nothing new to judge")
    }

    @Test
    fun `a reverted change gives up its claim on the window`() {
        // Revert undoes the change IN FULL, including the boundary it set. Otherwise the exchanges behind
        // a judgment the owner rejected would sit permanently out of reach, and the edge could never be
        // reconsidered from that evidence — a revert would silently become a permanent opt-out.
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply { seed("paul", "sol", "kindred pessimist") }
        val changes = FakeChanges().apply { seed("paul", "sol", STAMP, revertedAt = REVERT_STAMP) }
        val llm = ScriptedLlm(says("reads him with an eyebrow raised"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol")))

        service(comments, personas, stances, changes, llm, refresher).evolve(EvolutionSource.MANUAL)

        assertEquals(listOf(null), comments.windowsAsked, "the only recorded change was undone, so read all time")
        assertEquals("reads him with an eyebrow raised", stances.rows.getValue("paul" to "sol").stance)
    }

    // --- revert ------------------------------------------------------------------------------------

    @Test
    fun `revert restores the old text AND the old provenance, and stamps the row`() {
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply { seed("paul", "sol", "kindred pessimist") }
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("reads him with an eyebrow raised"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol")))
        val svc = service(comments, personas, stances, changes, llm, refresher)
        svc.evolve(EvolutionSource.MANUAL)

        val reverted = svc.revert(changes.rows.single().id)

        assertTrue(reverted)
        val row = stances.rows.getValue("paul" to "sol")
        assertEquals("kindred pessimist", row.stance)
        // Restoring `seeded` is the point: leaving it `evolved` would relabel a hand-seeded row, and the
        // reverted edge is deliberately free to drift again rather than frozen.
        assertEquals(RelationStanceRepository.SOURCE_SEEDED, row.source)
        assertEquals(REVERT_STAMP, changes.rows.single().revertedAt)
        assertEquals(1, llm.received.size, "a revert restores captured values; it buys no judgment")
    }

    @Test
    fun `an unknown id and a second revert are both no-ops`() {
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply { seed("paul", "sol", "kindred pessimist") }
        val changes = FakeChanges().apply { seed("paul", "sol", STAMP) }
        val llm = ScriptedLlm()
        val refresher = SpyRefresher(personas, stances)
        val svc = service(FakeComments(), personas, stances, changes, llm, refresher)

        assertFalse(svc.revert(404L), "an unknown change reverts nothing")
        assertTrue(svc.revert(1L))
        assertFalse(svc.revert(1L), "the reverted_at stamp is the double-revert guard")
        // Still the stamp from the FIRST revert: re-stamping would move the record of when the owner
        // actually intervened to whenever they last double-clicked.
        assertEquals(REVERT_STAMP, changes.rows.single().revertedAt)
        assertTrue(llm.received.isEmpty(), "no revert path touches the seam")
    }

    private companion object {
        // The fakes stamp rather than read a Clock, so a test can assert an exact value; the real
        // repositories take these from the injected Clock, which is what keeps src/main at zero
        // Instant.now() reads.
        const val STAMP = "2026-01-01T12:00:00Z"
        const val REVERT_STAMP = "2026-01-02T12:00:00Z"
    }
}
