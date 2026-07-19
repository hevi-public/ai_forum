package com.aiforum.acceptance.hooks

import com.aiforum.acceptance.config.FailingRepositoryToggle
import com.aiforum.acceptance.config.ScriptableArticleSource
import com.aiforum.acceptance.config.ScriptableGitHubClient
import com.aiforum.acceptance.config.ScriptableImageDescriber
import com.aiforum.acceptance.config.ScriptableLlmClient
import com.aiforum.acceptance.config.ScriptableShortcutClient
import com.aiforum.service.InFlightGenerations
import com.aiforum.shortcut.ShortcutService
import io.cucumber.java.Before
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Resets the real test SQLite DB and the Tier-1 fakes before every scenario, so each scenario is
 * isolated (see the cucumber-spring-bdd skill). In-flight workers first (so a lingering async draft
 * can't write into a freshly-cleared DB), then DB (order 0), then fakes (order 10).
 *
 * NOT @Component: glue classes (steps/hooks) are instantiated by Cucumber, which injects their
 * constructor dependencies from the Spring context. Marking glue @Component causes Spring to also
 * auto-detect it → duplicate beans → cucumber-spring refuses to start.
 */
class DatabaseResetHooks(
    private val jdbc: JdbcTemplate,
    private val llm: ScriptableLlmClient,
    private val describer: ScriptableImageDescriber,
    private val failingRepo: FailingRepositoryToggle,
    private val github: ScriptableGitHubClient,
    private val inFlight: InFlightGenerations,
    private val shortcut: ScriptableShortcutClient,
    private val shortcutService: ShortcutService,
    private val articleSource: ScriptableArticleSource,
) {
    @Before(order = -10)
    fun cancelInFlight() {
        // Seatbelt: trip + join any async draft left running by a prior scenario before the DB is wiped.
        inFlight.reset()
    }

    @Before(order = 0)
    fun resetDatabase() {
        // children before parents (foreign_keys=on) — attachment + comment_revision + comment_quote reference
        // comment, so first; github_pr_thread + ambient_run reference thread, so before thread (ambient_run's
        // FK is ON DELETE SET NULL, so order isn't strictly required, but keep the child-first discipline).
        listOf("routing_event", "attachment", "vote", "comment_revision", "comment_quote", "event_log", "comment", "thread_read", "github_pr_thread", "ambient_run", "thread", "persona").forEach {
            jdbc.update("DELETE FROM $it")
        }
    }

    @Before(order = 10)
    fun resetFakes() {
        llm.reset()
        describer.reset()
        github.reset()
        failingRepo.clear()
        shortcut.reset()
        shortcutService.evictCaches()
        articleSource.reset()
    }
}
