package com.aiforum.web

import com.aiforum.dto.Snippet
import com.aiforum.persona.TopicSpread
import com.aiforum.repo.InterestChange
import com.aiforum.repo.InterestChangeRepository
import com.aiforum.repo.PersonaInterestRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.service.DriftSource
import com.aiforum.service.InterestDriftService
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
 * One engagement an audited interest swap was judged from, as an /admin/interests row renders it.
 *
 * The prose is a SNAPSHOT taken at judgment time, never a live read of the comment: `comment.body` is
 * mutable in place (edit, revision select), so re-reading it would let the evidence drift under the
 * record that justifies it and the owner would be weighing the judgment against text the judge never
 * saw (`comment_quote.quoted_text`, V25 header; [CitedExchangeView] makes the same call one slice over).
 *
 * A separate type from [CitedExchangeView] rather than a shared one: the two audit logs snapshot the
 * same *shape* today by coincidence of both citing comments, and merging them would couple S4a's row
 * format to S4b's — the moment either pass changes what it snapshots, the shared type grows a nullable
 * field for the other's benefit.
 */
data class CitedEngagementView(
    val commentId: String,
    val threadId: String,
    val snippet: String,
) {
    /**
     * The cited comment in situ, or null when the snapshot carries no usable ids — precomputed here so
     * the "is this citation linkable?" decision is one Kotlin expression rather than a condition in the
     * template. `interest_change.cited` carries no foreign key by design (V27 header), so a citation
     * whose ids were never recorded — or whose thread has since been deleted — still has to render its
     * evidence, just without a dead link on it.
     */
    val permalink: String? =
        if (commentId.isNotBlank() && threadId.isNotBlank()) "/threads/$threadId#reply-$commentId" else null
}

/**
 * One audited interest swap as the /admin/interests log renders it — the row's data-* hooks live here.
 *
 * [personaId] is the raw id (the `data-interest-persona` hook, which the acceptance probe reads) and
 * [personaLabel] the visible name: the same split every author-bearing surface in this app makes
 * (`StanceChangeView:52-54`).
 *
 * [droppedSource] is the provenance the phrase carried BEFORE the pass wrote `drifted` over it, and it is
 * shown because it is what a revert restores (D10): a seeded phrase coming home labelled `drifted` would
 * be a lie the next pass reads.
 *
 * [reverted] is derived from `reverted_at` rather than carried as a timestamp because the page only asks
 * the yes/no question ("has the owner already undone this?"); the stamp itself is the storage layer's
 * double-revert guard (`InterestChangeRepository.markReverted`), not something the owner reads.
 *
 * There is deliberately no field here that counts anything — not how many phrases the member holds, not
 * how often it has drifted. `InterestChangeRepository` offers no aggregate at all for that reason (V27
 * header), and a view model that grew one would be where the cut score came back.
 */
data class InterestChangeView(
    val id: Long,
    val personaId: String,
    val personaLabel: String,
    val dropped: String,
    val droppedSource: String,
    val takenUp: String,
    val reverted: Boolean,
    val ago: String,
    val cited: List<CitedEngagementView>,
)

/**
 * One row of the room map: a phrase, and the members holding it BY NAME (D12).
 *
 * [holders] is a rendered string rather than a `List<String>` for two reasons, the second load-bearing:
 * a `@param` carrying a nested generic is noise the template does not need, and every value on this row
 * has to be digit-free — joining in Kotlin keeps the one place that could introduce a "2 of 7" in code
 * a test can read, rather than in a template expression nothing checks.
 *
 * Note the type: no `Int`, nothing keyed to a member. A count is the shape an owner starts thresholding
 * on, and a threshold an owner acts on is the population metric this slice keeps away from models — the
 * acceptance step asserts a digit-free row for exactly that reason.
 */
data class RoomTopicView(
    val phrase: String,
    val holders: String,
)

/**
 * The interest-drift pass's admin surface (plan_docs/ambient-slice-4b.md D9/D10/D12):
 *  - GET  /admin/interests              — the audit log (set down → taken up per member, with the words it
 *                                         was judged from) plus the room map, linked from /admin.
 *  - POST /admin/interests/drift        — run one pass by hand, then PRG-redirect (303) back to the log.
 *  - POST /admin/interests/{id}/revert  — undo one audited swap, 303 back to the log.
 *
 * This page carries the owner's ENTIRE control over the pass. S4b auto-applies with no approval queue
 * (D6, the S4a precedent), so there is no "pending" state to review — a swap is already live by the time
 * it appears here, and reading set-down → taken-up against the cited engagement, then reverting, is the
 * only lever. Hence a row that shows both phrases and the evidence rather than a summary line.
 *
 * Its own controller rather than another dependency on [AdminController], mirroring [StanceAdminController]
 * and [AmbientController]: the two POSTs make this a WRITE surface, which the read-only dashboard
 * controller deliberately is not.
 *
 * The manual trigger is NOT gated by `aiforum.interest-drift.enabled` — that flag is the SCHEDULER kill
 * switch. It also cannot be: the scheduler is `@Profile("!test")`, so this button is the only way the
 * acceptance suite can exercise the pass at all (D8). Public/no-auth like the rest of /admin (single-owner
 * PoC).
 */
@Controller
class InterestAdminController(
    private val drift: InterestDriftService,
    private val changes: InterestChangeRepository,
    private val interests: PersonaInterestRepository,
    private val personas: PersonaRepository,
    private val clock: Clock,
) {

    // Named for the page rather than the path (`interests` would shadow the repository property above at
    // every call site inside this class, which reads as a bug even while it compiles).
    @GetMapping("/admin/interests")
    fun interestLog(model: Model): String {
        val now = clock.instant()
        model.addAttribute("changes", changes.recent(RECENT_LIMIT).map { it.toView(now) })
        addRoomMap(model)
        return "admin_interests"
    }

    @PostMapping("/admin/interests/drift")
    fun runDrift(): ResponseEntity<Void> {
        // The return value (how many members moved) is deliberately dropped: the log below IS the readout,
        // and a "3 members drifted" flash message would be a number about members on the one page whose
        // whole design is that it carries none (D12).
        drift.drift(DriftSource.MANUAL)
        return backToLog()
    }

    /**
     * Undo one audited swap. An unknown or already-reverted id is a no-op in the service (its
     * `reverted_at IS NULL` predicate is the guard, in SQL), so this ignores the boolean and redirects
     * either way — the log itself shows what happened, and a failed revert on a stale page must not
     * become an error page the owner has to back out of ([StanceAdminController.revert]'s reasoning).
     */
    @PostMapping("/admin/interests/{id}/revert")
    fun revert(@PathVariable id: Long): ResponseEntity<Void> {
        drift.revert(id)
        return backToLog()
    }

    /**
     * The convergence readout (D12): which phrases most of the room now holds, which ones exactly one
     * member holds, and one line of plain English over both.
     *
     * **The id → display-name mapping here is the load-bearing line.**
     * [PersonaInterestRepository.sharedInterests] returns phrase → persona **IDS** (deliberately: a
     * `JOIN persona` would make the repository's return value a display concern), while
     * [TopicSpread.SharedTopic.holderNames] is rendered as prose the owner reads. Today `id == name` for
     * every seeded member, so omitting this map would render *correctly by luck* and no test in the suite
     * could catch it — the first hand-created member whose id and name diverge would silently start
     * showing raw ids on the admin page. Mapped explicitly for that reason. An id with no roster entry
     * falls back to itself rather than being dropped: a member deleted between the two reads is a race,
     * and a phrase losing a holder would understate the very convergence this map exists to show.
     *
     * `roster.size` is passed as the divisor and is deliberately never stored — the threshold is a
     * property of the room at the moment of the read (TopicSpread's KDoc), so nothing here can be
     * compared across weeks or keyed to a member.
     */
    private fun addRoomMap(model: Model) {
        val roster = personas.findAll()
        val nameById = roster.associate { it.id to it.name }
        val holdersByPhrase = interests.sharedInterests()
            .mapValues { (_, holderIds) -> holderIds.map { nameById[it] ?: it } }
        val spread = TopicSpread.of(holdersByPhrase, roster.size)
        model.addAttribute("shared", spread.shared.map { RoomTopicView(it.phrase, it.holderNames.joinToString(", ")) })
        model.addAttribute("sole", spread.sole.map { RoomTopicView(it.phrase, it.holderName) })
        model.addAttribute("spreadSentence", spread.sentence)
    }

    /** 303 See Other: a POST that did work, then GET the result — refresh-safe, lands on the fresh log. */
    private fun backToLog(): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(LOG_PATH)).build()

    private fun InterestChange.toView(now: Instant) = InterestChangeView(
        id = id,
        personaId = personaId,
        personaLabel = AuthorLabel.display(personaId),
        dropped = dropped,
        droppedSource = droppedSource,
        takenUp = takenUp,
        reverted = revertedAt != null,
        ago = RelativeTime.ago(Instant.parse(changedAt), now),
        cited = parseCited(cited),
    )

    /**
     * Split the audit row's `cited` snapshot into ready-to-render citations, so the template does no
     * parsing and no lookups (a template that parses is a template no test can reach).
     *
     * The stored format is one engagement per line, TAB-separated as `commentId \t threadId \t prose`
     * (V27's `interest_change.cited` comment; `StanceEvolutionService.CITED_SEPARATOR` writes the same
     * shape one slice over). Tabs rather than punctuation because the third field is model-adjacent free
     * text: the writer flattens it through [Snippet.oneLine] first, which collapses every whitespace run
     * to a single space — so a tab cannot survive inside the prose and cannot split a citation in the
     * wrong place. `limit = 3` keeps that guarantee even if a future writer stops flattening.
     *
     * A line that does not carry both ids is NOT dropped: it renders as unlinked evidence. `cited` has no
     * foreign key by design, so this column is the system's only copy of what was judged — losing a
     * malformed line would quietly delete the justification for a swap the owner is trying to review.
     */
    private fun parseCited(cited: String): List<CitedEngagementView> =
        cited.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val fields = line.split('\t', limit = 3)
                val located = fields.size == 3
                CitedEngagementView(
                    commentId = if (located) fields[0].trim() else "",
                    threadId = if (located) fields[1].trim() else "",
                    // Flattened again on the way out: the snapshot is only as well-behaved as whoever
                    // wrote it, and one un-flattened comment body must not be able to blow the row up.
                    snippet = Snippet.oneLine(if (located) fields[2] else line, SNIPPET_LEN),
                )
            }
            .toList()

    private companion object {
        const val LOG_PATH = "/admin/interests"

        const val RECENT_LIMIT = 50

        // S4a's figure and S4a's reason: this text is EVIDENCE the owner weighs a judgment against, not a
        // row label they scan past, so it gets room to say what was actually said.
        const val SNIPPET_LEN = 160
    }
}
