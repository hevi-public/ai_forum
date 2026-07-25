package com.aiforum.ambient

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Turns on Spring's `@Scheduled` support for the interest-drift pass, but ONLY where
 * [InterestDriftTicker] actually wires — a verbatim mirror of [StanceEvolutionSchedulingConfig] on its
 * OWN flag. Gated on `aiforum.interest-drift.enabled` (distinct from both `aiforum.ambient.enabled` and
 * `aiforum.stance-evolution.enabled`) and the `!test` profile, so the test context never starts a
 * scheduler thread and the config_guardrails rail (interest drift off under test) stays honest.
 *
 * It carries its own flag because this is the third paid loop and the one with the largest blast radius
 * on the room's character (plan_docs/ambient-slice-4b.md D8): an owner may want the room to bring in
 * articles and to let its members' opinions of each other move, while keeping what they are *into* fixed
 * — and must be able to kill this loop alone. `@EnableScheduling` is idempotent, so this coexists fine
 * with the ambient, backup and stance-evolution ones when several are on.
 */
@Configuration
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.interest-drift", name = ["enabled"], havingValue = "true")
@EnableScheduling
class InterestDriftSchedulingConfig
