package com.aiforum.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * `aiforum.memory` config (plan_docs/persona-memory.md §2.13) — the knobs on the Memory Scribe, the
 * pass that writes what a member privately *remembers*.
 *
 * The prefix is its OWN, not a corner of `aiforum.interest-drift`, for the reason every evolution
 * pass gets one: independent switchability. An owner who wants articles, stance drift and interest
 * drift but **not** memory must be able to say exactly that — and must be able to kill this loop
 * alone, without touching the three that are working.
 *
 * [enabled] and [cron] are ALSO read as raw properties, by `MemoryScribeSchedulingConfig` /
 * `MemoryScribeTicker`: an annotation attribute cannot read a bean, so `@ConditionalOnProperty` and
 * `@Scheduled` must name the key directly. Binding them here too costs nothing (same key, same
 * value) and gives `/__diag` a bean to inject; without that, the config_guardrails rail asserting
 * "memory consolidation is off under test" would have nothing to read.
 *
 * Note what is deliberately NOT here: the per-member record ceiling. `MAX_SCRIBE_MEMORIES = 24` is
 * a code constant on `MemoryScribeService` (§2.11, the `MAX_INTERESTS` pattern) — it is half of the
 * letter-protocol arithmetic (24 < 26), and a knob that could be turned past the alphabet would put
 * the labelling scheme's correctness in the owner's yml.
 */
@ConfigurationProperties(prefix = "aiforum.memory")
data class MemoryProperties(
    /**
     * The SCHEDULER master switch, off by default so unattended LLM spend is always opt-in. It
     * gates only the `@Scheduled` pair; `POST /admin/memory/run` always works, and has to — the
     * scheduler can never wire under `@Profile("!test")`, so the manual trigger is the only way the
     * acceptance suite can reach this slice at all.
     */
    val enabled: Boolean = false,
    /**
     * When the scheduler runs, once [enabled]. **Weekly**, Sunday 05:00 — the third slot in the
     * Sunday queue (04:00 stances, 04:30 interest drift, 05:00 scribe): same SQLite file, same
     * provider rate-limit window, never overlapping. Weekly because memory is consolidation, not
     * commentary — a store that grows nightly is a transcript wearing a new table name.
     *
     * Read from this bean by nobody — the `@Scheduled` annotation resolves the same key itself. It
     * is bound here so the prefix has one documented home rather than a value that exists only
     * inside an annotation string, and so `/__diag` can report it.
     */
    val cron: String = "0 0 5 * * SUN",
    /**
     * How many members one run may spend a judgment on. **0 (the default) means unlimited** — the
     * worst case is knowable and small: the roster, one call each, no fan-out (§2.4's cost
     * arithmetic; ≤7 calls a week at the seeded roster, under two percent of the combined weekly
     * paid ceiling). Clamped at the use site (`MemoryScribeService`), the house rule for every
     * tunable: never trust the bound value's range.
     */
    val maxPersonasPerRun: Int = 0,
    /**
     * How much forum experience a member must have accumulated since the pass last looked before
     * another judgment is worth buying. Three — S4b's arithmetic carried with its denominator: the
     * ambient loop produces roughly one engagement per member per day at the current tick volume,
     * so three is about three days of one member's attention. One exchange is not a memory.
     * Clamped `maxOf(1, …)` at the use site.
     */
    val minEngagements: Int = 3,
    /**
     * The evidence horizon in days (§2.6 D6b, a recorded owner call): a member does not consolidate
     * evidence older than this, REGARDLESS of window state — which is what kills the
     * dead-coarseFloor class by construction (one never-stamped member can no longer hold the
     * global floor at NULL and force an all-time read forever, the defect the prior two slices
     * shipped twice). Semantically honest for memory: you don't remember what happened before you
     * started remembering. Clamped `maxOf(1, …)` at the use site.
     */
    val maxLookbackDays: Int = 90,
)

/**
 * Enables [MemoryProperties]. Deliberately NOT `@Profile`-scoped, the [InterestDriftConfig] /
 * [StanceEvolutionConfig] pattern: the properties bean must exist under EVERY profile — including
 * `test`, where the scheduler itself can never wire — or the test-only DiagnosticsController has
 * nothing to inject and the config_guardrails rail asserting "memory consolidation is off under
 * test" cannot be written. Wiring the SCHEDULER is a separate concern, gated at those beans.
 */
@Configuration
@EnableConfigurationProperties(MemoryProperties::class)
class MemoryConfig
