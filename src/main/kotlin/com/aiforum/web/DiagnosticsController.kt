package com.aiforum.web

import com.aiforum.ambient.ArticleSource
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
        "activeProfiles" to env.activeProfiles.toList(),
    )
}
