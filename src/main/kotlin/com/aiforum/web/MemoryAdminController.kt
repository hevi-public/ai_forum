package com.aiforum.web

import com.aiforum.dto.Snippet
import com.aiforum.repo.MemoryChange
import com.aiforum.repo.MemoryChangeRepository
import com.aiforum.service.MemoryScribeService
import com.aiforum.service.ScribeSource
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import java.net.URI
import java.time.Clock
import java.time.Instant

/**
 * One engagement an audited memory write was judged from, as an /admin/memory row renders it.
 *
 * The prose is a SNAPSHOT taken at judgment time, never a live read of the comment: `comment.body`
 * is mutable in place, so re-reading would let the evidence drift under the record that justifies
 * it ([CitedEngagementView] makes the same call one slice back). A separate type from that one
 * rather than a shared one, deliberately: the two audit logs snapshot the same *shape* today by
 * coincidence of both citing comments, and merging them would couple S4b's row format to this
 * slice's the moment either pass changes what it snapshots.
 */
data class CitedMemoryView(
    val commentId: String,
    val threadId: String,
    val snippet: String,
) {
    /** The cited comment in situ, or null when the snapshot carries no usable ids — precomputed so
     *  "is this citation linkable?" is one Kotlin expression rather than a template condition.
     *  `memory_change.cited` carries no foreign key by design (V28), so a citation whose thread has
     *  since been deleted still renders its evidence, unlinked rather than absent. */
    val permalink: String? =
        if (commentId.isNotBlank() && threadId.isNotBlank()) "/threads/$threadId#reply-$commentId" else null
}

/**
 * One audited memory write as the /admin/memory log renders it — the row's data-* hooks live here.
 *
 * [personaId] is the raw id and [personaLabel] the visible name, the split every author-bearing
 * surface makes. [parentBody] is the snapshot of the antecedent the record extended — prose, never
 * a live read, for the same reason [cited] is. [reverted] is derived from `reverted_at` because
 * the page only asks the yes/no question; the stamp itself is the storage layer's double-revert
 * guard.
 *
 * There is deliberately no field here that counts anything — not how many records a member holds,
 * not how often the pass has written. `MemoryChangeRepository` offers no aggregate at all (§4
 * Stays-Cut), and a view model that grew one would be where a memory-health score came back.
 */
data class MemoryChangeView(
    val id: Long,
    val personaId: String,
    val personaLabel: String,
    val body: String,
    val parentBody: String,
    val reverted: Boolean,
    val ago: String,
    val cited: List<CitedMemoryView>,
)

/**
 * The Memory Scribe's admin surface (plan_docs/persona-memory.md §2.12):
 *  - GET  /admin/memory             — the audit log, newest first, linked from /admin.
 *  - POST /admin/memory/run         — run one pass by hand, synchronously, then render the fresh log.
 *  - POST /admin/memory/revert/{id} — undo one audited write, 303 back to the log.
 *
 * This page carries the owner's ENTIRE control over the pass: writes auto-apply with no approval
 * queue (the standing owner override of §6.5), so a record is already live by the time it appears
 * here, and reading it against its cited evidence, then reverting, is the only lever. Its own
 * controller rather than a dependency on [AdminController], the [InterestAdminController] pattern:
 * the two POSTs make this a WRITE surface, which the read-only dashboard deliberately is not.
 *
 * **Room-free by design**: no roster read, no per-member grouping, no count of anything — there is
 * no memory analogue of the interests room map, because any cross-member aggregate over this table
 * is a memory-health score wearing an auditor's badge (§2.12, no stat tile).
 *
 * The manual trigger is NOT gated by `aiforum.memory.enabled` — that flag is the SCHEDULER kill
 * switch, and it cannot gate this: the scheduler pair is `@Profile("!test")`, so this button is the
 * only way the acceptance suite can exercise the pass at all. Public/no-auth like the rest of
 * /admin (single-owner PoC).
 */
@Controller
class MemoryAdminController(
    private val scribe: MemoryScribeService,
    private val changes: MemoryChangeRepository,
    private val clock: Clock,
) {

    @GetMapping("/admin/memory")
    fun memoryLog(model: Model): String = renderLog(model)

    /**
     * The prod button and the only acceptance seam. Synchronous — the pass runs on this request
     * thread — and it answers 200 with the freshly rendered log rather than a PRG redirect: the
     * feature contract pins the status (a failing member inside the run is a recorded outcome, and
     * the run itself must complete with a page, scenario 13), and the default RestClient does not
     * follow a 303, so a redirect would make "the pass completed" unreadable at the one seam that
     * asserts it. The return value (how many records landed) is deliberately dropped: the log below
     * IS the readout, and a "wrote N memories" flash would be a count on a surface designed to
     * carry none.
     */
    @PostMapping("/admin/memory/run")
    fun run(model: Model): String {
        scribe.consolidate(ScribeSource.MANUAL)
        return renderLog(model)
    }

    /**
     * Undo one audited write. An unknown, already-reverted or superseded id is a no-op in the
     * service (action-site re-read, `reverted_at IS NULL` in SQL), so this ignores the boolean and
     * redirects either way — the log itself shows what happened, and a failed revert on a stale
     * page must not become an error page the owner has to back out of.
     */
    @PostMapping("/admin/memory/revert/{id}")
    fun revert(@PathVariable id: Long): ResponseEntity<Void> {
        scribe.revert(id)
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(LOG_PATH)).build()
    }

    private fun renderLog(model: Model): String {
        val now = clock.instant()
        model.addAttribute("changes", changes.recent(RECENT_LIMIT).map { it.toView(now) })
        return "admin_memory"
    }

    private fun MemoryChange.toView(now: Instant) = MemoryChangeView(
        id = id,
        personaId = personaId,
        personaLabel = AuthorLabel.display(personaId),
        body = body,
        parentBody = parentBody.orEmpty(),
        reverted = revertedAt != null,
        ago = RelativeTime.ago(Instant.parse(changedAt), now),
        cited = parseCited(cited),
    )

    /**
     * Split the audit row's `cited` snapshot into ready-to-render citations, so the template does
     * no parsing and no lookups. The stored format is one engagement per line, TAB-separated as
     * `commentId \t threadId \t prose` (`MemoryScribeService.renderCited`). A line that does not
     * carry both ids is NOT dropped: it renders as unlinked evidence — `cited` has no foreign key,
     * so this column is the system's only copy of what was judged, and losing a malformed line
     * would quietly delete the justification for a record the owner is reviewing.
     */
    private fun parseCited(cited: String): List<CitedMemoryView> =
        cited.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val fields = line.split('\t', limit = 3)
                val located = fields.size == 3
                CitedMemoryView(
                    commentId = if (located) fields[0].trim() else "",
                    threadId = if (located) fields[1].trim() else "",
                    // Flattened again on the way out: the snapshot is only as well-behaved as
                    // whoever wrote it, and one un-flattened body must not blow the row up.
                    snippet = Snippet.oneLine(if (located) fields[2] else line, SNIPPET_LEN),
                )
            }
            .toList()

    private companion object {
        const val LOG_PATH = "/admin/memory"

        const val RECENT_LIMIT = 50

        // The S4a/S4b figure and reason: this text is EVIDENCE the owner weighs a record against,
        // not a row label they scan past, so it gets room to say what was actually said.
        const val SNIPPET_LEN = 160
    }
}
