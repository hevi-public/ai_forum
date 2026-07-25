package com.aiforum.ambient

import com.aiforum.service.EvolutionSource
import com.aiforum.service.StanceEvolutionService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The gated `@Scheduled` caller for the stance-evolution pass (plan_docs/ambient-slice-4a.md D1) — the
 * thin afterthought behind the manual `POST /admin/stances/evolve`. Same two gates as
 * [StanceEvolutionSchedulingConfig] so it never wires under test and only when the owner opts in;
 * because it cannot wire under `@Profile("!test")`, the manual trigger is the ONLY way the acceptance
 * suite can exercise this slice at all, which is why that endpoint is ungated.
 *
 * Not unit-tested, by house precedent ([AmbientTicker], SqliteBackup): the annotation is framework
 * glue; the covered thing is [StanceEvolutionService], which the acceptance suite drives over HTTP.
 * Once a day at 04:00 by default — slower than the ambient tick on purpose, since a relationship that
 * lurches every few hours reads as noise; override via `aiforum.stance-evolution.cron`.
 *
 * The body is a block rather than an expression so the scheduled method stays void: `evolve` returns
 * how many edges moved, which is for the owner's button to report and has nowhere to go here (the log
 * line inside the pass already carries it).
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.stance-evolution", name = ["enabled"], havingValue = "true")
class StanceEvolutionTicker(private val evolution: StanceEvolutionService) {

    @Scheduled(cron = "\${aiforum.stance-evolution.cron:0 0 4 * * *}")
    fun tick() {
        evolution.evolve(EvolutionSource.SCHEDULED)
    }
}
