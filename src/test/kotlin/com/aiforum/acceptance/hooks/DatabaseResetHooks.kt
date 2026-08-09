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
        // article_seen (V23) is standalone (no FKs), cleared alongside ambient_run so a URL a prior scenario's
        // feed source marked seen can't linger into the next (the real FeedArticleSource never wires under
        // test, but keep the dedupe registry scenario-isolated the same as everything else).
        // persona_stance (V24) references persona(id) from BOTH of its endpoint columns, so it precedes
        // persona here. Its FKs are ON DELETE CASCADE — unlike every other row above, SQLite would clear
        // these for us — but the wipe stays explicit anyway: a reset that leans on a cascade reads as if
        // stances were never seeded at all, and the day someone drops the CASCADE the resets would start
        // failing somewhere far from here instead of on this line.
        // stance_change (V25) is the same story one slice on — both of its endpoint columns CASCADE from
        // persona(id) — so it precedes persona too, and the wipe stays explicit for the reason just given.
        // persona_interest + interest_change (V27) are the S4b pair of the same shape — one CASCADEing FK to
        // persona(id) each — so both precede persona, and both are wiped explicitly on the same argument: a
        // scenario that seeds an interest and one that leans on a cascade are indistinguishable from here,
        // and the day someone drops a CASCADE the resets should fail on this line rather than somewhere far
        // from it. (`persona.interests_judged_at` needs no wiping — it goes with the persona row.)
        // memory_change + persona_memory (V28) follow the same discipline, child first: memory_change holds
        // only a BARE memory_id (no FK to persona_memory — audit rows must survive deletes), but both
        // CASCADE from persona(id), so both precede persona and both are wiped explicitly. persona_memory
        // additionally self-references through its composite same-persona FK; a single DELETE FROM clears
        // parent and child rows in one statement, so no intra-table ordering is needed.
        // (`persona.memory_judged_at` needs no wiping — it goes with the persona row.)
        // owner_pref (V29) has NO foreign keys in either direction, so its position in this list is free;
        // it sits last because that is where a table nothing else depends on belongs. What is not free is
        // its PRESENCE: it holds ONE GLOBAL ROW (id = 1) whose absence IS the default front-page view, so
        // a scenario that switches to the activity view would otherwise hand its choice to every scenario
        // that ran after it — the exact leak shape a singleton preference row has, and one that shows up
        // as an unrelated feature failing only in certain run orders. Deleting the row restores the
        // default rather than some other stored value, which is why no seed row is re-inserted here.
        // generation_tool_call (V30) heads the list because it references comment(id) — but its position
        // is the least of it. Its FK is ON DELETE CASCADE, so the comment-linked rows would go with the
        // comment DELETE below; the rows that would NOT are exactly the ones with comment_id NULL, the
        // traces of generations that FAILED before they could post. Those cascade from nothing, and would
        // be the only table in this reset surviving into the next scenario — where the very next
        // "no tool calls were recorded" assertion would read a previous scenario's failure. So here the
        // explicit wipe is load-bearing, not just the house discipline the notes above describe.
        listOf("generation_tool_call", "routing_event", "attachment", "vote", "comment_revision", "comment_quote", "event_log", "comment", "thread_read", "github_pr_thread", "ambient_run", "article_seen", "thread", "stance_change", "persona_stance", "interest_change", "persona_interest", "memory_change", "persona_memory", "persona", "owner_pref").forEach {
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
