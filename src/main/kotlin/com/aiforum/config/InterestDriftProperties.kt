package com.aiforum.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * `aiforum.interest-drift` config (plan_docs/ambient-slice-4b.md D8) — the knobs on the pass that
 * rewrites what a member is *into* from what that member actually wrote.
 *
 * The prefix is its OWN, not a corner of `aiforum.ambient` or of `aiforum.stance-evolution`, and here
 * that is more than the independent-switchability argument [StanceEvolutionProperties] makes: this is
 * the convergence-risk mechanism (D12), the one loop that can move the room's character rather than its
 * output, so an owner who wants articles and relation drift but **not** topic drift must be able to say
 * exactly that — and must be able to kill this loop alone, without touching the two that are working.
 *
 * [enabled] and [cron] are ALSO read as raw properties, by `InterestDriftSchedulingConfig` /
 * `InterestDriftTicker`: an annotation attribute cannot read a bean, so `@ConditionalOnProperty` and
 * `@Scheduled` must name the key directly. Binding them here too costs nothing (same key, same value)
 * and gives `/__diag` a bean to inject; without that, the config_guardrails rail asserting "interest
 * drift is off under test" would have nothing to read.
 */
@ConfigurationProperties(prefix = "aiforum.interest-drift")
data class InterestDriftProperties(
    /**
     * The SCHEDULER master switch, off by default so unattended LLM spend is always opt-in. It gates
     * only the `@Scheduled` pair; `POST /admin/interests/drift` always works, and has to — the scheduler
     * can never wire under `@Profile("!test")`, so the manual trigger is the only way the acceptance
     * suite can reach this slice at all.
     *
     * It is half of what keeps the pass safe; the other half is that drift is additionally opt-in **per
     * member**, because a member the owner gave no interests is skipped before any spend (D8).
     */
    val enabled: Boolean = false,
    /**
     * When the scheduler runs, once [enabled]. **Weekly**, Sunday 04:30 — deliberately slower than the
     * nightly stance pass and far slower than the ambient tick: a preoccupation that changes every night
     * is not a preoccupation, and a room whose members all move every night *is* the convergence failure
     * mode this slice is built to avoid. The half-hour offset keeps it off the same SQLite file and the
     * same provider rate-limit window as the 04:00 stance pass.
     *
     * Read from this bean by nobody — the `@Scheduled` annotation resolves the same key itself. It is
     * bound here so the prefix has one documented home rather than a value that exists only inside an
     * annotation string.
     */
    val cron: String = "0 30 4 * * SUN",
    /**
     * How many members one run may spend a judgment on. **0 (the default) means unlimited**, and unlike
     * S4a's identical default that is argued rather than inherited: the worst case here is *knowable*
     * and small — the roster, one call each, no recompose fan-out (D7) — where S4a's was forty-two edges
     * plus seven composes.
     *
     * **Rejected: a cap that bites (2 of 7, say).** It would turn the benign starvation residual live. A
     * refused judgment deliberately leaves the watermark NULL so the evidence is re-judged, NULL sorts
     * first, so one persistently-refused member would hold half of every weekly budget for good. Clamped
     * at the use site (`InterestDriftService`), the house rule for every tunable: never trust the bound
     * value's range.
     */
    val maxPersonasPerRun: Int = 0,
    /**
     * How much a member must have written since the pass last looked before another judgment is worth
     * buying. Three, and the arithmetic matters because a floor asserted without one is a guess: the
     * ambient loop produces roughly one POSTED comment per member per day at the seeded roster of seven,
     * so three engagements is about three days of one member's attention — one comment is not a change
     * of heart. S4a's `min-exchanges: 1` is not the same number because it has a different denominator:
     * forty-two directed pairs sharing that same trickle.
     *
     * Clamped `maxOf(1, …)` at the use site, so a zero or negative override cannot turn "judge a member
     * who has said nothing" into a supported configuration.
     */
    val minEngagements: Int = 3,
    /**
     * The per-member authoring ceiling (D11). Not enforced in SQL — SQLite cannot express "at most four
     * rows per persona_id" in a CHECK — and deliberately not enforced by the drift pass either, which
     * needs no ceiling because it is swap-only: one phrase set down for every phrase taken up, so no
     * model can raise a member's count by any amount at all (I3).
     *
     * **Bound here, enforced elsewhere**, and that split is deliberate rather than an oversight: the
     * ceiling the owner actually meets is `PersonaController.MAX_INTERESTS`, a constant on the write
     * surface that owns it (its KDoc explains why that controller does not take a dependency on this
     * pass's configuration). This binding exists so the yml key documented under this prefix has a home
     * that reads it — an unbound key binds to nothing, silently, and a knob that silently does nothing is
     * worse than no knob. If the two ever have to move together, inject this bean there and delete the
     * constant.
     */
    val maxInterests: Int = 4,
)

/**
 * Enables [InterestDriftProperties]. Deliberately NOT `@Profile`-scoped, the [StanceEvolutionConfig]
 * pattern: the properties bean must exist under EVERY profile — including `test`, where the scheduler
 * itself can never wire — or the test-only DiagnosticsController has nothing to inject and the
 * config_guardrails rail asserting "interest drift is off under test" cannot be written. Wiring the
 * SCHEDULER is a separate concern, gated at those beans.
 */
@Configuration
@EnableConfigurationProperties(InterestDriftProperties::class)
class InterestDriftConfig
