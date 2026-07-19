package com.aiforum.ambient

import com.aiforum.service.AmbientTickService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The gated `@Scheduled` caller for the ambient loop (plan_docs/ambient-slice-1.md) — the thin afterthought
 * behind the manual `POST /admin/ambient/tick`. Same two gates as [AmbientSchedulingConfig] so it never
 * wires under test (the ScriptableArticleSource + the config rail keep the suite deterministic and free)
 * and only when the owner opts in.
 *
 * Not unit-tested, by house precedent (SqliteBackup): the annotation is framework glue; the covered thing
 * is [AmbientTickService], which the acceptance suite drives via the HTTP trigger. Three ticks a day by
 * default (09:00 / 15:00 / 21:00) — the direction doc §8 few-ticks-a-day posture; override the cron via
 * `aiforum.ambient.cron`.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.ambient", name = ["enabled"], havingValue = "true")
class AmbientTicker(private val tickService: AmbientTickService) {

    @Scheduled(cron = "\${aiforum.ambient.cron:0 0 9,15,21 * * *}")
    fun tick() = tickService.tick(TickSource.SCHEDULED)
}
