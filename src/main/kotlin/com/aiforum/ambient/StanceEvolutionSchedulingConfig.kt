package com.aiforum.ambient

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Turns on Spring's `@Scheduled` support for the stance-evolution pass, but ONLY where
 * [StanceEvolutionTicker] actually wires — a verbatim mirror of [AmbientSchedulingConfig] on its OWN
 * flag. Gated on `aiforum.stance-evolution.enabled` (distinct from `aiforum.ambient.enabled`) and the
 * `!test` profile, so the test context never starts a scheduler thread and the config_guardrails rail
 * (stance evolution off under test) stays honest.
 *
 * It carries its own flag because posting and relation drift are independently switchable: an owner may
 * want the room to bring in articles without letting its members' opinions of each other move, or the
 * reverse (plan_docs/ambient-slice-4a.md D1). `@EnableScheduling` is idempotent, so this coexists fine
 * with the ambient and backup ones when several are on.
 */
@Configuration
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.stance-evolution", name = ["enabled"], havingValue = "true")
@EnableScheduling
class StanceEvolutionSchedulingConfig
