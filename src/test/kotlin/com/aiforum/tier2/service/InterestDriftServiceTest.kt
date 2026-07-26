package com.aiforum.tier2.service

import com.aiforum.config.InterestDriftProperties
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import com.aiforum.persona.InterestDriftPrompts
import com.aiforum.persona.Interests
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.Interest
import com.aiforum.repo.InterestChange
import com.aiforum.repo.InterestChangeRepository
import com.aiforum.repo.PersonaExchange
import com.aiforum.repo.PersonaInterestRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.service.DriftSource
import com.aiforum.service.InterestDriftService
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
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tier-2: [InterestDriftService] running its real orchestration over in-memory subclass fakes of the
 * repositories plus a scripted [LlmClient] (the all-open plugin makes `@Repository`/`@Service` methods
 * overridable — the same shape as `StanceEvolutionServiceTest`). No mocking library, and the ONE faked IO
 * seam is the scripted client, so *"how many judgments did this run buy?"* is directly assertable.
 *
 * What this pins that the acceptance suite cannot: the acceptance scenarios drive one pass over one
 * member, so the multi-member behaviour — window-age ordering, a cap spending on the oldest window rather
 * than the earliest name, per-member isolation across both the seam and the storage side — plus the
 * per-member window arithmetic that decides what a SECOND run even looks at. Several tests here run the
 * pass twice for that reason: what one run costs is only half the question, and "does a settled room
 * re-buy the same judgment every week" is the other half.
 *
 * Two structural guarantees ride along in the fakes rather than in a test of their own, because that is
 * where they cannot be forgotten:
 *
 * - **I1** — [RosterPersonas.update] fails the test if it is called at all. `update` is the only writer of
 *   `descriptor`/`abilities`/`dials`, and it also rewrites `system_prompt` from its argument, so this
 *   catches a clobber that a byte-identity assertion on three columns would miss.
 * - **The anti-recompose pin (D7)** is the constructor: [InterestDriftService] takes no
 *   `PersonaPromptRefresher` and no `PromptComposer`, so the "a SpyRefresher records zero calls"
 *   assertion the design imagined has nothing to spy on. Interests are injected into a generation at
 *   settle time; a drift costs no composition, and acquiring the dependency would have to be a visible
 *   edit to the constructor rather than a quiet extra call.
 */
@Tag("tier2")
class InterestDriftServiceTest {

    // --- fakes -------------------------------------------------------------------------------------

    /**
     * The roster, and the I1 tripwire: `update` is the drift pass's only conceivable route to a member's
     * immutable core, so calling it is a test failure rather than an assertion someone has to remember to
     * write.
     */
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
            fail<Unit>("the drift pass has no write path to a member's character — update($id) must never be called")
        }
    }

    /**
     * Serves a programmed engagement list. The service reads the history ONCE per run, coarsely bounded by
     * the oldest watermark on the roster, and then narrows PER MEMBER in memory — so `since` is null
     * whenever any member is unjudged and a floor otherwise. The real query's LEXICOGRAPHIC comparison is
     * mirrored exactly rather than parsed, because that is the property the service's floor margin has to
     * survive: a fraction-less stamp sorts after every sub-second stamp of the same second.
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
     * The interest table in memory. Four behaviours are mirrored from the real repository because the
     * service leans on all four: reads are ordered case-insensitively (V27's NOCASE collation, and the
     * order the prompt is built from), `upsert`/`delete` [Interests.clean] their argument at the door,
     * `upsert` overwrites PROVENANCE while keeping the stored casing, and `markJudged` owns the watermark
     * alone — including clearing it on null, which is what a revert needs.
     *
     * [failUpsertFor] arms one member's interest write to throw, which is how the transaction boundary is
     * driven without a database.
     */
    private class FakeInterests(private val failUpsertFor: String? = null) :
        PersonaInterestRepository(JdbcTemplate(), Clock.systemUTC()) {
        val rows = mutableListOf<Interest>()
        private val judged = mutableMapOf<String, String?>()

        /**
         * Fixture setup, and it deliberately does NOT go through [upsert]: a test that arms a member's
         * write to fail still has to be able to give that member the interests the failure happens to.
         * It is also what the mid-pass test uses to play the owner's edit landing during a judgment.
         */
        fun seed(
            personaId: String,
            interest: String,
            source: String = PersonaInterestRepository.SOURCE_SEEDED,
        ) = write(personaId, interest, source)

        /** Pre-stamp a member's watermark — what a member judged in an earlier run looks like. */
        fun judgedSince(personaId: String, at: String) = markJudged(personaId, at)

        fun sourceOf(personaId: String, interest: String) =
            rows.first { it.personaId == personaId && it.interest.equals(interest, ignoreCase = true) }.source

        // `ORDER BY interest` under V27's NOCASE collation — folded here rather than compared raw, because
        // the prompt is built from this order and "Storage engines" must not sort away from "storage
        // engines" while being the same row.
        override fun of(personaId: String) = rows.filter { it.personaId == personaId }
            .sortedBy { it.interest.lowercase() }

        override fun findAll() = rows.sortedWith(compareBy({ it.personaId }, { it.interest }))

        override fun upsert(personaId: String, interest: String, source: String) {
            if (personaId == failUpsertFor) throw IllegalStateException("database is locked")
            write(personaId, interest, source)
        }

        private fun write(personaId: String, interest: String, source: String) {
            val cleaned = Interests.clean(interest)
            val at = rows.indexOfFirst { it.personaId == personaId && it.interest.equals(cleaned, ignoreCase = true) }
            // ON CONFLICT DO UPDATE rewrites source and updated_at but deliberately NOT the phrase, so a
            // row keeps the casing it was created with.
            val row = Interest(personaId, if (at >= 0) rows[at].interest else cleaned, source, STAMP)
            if (at >= 0) rows[at] = row else rows += row
        }

        override fun delete(personaId: String, interest: String) {
            val cleaned = Interests.clean(interest)
            rows.removeAll { it.personaId == personaId && it.interest.equals(cleaned, ignoreCase = true) }
        }

        override fun judgedAt(personaId: String) = judged[personaId]

        override fun markJudged(personaId: String, at: String?) {
            judged[personaId] = at
        }
    }

    /** [failFor] arms one member's audit write to throw — the other half of the isolation fixture. */
    private class FakeInterestChanges(private val failFor: String? = null) :
        InterestChangeRepository(JdbcTemplate(), Clock.systemUTC()) {
        val rows = mutableListOf<InterestChange>()

        fun seed(personaId: String, changedAt: String, revertedAt: String? = null) {
            rows += InterestChange(
                rows.size + 1L, personaId, "was", PersonaInterestRepository.SOURCE_SEEDED,
                "now", "", changedAt, revertedAt,
            )
        }

        /** A seeded row whose phrases are named, for the chains where which phrase moved is the point. */
        fun seedSwap(personaId: String, dropped: String, takenUp: String, changedAt: String) {
            rows += InterestChange(
                rows.size + 1L, personaId, dropped, PersonaInterestRepository.SOURCE_SEEDED,
                takenUp, "", changedAt, null,
            )
        }

        override fun record(
            personaId: String,
            dropped: String,
            droppedSource: String,
            takenUp: String,
            cited: String,
        ): Long {
            if (personaId == failFor) throw IllegalStateException("database is locked")
            val id = rows.size + 1L
            rows += InterestChange(id, personaId, dropped, droppedSource, takenUp, cited, STAMP, null)
            return id
        }

        override fun recent(limit: Int) =
            rows.sortedWith(compareByDescending<InterestChange> { it.changedAt }.thenByDescending { it.id })
                .take(limit)

        override fun find(id: Long) = rows.firstOrNull { it.id == id }

        override fun markReverted(id: Long) {
            val at = rows.indexOfFirst { it.id == id && it.revertedAt == null }
            if (at >= 0) rows[at] = rows[at].copy(revertedAt = REVERT_STAMP)
        }

        // Mirrors the real per-member query: the newest STANDING change for this member, ignoring both
        // reverted rows and every other member's history.
        override fun lastStandingChangeAt(personaId: String) =
            rows.filter { it.personaId == personaId && it.revertedAt == null }.maxOfOrNull { it.changedAt }
    }

    /** A FIFO of scripted answers; an entry that throws models a seam fault (rate limit, timeout). */
    private class ScriptedLlm(answers: List<() -> String> = emptyList()) : LlmClient {
        private val queue = ArrayDeque(answers)
        val received = mutableListOf<LlmRequest>()
        override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
            received += request
            val next = queue.removeFirstOrNull()
            // An unscripted call answers something the parse cannot use, so a run that buys a judgment
            // nobody expected shows up as an assertion failure rather than as a silent extra write.
            return LlmResponse(if (next != null) next() else "unscripted reply")
        }
    }

    /**
     * A transaction manager that only RECORDS what the template asked for, so the service runs its REAL
     * [TransactionTemplate] here. In-memory fakes cannot be rolled back, so what Tier 2 can pin is that
     * the audit row, the two interest writes and the watermark stamp were submitted as ONE unit and that a
     * fault inside asks for a rollback rather than committing a swap that never landed. The undo itself is
     * Spring's, and is pinned against a real DataSource by `CommentRepositoryTransactionTest`.
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

    private fun persona(id: String) = PersonaRepository.Persona(
        id, id.replaceFirstChar { it.uppercase() }, "$id, who reads storage papers for fun", "",
    )

    /** A fixed clock, the way the real service gets one — src/main reads no wall clock anywhere. */
    private fun clockAt(stamp: String) = Clock.fixed(Instant.parse(stamp), ZoneOffset.UTC)

    /**
     * One thing a member wrote. The addressee is deliberately OFF the roster in most fixtures: evidence is
     * the member's OWN words, so who they were aimed at does not decide whether they count — and nothing
     * from the other side reaches the prompt, because `towardBody` is never rendered.
     */
    private fun exchange(
        from: String,
        to: String,
        body: String,
        createdAt: String = TALKED_AT,
    ) = PersonaExchange(
        "c-$from-${body.take(12)}", "th1", "Rust in the kernel", from, to, body,
        "the article's own summary, which the judge must never see", createdAt,
    )

    /** The shipped engagement floor is three, so a qualifying fixture needs three. */
    private fun spokeThrice(from: String, to: String = "paul", createdAt: String = TALKED_AT) = listOf(
        exchange(from, to, "The scheduler is the interesting part", createdAt),
        exchange(from, to, "Preemption cost decides this", createdAt),
        exchange(from, to, "Nobody benchmarks the wake-up path", createdAt),
    )

    /** The two-line answer the judge is asked for. */
    private fun swap(drop: String, take: String) = "DROP: $drop\nTAKE: $take"

    private fun says(vararg texts: String): List<() -> String> = texts.map { text -> { text } }

    private fun service(
        comments: FakeComments,
        personas: RosterPersonas,
        interests: FakeInterests,
        changes: FakeInterestChanges,
        llm: ScriptedLlm,
        props: InterestDriftProperties = InterestDriftProperties(),
        transactions: TransactionTemplate = TransactionTemplate(RecordingTransactions()),
        // An hour after the engagements: the pass runs AFTER the conversation it judges, so the watermark
        // it stamps genuinely covers that evidence and a second run sees nothing new.
        clock: Clock = clockAt(RUN_STAMP),
    ) = InterestDriftService(comments, personas, interests, changes, llm, transactions, clock, props)

    // --- the write path ----------------------------------------------------------------------------

    @Test
    fun `a qualifying member drifts, is audited, and holds exactly as many interests as before`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val interests = FakeInterests().apply {
            seed("sol", "typography")
            seed("sol", "small tools")
        }
        val changes = FakeInterestChanges()
        val llm = ScriptedLlm(says(swap("typography", "kernel scheduling")))
        val comments = FakeComments(spokeThrice("sol"))

        val changed = service(comments, personas, interests, changes, llm).drift(DriftSource.MANUAL)

        assertEquals(1, changed)
        // I3: one set down, one taken up. A pass that could add would let a member accumulate the room's
        // vocabulary, and convergence would no longer need displacement.
        assertEquals(listOf("kernel scheduling", "small tools"), interests.phrasesOf("sol"))
        // The provenance moved with the phrase: `drifted` is what tells the owner's page this was the
        // model's doing, and a later revert what to restore.
        assertEquals(PersonaInterestRepository.SOURCE_DRIFTED, interests.sourceOf("sol", "kernel scheduling"))
        val audit = changes.rows.single()
        assertEquals("sol", audit.personaId)
        assertEquals("typography", audit.dropped)
        assertEquals("kernel scheduling", audit.takenUp)
        // Without the OLD provenance the revert would bring a seeded phrase home labelled `drifted`, and
        // the next pass would read a lie about who wrote it.
        assertEquals(PersonaInterestRepository.SOURCE_SEEDED, audit.droppedSource)
        assertNull(audit.revertedAt, "a fresh audit row is not reverted")
        assertEquals(RUN_STAMP, interests.judgedAt("sol"), "a usable verdict closes the window it read")
        // One judgment, carrying the synthetic judge identity so a spy can tell it from a reply, and the
        // judge's own SYSTEM prompt rather than a persona's.
        val request = llm.received.single()
        assertEquals(InterestDriftPrompts.JUDGE_ID, request.persona.id)
        assertEquals(InterestDriftPrompts.JUDGE_NAME, request.persona.name)
        assertEquals(InterestDriftPrompts.SYSTEM, request.context.personaSystemPrompt)
        val instruction = request.context.comments.single().body
        assertTrue(
            instruction.contains("Nobody benchmarks the wake-up path"),
            "the member's own words are the evidence: $instruction",
        )
        assertTrue(
            instruction.contains("who reads storage papers for fun"),
            "the member's character is named to the judge as fixed: $instruction",
        )
        assertFalse(
            instruction.contains("the article's own summary"),
            "what the member was ANSWERING is fetched, untrusted text and must not reach the judge",
        )
    }

    @Test
    fun `the audit cites every engagement the judge read, as snapshotted prose plus its ids`() {
        // The audit IS the owner's control here, so it has to carry enough to judge the judgment: the
        // evidence as it read at the time (bodies are editable in place) AND the ids the page needs to
        // build a /threads/{thread}#reply-{comment} permalink back to it.
        val personas = RosterPersonas(listOf(persona("sol")))
        val interests = FakeInterests().apply { seed("sol", "typography") }
        val changes = FakeInterestChanges()
        val llm = ScriptedLlm(says(swap("typography", "kernel scheduling")))
        val comments = FakeComments(spokeThrice("sol"))

        service(comments, personas, interests, changes, llm).drift(DriftSource.MANUAL)

        val cited = InterestDriftService.parseCited(changes.rows.single().cited)
        assertEquals(
            listOf(
                "The scheduler is the interesting part",
                "Preemption cost decides this",
                "Nobody benchmarks the wake-up path",
            ),
            cited.map { it.snippet },
            "every engagement the judgment saw is cited — the audit shows the evidence, never a sample of it",
        )
        assertTrue(cited.all { it.threadId == "th1" && it.commentId.isNotBlank() }, "each citation is linkable")
    }

    // --- the three free skips ----------------------------------------------------------------------

    @Test
    fun `a member whose every interest the owner pinned is skipped without a judgment call`() {
        // The never-clobber contract, enforced BEFORE the judgment: a member the owner has taken over by
        // hand is also a member this pass stops spending money on.
        val personas = RosterPersonas(listOf(persona("sol")))
        val interests = FakeInterests().apply {
            seed("sol", "boring technology choices", PersonaInterestRepository.SOURCE_OWNER)
        }
        val changes = FakeInterestChanges()
        val llm = ScriptedLlm(says(swap("boring technology choices", "kernel scheduling")))
        val comments = FakeComments(spokeThrice("sol"))

        val changed = service(comments, personas, interests, changes, llm).drift(DriftSource.MANUAL)

        assertEquals(0, changed)
        assertTrue(llm.received.isEmpty(), "an owner-authored interest set must cost no judgment at all")
        assertEquals(listOf("boring technology choices"), interests.phrasesOf("sol"))
        assertTrue(changes.rows.isEmpty(), "nothing changed, so nothing is audited")
        assertNull(interests.judgedAt("sol"), "a member the pass never judged keeps its window open")
    }

    @Test
    fun `a member the owner gave no interests is skipped without a judgment call`() {
        // Drift is opt-in PER MEMBER: with nothing authored there is nothing to swap, so an owner who
        // authors no interests pays nothing even with the scheduler on.
        val personas = RosterPersonas(listOf(persona("sol")))
        val interests = FakeInterests()
        val changes = FakeInterestChanges()
        val llm = ScriptedLlm(says(swap("typography", "kernel scheduling")))
        val comments = FakeComments(spokeThrice("sol"))

        val changed = service(comments, personas, interests, changes, llm).drift(DriftSource.MANUAL)

        assertEquals(0, changed)
        assertTrue(llm.received.isEmpty(), "there is nothing to set down, so there is no question to ask")
        assertTrue(interests.rows.isEmpty(), "and the pass must not author the interest it was about to move")
    }

    @Test
    fun `a member below the engagement floor is never judged`() {
        // One comment is not a change of heart, and the floor is what makes a quiet week free.
        val personas = RosterPersonas(listOf(persona("sol")))
        val interests = FakeInterests().apply { seed("sol", "typography") }
        val changes = FakeInterestChanges()
        val llm = ScriptedLlm(says(swap("typography", "kernel scheduling")))
        val comments = FakeComments(spokeThrice("sol").take(2))

        val changed = service(comments, personas, interests, changes, llm).drift(DriftSource.MANUAL)

        assertEquals(0, changed)
        assertTrue(llm.received.isEmpty(), "two engagements under the three-engagement floor buys no judgment")
        assertNull(interests.judgedAt("sol"), "a member that was never looked at was not judged either")
    }

    // --- the guardrail and the failure posture -----------------------------------------------------

    @Test
    fun `a judgment carrying a number is refused and nothing is written`() {
        // The one place a number could enter what a member is into is the model's answer, so a
        // digit-bearing answer leaves the interests exactly where they were — and writes no audit row,
        // because nothing happened for the owner to review.
        val personas = RosterPersonas(listOf(persona("sol")))
        val interests = FakeInterests().apply { seed("sol", "typography") }
        val changes = FakeInterestChanges()
        val llm = ScriptedLlm(says(swap("typography", "kernel scheduling, priority 2 of 5")))
        val comments = FakeComments(spokeThrice("sol"))

        val changed = service(comments, personas, interests, changes, llm).drift(DriftSource.SCHEDULED)

        assertEquals(0, changed)
        assertEquals(listOf("typography"), interests.phrasesOf("sol"))
        assertTrue(changes.rows.isEmpty(), "a refused judgment is not a change, so it is not audited")
    }

    /**
     * The cost split, in the direction that costs money: a REJECTED answer never told us what the judge
     * thought of this evidence (the model returned a digit), so the window stays open and the same
     * engagements are judged again next run. Stamping here would let one disobedient answer bury a
     * conversation for good.
     */
    @Test
    fun `a refused answer leaves the window open, so the evidence is judged again`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val interests = FakeInterests().apply { seed("sol", "typography") }
        val changes = FakeInterestChanges()
        val llm = ScriptedLlm(
            says(
                swap("typography", "kernel scheduling, priority 2 of 5"),
                swap("typography", "kernel scheduling"),
            ),
        )
        val comments = FakeComments(spokeThrice("sol"))
        val svc = service(comments, personas, interests, changes, llm)

        svc.drift(DriftSource.SCHEDULED)
        assertNull(interests.judgedAt("sol"), "a refused answer is not a judgment of the evidence")

        svc.drift(DriftSource.SCHEDULED)

        assertEquals(2, llm.received.size, "the retry the per-member window exists for")
        assertEquals(listOf("kernel scheduling"), interests.phrasesOf("sol"))
    }

    /**
     * THE cost defect S4a shipped and had to fix, pinned here from the first commit. The judge is
     * INSTRUCTED to answer NONE when the member's own words do not pull it anywhere, so "nothing moved" is
     * the designed steady state of most members most weeks — and it writes no audit row, deliberately,
     * because a history page full of "nothing happened" destroys the one control the owner has. Bind the
     * window to the audit table alone and that member re-qualifies on the same engagements every run and
     * buys another judgment every week, forever, across the whole roster.
     *
     * Delete the `markJudged` on the unchanged branch and the second run below judges again: the scripted
     * swap is waiting for it, so the interest moves and this test reddens.
     */
    @Test
    fun `an unchanged verdict writes nothing, yet still closes the window`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val interests = FakeInterests().apply { seed("sol", "typography") }
        val changes = FakeInterestChanges()
        val llm = ScriptedLlm(says("NONE", swap("typography", "kernel scheduling")))
        val comments = FakeComments(spokeThrice("sol"))
        val svc = service(comments, personas, interests, changes, llm)

        val changed = svc.drift(DriftSource.SCHEDULED)
        svc.drift(DriftSource.SCHEDULED)

        assertEquals(0, changed)
        assertEquals(listOf("typography"), interests.phrasesOf("sol"), "no interest write")
        assertTrue(changes.rows.isEmpty(), "nothing moved, so the owner's history page records nothing")
        assertEquals(RUN_STAMP, interests.judgedAt("sol"), "but the evidence WAS judged, and is closed")
        assertEquals(1, llm.received.size, "a settled member must not re-buy the same judgment every run")
    }

    @Test
    fun `one member's seam failure does not cost the next member its judgment`() {
        // The 04:30 rate-limit case. An unattended pass that dies on the first member and takes the run
        // with it is worse than one that records the failure and judges everybody else.
        val personas = RosterPersonas(listOf(persona("dana"), persona("sol")))
        val interests = FakeInterests().apply {
            seed("dana", "typography")
            seed("sol", "small tools")
        }
        val changes = FakeInterestChanges()
        // Neither member has been judged, so the id tiebreak orders them and dana's judgment is the one
        // that explodes.
        val llm = ScriptedLlm(
            listOf({ throw RuntimeException("rate-limited") }, { swap("small tools", "kernel scheduling") }),
        )
        val comments = FakeComments(spokeThrice("dana") + spokeThrice("sol"))

        val changed = service(comments, personas, interests, changes, llm).drift(DriftSource.SCHEDULED)

        assertEquals(1, changed, "the run must return, not throw")
        assertEquals(listOf("typography"), interests.phrasesOf("dana"))
        assertEquals(listOf("kernel scheduling"), interests.phrasesOf("sol"))
        // A rate limit is not a verdict: the evidence was never judged, so the window stays open and next
        // week's run gets another look at it. Stamping here would make a busy provider look, to every
        // later run, exactly like a member who has settled.
        assertNull(interests.judgedAt("dana"), "a seam failure must not close the window")
    }

    /**
     * The STORAGE side of the same promise, and the one the LLM guard does not cover: a locked database or
     * a constraint violation on one member's write must cost that member and nothing else. Without a
     * per-member catch it escapes to the run-level one, and the pass returns looking like it finished
     * while every member still queued behind the bad one was silently abandoned.
     */
    @Test
    fun `one member's repository failure does not abandon the rest of the run`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("sol")))
        val interests = FakeInterests().apply {
            seed("dana", "typography")
            seed("sol", "small tools")
        }
        val changes = FakeInterestChanges(failFor = "dana")
        val llm = ScriptedLlm(
            says(swap("typography", "release engineering"), swap("small tools", "kernel scheduling")),
        )
        val comments = FakeComments(spokeThrice("dana") + spokeThrice("sol"))

        val changed = service(comments, personas, interests, changes, llm).drift(DriftSource.SCHEDULED)

        assertEquals(1, changed, "the run continues past the member whose audit write threw")
        assertEquals(listOf("typography"), interests.phrasesOf("dana"), "nothing half-written")
        assertNull(interests.judgedAt("dana"), "a swap that never landed must not close its window")
        assertEquals(listOf("kernel scheduling"), interests.phrasesOf("sol"))
    }

    /**
     * The audit row, the delete, the insert and the watermark stamp are ONE unit of work. Committing the
     * audit row alone is the worst of both worlds — the owner reads a swap that never happened, and that
     * row stands as the member's history — and committing the delete without the insert costs the member
     * an interest outright, which is I3 broken in the direction nobody is watching.
     *
     * `@Transactional` could not do this job here: the write path is a private method the service calls on
     * itself, and a Spring proxy sees neither — the annotation would read as a guarantee and do nothing at
     * all. So the service drives a real [TransactionTemplate], and what this pins is that it asked for a
     * rollback rather than a commit, plus the watermark it never reached. The rollback itself is Spring's,
     * over a real DataSource (`CommentRepositoryTransactionTest`); in-memory fakes cannot be undone, so
     * the interest rows are deliberately not asserted on here.
     */
    @Test
    fun `an interest write that fails rolls back the audit row rather than committing it alone`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val interests = FakeInterests(failUpsertFor = "sol").apply { seed("sol", "typography") }
        val changes = FakeInterestChanges()
        val llm = ScriptedLlm(says(swap("typography", "kernel scheduling")))
        val comments = FakeComments(spokeThrice("sol"))
        val manager = RecordingTransactions()

        val changed = service(
            comments, personas, interests, changes, llm,
            transactions = TransactionTemplate(manager),
        ).drift(DriftSource.SCHEDULED)

        assertEquals(0, changed)
        assertEquals(1, manager.rollbacks, "the audit row must not be left standing for a swap that failed")
        assertEquals(0, manager.commits)
        assertNull(interests.judgedAt("sol"), "and the failed swap must not close the member's window")
    }

    // --- the pass's own duration -------------------------------------------------------------------

    /**
     * The never-clobber contract has to survive the pass's own DURATION, which is the part a single-member
     * test cannot reach — and it is the defect S4a shipped and had to fix in `bed019fe`. The pass reads its
     * queue once (ordering and windows need one consistent view), runs uncapped and synchronous, and
     * spends up to a minute per judgment, so the owner is looking at a hung browser tab for as long as the
     * run takes and the persona form in another tab is exactly where they go while they wait.
     *
     * Here the owner pins sol's interest WHILE dana is being judged. Nothing in the queue knows, so a pass
     * that trusts its snapshot deletes the phrase the owner just pinned and writes the model's in its
     * place — with the audit row citing the PRE-EDIT provenance, so Revert would restore a `seeded` row
     * over what the owner typed and their own choice is gone from a table that keeps no history.
     * Unrecoverable, not merely wrong.
     */
    @Test
    fun `an interest the owner pins mid-pass is not overwritten by the pass already running`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("sol")))
        val interests = FakeInterests().apply {
            seed("dana", "typography")
            seed("sol", "small tools")
        }
        val changes = FakeInterestChanges()
        // Neither member is judged, so the id tiebreak puts dana first. The owner's edit lands during
        // dana's judgment — the scripted answer IS that moment — and sol's phrase is what they pin.
        val llm = ScriptedLlm(
            listOf(
                {
                    interests.seed("sol", "small tools", PersonaInterestRepository.SOURCE_OWNER)
                    swap("typography", "release engineering")
                },
                { swap("small tools", "kernel scheduling") },
            ),
        )
        val comments = FakeComments(spokeThrice("dana") + spokeThrice("sol"))

        val changed = service(comments, personas, interests, changes, llm).drift(DriftSource.MANUAL)

        assertEquals(listOf("small tools"), interests.phrasesOf("sol"), "the owner's choice survives a running pass")
        assertEquals(
            PersonaInterestRepository.SOURCE_OWNER, interests.sourceOf("sol", "small tools"),
            "and keeps its provenance, which is what keeps it out of every later pass",
        )
        assertTrue(
            changes.rows.none { it.personaId == "sol" },
            "no audit row may claim a swap for a member the pass must not touch",
        )
        assertEquals(1, changed, "dana still drifts — one owner edit costs one member, not the run")
        assertEquals(
            1, llm.received.size,
            "the skip is decided BEFORE the judgment, so a pinned member costs nothing even mid-pass",
        )
        assertNull(interests.judgedAt("sol"), "a member the pass never judged keeps its window open")
    }

    // --- single flight -----------------------------------------------------------------------------

    /**
     * `POST /admin/interests/drift` runs the whole pass synchronously on the request thread, with no cap by
     * default and up to 60s per judgment — so the browser giving up and the owner clicking again is the
     * NORMAL way a second pass starts, not a hypothetical one. Both passes would then read the same
     * unstamped members: every judgment paid for twice, and a second audit row whose "before" is the first
     * pass's "after", which is a swap the member never went through.
     *
     * Two real threads with latches rather than a re-entrant call, because the guard's job is to hold
     * against a genuinely concurrent caller; the latches keep it deterministic (no sleeps, no polling).
     */
    @Test
    fun `a second caller while a pass is in flight does nothing at all`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val interests = FakeInterests().apply { seed("sol", "typography") }
        val changes = FakeInterestChanges()
        val judging = CountDownLatch(1)
        val release = CountDownLatch(1)
        val llm = ScriptedLlm(
            listOf({
                judging.countDown()
                release.await(5, TimeUnit.SECONDS)
                swap("typography", "kernel scheduling")
            }),
        )
        val comments = FakeComments(spokeThrice("sol"))
        val svc = service(comments, personas, interests, changes, llm)

        val first = thread(name = "interest-drift-pass") { svc.drift(DriftSource.MANUAL) }
        assertTrue(judging.await(5, TimeUnit.SECONDS), "the first pass should reach its judgment")

        val second = svc.drift(DriftSource.MANUAL)

        assertEquals(0, second, "the second caller returns immediately, having changed nothing")
        assertEquals(1, llm.received.size, "and having bought no judgment")
        release.countDown()
        first.join(5_000)
        assertEquals(1, changes.rows.size, "one pass, one audit row — not one before→after that never happened")
        // The guard is released, so the NEXT click is a normal pass again rather than a permanent no-op.
        assertEquals(0, svc.drift(DriftSource.MANUAL), "nothing new to judge, but the guard is open")
    }

    // --- the cap, and what it must not starve ------------------------------------------------------

    /**
     * The ordering, pinned where it actually decides something: BOTH members qualify at once and the cap
     * must choose one, so window age is the only thing that can decide. lune was judged at nine and dana at
     * noon, both have written since — and the older window goes first, which is the opposite of what id or
     * roster order would pick, since dana sorts before lune.
     *
     * Sort by name instead and `take(cap)` starves the tail FOREVER rather than "later": a member that
     * keeps coming back unchanged, refused or rate-limited would hold the same budget slot on every run.
     */
    @Test
    fun `with both members qualifying, the cap spends on the older window, not the earlier name`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("lune")))
        val interests = FakeInterests().apply {
            seed("dana", "typography")
            seed("lune", "small tools")
            judgedSince("dana", "2026-01-01T12:00:00Z")
            judgedSince("lune", "2026-01-01T09:00:00Z")
        }
        val changes = FakeInterestChanges()
        val llm = ScriptedLlm(says(swap("small tools", "kernel scheduling")))
        val comments = FakeComments(
            spokeThrice("dana", createdAt = "2026-01-01T12:30:00Z") +
                spokeThrice("lune", createdAt = "2026-01-01T12:30:00Z"),
        )

        service(
            comments, personas, interests, changes, llm,
            props = InterestDriftProperties(maxPersonasPerRun = 1),
        ).drift(DriftSource.SCHEDULED)

        assertEquals(1, llm.received.size, "the cap bounds judgments, not just writes")
        assertEquals(
            listOf("kernel scheduling"), interests.phrasesOf("lune"),
            "the member waiting longest was judged",
        )
        assertEquals(
            listOf("typography"), interests.phrasesOf("dana"),
            "the recently-judged member waits its turn, however early its name sorts",
        )
        // Every member has a window, so the read is bounded — one whole second below the oldest of them,
        // which is the exact width of the lexicographic anomaly the SQL comparison has to clear.
        assertEquals(listOf<String?>("2026-01-01T08:59:59Z"), comments.windows)
    }

    @Test
    fun `zero means unlimited, so every qualifying member is judged`() {
        val personas = RosterPersonas(listOf(persona("dana"), persona("sol")))
        val interests = FakeInterests().apply {
            seed("dana", "typography")
            seed("sol", "small tools")
        }
        val changes = FakeInterestChanges()
        val llm = ScriptedLlm(
            says(swap("typography", "release engineering"), swap("small tools", "kernel scheduling")),
        )
        val comments = FakeComments(spokeThrice("dana") + spokeThrice("sol"))

        val changed = service(
            comments, personas, interests, changes, llm,
            props = InterestDriftProperties(maxPersonasPerRun = 0),
        ).drift(DriftSource.SCHEDULED)

        assertEquals(2, changed)
        assertEquals(2, changes.rows.size, "one audit row per member that moved")
    }

    // --- the convergence guardrail -----------------------------------------------------------------

    /**
     * D12's second test, and the reason it is worth its own run: the room map on /admin/interests makes
     * convergence visible to the OWNER, and this asserts it stays invisible to every model. The judge's
     * instruction is byte-identical whether the room shares a phrase or not, because it is built from one
     * member's own material and nothing else — so there is no cross-member channel to converge through and
     * nothing population-shaped to optimise against.
     *
     * A later "give the judge a little room context" change reddens here instead of quietly opening the
     * channel.
     */
    @Test
    fun `the judge prompt is byte-identical over a converged and an un-converged room`() {
        fun promptFor(paulIsInto: String): String {
            val personas = RosterPersonas(listOf(persona("paul"), persona("sol")))
            val interests = FakeInterests().apply {
                seed("sol", "typography")
                seed("paul", paulIsInto)
            }
            val llm = ScriptedLlm(says("NONE"))
            // Only sol has written, so only sol is judged; paul is on the roster to be converged WITH.
            val comments = FakeComments(spokeThrice("sol", to = "paul"))
            service(comments, personas, interests, FakeInterestChanges(), llm).drift(DriftSource.SCHEDULED)
            return llm.received.single().context.comments.single().body
        }

        assertEquals(
            promptFor("release engineering"), promptFor("typography"),
            "what the rest of the room is into must make no difference to what one member is asked",
        )
    }

    // --- revert ------------------------------------------------------------------------------------

    /**
     * Revert undoes the swap IN FULL: the phrase, its provenance, and the window the judgment took. Leaving
     * the stamp would put the engagements the owner just disagreed about permanently out of reach and turn
     * one disagreement into a silent, permanent opt-out — the member would be free to drift in principle
     * and blind to its own words in practice. Freezing is what pinning is for, so the second pass here
     * moves the restored phrase again, deliberately.
     */
    @Test
    fun `revert restores the phrase AND its provenance, and reopens the window`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val interests = FakeInterests().apply { seed("sol", "typography") }
        val changes = FakeInterestChanges()
        val llm = ScriptedLlm(
            says(swap("typography", "kernel scheduling"), swap("typography", "release engineering")),
        )
        val comments = FakeComments(spokeThrice("sol"))
        val svc = service(comments, personas, interests, changes, llm)
        svc.drift(DriftSource.MANUAL)

        val reverted = svc.revert(changes.rows.single().id)

        assertTrue(reverted)
        assertEquals(listOf("typography"), interests.phrasesOf("sol"))
        // Restoring `seeded` is the point: leaving it `drifted` would relabel a hand-seeded phrase, and the
        // reverted phrase is deliberately free to drift again rather than frozen.
        assertEquals(PersonaInterestRepository.SOURCE_SEEDED, interests.sourceOf("sol", "typography"))
        assertEquals(REVERT_STAMP, changes.rows.single().revertedAt)
        // With no surviving change left, the watermark is CLEARED rather than left standing: this member is
        // back to never-judged, which is exactly the state the reverted judgment found it in.
        assertNull(interests.judgedAt("sol"), "the reverted judgment gives back the window it took")
        assertEquals(1, llm.received.size, "a revert restores captured values; it buys no judgment")

        svc.drift(DriftSource.MANUAL)

        assertEquals(
            listOf("release engineering"), interests.phrasesOf("sol"),
            "the reopened window means the same engagements are judged again — revert undoes, it does not freeze",
        )
    }

    @Test
    fun `a revert rolls the window back to the surviving change, not to the beginning of time`() {
        // Everything before the previous surviving change was already acted on and the owner did not object
        // to any of it, so a revert reopens exactly the evidence the rejected judgment consumed and nothing
        // older. Read AFTER markReverted, or the query hands back this very change's stamp and the revert
        // reopens nothing at all.
        val personas = RosterPersonas(listOf(persona("sol")))
        val interests = FakeInterests().apply { seed("sol", "typography") }
        val changes = FakeInterestChanges().apply { seed("sol", SURVIVING_CHANGE) }
        val llm = ScriptedLlm(says(swap("typography", "kernel scheduling")))
        val comments = FakeComments(spokeThrice("sol"))
        val svc = service(comments, personas, interests, changes, llm)
        svc.drift(DriftSource.MANUAL)

        svc.revert(changes.rows.last().id)

        assertEquals(SURVIVING_CHANGE, interests.judgedAt("sol"))
    }

    @Test
    fun `an unknown id and a second revert are both no-ops`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        // The member must actually HOLD the phrase the audit row says it took up, or the revert is
        // refused as superseded before either guard under test is reached. The old fixture seeded an
        // unrelated phrase, which made this test pass while describing a state the pass cannot produce.
        val interests = FakeInterests().apply { seed("sol", "now") }
        val changes = FakeInterestChanges().apply { seed("sol", STAMP) }
        val llm = ScriptedLlm()
        val svc = service(FakeComments(), personas, interests, changes, llm)

        assertFalse(svc.revert(404L), "an unknown change reverts nothing")
        assertTrue(svc.revert(1L))
        assertFalse(svc.revert(1L), "the reverted_at stamp is the double-revert guard")
        // Still the stamp from the FIRST revert: re-stamping would move the record of when the owner
        // actually intervened to whenever they last double-clicked.
        assertEquals(REVERT_STAMP, changes.rows.single().revertedAt)
        assertTrue(llm.received.isEmpty(), "no revert path touches the seam")
    }

    /**
     * A SUPERSEDED change cannot be undone. Reverting restores `dropped` and removes `takenUp` — but a
     * later drift has already moved `takenUp` on, so the delete finds nothing while the upsert still
     * lands, and the member ends up holding one MORE phrase than it started with. That breaks the
     * one-for-one count invariant through the owner's own control surface, and the log offers a Revert
     * button on every unreverted row.
     */
    @Test
    fun `reverting a superseded change is refused, rather than adding an interest back`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        // A -> B happened, then B -> C: the member now holds C, and the FIRST change's takenUp (B) is
        // long gone.
        val interests = FakeInterests().apply { seed("sol", "C") }
        val changes = FakeInterestChanges().apply {
            seedSwap("sol", dropped = "A", takenUp = "B", changedAt = SURVIVING_CHANGE)
            seedSwap("sol", dropped = "B", takenUp = "C", changedAt = STAMP)
        }
        val svc = service(FakeComments(), personas, interests, changes, ScriptedLlm())

        assertFalse(svc.revert(1L), "the first change is superseded and cannot be undone")
        assertEquals(listOf("C"), interests.phrasesOf("sol"), "and the member's set is untouched")
        assertNull(changes.rows.first().revertedAt, "a refused revert leaves the audit row un-reverted")
    }

    /**
     * Owner provenance is a freeze, and revert is not an exception to it. The pass swapped X for Y; the
     * owner then typed X back, which is the documented pinning gesture and stamps it `owner`. Reverting
     * the change would upsert X with its OLD provenance over that row — `upsert` overwrites provenance
     * by design — and reopen the window, so the next pass could set aside the phrase the owner had just
     * pinned, with nothing left to show a pin ever existed. The change is not superseded (Y is still
     * held), so the presence guard passes it.
     */
    @Test
    fun `a revert that would land on an owner-pinned phrase is refused, not applied`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val interests = FakeInterests().apply {
            seed("sol", "Y")
            seed("sol", "X", PersonaInterestRepository.SOURCE_OWNER)
        }
        val changes = FakeInterestChanges().apply {
            seedSwap("sol", dropped = "X", takenUp = "Y", changedAt = STAMP)
        }
        val svc = service(FakeComments(), personas, interests, changes, ScriptedLlm())

        assertFalse(svc.revert(1L), "restoring X would overwrite the owner's own pin of X")
        assertEquals(
            PersonaInterestRepository.SOURCE_OWNER, interests.sourceOf("sol", "X"),
            "the pin survives, which is the whole of D11's promise",
        )
        assertNull(changes.rows.single().revertedAt, "a refused revert leaves the row honestly un-reverted")
    }

    /**
     * The mirror of the same hazard: the owner pinned the phrase the pass TOOK UP. Reverting would
     * `delete` it, which would make revert the only path in the system that removes an owner-authored
     * row without the owner's own form.
     */
    @Test
    fun `a revert that would delete an owner-pinned phrase is refused`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val interests = FakeInterests().apply { seed("sol", "Y", PersonaInterestRepository.SOURCE_OWNER) }
        val changes = FakeInterestChanges().apply {
            seedSwap("sol", dropped = "X", takenUp = "Y", changedAt = STAMP)
        }
        val svc = service(FakeComments(), personas, interests, changes, ScriptedLlm())

        assertFalse(svc.revert(1L))
        assertEquals(listOf("Y"), interests.phrasesOf("sol"), "the owner's phrase is untouched")
        assertEquals(PersonaInterestRepository.SOURCE_OWNER, interests.sourceOf("sol", "Y"))
    }

    private companion object {
        // The fakes stamp rather than read a Clock, so a test can assert an exact value; the real
        // repositories take these from the injected Clock, which is what keeps src/main at zero
        // Instant.now() reads.
        const val STAMP = "2026-01-01T12:00:00Z"
        const val REVERT_STAMP = "2026-01-02T12:00:00Z"

        /** When the members wrote what they wrote — before the pass reads it, which is the real sequence. */
        const val TALKED_AT = "2026-01-01T12:00:00Z"

        /** An earlier audited change the owner did NOT revert — the floor a revert may roll back to. */
        const val SURVIVING_CHANGE = "2026-01-01T09:00:00Z"

        /**
         * What the SERVICE's own clock reads — the instant every watermark this suite stamps carries.
         * Deliberately later than the engagements, because that is the order things happen in: the members
         * write, then the pass reads what they wrote.
         */
        const val RUN_STAMP = "2026-01-01T13:00:00Z"
    }
}
