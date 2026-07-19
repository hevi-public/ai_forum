package com.aiforum.ambient

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Turns on Spring's `@Scheduled` support for the ambient ticker, but ONLY where [AmbientTicker] actually
 * wires — a verbatim mirror of `backup/SchedulingConfig` on its OWN flag. Gated on `aiforum.ambient.enabled`
 * (its own switch, distinct from `aiforum.backups.enabled`) and the `!test` profile, so the test context
 * never starts a scheduler thread and the config_guardrails rail (ambient ticking off under test) stays
 * honest. It carries its own flag because ambient ticking and backups are independently switchable — an
 * owner may want snapshots without an autonomous posting loop, or vice versa. `@EnableScheduling` is
 * idempotent, so it coexists fine with the backup one when both are on.
 */
@Configuration
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.ambient", name = ["enabled"], havingValue = "true")
@EnableScheduling
class AmbientSchedulingConfig
