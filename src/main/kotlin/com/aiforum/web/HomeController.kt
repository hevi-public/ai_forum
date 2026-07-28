package com.aiforum.web

import com.aiforum.repo.FeedRepository
import com.aiforum.repo.OwnerPrefRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.ThreadRepository
import com.aiforum.shortcut.ShortcutService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import java.time.Clock

/**
 * View model for a single thread card on the front page (plan_docs/ambient-slice-6.md §2.1).
 *
 * [author] is the OP's persona attribution (V20), or null for an owner-authored thread — the card emits
 * its byline hook only when a persona authored it, and the value stays the RAW id the ambient scenarios
 * read. The last three fields are S6's additions and are **defaulted**, so the type's name and shape
 * survive: [ago] is the compact "time ago" of the thread's last activity ("" when the stored stamp will
 * not parse), [excerpt] a one-line preview of the newest settled comment (or of the thread's own opening
 * post when it has no replies), and [excerptBy] the voice that preview came from, or null when there is
 * none to name (see [FeedCards.threadCard]).
 */
data class ThreadRow(
    val id: String,
    val title: String,
    val unreadCount: Int,
    val author: String? = null,
    val ago: String = "",
    val excerpt: String = "",
    val excerptBy: String? = null,
)

/**
 * View model for a single card in the activity stream — a settled comment or a thread opening, told
 * apart by [kind] ("comment" / "post").
 *
 * [author] is the raw stored id (the stream's own hook), [byline] and [monogram] its display forms, and
 * [href] the link *into* the thread at this event. A post card's [id] is its [threadId], so both kinds
 * share one href shape (see [FeedCards.activityCard]).
 */
data class ActivityRow(
    val kind: String,
    val id: String,
    val threadId: String,
    val threadTitle: String,
    val author: String,
    val byline: String,
    val monogram: String,
    val hue: Int,
    val excerpt: String,
    val ago: String,
    val unread: Boolean,
    val href: String,
    val threadHref: String,
)

/**
 * The front page as one typed object: which view the owner is looking at, and that view's cards.
 *
 * **Two typed lists, never one shared card type** (D5). The list belonging to the view that is not
 * showing is empty, because only the active view's query is issued at all — the template branches on
 * [view], and JTE's typed `@param` is what stops a stream card ever reaching the thread-card fragment.
 */
data class FeedPage(val view: FeedView, val threadCards: List<ThreadRow>, val events: List<ActivityRow>)

/** A row in the right-rail "Active threads" box: thread + a compact "time ago" of its last activity. */
data class ActiveThreadRow(val id: String, val title: String, val ago: String)

/** A row in the right-rail "Recent comments" box: a quoted snippet linking to the comment in its thread. */
data class RecentCommentRow(val threadId: String, val id: String, val author: String, val snippet: String, val ago: String)

/** A row in the right-rail "Starred" box and the /starred page: a cross-thread bookmark. */
data class StarredCommentRow(val threadId: String, val id: String, val threadTitle: String, val author: String, val snippet: String, val ago: String)

/**
 * Renders the front page (GET /) as one of two readings of the same forum (S6): activity-sorted thread
 * cards, or the reverse-chronological activity stream behind the toggle — whichever the owner last chose.
 * The left rail's Members box lists the roster; the right rail's boxes are the shared [RailFeeds].
 *
 * **Note what is absent from the constructor: `ThreadReadRepository`.** The front page used to be
 * `threads.findAll().map { threadReads.unreadCount(it.id) }` — two queries per row over an unbounded
 * read. Both collapse into [FeedRepository]'s one grouped query, and with no per-row door injected here,
 * bringing the 2N+1 back costs a visible constructor change rather than a line slipped into a `.map {}`
 * (I4). [ThreadRepository] survives for `count()` alone, which both views need for the left rail.
 */
@Controller
class HomeController(
    private val threads: ThreadRepository,
    private val feed: FeedRepository,
    private val prefs: OwnerPrefRepository,
    private val personas: PersonaRepository,
    private val railFeeds: RailFeeds,
    private val shortcut: ShortcutService,
    private val clock: Clock,
) {
    @GetMapping("/")
    fun home(model: Model): String {
        val personaViews = personas.findAll().map {
            PersonaView(it.id, it.name, it.descriptor, it.slug, colorIndex = it.colorIndex)
        }
        val view = prefs.feedView()
        val now = clock.instant()
        // Only the active view's query is issued — the other list stays empty. That is why the left rail's
        // thread count comes from ThreadRepository.count() and not from threadCards.size: the activity
        // view has no thread cards to count, and counting them would have re-introduced the very read the
        // stream exists without.
        val page = when (view) {
            FeedView.THREADS -> FeedPage(view, feed.feedThreads().map { FeedCards.threadCard(it, now) }, emptyList())
            FeedView.ACTIVITY -> FeedPage(
                view, emptyList(),
                feed.recentActivity(FeedCards.STREAM_LIMIT).map { FeedCards.activityCard(it, personaViews, now) },
            )
        }
        model.addAttribute("feed", page)
        model.addAttribute("personas", personaViews)
        // Left-rail "~/forum" nav counts.
        model.addAttribute("threadCount", threads.count())
        model.addAttribute("personaCount", personaViews.size)
        // Right-rail feeds — shared with the thread page via RailFeeds so they read identically there.
        // All four are read in both views; the activity view only declines to RENDER the recent-comments
        // box (D11), and that suppression lives in index.kte so the fragment and RailFeeds stay shared
        // with every thread page byte for byte.
        model.addAttribute("activeThreads", railFeeds.activeThreads())
        model.addAttribute("recentComments", railFeeds.recentComments())
        model.addAttribute("starredComments", railFeeds.starredComments())
        // Right-rail Shortcut box — the configured default query; hides itself when the integration is off.
        model.addAttribute("shortcut", shortcut.boxStories())
        return "index"
    }

    /**
     * Remember which view the owner chose, then redirect back to it.
     *
     * **A plain form POST followed by a redirect** (§2.4): `localStorage` is invisible to a suite that
     * drives no browser, `HttpClient` keeps no cookie jar, and an htmx control whose target flips with
     * state shipped broken once with no tier to catch it. The redirect is the PRG half — a refresh after
     * switching must not re-post the choice.
     *
     * An unknown view is refused with a 400 and nothing is written. This is the middle of the three
     * layers guarding the stored value: [OwnerPrefRepository.setFeedView] takes the enum, so there is no
     * String door below here, and V29's CHECK refuses one below that.
     */
    @PostMapping("/feed-view", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun chooseFeedView(@RequestParam("view") view: String?): String {
        val chosen = FeedView.of(view)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown feed view: $view")
        prefs.setFeedView(chosen)
        return "redirect:/"
    }
}
