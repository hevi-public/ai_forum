package com.aiforum.web

import com.aiforum.dto.Snippet
import com.aiforum.repo.StanceChange
import com.aiforum.repo.StanceChangeRepository
import com.aiforum.service.EvolutionSource
import com.aiforum.service.StanceEvolutionService
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
 * One exchange an audited stance change was judged from, as the /admin/stances row renders it.
 *
 * The prose is a SNAPSHOT taken at judgment time, not a live read of the comment: `comment.body` is
 * mutable in place (edit, revision select), so re-reading it would let the evidence drift under the
 * record that justifies it and the owner would be reviewing the judgment against text the judge never
 * saw (the `comment_quote.quoted_text` precedent, V25 header).
 */
data class CitedExchangeView(
    val commentId: String,
    val threadId: String,
    val snippet: String,
) {
    /**
     * The cited comment in situ, or null when the snapshot carries no usable ids. Precomputed here
     * rather than assembled in the template so the "is this citation linkable?" decision lives in one
     * Kotlin expression: `stance_change.cited` has no foreign key by design (V25 header), so a citation
     * whose ids were never recorded — or whose thread has since been deleted — still has to render its
     * evidence, just without a dead link on it.
     */
    val permalink: String? =
        if (commentId.isNotBlank() && threadId.isNotBlank()) "/threads/$threadId#reply-$commentId" else null
}

/**
 * One audited stance rewrite as the /admin/stances log renders it — the row's data-* hooks live here.
 *
 * [reverted] is derived from `reverted_at` rather than carried as a timestamp because the page only ever
 * asks the yes/no question ("has the owner already undone this?"); the stamp itself is the storage
 * layer's double-revert guard, not something the owner reads.
 */
data class StanceChangeView(
    val id: Long,
    // The raw persona ids stay on the data-* hooks (data-stance-from / data-stance-to); only the visible
    // names go through AuthorLabel, the same split every other author-bearing surface makes.
    val fromPersona: String,
    val toPersona: String,
    val fromLabel: String,
    val toLabel: String,
    val oldStance: String,
    val newStance: String,
    // The provenance the row carried BEFORE the pass wrote 'evolved' over it — what a revert restores,
    // so it is shown: reverting a seeded edge back to `seeded` reads very differently from reverting one
    // the owner had already taken over by hand.
    val oldSource: String,
    val reverted: Boolean,
    val ago: String,
    val cited: List<CitedExchangeView>,
)

/**
 * The relation-stance evolution pass's admin surface (plan_docs/ambient-slice-4a.md D9/D10):
 *  - GET  /admin/stances              — the audit log (old → new per changed edge, with the exchanges it
 *                                       was judged from), linked from the /admin stance-changes tile.
 *  - POST /admin/stances/evolve       — run one pass by hand, then PRG-redirect (303) back to the log.
 *  - POST /admin/stances/{id}/revert  — undo one audited change, 303 back to the log.
 *
 * This page carries the owner's ENTIRE control over the pass. S4a auto-applies with no approval queue
 * (direction doc §11.5, owner call), so there is no "pending" state to review — a change is already live
 * by the time it appears here, and reading old → new against the cited exchange, then reverting, is the
 * only lever. That is why the row shows both texts and the evidence rather than a summary line.
 *
 * Its own controller rather than a fifth dependency on [AdminController]: [AmbientController] set that
 * precedent for a per-surface admin page, and the two POSTs make this one a write surface, which the
 * read-only dashboard controller deliberately is not.
 *
 * The manual trigger is NOT gated by `aiforum.stance-evolution.enabled` — that flag is the SCHEDULER kill
 * switch. It also cannot be: the scheduler is `@Profile("!test")`, so this button is the only way the
 * acceptance suite can exercise the pass at all. Public/no-auth like the rest of /admin (single-owner PoC).
 */
@Controller
class StanceAdminController(
    private val evolution: StanceEvolutionService,
    private val changes: StanceChangeRepository,
    private val clock: Clock,
) {

    @GetMapping("/admin/stances")
    fun stances(model: Model): String {
        val now = clock.instant()
        model.addAttribute("changes", changes.recent(RECENT_LIMIT).map { it.toView(now) })
        return "admin_stances"
    }

    @PostMapping("/admin/stances/evolve")
    fun evolve(): ResponseEntity<Void> {
        evolution.evolve(EvolutionSource.MANUAL)
        return backToLog()
    }

    /**
     * Undo one audited change. An unknown or already-reverted id is a no-op in the service, so this
     * ignores the return value and redirects either way — the log itself shows what happened, and a
     * failed revert on a stale page must not become an error page the owner has to back out of.
     */
    @PostMapping("/admin/stances/{id}/revert")
    fun revert(@PathVariable id: Long): ResponseEntity<Void> {
        evolution.revert(id)
        return backToLog()
    }

    /** 303 See Other: a POST that did work, then GET the result — refresh-safe, lands on the fresh log. */
    private fun backToLog(): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(LOG_PATH)).build()

    private fun StanceChange.toView(now: Instant) = StanceChangeView(
        id = id,
        fromPersona = fromPersona,
        toPersona = toPersona,
        fromLabel = AuthorLabel.display(fromPersona),
        toLabel = AuthorLabel.display(toPersona),
        oldStance = oldStance,
        newStance = newStance,
        oldSource = oldSource,
        reverted = revertedAt != null,
        ago = RelativeTime.ago(Instant.parse(changedAt), now),
        cited = parseCited(cited),
    )

    /**
     * Split the audit row's `cited` snapshot into ready-to-render citations, so the template does no
     * parsing and no lookups (a template that parses is a template no test can reach).
     *
     * The stored format is one exchange per line, TAB-separated as `commentId \t threadId \t prose`.
     * Tabs and not a punctuation delimiter because the third field is model-adjacent free text: the
     * writer flattens it through [Snippet.oneLine] first, which collapses every whitespace run to a
     * single space — so a tab cannot survive inside the prose and cannot split a citation in the wrong
     * place. `limit = 3` keeps that guarantee even if a future writer stops flattening.
     *
     * A line that does not carry both ids is NOT dropped: it renders as unlinked evidence. `cited` has no
     * foreign key by design (V25 header), so this column is the only copy of what was judged — losing a
     * malformed line would quietly delete the justification for a change the owner is trying to review.
     */
    private fun parseCited(cited: String): List<CitedExchangeView> =
        cited.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val fields = line.split('\t', limit = 3)
                val located = fields.size == 3
                CitedExchangeView(
                    commentId = if (located) fields[0].trim() else "",
                    threadId = if (located) fields[1].trim() else "",
                    // Flattened again on the way out: the snapshot is only as well-behaved as whoever
                    // wrote it, and one un-flattened comment body must not be able to blow the row up.
                    snippet = Snippet.oneLine(if (located) fields[2] else line, SNIPPET_LEN),
                )
            }
            .toList()

    private companion object {
        const val LOG_PATH = "/admin/stances"

        const val RECENT_LIMIT = 50

        // Longer than the /admin/comments snippet: this text is EVIDENCE the owner weighs a judgment
        // against, not a row label they scan past, so it gets room to say what was actually said.
        const val SNIPPET_LEN = 160
    }
}
