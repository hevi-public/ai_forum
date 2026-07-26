package com.aiforum.ambient

import com.aiforum.service.DriftSource
import com.aiforum.service.InterestDriftService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The gated `@Scheduled` caller for the interest-drift pass (plan_docs/ambient-slice-4b.md D8) — the thin
 * afterthought behind the manual `POST /admin/interests/drift`. Same two gates as
 * [InterestDriftSchedulingConfig] so it never wires under test and only when the owner opts in; because
 * it cannot wire under `@Profile("!test")`, the manual trigger is the ONLY way the acceptance suite can
 * exercise this slice at all, which is why that endpoint is ungated.
 *
 * Not unit-tested, by house precedent ([AmbientTicker], [StanceEvolutionTicker], SqliteBackup): the
 * annotation is framework glue; the covered thing is [InterestDriftService], which the acceptance suite
 * drives over HTTP.
 *
 * **Weekly**, Sunday 04:30 by default — slower than the nightly stance pass on purpose, since a
 * preoccupation that changes every night is not a preoccupation, and the half-hour offset keeps the two
 * paid passes off the same SQLite file and the same rate-limit window. Override via
 * `aiforum.interest-drift.cron`.
 *
 * The body is a block rather than an expression so the scheduled method stays void: `drift` returns how
 * many members moved, which is for the owner's button to report and has nowhere to go here (the log line
 * inside the pass already carries it).
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.interest-drift", name = ["enabled"], havingValue = "true")
class InterestDriftTicker(private val drift: InterestDriftService) {

    @Scheduled(cron = "\${aiforum.interest-drift.cron:0 30 4 * * SUN}")
    fun tick() {
        drift.drift(DriftSource.SCHEDULED)
    }
}
