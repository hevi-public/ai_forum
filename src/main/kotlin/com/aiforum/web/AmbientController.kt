package com.aiforum.web

import com.aiforum.ambient.TickSource
import com.aiforum.repo.AmbientRunRepository
import com.aiforum.service.AmbientTickService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.Locale

/** One recorded tick as the /admin/ambient run list renders it — the row's data-* hooks live here. */
data class AmbientRunView(
    val id: Long,
    val outcome: String,
    // Which action the run dispatched ('post' | 'comment', V22) — rendered as data-action on the row.
    val action: String,
    val source: String,
    val ago: String,
    val detail: String?,
    val articleTitle: String?,
    val articleUrl: String?,
    val personaId: String?,
    val threadId: String?,
    // What this run's generations cost, preformatted to 4dp with Locale.ROOT (issue #15) — rendered as
    // data-cost-usd on the row. A STRING because the formatting decision belongs to the view, not the
    // template, and Locale.ROOT because a comma decimal separator would make the attribute unparseable
    // on a machine whose default locale happens to be European. Null when the run is unpriced, which JTE
    // renders as no attribute at all — absent means UNKNOWN, and that is deliberately distinguishable
    // from a "0.0000" that would claim the tick was free.
    val costUsd: String?,
)

/**
 * The ambient loop's admin surface (plan_docs/ambient-slice-1.md):
 *  - GET  /admin/ambient        — the run log (recent ticks + the manual-trigger button), linked from the
 *                                 /admin ambient-runs stat tile.
 *  - POST /admin/ambient/tick   — fire one tick by hand, then PRG-redirect (303) back to the run log so the
 *                                 button lands on the run it just made.
 *
 * The manual trigger is deliberately NOT gated by `aiforum.ambient.enabled` — that flag is the SCHEDULER
 * kill switch; the owner can always hand-fire a tick from here (manual-trigger-first). Public/no-auth like
 * the rest of /admin (single-owner PoC).
 */
@Controller
class AmbientController(
    private val tickService: AmbientTickService,
    private val ambientRuns: AmbientRunRepository,
    private val clock: Clock,
) {

    @GetMapping("/admin/ambient")
    fun ambient(model: Model): String {
        val now = clock.instant()
        model.addAttribute("runs", ambientRuns.recent(RECENT_LIMIT).map { it.toView(now) })
        return "admin_ambient"
    }

    @PostMapping("/admin/ambient/tick")
    fun tick(): ResponseEntity<Void> {
        tickService.tick(TickSource.MANUAL)
        // 303 See Other: a POST that did work, then GET the result — refresh-safe, lands on the fresh run.
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/admin/ambient")).build()
    }

    private fun AmbientRunRepository.AmbientRun.toView(now: Instant) = AmbientRunView(
        id = id,
        outcome = outcome,
        action = action,
        source = source,
        ago = RelativeTime.ago(Instant.parse(tickTime), now),
        detail = detail,
        articleTitle = articleTitle,
        articleUrl = articleUrl,
        personaId = personaId,
        threadId = threadId,
        costUsd = costUsd?.let { String.format(Locale.ROOT, "%.4f", it) },
    )

    private companion object {
        const val RECENT_LIMIT = 50
    }
}
