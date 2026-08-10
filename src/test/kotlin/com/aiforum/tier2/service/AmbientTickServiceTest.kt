package com.aiforum.tier2.service

import ch.qos.logback.classic.Level
import com.aiforum.ambient.Article
import com.aiforum.ambient.ArticleSource
import com.aiforum.ambient.TickSource
import com.aiforum.domain.budget.DepthBudget
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReplyView
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
import com.aiforum.testsupport.LogCapture
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
 * only ONE tick: the round-robin author arithmetic across SEVERAL ticks, the S2 action-parity/cross-fallback,
 * the gate-driven author pick, the two comment exclusions, and the failure-recording path (a throwing source
 * records 'failed' and never propagates). No IO seam is touched — the LLM never runs (the tick makes no call
 * of its own; the summon is a spy).
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

    /** Records inserted threads AND serves a programmed set of active threads to the comment action;
     *  [throwsOnActive] models a DB fault (e.g. SQLITE_BUSY) in the comment branch's thread scan. */
    private class RecordingThreads(
        private val active: List<ThreadRepository.Thread> = emptyList(),
        private val throwsOnActive: Boolean = false,
    ) : ThreadRepository(JdbcTemplate(), Clock.systemUTC()) {
        val inserted = mutableListOf<Triple<String, String, String?>>() // id, title, authorId
        override fun insert(id: String, title: String, body: String, authorId: String?) {
            inserted += Triple(id, title, authorId)
        }

        override fun findActive(limit: Int): List<ActiveThread> {
            if (throwsOnActive) throw RuntimeException("comment scan exploded")
            return active.take(limit).map { ActiveThread(it.id, it.title, "t") }
        }

        override fun find(id: String) = active.firstOrNull { it.id == id }
    }

    /** Serves a programmed thread -> already-posted-authors map for exclusion rule (b). */
    private class FakeComments(private val posted: Map<String, Set<String>> = emptyMap()) :
        CommentRepository(JdbcTemplate(), Clock.systemUTC()) {
        override fun postedAuthors(threadId: String) = posted[threadId] ?: emptySet()
    }

    /**
     * The run log in memory. [record] mirrors the real repository's `INSERT … RETURNING id` by handing
     * back the 1-based row id (issue #15) — that id is what the tick passes to its post-settle hook, so a
     * fake that returned nothing would make the cost path untestable. [added] captures every [addCost].
     */
    private class RecordingRuns : AmbientRunRepository(JdbcTemplate(), Clock.systemUTC()) {
        val runs = mutableListOf<AmbientRun>()
        val added = mutableListOf<Pair<Long, Double>>()

        /** When set, every [addCost] throws — a locked accounting UPDATE (SQLITE_BUSY) in one flag. */
        var costThrows = false
        override fun count() = runs.size
        override fun record(
            source: TickSource,
            outcome: String,
            action: String,
            detail: String?,
            articleTitle: String?,
            articleUrl: String?,
            personaId: String?,
            threadId: String?,
            costUsd: Double?,
        ): Long {
            val id = runs.size + 1L
            runs += AmbientRun(
                id, "t", source.name.lowercase(), outcome, action, detail,
                articleTitle, articleUrl, personaId, threadId, costUsd,
            )
            return id
        }

        override fun addCost(runId: Long, deltaUsd: Double) {
            if (costThrows) throw RuntimeException("accounting UPDATE is locked")
            added += runId to deltaUsd
        }
    }

    /** One captured summon call — enough to assert WHO was summoned WHERE with WHICH starting budget. */
    private data class SummonCall(val threadId: String, val personaIds: List<String>, val initialBudget: Int?)

    private class SpyGeneration : GenerationService(
        object : LlmClient {
            override fun generate(request: LlmRequest, cancellation: CancellationToken) = LlmResponse("x")
        },
        CommentRepository(JdbcTemplate(), Clock.systemUTC()),
        PersonaRepository(JdbcTemplate()),
    ) {
        val summons = mutableListOf<SummonCall>()

        /** The post-settle hook each summon carried (null when none) — index-aligned with [summons]. */
        val settleHooks = mutableListOf<((List<ReplyView>) -> Unit)?>()

        /** Every autoGrow invocation as (threadId, withinSubtreeOf) — populated only via a captured hook. */
        val grown = mutableListOf<Pair<String, String?>>()

        /** What a captured hook's autoGrow should hand back — the growth round's own settled views, so
         *  the two-phase cost write (issue #15) can be driven without a real generation. */
        var growthReplies: List<ReplyView> = emptyList()

        /** Subtree roots whose growth round explodes — one node failing while its siblings do not. */
        var growthThrowsFor: Set<String> = emptySet()

        /** How many runs had been recorded by the time each summon was dispatched — the record-BEFORE-
         *  dispatch proof (issue #15). Populated by the test's own probe, index-aligned with [summons]. */
        var onSummon: () -> Unit = {}

        override fun summonAsync(
            threadId: String,
            parentId: String?,
            personaIds: List<String>,
            text: String,
            scope: ScopeMode,
            includeSiblings: Boolean,
            postAsOwner: Boolean,
            routingScope: ScopeMode,
            initialBudget: Int?,
            onSettled: ((List<ReplyView>) -> Unit)?,
        ) {
            onSummon()
            summons += SummonCall(threadId, personaIds, initialBudget)
            settleHooks += onSettled
        }

        override fun autoGrow(threadId: String, withinSubtreeOf: String?): List<ReplyView> {
            // Recorded BEFORE the throw, so a test can prove the attempt happened as well as that it failed.
            grown += threadId to withinSubtreeOf
            if (withinSubtreeOf in growthThrowsFor) throw RuntimeException("growth round exploded for $withinSubtreeOf")
            return growthReplies
        }
    }

    /** A settled node as the post-settle hook sees it — only id and cost are read there. */
    private fun settledView(id: String, costUsd: Double? = null) = ReplyView(
        id = id, authorId = "sol", body = "b", state = GenerationState.POSTED, failureCategory = null,
        reason = null, retryable = false, retryAfterSeconds = null, voteCount = 0, depth = 0,
        costUsd = costUsd,
    )

    private fun persona(id: String, abilities: List<String> = emptyList(), talkativeness: Int? = null) =
        PersonaRepository.Persona(
            id, id, "", "",
            abilities = abilities,
            dials = talkativeness?.let { mapOf("talkativeness" to it) } ?: emptyMap(),
        )

    private fun article(title: String) = Article(title, "https://example.org/$title", "summary of $title")

    private fun thread(id: String, title: String, authorId: String? = null) =
        ThreadRepository.Thread(id, title, "", authorId = authorId)

    private fun service(
        source: ArticleSource,
        personas: PersonaRepository,
        threads: RecordingThreads,
        runs: RecordingRuns,
        gen: SpyGeneration,
        comments: CommentRepository = FakeComments(),
    ) = AmbientTickService(source, personas, threads, runs, gen, comments)

    @Test
    fun `the author rotates round-robin over the rowid roster, keyed by prior run count`() {
        val personas = RosterPersonas(listOf(persona("sol"), persona("vex")))
        val threads = RecordingThreads()
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        val source = FakeSource(listOf(article("A"), article("B"), article("C")))
        val svc = service(source, personas, threads, runs, gen)

        svc.tick(TickSource.MANUAL)   // count 0 -> post preferred, no relevance -> roster[0] = sol
        svc.tick(TickSource.MANUAL)   // count 1 -> comment preferred, none clears -> post roster[1] = vex
        svc.tick(TickSource.MANUAL)   // count 2 -> post preferred, roster[0] = sol (wraps)

        // Each thread carries its persona byline in round-robin order — never the owner.
        assertEquals(listOf("sol", "vex", "sol"), threads.inserted.map { it.third })
        // Three posted 'post' runs, each attributed to the same persona that authored the thread.
        assertEquals(listOf("posted", "posted", "posted"), runs.runs.map { it.outcome })
        assertEquals(listOf("post", "post", "post"), runs.runs.map { it.action })
        assertEquals(listOf("sol", "vex", "sol"), runs.runs.map { it.personaId })
        // The room was summoned on each opened thread, in order (a post summon carries no initialBudget).
        assertEquals(threads.inserted.map { it.first }, gen.summons.map { it.threadId })
        assertTrue(gen.summons.all { it.initialBudget == null }, "a post summon inherits budget, not AMBIENT_GRANT")
    }

    @Test
    fun `a throwing article source records a failed run and never propagates`() {
        val personas = RosterPersonas(listOf(persona("sol")))
        val threads = RecordingThreads()
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        val svc = service(FakeSource(throws = true), personas, threads, runs, gen)

        svc.tick(TickSource.SCHEDULED)   // must NOT throw (Exception is caught; a JVM Error still would)

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

    // --- S2: action parity + cross-fallback -------------------------------------------------------

    @Test
    fun `a post-preferred tick with no article falls back to the comment action`() {
        // count 0 → post preferred; but the source is empty, so it falls back to comment (§5 step 2).
        val threads = RecordingThreads(listOf(thread("T1", "Scaling SQLite")))
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        val personas = RosterPersonas(listOf(persona("sol", abilities = listOf("sqlite"), talkativeness = 8)))
        val svc = service(FakeSource(emptyList()), personas, threads, runs, gen)

        svc.tick(TickSource.MANUAL)

        val run = runs.runs.single()
        assertEquals("posted", run.outcome)
        assertEquals("comment", run.action)
        assertEquals("sol", run.personaId)
        assertEquals("T1", run.threadId)
        assertTrue(threads.inserted.isEmpty(), "a comment opens no new thread")
        // The comment is summoned at top level as the chosen persona, carrying the ambient depth grant.
        assertEquals(listOf(SummonCall("T1", listOf("sol"), DepthBudget.AMBIENT_GRANT)), gen.summons)
    }

    @Test
    fun `a comment-preferred tick with nothing to say falls back to posting`() {
        // Pre-seed one run so count 1 → comment preferred; the only persona authored the one active thread
        // (excluded from commenting there), so comment yields nothing and it falls back to the article post.
        val threads = RecordingThreads(listOf(thread("T1", "Scaling SQLite", authorId = "sol")))
        val runs = RecordingRuns().apply { record(TickSource.MANUAL, "posted", "post", null, "seed", null, "sol", "T0", null) }
        val gen = SpyGeneration()
        val personas = RosterPersonas(listOf(persona("sol", abilities = listOf("sqlite"), talkativeness = 8)))
        val svc = service(FakeSource(listOf(article("Q"))), personas, threads, runs, gen)

        svc.tick(TickSource.MANUAL)

        assertEquals("post", runs.runs.last().action, "comment had no eligible pair, so it posted the article")
        assertEquals(listOf("sol"), threads.inserted.map { it.third })
    }

    // --- S2: gate-driven author pick + comment exclusions ------------------------------------------

    @Test
    fun `the post author is the best relevance match, not the round-robin slot`() {
        // roster[0] is alpha (no abilities); beta matches the article, so relevance ranking picks beta,
        // overriding the count%size=0 round-robin slot that would otherwise pick alpha.
        val threads = RecordingThreads()
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        val personas = RosterPersonas(listOf(persona("alpha"), persona("beta", abilities = listOf("summary"))))
        val svc = service(FakeSource(listOf(article("A"))), personas, threads, runs, gen)

        svc.tick(TickSource.MANUAL)

        // article body = "A\n\nsummary of A"; beta's "summary" tag matches, alpha's none.
        assertEquals("beta", threads.inserted.single().third)
        assertEquals("beta", runs.runs.single().personaId)
    }

    @Test
    fun `the comment action excludes the thread author and pins the clearing persona`() {
        // sol authored T1 (exclusion a); vex matches + is chatty, so vex is the one summoned to comment.
        val threads = RecordingThreads(listOf(thread("T1", "Scaling SQLite", authorId = "sol")))
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        val personas = RosterPersonas(
            listOf(
                persona("sol", abilities = listOf("sqlite"), talkativeness = 8),
                persona("vex", abilities = listOf("sqlite"), talkativeness = 8),
            ),
        )
        val svc = service(FakeSource(emptyList()), personas, threads, runs, gen)

        svc.tick(TickSource.MANUAL)

        assertEquals("vex", runs.runs.single().personaId, "sol authored the thread, so vex comments")
        assertEquals(listOf("vex"), gen.summons.single().personaIds)
    }

    @Test
    fun `the comment action excludes personas who already posted in the thread`() {
        // Owner-authored thread; sol already POSTED there (exclusion b), so vex is the one that comments.
        val threads = RecordingThreads(listOf(thread("T1", "Scaling SQLite")))
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        val comments = FakeComments(mapOf("T1" to setOf("sol")))
        val personas = RosterPersonas(
            listOf(
                persona("sol", abilities = listOf("sqlite"), talkativeness = 8),
                persona("vex", abilities = listOf("sqlite"), talkativeness = 8),
            ),
        )
        val svc = service(FakeSource(emptyList()), personas, threads, runs, gen, comments)

        svc.tick(TickSource.MANUAL)

        assertEquals("vex", runs.runs.single().personaId)
    }

    @Test
    fun `the comment action hooks automatic growth on settle, the post action hooks none`() {
        // Comment tick (post falls back — the source is empty): the summon must carry a post-settle hook
        // that consumes the AMBIENT_GRANT by growing exactly the commented thread — and only when invoked
        // (i.e. after the settle), never at dispatch time.
        val threads = RecordingThreads(listOf(thread("T1", "Scaling SQLite")))
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        val personas = RosterPersonas(listOf(persona("sol", abilities = listOf("sqlite"), talkativeness = 8)))
        val svc = service(FakeSource(emptyList()), personas, threads, runs, gen)

        svc.tick(TickSource.MANUAL)

        val hook = gen.settleHooks.single()
        assertTrue(hook != null, "the comment summon must carry a post-settle growth hook")
        assertTrue(gen.grown.isEmpty(), "growth must not run at dispatch time — only after the settle")
        // The hook receives the nodes the summon settled and must grow ONLY those subtrees — an
        // owner-granted branch elsewhere in the thread (deliberately left un-grown) must never be drained
        // by an ambient settle, so a thread-wide autoGrow(threadId) here would be wrong.
        hook!!.invoke(listOf(settledView("ambient-node-1")))
        assertEquals(
            listOf("T1" to "ambient-node-1"), gen.grown,
            "the settle hook grows the commented thread scoped to the settled comment's own subtree",
        )

        // The POST action GROWS NOTHING: an article thread's first summoned round is born at budget 0 and
        // must stall without owner engagement (depth_budget's ambient-stall scenario) — growing there
        // would be a silent re-grant. It does carry a hook since issue #15, but that hook's only job is
        // pricing the run, which is why the assertion below is about `grown`, not about the hook's
        // existence. (count is now 1 → comment preferred, but the fresh RecordingThreads has no active
        // threads, so it falls back to posting the article.)
        val svc2 = service(FakeSource(listOf(article("A"))), personas, RecordingThreads(), runs, gen)
        svc2.tick(TickSource.MANUAL)

        assertEquals("post", runs.runs.last().action)
        gen.settleHooks.last()?.invoke(listOf(settledView("post-node-1")))
        assertEquals(
            listOf("T1" to "ambient-node-1"), gen.grown,
            "the post tick's settle must grow nothing, even once its hook has run",
        )
    }

    // --- issue #15: the run row is priced by the settle, and exists before the dispatch ---------------

    @Test
    fun `the run row is recorded BEFORE the summon is dispatched`() {
        // The race the ordering kills: the post-settle hook prices a run by id, and it runs on a worker.
        // Recording after summonAsync leaves a window in which a fast settle has nothing to price. The
        // probe fires INSIDE the fake's summonAsync, so it observes the world exactly at dispatch time.
        val threads = RecordingThreads()
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        var runsAtDispatch = -1
        gen.onSummon = { runsAtDispatch = runs.runs.size }
        val svc = service(FakeSource(listOf(article("A"))), RosterPersonas(listOf(persona("sol"))), threads, runs, gen)

        svc.tick(TickSource.MANUAL)

        assertEquals(1, runsAtDispatch, "the run row must already be committed when the summon is dispatched")
    }

    @Test
    fun `the post path charges the run with what its replies cost`() {
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        val svc = service(FakeSource(listOf(article("A"))), RosterPersonas(listOf(persona("sol"))), RecordingThreads(), runs, gen)

        svc.tick(TickSource.MANUAL)
        gen.settleHooks.single()!!.invoke(listOf(settledView("n1", 0.07), settledView("n2", 0.05)))

        assertEquals(listOf(1L to 0.12), runs.added.map { it.first to round4(it.second) })
    }

    @Test
    fun `the comment path charges the fan-out first, then the growth it triggered`() {
        // Two writes, in that order, is the point (issue #15): the comment's own spend is committed before
        // growth is attempted, so a growth round that throws still leaves the run priced for what it
        // definitely cost. One write at the end would lose the whole figure to that failure.
        val threads = RecordingThreads(listOf(thread("T1", "Scaling SQLite")))
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        gen.growthReplies = listOf(settledView("g1", 0.03), settledView("g2", 0.03))
        val personas = RosterPersonas(listOf(persona("sol", abilities = listOf("sqlite"), talkativeness = 8)))
        val svc = service(FakeSource(emptyList()), personas, threads, runs, gen)

        svc.tick(TickSource.MANUAL)
        gen.settleHooks.single()!!.invoke(listOf(settledView("c1", 0.05)))

        assertEquals(
            listOf(1L to 0.05, 1L to 0.06), runs.added.map { it.first to round4(it.second) },
            "phase one is the comment's own cost, phase two the growth round's",
        )
    }

    @Test
    fun `unpriced replies write no cost at all, and neither does an empty settle`() {
        // NULL means UNKNOWN. A provider that reports no cost (openai/opencode/stub, an older CLI) must
        // leave the column untouched — writing a summed 0.0 would claim the tick was free, which is the
        // one wrong answer for an operator watching spend.
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        val svc = service(FakeSource(listOf(article("A"))), RosterPersonas(listOf(persona("sol"))), RecordingThreads(), runs, gen)

        svc.tick(TickSource.MANUAL)
        val hook = gen.settleHooks.single()!!
        hook.invoke(listOf(settledView("n1"), settledView("n2")))
        hook.invoke(emptyList())

        assertTrue(runs.added.isEmpty(), "no priced reply means no UPDATE — never a claimed zero")
    }

    @Test
    fun `a failing cost write never aborts the growth round that follows it`() {
        // Accounting must never be able to abort product behaviour. Before issue #15 the growth round could
        // not be blocked by accounting because there WAS no accounting write in this hook; the phase-one
        // cost UPDATE put one in front of it, and an unguarded throw there (a locked row, a closed pool)
        // would take the whole round down with it. The reply is the product; the figure is commentary.
        val threads = RecordingThreads(listOf(thread("T1", "Scaling SQLite")))
        val runs = RecordingRuns().apply { costThrows = true }
        val gen = SpyGeneration()
        val personas = RosterPersonas(listOf(persona("sol", abilities = listOf("sqlite"), talkativeness = 8)))
        val svc = service(FakeSource(emptyList()), personas, threads, runs, gen)

        svc.tick(TickSource.MANUAL)
        LogCapture.on(AmbientTickService::class.java).use { logs ->
            gen.settleHooks.single()!!.invoke(listOf(settledView("c1", 0.05)))

            // The lost figure is operator-actionable — a run that silently stops being priced is exactly
            // the drift an operator watching spend needs told about — so it WARNs, under a stable id.
            val e = logs.withEvent("ambient.cost.failed").single()
            assertEquals(Level.WARN, e.level)
            assertEquals("accounting UPDATE is locked", logs.keyValue(e, "reason"))
        }

        assertEquals(
            listOf("T1" to "c1"), gen.grown,
            "the growth round runs even though its cost write threw — accounting never blocks the product",
        )
        assertTrue(runs.added.isEmpty(), "nothing was priced, and nothing pretended to be")
    }

    @Test
    fun `one node's growth failure does not lose a sibling node's growth cost`() {
        // Per-node isolation. autoGrow used to be flat-mapped bare, so the FIRST node to throw took the
        // whole phase-two write with it — and the already-persisted growth replies of every other node
        // went unpriced, spend that really happened and would never be charged. Each node's round is now
        // its own attempt; the loss window is the throwing node's own in-flight round and nothing else.
        val threads = RecordingThreads(listOf(thread("T1", "Scaling SQLite")))
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        gen.growthThrowsFor = setOf("c1")
        gen.growthReplies = listOf(settledView("g1", 0.04))
        val personas = RosterPersonas(listOf(persona("sol", abilities = listOf("sqlite"), talkativeness = 8)))
        val svc = service(FakeSource(emptyList()), personas, threads, runs, gen)

        svc.tick(TickSource.MANUAL)
        LogCapture.on(AmbientTickService::class.java).use { logs ->
            gen.settleHooks.single()!!.invoke(listOf(settledView("c1", 0.05), settledView("c2", 0.05)))

            val e = logs.withEvent("ambient.growth.failed").single()
            assertEquals(Level.WARN, e.level, "a swallowed growth round must not be silent")
            assertEquals("c1", logs.keyValue(e, "node"))
        }

        assertEquals(
            listOf("T1" to "c1", "T1" to "c2"), gen.grown,
            "c1's failure must not stop c2's branch from growing",
        )
        assertEquals(
            listOf(1L to 0.10, 1L to 0.04), runs.added.map { it.first to round4(it.second) },
            "phase two still charges the growth that DID happen — c2's round",
        )
    }

    /** Double addition is inexact; the run row is read at 4dp, so compare at the precision that ships. */
    private fun round4(v: Double) = Math.round(v * 10_000) / 10_000.0

    @Test
    fun `a comment-branch fault records a failed run attributed to the comment action`() {
        // Even count → post preferred, but the source is empty, so the tick falls back to the comment
        // branch — whose active-thread scan then throws (a realistic SQLITE_BUSY stand-in). The failed run
        // must carry action="comment": the fault was in the comment path, and a hardcoded "post" would
        // misdirect the operator toward the ArticleSource. No fallback-on-exception either: the throw ends
        // the tick (recorded, swallowed), it does not sneak back into the other action.
        val threads = RecordingThreads(throwsOnActive = true)
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        val personas = RosterPersonas(listOf(persona("sol", abilities = listOf("sqlite"), talkativeness = 8)))
        val svc = service(FakeSource(emptyList()), personas, threads, runs, gen)

        svc.tick(TickSource.MANUAL)   // must NOT throw

        val run = runs.runs.single()
        assertEquals("failed", run.outcome)
        assertEquals("comment", run.action)
        assertEquals("comment scan exploded", run.detail)
        assertTrue(threads.inserted.isEmpty(), "no fallback-on-exception: the post action must not run")
        assertTrue(gen.summons.isEmpty(), "a failed tick summons no one")
    }

    @Test
    fun `a no-op tick records the preferred action, not a hardcoded post`() {
        // One prior run → count 1 → comment preferred; no active threads and no article, so neither action
        // executes. The no-op row says whose TURN it was (comment), the most informative label available.
        val threads = RecordingThreads()
        val runs = RecordingRuns().apply {
            record(TickSource.MANUAL, "posted", "post", null, "seed", null, "sol", "T0", null)
        }
        val gen = SpyGeneration()
        val svc = service(FakeSource(emptyList()), RosterPersonas(listOf(persona("sol"))), threads, runs, gen)

        svc.tick(TickSource.MANUAL)

        assertEquals("no-op", runs.runs.last().outcome)
        assertEquals("comment", runs.runs.last().action)
    }

    @Test
    fun `a below-threshold thread yields no comment and no post`() {
        // vex matches (relevance 1) but is a near-lurker (talkativeness 2): 2*1 < THRESHOLD, so the gate
        // stays shut. With no article either, the tick is a clean no-op — and never touches the LLM seam.
        val threads = RecordingThreads(listOf(thread("T1", "Scaling SQLite")))
        val runs = RecordingRuns()
        val gen = SpyGeneration()
        val personas = RosterPersonas(listOf(persona("vex", abilities = listOf("sqlite"), talkativeness = 2)))
        val svc = service(FakeSource(emptyList()), personas, threads, runs, gen)

        svc.tick(TickSource.MANUAL)

        assertEquals("no-op", runs.runs.single().outcome)
        assertTrue(gen.summons.isEmpty(), "below-threshold means no summon at all (no LLM call)")
    }
}
