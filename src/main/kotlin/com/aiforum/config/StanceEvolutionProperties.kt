package com.aiforum.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * `aiforum.stance-evolution` config (plan_docs/ambient-slice-4a.md D4) — the knobs on the pass that
 * rewrites relation stances from what the members actually did to each other.
 *
 * The prefix is its OWN, not a corner of `aiforum.ambient`: an owner who wants the room to post
 * articles but not to let its relationships drift must be able to say exactly that, which is the same
 * independent-switchability argument `AmbientSchedulingConfig`'s KDoc makes for why ambient did not
 * reuse `aiforum.backups.enabled`.
 *
 * [enabled] and [cron] are ALSO read as raw properties, by
 * `StanceEvolutionSchedulingConfig`/`StanceEvolutionTicker` — an annotation attribute cannot read a
 * bean, so `@ConditionalOnProperty` and `@Scheduled` must name the key directly. Binding them here too
 * costs nothing (same key, same value) and gives the prefix one readable home that `/__diag` can inject;
 * without that, the config_guardrails rail would have nothing to assert the cap against.
 */
@ConfigurationProperties(prefix = "aiforum.stance-evolution")
data class StanceEvolutionProperties(
    /**
     * The SCHEDULER master switch, off by default so unattended LLM spend is always opt-in. It gates
     * only the `@Scheduled` pair; `POST /admin/stances/evolve` always works, and has to — the scheduler
     * can never wire under `@Profile("!test")`, so the manual trigger is the only way the acceptance
     * suite can reach this slice at all.
     */
    val enabled: Boolean = false,
    /**
     * When the scheduler runs, once [enabled]. 04:00 daily: the pass is deliberately slower than the
     * ambient tick (three times a day) because a relationship that lurches every few hours reads as
     * noise rather than as a room whose members are getting to know each other.
     *
     * Read from this bean by nobody — the `@Scheduled` annotation resolves the same key itself. It is
     * bound here so the prefix has one documented home rather than a value that exists only inside an
     * annotation string.
     */
    val cron: String = "0 0 4 * * *",
    /**
     * How many edges one run may spend a judgment on. **0 (the default) means unlimited** — the owner's
     * "let it rip" call of 2026-07-25, made with the cost stated: one judgment per qualifying pair plus
     * one recompose per affected member. What keeps that safe is [enabled] defaulting to false, not a
     * cap; this exists so turning the spend down later is a config edit rather than a code change.
     * Clamped at the use site (`StanceEvolutionService`), the way every other tunable in this repo is.
     */
    val maxEdgesPerRun: Int = 0,
    /**
     * How many exchanges a directed pair must have produced in the window before it is worth a
     * judgment. 1 by default: the window already bounds the pass to what has happened since it last
     * changed something, so a quiet forum is a no-op run without needing a threshold on top. Raise it
     * to make the room slower to re-read each other.
     */
    val minExchanges: Int = 1,
)

/**
 * Enables [StanceEvolutionProperties]. Deliberately NOT `@Profile`-scoped, the `AmbientConfig` pattern
 * (plan_docs/ambient-slice-5.md §4): the properties bean must exist under EVERY profile — including
 * `test`, where the scheduler itself can never wire — or the test-only DiagnosticsController has
 * nothing to inject and the config_guardrails rail asserting "stance evolution is off under test"
 * cannot be written. Wiring the SCHEDULER is a separate concern, gated at those beans.
 */
@Configuration
@EnableConfigurationProperties(StanceEvolutionProperties::class)
class StanceEvolutionConfig
