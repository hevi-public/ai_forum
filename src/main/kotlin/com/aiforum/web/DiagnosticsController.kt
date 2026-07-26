package com.aiforum.web

import com.aiforum.ambient.AmbientFeedProperties
import com.aiforum.ambient.ArticleSource
import com.aiforum.config.InterestDriftProperties
import com.aiforum.config.StanceEvolutionProperties
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Test-only diagnostics so the config_guardrails.feature rail scenarios can assert the wiring from the
 * outside (test → test DB, backups off, ambient ticking off + faked). Exposed ONLY under the `test` profile.
 */
@RestController
@Profile("test")
class DiagnosticsController(
    private val env: Environment,
    // The wired ArticleSource — the @Primary ScriptableArticleSource under test; its runtime simple class
    // name proves the scriptable fake (not a live fetcher) is what an ambient tick would draw from.
    private val articleSource: ArticleSource,
    // S5 (plan_docs/ambient-slice-5.md §2/§4): the feed allowlist. Bound from a NON-profiled
    // @Configuration (AmbientConfig), so the bean exists — empty — under test even though the real
    // FeedArticleSource can never wire here; that lets the config_guardrails rail assert ambientFeedCount=0.
    private val feedProperties: AmbientFeedProperties,
    // S4a (plan_docs/ambient-slice-4a.md D12): the stance-evolution knobs. Bound from a NON-profiled
    // @Configuration (StanceEvolutionConfig) for the same reason as the feed properties above — the
    // scheduler itself can never wire under test, so the rail has to read the CONFIG, not the ticker.
    private val stanceEvolution: StanceEvolutionProperties,
    // S4b (plan_docs/ambient-slice-4b.md D15): the interest-drift knobs, bound from a NON-profiled
    // @Configuration (InterestDriftConfig) for the third time and the same reason — under `test` the
    // scheduler pair can never wire, so this bean is the only thing left to assert against.
    private val interestDrift: InterestDriftProperties,
) {

    @GetMapping("/__diag")
    fun diag(): Map<String, Any?> = mapOf(
        "datasourceUrl" to env.getProperty("spring.datasource.url"),
        "backupsEnabled" to env.getProperty("aiforum.backups.enabled", Boolean::class.java),
        // Personas must never get network tools authorised under the test profile (see
        // ProcessLlmClient --allowedTools); the rails below pin these defaults against drift. Both are the
        // same risk class (untrusted content fetched from the host); the gh-readonly MCP is enabled in
        // dev/prod but must stay off under test.
        "webFetchEnabled" to env.getProperty("aiforum.llm.web-fetch-enabled", Boolean::class.java, false),
        "githubToolsEnabled" to env.getProperty("aiforum.llm.github-tools-enabled", Boolean::class.java, false),
        // The ambient loop's scheduler must stay off under test (its own switch, defaults false), and the
        // article source must be the scriptable fake — both pinned by config_guardrails against drift.
        "ambientEnabled" to env.getProperty("aiforum.ambient.enabled", Boolean::class.java, false),
        "articleSource" to articleSource.javaClass.simpleName,
        // S5: which source the `aiforum.ambient.source` switch selects (stub | feed), defaulting to
        // stub, and how many feeds are configured. Under test both stay at the safe defaults (stub, 0)
        // — the config_guardrails rail pins them so a drift toward a live fetcher fails a scenario.
        "ambientSource" to env.getProperty("aiforum.ambient.source", "stub"),
        "ambientFeedCount" to feedProperties.feeds.size,
        // S4a: the OTHER scheduled loop that costs LLM calls. Anything in this codebase that spends
        // money unattended gets a rail, and this pair is the only thing that would catch a future drift
        // toward a live, paid evolution pass running inside the suite. Read off the bound properties
        // rather than the raw environment: it is the same key the ticker's @ConditionalOnProperty
        // resolves, and reading the bean also proves the bean EXISTS under test — which is the half the
        // cap below depends on.
        "stanceEvolutionEnabled" to stanceEvolution.enabled,
        "stanceEvolutionMaxEdgesPerRun" to stanceEvolution.maxEdgesPerRun,
        // The cadence completes the picture the two rails above start: "off" and "uncapped" only say what
        // a run costs, not how often one would happen if the switch were flipped. It is also the one
        // reader this bound value has — the `@Scheduled` annotation resolves the same key itself — so
        // exposing it here is what keeps `cron` a documented, inspectable setting rather than a string
        // that exists twice with nothing comparing the copies.
        "stanceEvolutionCron" to stanceEvolution.cron,
        // S4b: the THIRD unattended spender, and the one with the largest blast radius on the room's
        // character — it is the only loop that can change what a member is *into*. Same three-key shape as
        // the pair above, and read off the bound bean for the same two reasons: it is the key the ticker's
        // @ConditionalOnProperty resolves, and injecting the bean at all is what proves InterestDriftConfig
        // stayed un-profiled. A drift toward a live, paid pass running inside the suite fails a scenario
        // rather than showing up on a bill.
        "interestDriftEnabled" to interestDrift.enabled,
        "interestDriftMaxPersonasPerRun" to interestDrift.maxPersonasPerRun,
        "interestDriftCron" to interestDrift.cron,
        "activeProfiles" to env.activeProfiles.toList(),
    )
}
