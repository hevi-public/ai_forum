package com.aiforum.ambient

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Turns on Spring's `@Scheduled` support for the Memory Scribe, but ONLY where [MemoryScribeTicker]
 * actually wires — a verbatim mirror of [InterestDriftSchedulingConfig] on its OWN flag. Gated on
 * `aiforum.memory.enabled` (distinct from the ambient, stance-evolution and interest-drift
 * switches) and the `!test` profile, so the test context never starts a scheduler thread and the
 * config_guardrails rail (memory consolidation off under test) stays honest.
 *
 * It carries its own flag because this is the FOURTH paid loop and the one that writes into every
 * member's private store (plan_docs/persona-memory.md §2.13): an owner may want articles, stance
 * drift and interest drift while keeping what the members remember owner-authored only — and must
 * be able to kill this loop alone. `@EnableScheduling` is idempotent, so this coexists fine with
 * the ambient, backup, stance-evolution and interest-drift ones when several are on.
 */
@Configuration
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.memory", name = ["enabled"], havingValue = "true")
@EnableScheduling
class MemoryScribeSchedulingConfig
