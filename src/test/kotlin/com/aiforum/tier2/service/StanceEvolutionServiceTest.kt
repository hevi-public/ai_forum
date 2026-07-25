package com.aiforum.tier2.service

import com.aiforum.config.StanceEvolutionProperties
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmException
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tier-2: [StanceEvolutionService] running its real orchestration over in-memory subclass fakes of the
 * repositories plus a scripted [LlmClient] (the all-open plugin makes @Repository/@Service methods
 * overridable — the same shape as [AmbientTickServiceTest]). No mocking library, and the ONE IO seam is
 * the scripted client, so "how many judgments did this run buy?" is directly assertable.
 *
 * What this pins that the acceptance suite cannot: the acceptance scenarios drive one pass over one
 * pair, so the multi-pair behaviour — window-age ordering, the cap ROTATING rather than starving the
 * same edges forever, the recompose fan-out collapsing to one call per holder — plus the two free skips
 * (owner-authored, no stance row) costing ZERO seam calls, and the per-edge window arithmetic that
 * decides what a second run even looks at. Several tests here run the pass TWICE for that reason: what
 * one run costs is only half the question, and "does a settled forum re-buy the same judgment every
 * night" is the other half.
 */
@Tag("tier2")
class StanceEvolutionServiceTest {

    // --- fakes -------------------------------------------------------------------------------------

    private class RosterPersonas(private val roster: List<PersonaRepository.Persona>) :
        PersonaRepository(JdbcTemplate()) {
        override fun findAll() = roster
        override fun find(id: String) = roster.firstOrNull { it.id == id }
    }

    /**
     * Serves a programmed exchange list. The service reads the history ONCE per run, coarsely bounded by
     * the oldest watermark in the graph, and then narrows PER EDGE in memory — so `since` is null
     * whenever any edge is unjudged and a floor otherwise. The real query's LEXICOGRAPHIC comparison is
     * mirrored exactly rather than parsed, because that is the property the service's floor margin has
     * to survive: a fraction-less stamp sorts after every sub-second stamp of the same second.
     */
    private class FakeComments(private val all: List<PersonaExchange> = emptyList()) :
        CommentRepository(JdbcTemplate(), Clock.systemUTC()) {
        val windows = mutableListOf<String?>()
        override fun exchangesSince(since: String?): List<PersonaExchange> {
            windows += since
            return if (since == null) all else all.filter { it.createdAt > since }
        }
    }

    /**
     * The relation graph in memory. Two behaviours are mirrored from the real repository because the
     * service leans on both: [upsert] must NOT touch `judged_at` (V26 — an authored write is not a
     * judgment), and [markJudged] must own it alone, including clearing it on null.
     *
     * [failUpsertFor] arms one edge's stance write to throw, which is how the transaction and the
     * per-edge isolation are driven without a database.
     */
    private class FakeStances(private val failUpsertFor: Pair<String, String>? = null) :
        RelationStanceRepository(JdbcTemplate(), Clock.systemUTC()) {
        val rows = LinkedHashMap<Pair<String, String>, Stance>()

        fun seed(
            from: String,
            to: String,
            stance: String,
            source: String = RelationStanceRepository.SOURCE_SEEDED,
            judgedAt: String? = null,
        ) {
            rows[from to to] = Stance(from, to, stance, source, STAMP, judgedAt)
        }

        fun judgedAt(from: String, to: String) = rows.getValue(from to to).judgedAt

        override fun find(from: String, to: String) = rows[from to to]
        override fun findAll() = rows.values.sortedWith(compareBy({ it.fromPersona }, { it.toPersona }))
        override fun from(fromId: String) = rows.values.filter { it.fromPersona == fromId }

        override fun upsert(from: String, to: String, stance: String, source: String) {
            if (from to to == failUpsertFor) throw IllegalStateException("database is locked")
            rows[from to to] = Stance(from, to, stance, source, STAMP, rows[from to to]?.judgedAt)
        }

        // A pair with no row is a no-op, like the real UPDATE … WHERE.
        override fun markJudged(from: String, to: String, at: String?) {
            rows[from to to]?.let { rows[from to to] = it.copy(judgedAt = at) }
        }
    }

    /** [failFor] arms one edge's audit write to throw — the other half of the isolation fixture. */
    private class FakeChanges(private val failFor: Pair<String, String>? = null) :
        StanceChangeRepository(JdbcTemplate(), Clock.systemUTC()) {
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
            if (fromPersona to toPersona == failFor) throw IllegalStateException("database is locked")
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

        // Mirrors the real per-edge query: the newest STANDING change for this pair, ignoring both
        // reverted rows and every other pair's history.
        override fun lastStandingChangeAt(fromPersona: String, toPersona: String) =
            rows.filter { it.fromPersona == fromPersona && it.toPersona == toPersona && it.revertedAt == null }
                .maxOfOrNull { it.changedAt }
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

    /**
     * A transaction manager that only RECORDS what the template asked for, so the service can run its
     * REAL [TransactionTemplate] here. In-memory fakes cannot be rolled back, so what Tier 2 can pin is
     * that the audit row, the stance write and the watermark stamp were submitted as ONE unit and that a
     * fault inside asks for a rollback rather than committing a change that never landed. The undo
     * itself is Spring's, and is pinned against a real DataSource by `CommentRepositoryTransactionTest`.
     */
    private class RecordingTransactions : PlatformTransactionManager {
        var commits = 0
        var rollbacks = 0
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

        override fun commit(status: TransactionStatus) {
            commits++
        }

        override fun rollback(status: TransactionStatus) {
            rollbacks++
        }
    }

    // --- fixture helpers ---------------------------------------------------------------------------

    private fun persona(id: String) = PersonaRepository.Persona(id, id.replaceFirstChar { it.uppercase() }, "", "")

    /** A fixed clock, the way the real service gets one — src/main reads no wall clock anywhere. */
    private fun clockAt(stamp: String) = Clock.fixed(Instant.parse(stamp), ZoneOffset.UTC)

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
        transactions: TransactionTemplate = TransactionTemplate(RecordingTransactions()),
        // An hour after the default exchange: the pass runs AFTER the conversation it judges, so the
        // watermark it stamps genuinely covers that evidence and a second run sees nothing new.
        clock: Clock = clockAt(RUN_STAMP),
    ) = StanceEvolutionService(comments, personas, stances, changes, llm, refresher, transactions, clock, props)

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
        // A rate limit is not a verdict: the evidence was never judged, so the window stays open and
        // tomorrow's run gets another look at it. Stamping here would make a busy provider look, to
        // every later run, exactly like a settled relationship.
        assertNull(stances.judgedAt("paul", "sol"), "a seam failure must not close the window")
    }

    @Test
    fun `one pair's seam failure does not cost the next pair its judgment`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("paul"), persona("sol")))
        val stances = FakeStances().apply {
            seed("dana", "sol", "treats him as weather")
            seed("paul", "sol", "kindred pessimist")
        }
        val changes = FakeChanges()
        // Neither edge has been judged, so the (from, to) tiebreak orders them and dana's judgment is
        // the one that explodes.
        val llm = ScriptedLlm(listOf({ throw RuntimeException("rate-limited") }, { "reads him with an eyebrow raised" }))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("dana", "sol"), exchange("paul", "sol")))

        val changed = service(comments, personas, stances, changes, llm, refresher).evolve(EvolutionSource.SCHEDULED)

        assertEquals(1, changed)
        assertEquals("treats him as weather", stances.rows.getValue("dana" to "sol").stance)
        assertEquals("reads him with an eyebrow raised", stances.rows.getValue("paul" to "sol").stance)
        assertEquals(listOf("paul"), refresher.refreshed, "only the member whose edge actually moved")
    }

    /**
     * The never-clobber contract has to survive the pass's own DURATION, which is the part a
     * single-pair test cannot reach. The pass reads the graph once (ordering and windows need one
     * consistent view), runs uncapped and synchronous, and spends up to a minute per judgment — so the
     * owner is looking at a hung browser tab for as long as the run takes, and the persona form in
     * another tab is exactly where they go while they wait.
     *
     * Here the owner pins paul→sol WHILE dana→sol is being judged. Nothing in the snapshot knows, so a
     * pass that trusts it writes the model's sentence over the owner's words with `SOURCE_EVOLVED`, and
     * the audit row it leaves cites the PRE-EDIT text — so Revert restores a sentence the owner never
     * wrote and their own words exist nowhere in the system. The stance row keeps no history: this is
     * unrecoverable, not merely wrong.
     */
    @Test
    fun `a stance the owner pins mid-pass is not overwritten by the pass already running`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("paul"), persona("sol")))
        val stances = FakeStances().apply {
            seed("dana", "sol", "treats him as weather")
            seed("paul", "sol", "kindred pessimist")
        }
        val changes = FakeChanges()
        // Neither edge is judged, so the (from, to) tiebreak puts dana first. The owner's edit lands
        // during dana's judgment — the scripted answer IS that moment — and paul→sol is what they pin.
        val llm = ScriptedLlm(
            listOf(
                {
                    stances.seed("paul", "sol", OWNER_PINNED, source = RelationStanceRepository.SOURCE_OWNER)
                    "has started listening properly"
                },
                { "reads him with an eyebrow already raised" },
            ),
        )
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("dana", "sol"), exchange("paul", "sol")))

        val changed = service(comments, personas, stances, changes, llm, refresher).evolve(EvolutionSource.MANUAL)

        val pinned = stances.rows.getValue("paul" to "sol")
        assertEquals(OWNER_PINNED, pinned.stance, "the owner's own words survive a pass that was already running")
        assertEquals(RelationStanceRepository.SOURCE_OWNER, pinned.source, "and keep their provenance")
        assertTrue(
            changes.rows.none { it.fromPersona == "paul" && it.toPersona == "sol" },
            "no audit row may claim a change to an edge the pass must not touch",
        )
        assertEquals(1, changed, "dana's edge still moves — one owner edit costs one edge, not the run")
        assertEquals(
            1, llm.received.size,
            "the skip is decided BEFORE the judgment, so a pinned edge costs nothing even mid-pass",
        )
        assertNull(stances.judgedAt("paul", "sol"), "an edge the pass never judged keeps its window open")
    }

    /**
     * The other half of the same race, against the other absolute in the class KDoc: "Never invent an
     * edge." Blanking the field on the persona form DELETES the row
     * (`PersonaController.applyStanceEdits`), and `upsert` is an `INSERT … ON CONFLICT` — so a pass
     * holding a stale snapshot resurrects the retracted edge as system-authored, and the owner's
     * retraction silently un-happens.
     */
    @Test
    fun `an edge the owner retracts mid-pass is not resurrected by the pass already running`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("paul"), persona("sol")))
        val stances = FakeStances().apply {
            seed("dana", "sol", "treats him as weather")
            seed("paul", "sol", "kindred pessimist")
        }
        val changes = FakeChanges()
        val llm = ScriptedLlm(
            listOf(
                {
                    stances.rows.remove("paul" to "sol")
                    "has started listening properly"
                },
                { "reads him with an eyebrow already raised" },
            ),
        )
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("dana", "sol"), exchange("paul", "sol")))

        val changed = service(comments, personas, stances, changes, llm, refresher).evolve(EvolutionSource.MANUAL)

        assertFalse(
            stances.rows.containsKey("paul" to "sol"),
            "a retracted edge stays retracted — the pass moves stances, it does not author them",
        )
        assertTrue(
            changes.rows.none { it.fromPersona == "paul" && it.toPersona == "sol" },
            "and leaves no audit row for an edge that no longer exists",
        )
        assertEquals(1, changed)
        assertEquals(1, llm.received.size, "a vanished edge is skipped before the judgment is bought")
    }

    /**
     * The STORAGE side of the same promise, and the one the LLM guard does not cover: a locked database
     * or a constraint violation on one edge's write must cost that edge and nothing else. Without a
     * per-edge catch it escapes to the run-level one, and the pass returns looking like it finished
     * while every edge still queued behind the bad one was silently abandoned — the more edges a room
     * has, the more of the run one fault eats.
     */
    @Test
    fun `one pair's repository failure does not abandon the rest of the run`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("paul"), persona("sol")))
        val stances = FakeStances().apply {
            seed("dana", "sol", "treats him as weather")
            seed("paul", "sol", "kindred pessimist")
        }
        val changes = FakeChanges(failFor = "dana" to "sol")
        val llm = ScriptedLlm(says("has started listening properly", "reads him with an eyebrow raised"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("dana", "sol"), exchange("paul", "sol")))

        val changed = service(comments, personas, stances, changes, llm, refresher).evolve(EvolutionSource.SCHEDULED)

        assertEquals(1, changed, "the run continues past the edge whose audit write threw")
        assertEquals("treats him as weather", stances.rows.getValue("dana" to "sol").stance, "nothing half-written")
        assertNull(stances.judgedAt("dana", "sol"), "a change that never landed must not close its window")
        assertEquals("reads him with an eyebrow raised", stances.rows.getValue("paul" to "sol").stance)
        assertEquals(listOf("paul"), refresher.refreshed)
    }

    /**
     * The audit row, the stance write and the watermark stamp are ONE unit of work. Committing the audit
     * row alone is the worst of both worlds: the owner reads a before→after that never happened, and
     * that row stands as the edge's history — the exact wall the per-edge window exists to prevent,
     * arrived at from the other side.
     *
     * `@Transactional` could not do this job here: the write path is a private method the service calls
     * on itself, and a Spring proxy sees neither — the annotation would read as a guarantee and do
     * nothing at all. So the service drives a real [TransactionTemplate], and what this pins is that it
     * asked for a rollback rather than a commit. The rollback itself is Spring's, over a real DataSource
     * (`CommentRepositoryTransactionTest`).
     */
    @Test
    fun `a stance write that fails rolls back the audit row rather than committing it alone`() {
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances(failUpsertFor = "paul" to "sol").apply { seed("paul", "sol", "kindred pessimist") }
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("reads him with an eyebrow raised"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol")))
        val manager = RecordingTransactions()

        val changed = service(
            comments, personas, stances, changes, llm, refresher,
            transactions = TransactionTemplate(manager),
        ).evolve(EvolutionSource.SCHEDULED)

        assertEquals(0, changed)
        assertEquals(1, manager.rollbacks, "the audit row must not be left standing for a change that failed")
        assertEquals(0, manager.commits)
        assertNull(stances.judgedAt("paul", "sol"), "and the failed change must not close the edge's window")
        assertTrue(refresher.refreshed.isEmpty(), "nothing moved, so no prompt is recomposed")
    }

    // --- single flight -----------------------------------------------------------------------------

    /**
     * `POST /admin/stances/evolve` runs the whole pass synchronously on the request thread, with no cap
     * by default and up to 60s per judgment — so the browser giving up and the owner clicking again is
     * the NORMAL way a second pass starts, not a hypothetical one. Both passes would then read the same
     * un-stamped edges: every judgment paid for twice, and a second audit row whose "before" text is the
     * first pass's "after", which is a before→after the room never went through.
     *
     * Two real threads with latches rather than a re-entrant call, because the guard's job is to hold
     * against a genuinely concurrent caller; the latches keep it deterministic (no sleeps, no polling).
     */
    @Test
    fun `a second caller while a pass is in flight does nothing at all`() {
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply { seed("paul", "sol", "kindred pessimist") }
        val changes = FakeChanges()
        val judging = CountDownLatch(1)
        val release = CountDownLatch(1)
        val llm = ScriptedLlm(
            listOf({
                judging.countDown()
                release.await(5, TimeUnit.SECONDS)
                "reads him with an eyebrow raised"
            }),
        )
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol")))
        val svc = service(comments, personas, stances, changes, llm, refresher)

        val first = thread(name = "stance-pass") { svc.evolve(EvolutionSource.MANUAL) }
        assertTrue(judging.await(5, TimeUnit.SECONDS), "the first pass should reach its judgment")

        val second = svc.evolve(EvolutionSource.MANUAL)

        assertEquals(0, second, "the second caller returns immediately, having changed nothing")
        assertEquals(1, llm.received.size, "and having bought no judgment")
        release.countDown()
        first.join(5_000)
        assertEquals(1, changes.rows.size, "one pass, one audit row — not one before→after that never happened")
        // The guard is released, so the NEXT click is a normal pass again rather than a permanent no-op.
        assertEquals(0, svc.evolve(EvolutionSource.MANUAL), "nothing new to judge, but the guard is open")
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
        // Neither edge has ever been judged, so their windows are equally old and the (from, to)
        // tiebreak decides — a capped run is reproducible rather than dependent on query order.
        assertEquals("has started listening properly", stances.rows.getValue("dana" to "sol").stance)
        assertEquals("kindred pessimist", stances.rows.getValue("paul" to "sol").stance)
    }

    /**
     * The cap must ROTATE, and this is the test the old (from, to) ordering could not pass. Take the
     * first N of a name-sorted list and dana→sol — which comes back unchanged, so it never records a
     * change — holds the single budget slot on every run, for good. paul→sol is not judged "later"; it
     * is never judged at all. Ordering by window age instead, dana is stamped by the judgment that read
     * her exchanges, drops to the back of the queue, and the next run reaches the edge behind her.
     */
    @Test
    fun `the cap rotates — an unchanged edge does not hold the budget forever`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("paul"), persona("sol")))
        val stances = FakeStances().apply {
            seed("dana", "sol", "treats him as weather")
            seed("paul", "sol", "kindred pessimist")
        }
        val changes = FakeChanges()
        // Run 1 answers dana with her own standing view (the steady state of a settled pair); run 2 gets
        // paul's edge — under the old ordering it would be dana's turn again, forever.
        val llm = ScriptedLlm(says("treats him as weather", "reads him with an eyebrow raised"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("dana", "sol"), exchange("paul", "sol")))
        val svc = service(
            comments, personas, stances, changes, llm, refresher,
            props = StanceEvolutionProperties(maxEdgesPerRun = 1),
        )

        svc.evolve(EvolutionSource.SCHEDULED)
        svc.evolve(EvolutionSource.SCHEDULED)

        assertEquals(2, llm.received.size, "two runs, two edges — one judgment each")
        assertEquals(
            "reads him with an eyebrow raised",
            stances.rows.getValue("paul" to "sol").stance,
            "the second run must reach the edge the first run's cap left behind",
        )
        assertEquals("treats him as weather", stances.rows.getValue("dana" to "sol").stance)
    }

    /**
     * The ordering itself, pinned — which the rotation test above does NOT do. There, the unchanged edge
     * drops out of candidacy because it was stamped, so the cap never has to choose and the comparator
     * could be anything. Here both edges qualify at once and the cap must pick one, so window age is the
     * only thing that decides: lune was judged at nine and dana at noon, both have newer exchanges, and
     * the older window goes first — the opposite of what (from, to) order would pick, since dana sorts
     * before lune alphabetically.
     */
    @Test
    fun `with both edges qualifying, the cap spends on the older window, not the earlier name`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("lune"), persona("sol")))
        val stances = FakeStances().apply {
            seed("dana", "sol", "treats him as weather", judgedAt = "2026-01-01T12:00:00Z")
            seed("lune", "sol", "says little, all of it lands", judgedAt = "2026-01-01T09:00:00Z")
        }
        val llm = ScriptedLlm(says("has started waiting for his reply before forming a view"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(
            listOf(
                exchange("dana", "sol", createdAt = "2026-01-01T12:30:00Z"),
                exchange("lune", "sol", createdAt = "2026-01-01T12:30:00Z"),
            ),
        )

        service(
            comments, personas, stances, FakeChanges(), llm, refresher,
            props = StanceEvolutionProperties(maxEdgesPerRun = 1),
        ).evolve(EvolutionSource.SCHEDULED)

        assertEquals(1, llm.received.size, "the cap allowed exactly one judgment")
        assertEquals(
            "has started waiting for his reply before forming a view",
            stances.rows.getValue("lune" to "sol").stance,
            "the edge waiting longest was judged",
        )
        assertEquals(
            "treats him as weather",
            stances.rows.getValue("dana" to "sol").stance,
            "the recently-judged edge waits its turn, however early its name sorts",
        )
    }

    /**
     * Characterising a KNOWN residual rather than asserting a desirable outcome, so the next reader finds
     * it here instead of in production.
     *
     * A refused answer deliberately does NOT close the window — that is the retry semantics the whole
     * per-edge window exists for. But an unstamped edge has a null window, and null sorts first, so an
     * edge whose judgments keep being refused keeps its place at the head of the queue. With a cap set,
     * it can therefore hold that slot indefinitely and the edge behind it is never reached.
     *
     * Accepted for now because the two ways out are both worse than the disease at this size: stamping a
     * refusal would silence exactly the case that most deserves another look, and ordering by
     * "last attempted" instead of "last judged" needs a second persisted timestamp whose only job is
     * fairness. The shipped default cap is unlimited, so nothing starves unless the owner sets one — and
     * an edge refusing every judgment is a signal worth noticing rather than quietly rotating past.
     */
    @Test
    fun `a persistently refused edge keeps its place in the queue — the price of retrying it`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("paul"), persona("sol")))
        val stances = FakeStances().apply {
            seed("dana", "sol", "treats him as weather")
            seed("paul", "sol", "kindred pessimist")
        }
        // Every answer carries a digit, so every judgment is refused and no window ever closes.
        val llm = ScriptedLlm(says("trust level 4 out of 5", "trust level 3 out of 5"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("dana", "sol"), exchange("paul", "sol")))
        val svc = service(
            comments, personas, stances, FakeChanges(), llm, refresher,
            props = StanceEvolutionProperties(maxEdgesPerRun = 1),
        )

        svc.evolve(EvolutionSource.SCHEDULED)
        svc.evolve(EvolutionSource.SCHEDULED)

        assertEquals(
            listOf("dana", "dana"),
            llm.received.map { it.context.comments.single().body.substringAfter("Member: ").substringBefore("\n") }
                .map { it.lowercase() },
            "both runs went to the same refused edge — documented, not desired",
        )
        assertEquals("kindred pessimist", stances.rows.getValue("paul" to "sol").stance, "and paul was never reached")
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
    fun `an edge that moved stops re-reading the exchanges that moved it`() {
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply { seed("paul", "sol", "kindred pessimist") }
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("reads him with an eyebrow raised"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol")))
        val svc = service(comments, personas, stances, changes, llm, refresher)

        svc.evolve(EvolutionSource.MANUAL)
        svc.evolve(EvolutionSource.MANUAL)

        assertEquals(1, llm.received.size, "the second run had nothing new to judge — a quiet forum is free")
        assertEquals(RUN_STAMP, stances.judgedAt("paul", "sol"), "the judgment closed the window it read")
    }

    /**
     * THE root defect, and the reason `persona_stance.judged_at` exists at all. The judge is instructed
     * to repeat a standing view when the exchanges do not move the attitude, so "unchanged" is the
     * DESIGNED steady state of a settled pair — and it writes no audit row, deliberately, because a
     * history page full of "nothing happened" is noise. Bound the window to the audit table alone and
     * that pair re-qualifies on the same exchanges every run and buys another judgment every night,
     * forever, across every settled edge in the room.
     *
     * Delete the `markJudged` on the unchanged branch and the second run below judges again: the
     * scripted queue is empty by then, so the fake answers something new and the stance moves.
     */
    @Test
    fun `an unchanged verdict writes nothing, yet still closes the window`() {
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply { seed("paul", "sol", "kindred pessimist") }
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("kindred pessimist"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol")))
        val svc = service(comments, personas, stances, changes, llm, refresher)

        val changed = svc.evolve(EvolutionSource.SCHEDULED)
        svc.evolve(EvolutionSource.SCHEDULED)

        assertEquals(0, changed)
        assertEquals("kindred pessimist", stances.rows.getValue("paul" to "sol").stance, "no stance write")
        assertTrue(changes.rows.isEmpty(), "nothing moved, so the owner's history page records nothing")
        assertTrue(refresher.refreshed.isEmpty(), "nothing moved, so no stored prompt went stale")
        assertEquals(RUN_STAMP, stances.judgedAt("paul", "sol"), "but the evidence WAS judged, and is closed")
        assertEquals(1, llm.received.size, "a settled pair must not re-buy the same judgment every run")
    }

    /**
     * The other half of the split: a REFUSED answer never told us what the judge thought of this
     * evidence (the model returned a digit, D6), so the window stays open and the same exchanges are
     * judged again next run. Stamping here would let one disobedient answer bury a conversation.
     */
    @Test
    fun `a refused answer leaves the window open, so the evidence is judged again`() {
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply { seed("paul", "sol", "kindred pessimist") }
        val changes = FakeChanges()
        val llm = ScriptedLlm(says("trust level 4 out of 5, down from 5", "reads him with an eyebrow raised"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol")))
        val svc = service(comments, personas, stances, changes, llm, refresher)

        svc.evolve(EvolutionSource.SCHEDULED)
        assertNull(stances.judgedAt("paul", "sol"), "a refused answer is not a judgment of the evidence")

        svc.evolve(EvolutionSource.SCHEDULED)

        assertEquals(2, llm.received.size, "the retry the per-edge window exists for")
        assertEquals("reads him with an eyebrow raised", stances.rows.getValue("paul" to "sol").stance)
    }

    /**
     * Instant.toString() prints no fraction on a whole second, so `"…:08Z"` sorts AFTER `"…:08.4Z"` as a
     * STRING while being earlier in time — and a fixed clock lands on a whole second every single time.
     * Compare the window lexicographically and every sub-second exchange of the watermark's own second
     * is walled off permanently, because the next watermark is later still. Nobody would ever see it.
     *
     * The coarse SQL floor is in the same picture: it drops a whole second below the oldest watermark
     * precisely so its lexicographic comparison cannot lose this row before the exact test sees it.
     */
    @Test
    fun `an exchange in the same second as the watermark is still judged`() {
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply {
            seed("paul", "sol", "kindred pessimist", judgedAt = "2026-01-01T12:00:08Z")
        }
        val llm = ScriptedLlm(says("reads him with an eyebrow already raised"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol", createdAt = "2026-01-01T12:00:08.400Z")))

        val changed = service(
            comments, personas, stances, FakeChanges(), llm, refresher,
            clock = clockAt("2026-01-01T12:00:09Z"),
        ).evolve(EvolutionSource.SCHEDULED)

        assertEquals(1, changed, "the exchange is 400ms AFTER the watermark, whatever the strings say")
        assertEquals("reads him with an eyebrow already raised", stances.rows.getValue("paul" to "sol").stance)
    }

    /**
     * The coarse floor is a read-size optimisation and must never decide anything: it is passed only
     * when EVERY edge has a window, because one never-judged edge legitimately needs all-time history,
     * and it is the OLDEST of them, because the per-edge test is what narrows each judgment afterwards.
     */
    @Test
    fun `the exchange read is bounded only once every edge has a window`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("paul"), persona("sol")))
        val stances = FakeStances().apply {
            seed("dana", "sol", "treats him as weather", judgedAt = STAMP)
            seed("paul", "sol", "kindred pessimist")
        }
        val llm = ScriptedLlm(says("reads him with an eyebrow raised"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("dana", "sol"), exchange("paul", "sol")))
        val svc = service(comments, personas, stances, FakeChanges(), llm, refresher)

        svc.evolve(EvolutionSource.SCHEDULED)

        assertEquals(
            listOf<String?>(null), comments.windows,
            "paul→sol has never been judged, so this run needs the whole history",
        )

        svc.evolve(EvolutionSource.SCHEDULED)

        // dana's watermark (STAMP) is the oldest of the two, less the one-second safety margin.
        assertEquals("2026-01-01T11:59:59Z", comments.windows.last(), "now every edge has a floor to share")
        assertEquals(1, llm.received.size, "and the narrower read still judges nothing it should not")
    }

    /**
     * The bug this pins is invisible in any single-pair test: with ONE global watermark, sol→vex's
     * successful change moves the boundary for paul→sol too, so the pair whose judgment failed never gets
     * another look at the very exchanges that were meant to move it. Rate limits are exactly the case D12
     * anticipates, which is what makes "somebody else's success ate my evidence" a real failure and not a
     * theoretical one.
     */
    @Test
    fun `a pair whose judgment failed is re-judged, even when another pair changed in the same run`() {
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol"), persona("vex")))
        val stances = FakeStances().apply {
            seed("paul", "sol", "kindred pessimist")
            seed("vex", "sol", "finds him tiring")
        }
        val changes = FakeChanges()
        // Both edges are unjudged, so the (from, to) tiebreak orders them: paul→sol goes first and its
        // seam blows up; vex→sol follows and succeeds, recording a change — and therefore a boundary,
        // under the old global scheme.
        val llm = ScriptedLlm(
            listOf(
                { throw LlmException.RateLimited(Duration.ofSeconds(30)) },
                { "has stopped pretending to find him tiring" },
                { "reads him with an eyebrow already raised" },
            ),
        )
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(listOf(exchange("paul", "sol"), exchange("vex", "sol")))
        val svc = service(comments, personas, stances, changes, llm, refresher)

        svc.evolve(EvolutionSource.MANUAL)
        svc.evolve(EvolutionSource.MANUAL)

        assertEquals(
            "reads him with an eyebrow already raised",
            stances.rows.getValue("paul" to "sol").stance,
            "paul→sol kept its own window and was judged again on the evidence its rate limit lost",
        )
    }

    @Test
    fun `a reverted change gives up its claim on the window — and reopens exactly what it read`() {
        // Revert undoes the change IN FULL, including the boundary it set: the judgment stamped this
        // edge, so leaving the stamp would put the exchanges the owner just disagreed about permanently
        // out of reach and turn one disagreement into a silent, permanent opt-out. And no further back
        // than that — the watermark returns to the newest change that STILL stands, because everything
        // before it was already acted on and the owner did not object to any of it.
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply { seed("paul", "sol", "kindred pessimist") }
        val changes = FakeChanges().apply { seed("paul", "sol", SURVIVING_CHANGE) }
        val llm = ScriptedLlm(says("reads him with an eyebrow raised", "has stopped pretending to tolerate him"))
        val refresher = SpyRefresher(personas, stances)
        val comments = FakeComments(
            listOf(
                exchange("paul", "sol", "old news, judged long ago", createdAt = "2026-01-01T08:00:00Z"),
                exchange("paul", "sol", "This benchmark measures the wrong thing"),
            ),
        )
        val svc = service(comments, personas, stances, changes, llm, refresher)
        svc.evolve(EvolutionSource.MANUAL)

        svc.revert(changes.rows.last().id)

        assertEquals(
            SURVIVING_CHANGE, stances.judgedAt("paul", "sol"),
            "the window rolls back to the surviving change, not to the beginning of time",
        )

        svc.evolve(EvolutionSource.MANUAL)

        assertEquals("has stopped pretending to tolerate him", stances.rows.getValue("paul" to "sol").stance)
        val rejudged = StanceEvolutionService.parseCited(changes.rows.last().cited).map { it.snippet }
        assertEquals(
            listOf("This benchmark measures the wrong thing"), rejudged,
            "the reverted judgment's own evidence is reopened; the exchange behind the surviving change is not",
        )
    }

    /**
     * A first run over a long history must not paste every comment a pair ever exchanged into one prompt.
     * The cap keeps the most RECENT exchanges, which are also the ones that describe how the relationship
     * stands now — so bounding it costs the judgment nothing it wanted.
     */
    @Test
    fun `the evidence handed to one judgment is bounded`() {
        val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
        val stances = FakeStances().apply { seed("paul", "sol", "kindred pessimist") }
        val llm = ScriptedLlm(says("reads him with an eyebrow raised"))
        val refresher = SpyRefresher(personas, stances)
        val history = (1..40).map { exchange("paul", "sol", body = "exchange number $it") }
        val comments = FakeComments(history)

        service(comments, personas, stances, FakeChanges(), llm, refresher).evolve(EvolutionSource.MANUAL)

        val prompt = llm.received.single().context.comments.single().body
        assertTrue(prompt.contains("exchange number 40"), "the newest exchange is kept")
        assertFalse(prompt.contains("exchange number 1 "), "the oldest exchanges are dropped, not truncated in place")
        assertTrue(prompt.length < 20_000, "an unbounded first run would build an arbitrarily large prompt")
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
        // With no surviving change left, the watermark is CLEARED rather than left standing: this edge
        // is back to never-judged, which is exactly the state the reverted judgment found it in.
        assertNull(row.judgedAt, "the reverted judgment gives back the window it took")
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

        /** An earlier audited change the owner did NOT revert — the floor a revert may roll back to. */
        const val SURVIVING_CHANGE = "2026-01-01T09:00:00Z"

        /**
         * What the SERVICE's own clock reads — the instant every watermark this suite stamps carries.
         * Deliberately later than the exchanges (`exchange(createdAt = …)` defaults to [STAMP]), because
         * that is the real sequence: the members talk, then the pass reads what they said.
         */
        const val RUN_STAMP = "2026-01-01T13:00:00Z"

        /** What the owner types on the persona form while a pass is already running. */
        const val OWNER_PINNED = "Owner's note: Paul has decided Sol is worth listening to."
    }
}
