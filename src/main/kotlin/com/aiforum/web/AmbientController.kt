package com.aiforum.web

import com.aiforum.ambient.TickSource
import com.aiforum.repo.AmbientRunRepository
import com.aiforum.repo.GenerationToolCallRepository
import com.aiforum.service.AmbientTickService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import java.net.URI
import java.time.Clock
import java.time.Duration
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
 * The /admin/ambient usage strip (issue #16): rolling 24h/7d spend + tool-call-count aggregates,
 * computed live from the SAME `ambient_run`/`generation_tool_call` rows the run list and /admin/tools
 * render from — there is no separate ledger to drift out of sync (the reconcile scenario in
 * usage_observability.feature proves this rather than assuming it).
 *
 * Cost fields carry the SAME absent-means-unknown idiom [AmbientRunView.costUsd] already does: null
 * (never "0.0000") when every run in the window is unpriced. Tool-call counts are a plain COUNT and are
 * NEVER absent — 0 genuinely means "no tool calls in the window", not "we don't know".
 */
data class UsageAggregatesView(
    val cost24h: String?,
    val cost7d: String?,
    val toolCalls24h: Int,
    val toolCalls7d: Int,
)

/**
 * The ambient loop's admin surface (plan_docs/ambient-slice-1.md):
 *  - GET  /admin/ambient        — the run log (recent ticks + the manual-trigger button) plus the issue
 *                                 #16 usage strip above it, linked from the /admin ambient-runs stat tile.
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
    private val toolCalls: GenerationToolCallRepository,
    private val clock: Clock,
) {

    @GetMapping("/admin/ambient")
    fun ambient(model: Model): String {
        val now = clock.instant()
        model.addAttribute("runs", ambientRuns.recent(RECENT_LIMIT).map { it.toView(now) })
        model.addAttribute("usage", usageAggregates(now))
        return "admin_ambient"
    }

    @PostMapping("/admin/ambient/tick")
    fun tick(): ResponseEntity<Void> {
        tickService.tick(TickSource.MANUAL)
        // 303 See Other: a POST that did work, then GET the result — refresh-safe, lands on the fresh run.
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/admin/ambient")).build()
    }

    /** [now] minus 24h / 7d, ISO-8601 via the injected Clock (never `Instant.now()` — see the
     *  bdd-tiered-testing skill on the seam discipline this whole app holds to). */
    private fun usageAggregates(now: Instant): UsageAggregatesView {
        val cutoff24h = now.minus(Duration.ofHours(24)).toString()
        val cutoff7d = now.minus(Duration.ofDays(7)).toString()
        return UsageAggregatesView(
            cost24h = ambientRuns.costSince(cutoff24h)?.let { String.format(Locale.ROOT, "%.4f", it) },
            cost7d = ambientRuns.costSince(cutoff7d)?.let { String.format(Locale.ROOT, "%.4f", it) },
            toolCalls24h = toolCalls.countSince(cutoff24h),
            toolCalls7d = toolCalls.countSince(cutoff7d),
        )
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
