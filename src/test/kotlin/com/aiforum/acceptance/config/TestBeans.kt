package com.aiforum.acceptance.config

import com.aiforum.ambient.Article
import com.aiforum.ambient.ArticleSource
import com.aiforum.dto.ReasoningLeak
import com.aiforum.github.GitHubClient
import com.aiforum.github.GitHubOverview
import com.aiforum.github.GitHubResult
import com.aiforum.github.Issue
import com.aiforum.github.PullDetail
import com.aiforum.github.PullRequest
import com.aiforum.github.PullResult
import com.aiforum.github.RepoSummary
import com.aiforum.images.DescribeRequest
import com.aiforum.images.ImageDescriber
import com.aiforum.images.VisionUnavailableException
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import com.aiforum.shortcut.ShortcutClient
import com.aiforum.shortcut.StoryCard
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The scriptable Tier-1 IO double (see the cucumber-spring-bdd skill). Steps program it per scenario
 * to return canned output or throw a specific failure, and it SPIES on every request it received so
 * the +1 firewall and context-scoping scenarios can assert on what the model was actually handed.
 *
 * It's a singleton bean reset between scenarios by DatabaseResetHooks.
 */
@Component
@Primary
@Profile("test")
class ScriptableLlmClient : LlmClient {

    sealed interface Behavior {
        // `leak` mirrors what the real parsers (ReplySanitizer) would attach to a leaked completion, so a
        // scenario can drive the reasoning-leak badge through the real persist/render path. Null = clean.
        data class Respond(val text: String, val leak: ReasoningLeak? = null) : Behavior
        data class Fail(val ex: () -> RuntimeException) : Behavior
        /** Block until the cancellation token is tripped, then report cancellation. */
        object HangUntilCancelled : Behavior
        /**
         * Block until the scenario RELEASES it (or the token trips), then answer [text]. The deterministic
         * way to hold a phase of the async summon open — routing especially, which registers no draft and
         * so cannot be reached by the cancel endpoint. A scenario that needs to observe the page mid-summon
         * arms this, acts, asserts, then releases; nothing depends on a sleep or a timing window.
         */
        data class HangUntilReleased(val text: String) : Behavior
        /** Stream these chunks as individual TextDeltas (the aggregate is their concatenation), driving the
         *  AG-UI event path. Through the non-streaming generate() it degrades to the aggregate reply. */
        data class Stream(val deltas: List<String>, val leak: ReasoningLeak? = null) : Behavior
    }

    private val script = ConcurrentLinkedDeque<Behavior>()

    /** The spy: every request handed to the client, in order. CopyOnWriteArrayList because the async
     *  summon path writes from a worker thread while steps read it from the test thread — COW gives
     *  safe iteration and visibility without locking the readers. */
    val received = CopyOnWriteArrayList<LlmRequest>()

    /**
     * The gate [Behavior.HangUntilReleased] waits on. Open by default, so every scenario that never arms a
     * hang is untouched; arming one closes it, and [release] (or [reset], as the between-scenario seatbelt
     * for a worker a scenario forgot to free) opens it again. A plain flag rather than a latch because it
     * has to be re-closable across scenarios.
     */
    @Volatile
    private var released = true

    fun enqueue(behavior: Behavior): Unit {
        if (behavior is Behavior.HangUntilReleased) released = false
        script.addLast(behavior)
    }

    /** Let a hanging call finish and answer its scripted text. */
    fun release() {
        released = true
    }

    fun reset() {
        // Release BEFORE clearing: a worker parked in a hang outlives the scenario that armed it (routing
        // holds no registered draft, so inFlight.reset() cannot reach it), and a parked worker holds one of
        // the pool's threads and would bleed its LLM call into the next scenario's spy.
        released = true
        script.clear()
        received.clear()
    }

    override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
        received += request
        return produce(script.pollFirst() ?: Behavior.Respond("default reply"), cancellation)
    }

    override fun generate(
        request: LlmRequest,
        cancellation: CancellationToken,
        sink: com.aiforum.agui.AguiEventSink,
    ): LlmResponse {
        received += request
        sink.emit(com.aiforum.agui.AguiEvent.RunStarted(request.runId))
        return try {
            val behavior = script.pollFirst() ?: Behavior.Respond("default reply")
            // Stream emits a genuine per-chunk sequence; every other behaviour frames its aggregate as one
            // delta (matching the LlmClient default), so the SSE path sees the same shape the real backends do.
            if (behavior is Behavior.Stream) behavior.deltas.forEach { sink.emit(com.aiforum.agui.AguiEvent.TextDelta(request.runId, it)) }
            val response = produce(behavior, cancellation)
            if (behavior !is Behavior.Stream && response.text.isNotEmpty()) {
                sink.emit(com.aiforum.agui.AguiEvent.TextDelta(request.runId, response.text))
            }
            sink.emit(com.aiforum.agui.AguiEvent.RunFinished(request.runId))
            response
        } catch (e: Throwable) {
            sink.emit(com.aiforum.agui.AguiEvent.RunError(request.runId, e.message ?: "generation failed"))
            throw e
        }
    }

    /** The behaviour → response mapping shared by both generate paths (no event emission here). */
    private fun produce(behavior: Behavior, cancellation: CancellationToken): LlmResponse = when (behavior) {
        is Behavior.Respond -> LlmResponse(behavior.text, behavior.leak)
        is Behavior.Stream -> LlmResponse(behavior.deltas.joinToString(""), behavior.leak)
        is Behavior.Fail -> throw behavior.ex()
        Behavior.HangUntilCancelled -> {
            while (!cancellation.isCancelled) Thread.sleep(10)
            throw com.aiforum.llm.LlmException.Cancelled()
        }
        is Behavior.HangUntilReleased -> {
            while (!released && !cancellation.isCancelled) Thread.sleep(10)
            if (cancellation.isCancelled) throw com.aiforum.llm.LlmException.Cancelled()
            LlmResponse(behavior.text)
        }
    }
}

/**
 * The scriptable vision seam ([ImageDescriber]) under test — the sibling of [ScriptableLlmClient]. Steps
 * program the caption it returns (or make it fail), and it spies on every request so a scenario can assert
 * the vision model was actually invoked. Reset between scenarios by DatabaseResetHooks.
 */
@Component
@Primary
@Profile("test")
class ScriptableImageDescriber : ImageDescriber {

    val received = CopyOnWriteArrayList<DescribeRequest>()

    @Volatile
    var nextCaption: String = "an attached image"

    @Volatile
    var failNext: Boolean = false

    override fun describe(request: DescribeRequest): String {
        received += request
        if (failNext) throw VisionUnavailableException("scripted vision failure")
        return nextCaption
    }

    fun reset() {
        received.clear()
        nextCaption = "an attached image"
        failNext = false
    }
}

/**
 * The scriptable [ShortcutClient] under test — the read-only Shortcut seam's IO double, sibling of
 * [ScriptableLlmClient]. It does no network IO: steps program the stories a search returns and the
 * workflow-state names, and it spies on every query it was handed.
 *
 * [active] is false by default, so every Shortcut surface stays dark in the scenarios that don't opt in;
 * a step flips it on. Reset between scenarios by DatabaseResetHooks (which also evicts the service's
 * workflow-state cache so names can't leak across scenarios).
 */
@Component
@Primary
@Profile("test")
class ScriptableShortcutClient : ShortcutClient {

    @Volatile
    var active: Boolean = false

    @Volatile
    var failNext: Boolean = false

    @Volatile
    var states: Map<Long, String> = emptyMap()

    private val stories = CopyOnWriteArrayList<StoryCard>()

    /** The queries searched, in order — so a scenario can assert which feed ran. */
    val received = CopyOnWriteArrayList<String>()

    fun add(card: StoryCard) {
        stories += card
    }

    override fun isActive(): Boolean = active

    override fun searchStories(query: String, pageSize: Int): List<StoryCard> {
        received += query
        if (failNext) throw RuntimeException("scripted shortcut failure")
        return stories.take(pageSize)
    }

    override fun workflowStates(): Map<Long, String> = states

    fun reset() {
        active = false
        failNext = false
        states = emptyMap()
        stories.clear()
        received.clear()
    }
}

/**
 * The scriptable GitHub seam ([GitHubClient]) under test — the sibling of [ScriptableLlmClient]. The real
 * GhCliGitHubClient is present but inert under test (disabled), so this @Primary fake stands in and steps
 * program the snapshot the /github page renders: an [overview] (repo + open PRs + open issues) or an
 * unavailable off-state. Reset between scenarios by DatabaseResetHooks.
 */
@Component
@Primary
@Profile("test")
class ScriptableGitHubClient : GitHubClient {

    @Volatile
    var unavailableReason: String = "GitHub integration is off."

    @Volatile
    var repo: RepoSummary? = null

    val pulls = CopyOnWriteArrayList<PullRequest>()
    val issues = CopyOnWriteArrayList<Issue>()

    /** In-depth PR details a scenario programs, keyed by number; drives [pull] (the "Discuss this PR" path). */
    val pullDetails = ConcurrentHashMap<Int, PullDetail>()

    /** Every number passed to [pull], in order — so a scenario can assert an already-ingested PR isn't re-fetched. */
    val pullsRequested = CopyOnWriteArrayList<Int>()

    /** A repo present => an Ok snapshot; otherwise the unavailable off-state with the programmed reason. */
    override fun overview(): GitHubResult {
        val r = repo
        return if (r != null) GitHubResult.Ok(GitHubOverview(r, pulls.toList(), issues.toList()))
        else GitHubResult.Unavailable(unavailableReason)
    }

    /** A programmed detail => an Ok pull; otherwise the unavailable off-state (no such PR / integration off). */
    override fun pull(number: Int): PullResult {
        pullsRequested += number
        return pullDetails[number]?.let { PullResult.Ok(it) } ?: PullResult.Unavailable(unavailableReason)
    }

    fun reset() {
        unavailableReason = "GitHub integration is off."
        repo = null
        pulls.clear()
        issues.clear()
        pullDetails.clear()
        pullsRequested.clear()
    }
}

/**
 * The scriptable [ArticleSource] under test — the fifth IO port's fake, sibling of [ScriptableLlmClient].
 * Steps script the article(s) an ambient tick should collect; `next()` pops the first one (rotating it to
 * the back isn't needed — S1 is one-article-per-tick, and an empty list is the fake's natural reset state,
 * which is exactly the no-op scenario's precondition, so no scripting is required for it). Reset between
 * scenarios by DatabaseResetHooks.
 *
 * [failWith] (S2, plan_docs/ambient-slice-2.md §6) programs the NEXT [next] call to throw instead of
 * returning — the acceptance-level pin of the tick's outer failure handling the S1 Assay review asked
 * for (a broken feed must record a 'failed' run, never crash the tick).
 */
@Component
@Primary
@Profile("test")
class ScriptableArticleSource : ArticleSource {

    val articles = CopyOnWriteArrayList<Article>()

    /** The articles handed out, in order — so a scenario can assert the source was (or wasn't) drained. */
    val received = CopyOnWriteArrayList<Article>()

    @Volatile
    private var failure: RuntimeException? = null

    /**
     * S5 plumbing (plan_docs/ambient-slice-5.md §2 "Distinguishable no-ops"): the source's own account
     * of WHY [next] yielded null — "feeds returned no items" vs "all N feed items already seen" — so the
     * tick's no-op detail can distinguish them instead of the one fixed generic string it records today.
     *
     * GREEN (S5): the port now declares `fun emptyReason(): String? = null` (defaulted, so existing
     * implementers stay source-compatible), and [emptyReason] below overrides it by returning this field.
     * A settable property and a same-name override function coexist in Kotlin — `x.emptyReason` reads the
     * field, `x.emptyReason()` calls the override — so the step that programs it
     * (`the ArticleSource is empty because {string}` → `articleSource.emptyReason = reason`) still
     * compiles, and `AmbientTickService`'s no-op detail now reads it back through the port and appends it.
     */
    @Volatile
    var emptyReason: String? = null

    /** The port method — hands the tick this scenario's programmed [emptyReason] (or null when unset). */
    override fun emptyReason(): String? = emptyReason

    fun add(article: Article) {
        articles += article
    }

    /** Program the NEXT [next] call to throw [message] instead of returning — simulates a broken feed. */
    fun failWith(message: String) {
        failure = RuntimeException(message)
    }

    override fun next(): Article? {
        failure?.let { throw it }
        val article = articles.firstOrNull() ?: return null
        articles.removeAt(0)
        received += article
        return article
    }

    fun reset() {
        articles.clear()
        received.clear()
        failure = null
        emptyReason = null
    }
}

/**
 * A boundary toggle for simulating persistence failure (category E) WITHOUT mocking internal code:
 * a repository wrapper reads this flag and throws on the next write, so the real service/controller
 * path still runs and the draft-preservation behaviour is genuinely exercised.
 */
@Component
@Profile("test")
class FailingRepositoryToggle {
    @Volatile
    var failNextWrite: Boolean = false

    fun clear() {
        failNextWrite = false
    }
}

/**
 * Fixed clock under test so timestamps and Retry-After windows are deterministic and assertable.
 */
@Configuration
@Profile("test")
class FixedClockConfig {
    @Bean
    @Primary
    fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC)
}
