package com.aiforum.ambient

import com.aiforum.service.MemoryScribeService
import com.aiforum.service.ScribeSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The gated `@Scheduled` caller for the Memory Scribe (plan_docs/persona-memory.md §2.13) — the
 * thin afterthought behind the manual `POST /admin/memory/run`. Same two gates as
 * [MemoryScribeSchedulingConfig] so it never wires under test and only when the owner opts in;
 * because it cannot wire under `@Profile("!test")`, the manual trigger is the ONLY way the
 * acceptance suite can exercise this slice at all, which is why that endpoint is ungated.
 *
 * Not unit-tested, by house precedent ([AmbientTicker], [StanceEvolutionTicker],
 * [InterestDriftTicker]): the annotation is framework glue; the covered thing is
 * [MemoryScribeService], which the acceptance suite drives over HTTP.
 *
 * **Weekly**, Sunday 05:00 by default — the third slot in the Sunday queue (04:00 stances, 04:30
 * interest drift, 05:00 scribe), so the paid passes never share the SQLite file or the provider
 * rate-limit window. Override via `aiforum.memory.cron`.
 *
 * The body is a block rather than an expression so the scheduled method stays void: `consolidate`
 * returns how many records were written, which is for the owner's button to report and has nowhere
 * to go here (the log line inside the pass already carries it).
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.memory", name = ["enabled"], havingValue = "true")
class MemoryScribeTicker(private val scribe: MemoryScribeService) {

    @Scheduled(cron = "\${aiforum.memory.cron:0 0 5 * * SUN}")
    fun tick() {
        scribe.consolidate(ScribeSource.SCHEDULED)
    }
}
