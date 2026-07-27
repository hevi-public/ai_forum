package com.aiforum.tier2.service

import com.aiforum.config.MemoryProperties
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import com.aiforum.persona.MemoryScribePrompts
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.MemoryChange
import com.aiforum.repo.MemoryChangeRepository
import com.aiforum.repo.PersonaExchange
import com.aiforum.repo.PersonaMemory
import com.aiforum.repo.PersonaMemoryRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.service.MemoryScribeService
import com.aiforum.service.ScribeSource
import com.aiforum.testsupport.LogCapture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tier-2: [MemoryScribeService] running its real orchestration over in-memory subclass fakes of the
 * repositories plus a scripted [LlmClient] — the [InterestDriftServiceTest] shape, one pass on. The
 * ONE faked IO seam is the scripted client, so *"how many judgments did this run buy?"* is directly
 * assertable.
 *
 * What this pins that the acceptance suite cannot: multi-member behaviour (rotation under a biting
 * cap, per-member isolation on both the seam and the storage side), the 90-day horizon on a
 * null-windowed member, the read-instant stamp under a clock that MOVES between read and write, the
 * blinkers as byte-identity, and the transaction boundary.
 *
 * Three structural guarantees ride along in the fakes rather than in a test of their own, because
 * that is where they cannot be forgotten:
 *
 * - **I3, both halves** — [RosterPersonas.update] AND [FakeMemories.insertRoot] fail the test if
 *   called at all: every identity-adjacent write path, not just one (the judged graft). `update` is
 *   the only writer of the immutable core; `insertRoot` is the only writer of the §2.3 root.
 * - **The anti-recompose pin** is the constructor: [MemoryScribeService] takes no composer and no
 *   refresher, so there is nothing to spy on — acquiring one would be a visible constructor edit.
 * - **I2** — nothing here fakes `AmbientRunRepository`, because the service cannot reach one: the
 *   constructor has no parameter for it (scenario 21 pins the behavioural half over HTTP).
 */
@Tag("tier2")
class MemoryScribeServiceTest {

    // --- fakes -------------------------------------------------------------------------------------

    /** The roster, and the I3 tripwire: `update` is the pass's only conceivable route to a member's
     *  immutable core, so calling it is a test failure rather than an assertion to remember. */
    private class RosterPersonas(private val roster: List<PersonaRepository.Persona>) :
        PersonaRepository(JdbcTemplate()) {
        override fun findAll() = roster
        override fun find(id: String) = roster.firstOrNull { it.id == id }

        override fun update(
            id: String,
            name: String,
            descriptor: String,
            model: String,
            systemPrompt: String,
            abilities: List<String>,
            dials: Map<String, Int>,
        ) {
            fail<Unit>("the scribe pass has no write path to a member's character — update($id) must never be called")
        }
    }

    /** Serves a programmed engagement list and records every `since` floor the service asked with —
     *  which is how the horizon test reads what would have been materialised. */
    private class FakeComments(private val all: List<PersonaExchange> = emptyList()) :
        CommentRepository(JdbcTemplate(), Clock.systemUTC()) {
        val windows = mutableListOf<String?>()
        override fun exchangesSince(since: String?): List<PersonaExchange> {
            windows += since
            return if (since == null) all else all.filter { it.createdAt > since }
        }
    }

    /**
     * The memory tree in memory. Mirrors the four behaviours the service leans on: `recordsOf` is
     * records-only, newest-first with the id tiebreak (the letter list and the duplicate check are
     * built from this order); `insertScribeRecord` hard-codes provenance and honours the belt;
     * `deleteRecord` reparents-then-deletes; `judgedAt`/`markJudged` own the watermark alone.
     *
     * [failInsertFor] arms one member's record write to throw — how the transaction boundary and
     * the per-member isolation are driven without a database. **[insertRoot] fails the test if the
     * pass ever reaches it** (I3's second half): tests that need a root SEED one via [seed], which
     * writes the row list directly the way hand SQL would.
     */
    private class FakeMemories(private val failInsertFor: String? = null) :
        PersonaMemoryRepository(JdbcTemplate(), Clock.systemUTC()) {
        val rows = mutableListOf<PersonaMemory>()
        private val judged = mutableMapOf<String, Instant?>()
        private var seq = 0

        /** Fixture setup, deliberately NOT via the insert doors: a root has to be seedable even
         *  though [insertRoot] is a tripwire, and created-at stamps have to be choosable. */
        fun seed(
            personaId: String,
            body: String,
            source: String = PersonaMemoryRepository.SOURCE_OWNER,
            parentId: String? = null,
            kind: String = KIND_RECORD,
            createdAt: String = "2026-01-01T00:0${seq % 10}:00Z",
        ): String {
            val id = "m-${seq++}-$personaId"
            rows += PersonaMemory(id, personaId, parentId, kind, body, source, createdAt)
            return id
        }

        /** Pre-stamp a member's watermark — what a member judged in an earlier run looks like. */
        fun judgedSince(personaId: String, at: String) = markJudged(personaId, at)

        fun bodiesOf(personaId: String) = recordsOf(personaId).map { it.body }

        override fun recordsOf(personaId: String) =
            rows.filter { it.personaId == personaId && it.kind == KIND_RECORD }
                .sortedWith(compareByDescending<PersonaMemory> { it.createdAt }.thenBy { it.id })

        override fun rootOf(personaId: String) =
            rows.firstOrNull { it.personaId == personaId && it.kind == KIND_ROOT }

        override fun find(id: String) = rows.firstOrNull { it.id == id }

        override fun insertScribeRecord(personaId: String, body: String, parentId: String?, id: String): String {
            if (personaId == failInsertFor) throw IllegalStateException("database is locked")
            rows += PersonaMemory(
                id, personaId, parentId, KIND_RECORD, body, SOURCE_SCRIBE, "2026-01-01T06:00:00Z",
            )
            return id
        }

        override fun insertOwnerRecord(personaId: String, body: String, parentId: String?, id: String): String {
            rows += PersonaMemory(
                id, personaId, parentId, KIND_RECORD, body, SOURCE_OWNER, "2026-01-01T06:00:00Z",
            )
            return id
        }

        override fun insertRoot(personaId: String, body: String, id: String): String {
            fail<Unit>("the scribe pass has no write path to the root — insertRoot($personaId) must never be called")
            return id
        }

        override fun deleteRecord(id: String) {
            val row = rows.firstOrNull { it.id == id } ?: return
            rows.replaceAll { if (it.parentId == id) it.copy(parentId = row.parentId) else it }
            rows.removeAll { it.id == id }
        }

        override fun judgedAt(personaId: String): Instant? = judged[personaId]

        override fun markJudged(personaId: String, at: String) {
            judged[personaId] = Instant.parse(at)
        }
    }

    /** [failFor] arms one member's audit write to throw — the other half of the isolation fixture. */
    private class FakeMemoryChanges(private val failFor: String? = null) :
        MemoryChangeRepository(JdbcTemplate(), Clock.systemUTC()) {
        val rows = mutableListOf<MemoryChange>()

        override fun record(
            personaId: String,
            memoryId: String,
            body: String,
            parentBody: String?,
            cited: String,
            readAt: String,
        ): Long {
            if (personaId == failFor) throw IllegalStateException("database is locked")
            val id = rows.size + 1L
            rows += MemoryChange(id, personaId, memoryId, body, parentBody, cited, readAt, STAMP, null)
            return id
        }

        override fun recent(limit: Int) =
            rows.sortedWith(compareByDescending<MemoryChange> { it.changedAt }.thenByDescending { it.id })
                .take(limit)

        override fun find(id: Long) = rows.firstOrNull { it.id == id }

        override fun markReverted(id: Long) {
            val at = rows.indexOfFirst { it.id == id && it.revertedAt == null }
            if (at >= 0) rows[at] = rows[at].copy(revertedAt = REVERT_STAMP)
        }
    }

    /** A FIFO of scripted answers; an entry that throws models a seam fault (rate limit, timeout). */
    private class ScriptedLlm(answers: List<() -> String> = emptyList()) : LlmClient {
        private val queue = ArrayDeque(answers)
        val received = mutableListOf<LlmRequest>()
        override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
            received += request
            val next = queue.removeFirstOrNull()
            // An unscripted call answers something the parse cannot use, so a run that buys a
            // judgment nobody expected shows up as an assertion failure, not a silent extra write.
            return LlmResponse(if (next != null) next() else "unscripted reply")
        }
    }

    /** Records what the template asked for; the service runs its REAL [TransactionTemplate] over it.
     *  In-memory fakes cannot be rolled back, so what Tier 2 pins is that the audit row, the insert
     *  and the stamp were submitted as ONE unit, and that a fault inside asks for a rollback. */
    private class RecordingTransactions : PlatformTransactionManager {
        var commits = 0
        var rollbacks = 0
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
        override fun commit(status: TransactionStatus) { commits++ }
        override fun rollback(status: TransactionStatus) { rollbacks++ }
    }

    /**
     * A clock that hands out [instants] in order, then keeps returning the last one — how the
     * read-instant test moves time between the pass's evidence read and its writes without any
     * wall-clock dependence. The service reads its clock exactly once per run (that is the
     * contract under test), so the sequence IS the timeline.
     */
    private class SteppingClock(private val instants: List<Instant>) : Clock() {
        private var next = 0
        override fun instant(): Instant = instants[minOf(next++, instants.size - 1)]
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    // --- fixture helpers ---------------------------------------------------------------------------

    private fun persona(id: String) = PersonaRepository.Persona(
        id, id.replaceFirstChar { it.uppercase() }, "$id, who reads storage papers for fun", "",
    )

    private fun clockAt(stamp: String) = Clock.fixed(Instant.parse(stamp), ZoneOffset.UTC)

    private fun exchange(
        from: String,
        to: String,
        body: String,
        createdAt: String = TALKED_AT,
    ) = PersonaExchange(
        "c-$from-${body.take(12)}", "th1", "Rust in the kernel", from, to, body,
        "the article's own summary, which the scribe must never see", createdAt,
    )

    /** The shipped engagement floor is three, so a qualifying fixture needs three. The addressee is
     *  deliberately OFF the roster: evidence is what the member lived through, and the other side's
     *  membership is not what qualifies it. */
    private fun spokeThrice(from: String, to: String = "owner", createdAt: String = TALKED_AT) = listOf(
        exchange(from, to, "The scheduler is the interesting part", createdAt),
        exchange(from, to, "Preemption cost decides this", createdAt),
        exchange(from, to, "Nobody benchmarks the wake-up path", createdAt),
    )

    private fun says(vararg texts: String): List<() -> String> = texts.map { text -> { text } }

    private fun remember(body: String, extends: String? = null): String =
        "REMEMBER: $body" + (extends?.let { "\nEXTENDS: $it" } ?: "")

    private fun service(
        comments: FakeComments,
        personas: RosterPersonas,
        memories: FakeMemories,
        changes: FakeMemoryChanges,
        llm: ScriptedLlm,
        props: MemoryProperties = MemoryProperties(),
        transactions: TransactionTemplate = TransactionTemplate(RecordingTransactions()),
        // An hour after the engagements: the pass runs AFTER the conversation it judges, so the
        // watermark it stamps genuinely covers that evidence and a second run sees nothing new.
        clock: Clock = clockAt(RUN_STAMP),
    ) = MemoryScribeService(comments, personas, memories, changes, llm, transactions, clock, props)

    // --- the write path, and the read-instant stamp -------------------------------------------------

    @Test
    fun `a qualifying member gets one record, one audit row carrying read_at, and a closed window`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories()
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("Learned that preemption arguments never really end")))
        val comments = FakeComments(spokeThrice("sol"))

        val written = service(comments, personas, memories, changes, llm).consolidate(ScribeSource.MANUAL)

        assertEquals(1, written)
        val record = memories.recordsOf("sol").single()
        assertEquals("Learned that preemption arguments never really end", record.body)
        assertEquals(PersonaMemoryRepository.SOURCE_SCRIBE, record.source, "provenance at birth")
        assertNull(record.parentId, "no EXTENDS line means top-level attachment")
        val audit = changes.rows.single()
        assertEquals("sol", audit.personaId)
        assertEquals(record.id, audit.memoryId, "the audit row names the row it wrote")
        assertEquals(record.body, audit.body, "the snapshot is the record as written")
        assertEquals(RUN_STAMP, audit.readAt, "read_at carries the pre-query read instant (§2.6)")
        assertEquals(Instant.parse(RUN_STAMP), memories.judgedAt("sol"), "a usable verdict closes the window")
        // One judgment, carrying the synthetic scribe identity and the scribe's own SYSTEM prompt.
        val request = llm.received.single()
        assertEquals(MemoryScribePrompts.SCRIBE_ID, request.persona.id)
        assertEquals(MemoryScribePrompts.SCRIBE_NAME, request.persona.name)
        assertEquals(MemoryScribePrompts.SYSTEM, request.context.personaSystemPrompt)
        val instruction = request.context.comments.single().body
        assertTrue(
            instruction.contains("Nobody benchmarks the wake-up path"),
            "the member's own exchanges are the evidence: $instruction",
        )
        assertFalse(
            instruction.contains("the article's own summary"),
            "what the member was ANSWERING is fetched, untrusted text and must not reach the scribe",
        )
    }

    @Test
    fun `the watermark is stamped with the READ instant, not the instant the write happened`() {
        // The bed019fe contract, driven with a clock that MOVES between the evidence read and the
        // writes: the service reads its clock once, before the engagement query, and every stamp —
        // watermark and audit read_at alike — must carry THAT instant. Stamping from a post-LLM
        // clock read would leave anything posted during the sixty-second call behind a watermark
        // that never saw it, permanently.
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories()
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("Learned that preemption arguments never really end")))
        val comments = FakeComments(spokeThrice("sol"))
        val clock = SteppingClock(listOf(Instant.parse(RUN_STAMP), Instant.parse(LATER_STAMP)))

        service(comments, personas, memories, changes, llm, clock = clock).consolidate(ScribeSource.MANUAL)

        assertEquals(
            Instant.parse(RUN_STAMP), memories.judgedAt("sol"),
            "the stamp must be the read instant — a later clock read must never leak into it",
        )
        assertEquals(RUN_STAMP, changes.rows.single().readAt, "and the audit row records the same instant")
    }

    // --- the five §2.5 postures, three stamp behaviours ----------------------------------------------

    @Test
    fun `NOTHING writes no record and no audit row, and still closes the window`() {
        // The V26 cost lesson: NOTHING is the designed steady state and writes no audit row to
        // advance a window with. Delete the markJudged on this branch and the second run below
        // judges again — the scripted record is waiting for it, and this test reddens.
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories()
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says("NOTHING", remember("A decoy the closed window must never buy")))
        val comments = FakeComments(spokeThrice("sol"))
        val svc = service(comments, personas, memories, changes, llm)

        val written = svc.consolidate(ScribeSource.SCHEDULED)
        svc.consolidate(ScribeSource.SCHEDULED)

        assertEquals(0, written)
        assertTrue(memories.recordsOf("sol").isEmpty(), "no record")
        assertTrue(changes.rows.isEmpty(), "nothing happened, so the owner's history records nothing")
        assertEquals(Instant.parse(RUN_STAMP), memories.judgedAt("sol"), "but the evidence WAS judged")
        assertEquals(1, llm.received.size, "a settled member must not re-buy the same judgment every run")
    }

    @Test
    fun `a duplicate of an owner-authored record is refused as a row and still closes the window`() {
        // D5 both halves: inserting would be noise the owner has to weed; treating it as Rejected
        // would re-buy the identical judgment weekly (the exact V26 shape). The fold is
        // case-insensitive via MemoryText's canonical fold — the DB collation never participates.
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories().apply {
            seed("sol", "Trusts boring rollouts more than clever ones")
        }
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(
            says(
                remember("TRUSTS BORING ROLLOUTS MORE THAN CLEVER ONES"),
                remember("A decoy the closed window must never buy"),
            ),
        )
        val comments = FakeComments(spokeThrice("sol"))
        val svc = service(comments, personas, memories, changes, llm)

        svc.consolidate(ScribeSource.SCHEDULED)
        svc.consolidate(ScribeSource.SCHEDULED)

        assertEquals(listOf("Trusts boring rollouts more than clever ones"), memories.bodiesOf("sol"))
        assertEquals(
            PersonaMemoryRepository.SOURCE_OWNER, memories.recordsOf("sol").single().source,
            "the owner's row is untouched, provenance included",
        )
        assertTrue(changes.rows.isEmpty(), "a refused row is not a change, so it is not audited")
        assertEquals(Instant.parse(RUN_STAMP), memories.judgedAt("sol"), "the model did its job: stamped")
        assertEquals(1, llm.received.size, "and the second run bought nothing")
    }

    @Test
    fun `a duplicate of the ROOT is refused too - the fold covers every held row`() {
        // §2.5 names the root as a collision target: a scribe record repeating the member's own
        // root verbatim is noise twice over. Stamps, like every duplicate.
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories().apply {
            seed("sol", "Grew up fixing farm machinery", kind = PersonaMemoryRepository.KIND_ROOT)
        }
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("Grew up fixing farm machinery")))
        val comments = FakeComments(spokeThrice("sol"))

        val written = service(comments, personas, memories, changes, llm).consolidate(ScribeSource.MANUAL)

        assertEquals(0, written)
        assertTrue(memories.recordsOf("sol").isEmpty(), "no record row landed beside the root")
        assertEquals(Instant.parse(RUN_STAMP), memories.judgedAt("sol"), "a duplicate is a usable answer")
    }

    @Test
    fun `a rating-shaped answer is rejected, writes nothing, and leaves the window OPEN`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories()
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(
            says(
                "REMEMBER: Keeps a mental list of storage tricks\nimportance: high, 8/10",
                remember("Learned that benchmarks mislead without real traffic"),
            ),
        )
        val comments = FakeComments(spokeThrice("sol"))
        val svc = service(comments, personas, memories, changes, llm)

        svc.consolidate(ScribeSource.SCHEDULED)
        assertNull(memories.judgedAt("sol"), "a refused answer is not a judgment of the evidence")
        assertTrue(memories.recordsOf("sol").isEmpty())
        assertTrue(changes.rows.isEmpty())

        svc.consolidate(ScribeSource.SCHEDULED)

        assertEquals(2, llm.received.size, "the retry the per-member window exists for")
        assertEquals(listOf("Learned that benchmarks mislead without real traffic"), memories.bodiesOf("sol"))
    }

    @Test
    fun `a seam failure stamps nothing, and the pass completes rather than throwing`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories()
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(
            listOf(
                { throw RuntimeException("rate-limited") },
                { remember("Second chances only exist while the window stays open") },
            ),
        )
        val comments = FakeComments(spokeThrice("sol"))
        val svc = service(comments, personas, memories, changes, llm)

        val written = svc.consolidate(ScribeSource.SCHEDULED)

        assertEquals(0, written, "the run must return, not throw")
        assertNull(memories.judgedAt("sol"), "a rate limit is not a verdict — the window stays open")

        svc.consolidate(ScribeSource.SCHEDULED)

        assertEquals(listOf("Second chances only exist while the window stays open"), memories.bodiesOf("sol"))
    }

    // --- the free skips: decided before any spend, and before the cap --------------------------------

    @Test
    fun `a member below the engagement floor costs no seam call and keeps its window open`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories()
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("must never be bought")))
        val comments = FakeComments(spokeThrice("sol").take(2))

        val written = service(comments, personas, memories, changes, llm).consolidate(ScribeSource.MANUAL)

        assertEquals(0, written)
        assertTrue(llm.received.isEmpty(), "two engagements under the three-engagement floor buys no judgment")
        assertNull(memories.judgedAt("sol"), "a member never looked at was not judged either")
    }

    @Test
    fun `a member at the scribe-row ceiling is skipped free, and owner rows do not count against it`() {
        val personas = RosterPersonas(listOf(persona("sol"), persona("dana")))
        val memories = FakeMemories().apply {
            repeat(MemoryScribeService.MAX_SCRIBE_MEMORIES) { i ->
                seed("sol", "scribe row number ${"x".repeat(i + 1)}", source = PersonaMemoryRepository.SOURCE_SCRIBE)
            }
            // dana holds MORE rows than the ceiling — all the owner's, all uncounted (§2.11).
            repeat(MemoryScribeService.MAX_SCRIBE_MEMORIES + 1) { i ->
                seed("dana", "owner row number ${"y".repeat(i + 1)}")
            }
        }
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("Learned that ceilings are for models, not owners")))
        val comments = FakeComments(spokeThrice("sol") + spokeThrice("dana"))

        val written = service(comments, personas, memories, changes, llm).consolidate(ScribeSource.MANUAL)

        assertEquals(1, written, "dana is judged; sol's capacity skip is free")
        assertEquals(1, llm.received.size, "the at-capacity member bought no call")
        assertNull(memories.judgedAt("sol"), "a skipped member is not stamped")
        assertTrue(
            memories.bodiesOf("dana").contains("Learned that ceilings are for models, not owners"),
            "owner rows are uncounted by the ceiling — the pass may still write",
        )
    }

    @Test
    fun `free skips are decided BEFORE the cap, so a skipped member cannot eat a budget slot`() {
        // sol SORTS FIRST (null window beats dana's stamp) and is at capacity; with cap=1, a skip
        // decided after the cap would hand the whole run's budget to a member that was never going
        // to be judged, and dana — judgeable, with fresh evidence — would get nothing.
        val personas = RosterPersonas(listOf(persona("dana"), persona("sol")))
        val memories = FakeMemories().apply {
            judgedSince("dana", "2026-01-01T09:00:00Z")
            repeat(MemoryScribeService.MAX_SCRIBE_MEMORIES) { i ->
                seed("sol", "scribe row number ${"x".repeat(i + 1)}", source = PersonaMemoryRepository.SOURCE_SCRIBE)
            }
        }
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("Learned that a skip must not hold a seat")))
        val comments = FakeComments(
            spokeThrice("dana", createdAt = "2026-01-01T12:30:00Z") +
                spokeThrice("sol", createdAt = "2026-01-01T12:30:00Z"),
        )

        val written = service(
            comments, personas, memories, changes, llm,
            props = MemoryProperties(maxPersonasPerRun = 1),
        ).consolidate(ScribeSource.SCHEDULED)

        assertEquals(1, written, "the one budget slot went to the judgeable member")
        assertTrue(memories.bodiesOf("dana").contains("Learned that a skip must not hold a seat"))
    }

    // --- rotation under a biting cap ------------------------------------------------------------------

    @Test
    fun `with both members qualifying, the cap spends on the older window, not the earlier name`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("lune")))
        val memories = FakeMemories().apply {
            judgedSince("dana", "2026-01-01T12:00:00Z")
            judgedSince("lune", "2026-01-01T09:00:00Z")
        }
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("Learned that waiting longest buys the next look")))
        val comments = FakeComments(
            spokeThrice("dana", createdAt = "2026-01-01T12:30:00Z") +
                spokeThrice("lune", createdAt = "2026-01-01T12:30:00Z"),
        )

        service(
            comments, personas, memories, changes, llm,
            props = MemoryProperties(maxPersonasPerRun = 1),
        ).consolidate(ScribeSource.SCHEDULED)

        assertEquals(1, llm.received.size, "the cap bounds judgments, not just writes")
        assertTrue(
            memories.bodiesOf("lune").contains("Learned that waiting longest buys the next look"),
            "the member waiting longest was judged",
        )
        assertTrue(memories.recordsOf("dana").isEmpty(), "the recently-judged member waits its turn")
        // And the stamped member drops to the back: the next capped run reaches dana.
        assertEquals(Instant.parse(RUN_STAMP), memories.judgedAt("lune"))
        assertTrue(
            memories.judgedAt("lune")!!.isAfter(Instant.parse("2026-01-01T12:00:00Z")),
            "rotation: whoever was judged now holds the NEWEST window",
        )
    }

    // --- the 90-day horizon (D6b) ----------------------------------------------------------------------

    @Test
    fun `a never-judged member reads no further back than the horizon - there is no all-time read`() {
        // The dead-coarseFloor class, killed by construction: sol has never been stamped, which in
        // both prior slices meant `since = null` and an all-time materialisation. Here the SQL
        // floor must be the horizon (margin-coarsened), and evidence older than it must not
        // qualify the member. Mutation: drop the horizon clamp in `since`/`candidates` and both
        // halves of this test redden.
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories()
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("must never be bought")))
        // All the evidence is ~100 days old — outside the 90-day lookback from RUN_STAMP.
        val comments = FakeComments(spokeThrice("sol", createdAt = "2025-09-20T12:00:00Z"))

        val written = service(comments, personas, memories, changes, llm).consolidate(ScribeSource.SCHEDULED)

        assertEquals(0, written)
        assertTrue(llm.received.isEmpty(), "evidence beyond the horizon must not qualify a member")
        assertEquals(
            listOf<String?>(HORIZON_FLOOR),
            comments.windows,
            "the engagement read is floored at the horizon even though sol has no watermark",
        )
    }

    @Test
    fun `evidence inside the horizon still qualifies a null-windowed member`() {
        // The other half: the horizon bounds the read, it does not mute quiet members.
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories()
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("Learned that horizons bound reads, not members")))
        val comments = FakeComments(spokeThrice("sol", createdAt = TALKED_AT))

        val written = service(comments, personas, memories, changes, llm).consolidate(ScribeSource.SCHEDULED)

        assertEquals(1, written)
        assertEquals(listOf("Learned that horizons bound reads, not members"), memories.bodiesOf("sol"))
    }

    // --- the evidence cut (§2.4) ---------------------------------------------------------------------

    @Test
    fun `the evidence cut keeps the twelve chronologically newest engagements, not the twelve lexically last`() {
        // The cut is a `takeLast`, so whatever order the evidence arrives in IS the selection rule —
        // and the order `exchangesSince` supplies is SQL's `ORDER BY c.created_at`, string order over
        // `Instant.toString()`. That printer emits NO fraction on a whole second, so "04:30:00Z"
        // sorts AFTER "04:30:00.500Z" ('Z' is 0x5A, '.' is 0x2E) while being half a second OLDER.
        // Name the failure: thirteen engagements against a twelve-cut whose boundary falls inside one
        // such pair, and the string order keeps the older sibling and throws the newer one away —
        // MAX_EVIDENCE_ENGAGEMENTS' "the twelve most recent" becomes a documented lie, and the member
        // is judged on the words it had already moved past while the words it moved TO never reach
        // the scribe. It is the boundary pair that matters because it is the only place the two
        // orderings disagree: eleven engagements a minute later sort identically under both, so they
        // fill the cut without deciding it.
        //
        // Both directions are asserted, because either alone is passable by a mutation that keeps
        // everything: "the newer sibling is shown" survives deleting the cut, "the older sibling is
        // absent" survives deleting the pair. And the pair is fed NEWEST-first, so a `takeLast` with
        // the sort removed outright — not merely pointed the wrong way — also drops the newer one.
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories()
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("Learned that the newest words are the ones worth keeping")))
        val comments = FakeComments(
            listOf(
                exchange("sol", "owner", SUB_SECOND_BODY, createdAt = BOUNDARY_SUB),
                exchange("sol", "owner", WHOLE_SECOND_BODY, createdAt = BOUNDARY_WHOLE),
            ) + (1..11).map {
                exchange("sol", "owner", "$it - and the argument kept moving", createdAt = AFTER_THE_PAIR)
            },
        )

        service(comments, personas, memories, changes, llm).consolidate(ScribeSource.MANUAL)

        val instruction = llm.received.single().context.comments.single().body
        assertEquals(
            12, instruction.lines().count { it.startsWith("  - in ") },
            "thirteen engagements, twelve shown — the cut has to bite for the boundary to mean anything",
        )
        assertTrue(
            instruction.contains(SUB_SECOND_BODY),
            "the fractionally NEWER sibling is what the twelve most recent means: $instruction",
        )
        assertFalse(
            instruction.contains(WHOLE_SECOND_BODY),
            "and its half-second-older twin is the one the cut drops — string order keeps it instead",
        )
    }

    // --- the letter protocol at the service: resolution, degrade, and the judgment-site re-read --------

    @Test
    fun `the letter cut offers the twenty-six chronologically newest records, not the lexically first`() {
        // The sibling of the evidence cut, one list over: `recordsOf` is `ORDER BY created_at DESC,
        // id` — string order over `Instant.toString()`, which prints NO fraction on a whole second,
        // so "04:30:00Z" ('Z' = 0x5A) sorts BEFORE "04:30:00.500Z" ('.' = 0x2E) under DESC while
        // being half a second OLDER. Past the alphabet that inversion costs a record its letter: the
        // whole-second row takes the twenty-sixth slot and the genuinely newer sub-second row falls
        // off the end, so the model is offered an antecedent the member has already moved past and
        // never sees the one it moved to. `.sortedWith(MemoryRecall.NEWEST_FIRST)` at the call site
        // is what corrects it, and this test is what stops it being deleted as redundant re-sorting
        // of an already-ordered query.
        //
        // [FakeMemories.recordsOf] mirrors that `ORDER BY` faithfully — a String compare on
        // `createdAt` with the same id tiebreak — so what reddens here is the SERVICE's re-sort, not
        // the fake's ordering: strip the re-sort and this fixture yields exactly the list production
        // would have built.
        //
        // The fixture's arithmetic is the non-obvious part. The cut only bites past twenty-six
        // records, but a member holding twenty-four SCRIBE rows is skipped free before any seam call
        // (§2.11) — so a naive twenty-seven-scribe-row fixture buys no judgment and asserts on
        // nothing. Owner rows are UNCOUNTED by that ceiling and are parent candidates all the same,
        // which is what makes twenty-seven records reachable at all: 23 scribe + 4 owner, one under
        // the ceiling and one over the alphabet.
        //
        // Both directions are asserted, because a mutation each way trips only one of them: deleting
        // the sort drops the sub-second row (the "is offered" assertion), reversing it keeps BOTH
        // halves of the pair inside an oldest-first twenty-six (the "is not offered" assertion). The
        // pair is seeded LAST and whole-second first, so an implementation that does not sort at all
        // — fake included — also drops the newer row rather than being rescued by insertion order.
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories().apply {
            // Twenty-five rows a minute newer than the pair: identical under both orderings, so they
            // fill the cut without deciding it. Twenty-three carry the scribe's provenance — the
            // most a judgeable member can hold — and the rest are the owner's.
            repeat(25) { i ->
                seed(
                    "sol", "Filler record ${i + 1}, from the fortnight after the twins",
                    source = if (i < 23) PersonaMemoryRepository.SOURCE_SCRIBE else PersonaMemoryRepository.SOURCE_OWNER,
                    createdAt = AFTER_THE_PAIR,
                )
            }
            seed("sol", LETTER_WHOLE_BODY, createdAt = BOUNDARY_WHOLE)
            seed("sol", LETTER_SUB_BODY, createdAt = BOUNDARY_SUB)
        }
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("Learned that the newest record must keep its letter")))
        val comments = FakeComments(spokeThrice("sol"))

        service(comments, personas, memories, changes, llm).consolidate(ScribeSource.MANUAL)

        val instruction = llm.received.single().context.comments.single().body
        assertEquals(
            MemoryScribePrompts.MAX_PARENT_LETTERS,
            instruction.lines().count { it.matches(LETTERED_LINE) },
            "twenty-seven records, twenty-six letters — the cap has to bite for the boundary to mean anything",
        )
        assertTrue(
            instruction.contains(LETTER_SUB_BODY),
            "the fractionally NEWER twin is what 'newest first' owes the last letter: $instruction",
        )
        assertFalse(
            instruction.contains(LETTER_WHOLE_BODY),
            "and its half-second-older twin is the row the cut drops — string order offers it instead",
        )
    }

    @Test
    fun `EXTENDS resolves against the newest-first letter map and attaches beneath that record`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories()
        // Seeded oldest first: the rabbit hole (older) then the notes (newer) — so the letter list
        // offers A=notes, B=rabbit hole, and EXTENDS: B is the OLDER record.
        val rabbitHole = memories.seed("sol", "Fell down the write-ahead log rabbit hole once", createdAt = "2026-01-01T00:00:00Z")
        memories.seed("sol", "Keeps notes on every failed migration", createdAt = "2026-01-01T00:01:00Z")
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("Suspects the log format hides more surprises", extends = "B")))
        val comments = FakeComments(spokeThrice("sol"))

        service(comments, personas, memories, changes, llm).consolidate(ScribeSource.MANUAL)

        val written = memories.recordsOf("sol").single { it.source == PersonaMemoryRepository.SOURCE_SCRIBE }
        assertEquals(rabbitHole, written.parentId, "B is the second-NEWEST record, per the offered order")
        assertEquals(
            "Fell down the write-ahead log rabbit hole once", changes.rows.single().parentBody,
            "the audit row snapshots the antecedent's prose",
        )
    }

    @Test
    fun `a letter outside the offered set attaches top-level and the record still lands`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories().apply { seed("sol", "Fell down the write-ahead log rabbit hole once") }
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("Suspects the letter protocol has sharp edges", extends = "Q")))
        val comments = FakeComments(spokeThrice("sol"))

        val written = service(comments, personas, memories, changes, llm).consolidate(ScribeSource.MANUAL)

        assertEquals(1, written, "a broken decoration never costs a paid, well-formed record")
        val record = memories.recordsOf("sol").single { it.source == PersonaMemoryRepository.SOURCE_SCRIBE }
        assertNull(record.parentId, "unknown letter degrades to top-level")
        assertNull(changes.rows.single().parentBody)
    }

    @Test
    fun `a parent deleted during the judgment call degrades to top-level, logged as vanished`() {
        // The judgment-site re-read (bed019fe, third application): the letter resolves against the
        // snapshot the model was shown, but the resolved id is re-verified against the member's
        // CURRENT rows at write time. The scripted answer IS the moment the owner's delete lands.
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories()
        val doomed = memories.seed("sol", "Fell down the write-ahead log rabbit hole once")
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(
            listOf({
                memories.deleteRecord(doomed)
                remember("Suspects the log format hides more surprises", extends = "A")
            }),
        )
        val comments = FakeComments(spokeThrice("sol"))

        LogCapture.on(MemoryScribeService::class.java).use { logs ->
            val written = service(comments, personas, memories, changes, llm).consolidate(ScribeSource.MANUAL)

            assertEquals(1, written, "the paid record still lands")
            val record = memories.recordsOf("sol").single()
            assertNull(record.parentId, "a vanished parent degrades to top-level, never a dangling FK")
            // The event id lives in the message (the S4b `event=` convention this codebase logs in).
            assertEquals(
                1, logs.warns().count { it.contains("event=memory.parent.vanished") },
                "and the degrade is logged with its own event id: ${logs.warns()}",
            )
        }
    }

    // --- blinkers -----------------------------------------------------------------------------------

    @Test
    fun `the scribe instruction is byte-identical whether or not the rest of the room holds memories`() {
        // The convergence guardrail as byte-identity: what other members remember must make no
        // difference to what one member's scribe is asked. A later "give the scribe a little room
        // context" change reddens here instead of quietly opening the channel.
        fun promptFor(paulRemembers: Boolean): String {
            val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
            val memories = FakeMemories().apply {
                seed("sol", "Keeps a running list of storage tricks")
                if (paulRemembers) seed("paul", "Reads release notes end to end for fun")
            }
            val llm = ScriptedLlm(says("NOTHING"))
            // Only sol has lived through anything, so only sol is judged.
            val comments = FakeComments(spokeThrice("sol"))
            service(comments, personas, memories, FakeMemoryChanges(), llm).consolidate(ScribeSource.SCHEDULED)
            return llm.received.single().context.comments.single().body
        }

        assertEquals(
            promptFor(paulRemembers = true), promptFor(paulRemembers = false),
            "what the rest of the room remembers must make no difference to what one member is asked",
        )
    }

    // --- failure isolation, atomicity, single flight --------------------------------------------------

    @Test
    fun `one member's seam failure does not cost the next member its judgment`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("sol")))
        val memories = FakeMemories()
        val changes = FakeMemoryChanges()
        // Neither member is judged, so the id tiebreak orders them: dana's judgment explodes.
        val llm = ScriptedLlm(
            listOf(
                { throw RuntimeException("rate-limited") },
                { remember("Learned that one failure costs one member") },
            ),
        )
        val comments = FakeComments(spokeThrice("dana") + spokeThrice("sol"))

        val written = service(comments, personas, memories, changes, llm).consolidate(ScribeSource.SCHEDULED)

        assertEquals(1, written, "the run must return, not throw")
        assertTrue(memories.recordsOf("dana").isEmpty())
        assertNull(memories.judgedAt("dana"), "a seam failure must not close the window")
        assertEquals(listOf("Learned that one failure costs one member"), memories.bodiesOf("sol"))
    }

    @Test
    fun `one member's repository failure does not abandon the rest of the run`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("sol")))
        val memories = FakeMemories(failInsertFor = "dana")
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(
            says(
                remember("Learned that a locked database is survivable"),
                remember("Learned that the queue keeps moving"),
            ),
        )
        val comments = FakeComments(spokeThrice("dana") + spokeThrice("sol"))

        val written = service(comments, personas, memories, changes, llm).consolidate(ScribeSource.SCHEDULED)

        assertEquals(1, written, "the run continues past the member whose insert threw")
        assertTrue(memories.recordsOf("dana").isEmpty(), "nothing half-written")
        assertNull(memories.judgedAt("dana"), "a record that never landed must not close its window")
        assertEquals(listOf("Learned that the queue keeps moving"), memories.bodiesOf("sol"))
    }

    @Test
    fun `an insert that fails rolls back the audit row rather than committing it alone`() {
        // Audit row + insert + stamp are ONE unit (§2.16): an audit row committing alone would show
        // the owner a record that never existed AND stand as that member's history. The rollback
        // itself is Spring's over a real DataSource; what Tier 2 pins is that the service asked for
        // a rollback, never a commit, and the watermark it never reached.
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories(failInsertFor = "sol")
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("Learned that half a write is worse than none")))
        val comments = FakeComments(spokeThrice("sol"))
        val manager = RecordingTransactions()

        val written = service(
            comments, personas, memories, changes, llm,
            transactions = TransactionTemplate(manager),
        ).consolidate(ScribeSource.SCHEDULED)

        assertEquals(0, written)
        assertEquals(1, manager.rollbacks, "the audit row must not stand for a record that failed")
        assertEquals(0, manager.commits)
        assertNull(memories.judgedAt("sol"), "and the failed write must not close the member's window")
    }

    @Test
    fun `a second caller while a pass is in flight does nothing at all`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories()
        val changes = FakeMemoryChanges()
        val judging = CountDownLatch(1)
        val release = CountDownLatch(1)
        val llm = ScriptedLlm(
            listOf({
                judging.countDown()
                release.await(5, TimeUnit.SECONDS)
                remember("Learned that patience is cheaper than a double pass")
            }),
        )
        val comments = FakeComments(spokeThrice("sol"))
        val svc = service(comments, personas, memories, changes, llm)

        val first = thread(name = "memory-scribe-pass") { svc.consolidate(ScribeSource.MANUAL) }
        assertTrue(judging.await(5, TimeUnit.SECONDS), "the first pass should reach its judgment")

        val second = svc.consolidate(ScribeSource.MANUAL)

        assertEquals(0, second, "the second caller returns immediately, having changed nothing")
        assertEquals(1, llm.received.size, "and having bought no judgment")
        release.countDown()
        first.join(5_000)
        assertEquals(1, changes.rows.size, "one pass, one audit row")
        // The guard is released, so the NEXT click is a normal pass again rather than a permanent no-op.
        assertEquals(0, svc.consolidate(ScribeSource.MANUAL), "nothing new to judge, but the guard is open")
    }

    // --- revert -------------------------------------------------------------------------------------

    @Test
    fun `revert deletes the record, reparents its child, stamps the audit row - and does NOT reopen the window`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories()
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(
            says(
                remember("Preemption arguments never really end"),
                remember("A decoy a reopened window would buy"),
            ),
        )
        val comments = FakeComments(spokeThrice("sol"))
        val svc = service(comments, personas, memories, changes, llm)
        svc.consolidate(ScribeSource.MANUAL)
        val written = memories.recordsOf("sol").single()
        // The owner (or a later pass) chained a record beneath it: revert must hand the child up.
        val child = memories.seed("sol", "Kept digging afterwards", parentId = written.id, createdAt = "2026-01-01T05:00:00Z")

        val reverted = svc.revert(changes.rows.single().id)

        assertTrue(reverted)
        assertTrue(memories.recordsOf("sol").none { it.id == written.id }, "the record is gone")
        assertNull(
            memories.recordsOf("sol").single { it.id == child }.parentId,
            "the child is reparented to the grandparent (top level here), never cascaded",
        )
        assertEquals(REVERT_STAMP, changes.rows.single().revertedAt, "the audit row records the undo")
        // D10, the argued departure: NO rollback — a reopened window would re-read the same
        // evidence and re-manufacture the row the owner just killed.
        assertEquals(Instant.parse(RUN_STAMP), memories.judgedAt("sol"), "the window did not move")

        svc.consolidate(ScribeSource.MANUAL)

        assertEquals(1, llm.received.size, "the next run buys no new judgment — the evidence stays consumed")
    }

    @Test
    fun `reverting a record the owner already deleted is skipped as superseded, audit row untouched`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories()
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("Preemption arguments never really end")))
        val comments = FakeComments(spokeThrice("sol"))
        val svc = service(comments, personas, memories, changes, llm)
        svc.consolidate(ScribeSource.MANUAL)
        memories.deleteRecord(changes.rows.single().memoryId)

        LogCapture.on(MemoryScribeService::class.java).use { logs ->
            val reverted = svc.revert(changes.rows.single().id)

            assertFalse(reverted, "a vanished record cannot be re-deleted")
            assertTrue(
                logs.warns().any { it.contains("event=memory.revert.skipped") && it.contains("reason=superseded") },
                "the skip must be logged with reason=superseded, got: ${logs.warns()}",
            )
        }
        assertNull(changes.rows.single().revertedAt, "the audit row survives, honestly un-reverted")
    }

    @Test
    fun `an unknown id and a second revert are both no-ops`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val memories = FakeMemories()
        val changes = FakeMemoryChanges()
        val llm = ScriptedLlm(says(remember("Preemption arguments never really end")))
        val comments = FakeComments(spokeThrice("sol"))
        val svc = service(comments, personas, memories, changes, llm)
        svc.consolidate(ScribeSource.MANUAL)
        val id = changes.rows.single().id

        assertFalse(svc.revert(404L), "an unknown change reverts nothing")
        assertTrue(svc.revert(id))
        assertFalse(svc.revert(id), "the reverted_at stamp is the double-revert guard")
        assertEquals(REVERT_STAMP, changes.rows.single().revertedAt, "still the FIRST revert's stamp")
        assertEquals(1, llm.received.size, "no revert path touches the seam")
    }

    private companion object {
        const val STAMP = "2026-01-01T13:00:00Z"
        const val REVERT_STAMP = "2026-01-02T12:00:00Z"

        /** When the members lived through what they lived through — before the pass reads it. */
        const val TALKED_AT = "2026-01-01T12:00:00Z"

        /** What the SERVICE's own clock reads — the instant every watermark this suite stamps
         *  carries. Later than the engagements, because that is the order things happen in. */
        const val RUN_STAMP = "2026-01-01T13:00:00Z"

        /** A second, later instant for the stepping clock: if any stamp carries this, the service
         *  read its clock again after the evidence query — the exact defect under test. */
        const val LATER_STAMP = "2026-01-01T13:02:00Z"

        /** RUN_STAMP − 90 days − the one-second floor margin, truncated to a whole second — the
         *  coarse SQL floor a never-stamped roster must produce under the default lookback. */
        const val HORIZON_FLOOR = "2025-10-03T12:59:59Z"

        /** The boundary pair, one shared second: `Instant.toString()` prints no fraction on a whole
         *  second, so [BOUNDARY_WHOLE] string-sorts AFTER [BOUNDARY_SUB] while being half a second
         *  older. BOTH string-ordered cuts — the evidence `takeLast` and the letter `take` — have to
         *  fall between exactly these two to be worth pinning, so both fixtures share the pair. */
        const val BOUNDARY_WHOLE = "2026-01-01T04:30:00Z"
        const val BOUNDARY_SUB = "2026-01-01T04:30:00.500Z"

        /** The rows that FILL each cut — a minute after the pair, so they are newer under BOTH
         *  orderings and cannot be what decides it. */
        const val AFTER_THE_PAIR = "2026-01-01T04:31:00Z"

        /** The pair's prose, distinct in the first twelve characters (which is what [exchange] folds
         *  into the comment id) so nothing about this test rides on the comparator's id tiebreak —
         *  the pair's stamps differ, so the tiebreak never even runs on it. */
        const val WHOLE_SECOND_BODY = "The whole-second half of the boundary pair"
        const val SUB_SECOND_BODY = "The sub-second half of the boundary pair"

        /** The same pair on the RECORD side — distinct prose from the engagement pair, because the
         *  two fixtures pin different cuts and a shared string would let one test's failure read as
         *  the other's. Neither is a substring of the other: `contains` is the assertion. */
        const val LETTER_WHOLE_BODY = "The whole-second record, the elder twin"
        const val LETTER_SUB_BODY = "The sub-second record, the younger twin"

        /** A lettered candidate line, as [MemoryScribePrompts.instruction] renders it ("  A. body")
         *  — how the offered list is counted without re-deriving the prompt's layout inline. */
        val LETTERED_LINE = Regex("^ {2}[A-Z]\\. .+")
    }
}
