package com.aiforum.web

import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.ThreadReadRepository
import com.aiforum.repo.ThreadRepository
import com.aiforum.shortcut.ShortcutService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/** View model for a single row on the front page. [author] is the OP's persona attribution (V20), or
 *  null for an owner-authored thread — the row emits its byline hook only when a persona authored it. */
data class ThreadRow(val id: String, val title: String, val unreadCount: Int, val author: String? = null)

/** A row in the right-rail "Active threads" box: thread + a compact "time ago" of its last activity. */
data class ActiveThreadRow(val id: String, val title: String, val ago: String)

/** A row in the right-rail "Recent comments" box: a quoted snippet linking to the comment in its thread. */
data class RecentCommentRow(val threadId: String, val id: String, val author: String, val snippet: String, val ago: String)

/** A row in the right-rail "Starred" box and the /starred page: a cross-thread bookmark. */
data class StarredCommentRow(val threadId: String, val id: String, val threadTitle: String, val author: String, val snippet: String, val ago: String)

/**
 * Renders the front page (GET /): empty state when no threads exist; otherwise a list of thread
 * rows with unread reply counts (§2). The left rail's Members box lists the roster; the right rail's
 * Active-threads box lists the most recently active threads.
 */
@Controller
class HomeController(
    private val threads: ThreadRepository,
    private val threadReads: ThreadReadRepository,
    private val personas: PersonaRepository,
    private val railFeeds: RailFeeds,
    private val shortcut: ShortcutService,
) {
    @GetMapping("/")
    fun home(model: Model): String {
        val rows = threads.findAll().map { t ->
            ThreadRow(t.id, t.title, threadReads.unreadCount(t.id), t.authorId)
        }
        model.addAttribute("threads", rows)
        val personaViews = personas.findAll().map {
            PersonaView(it.id, it.name, it.descriptor, it.slug, colorIndex = it.colorIndex)
        }
        model.addAttribute("personas", personaViews)
        // Left-rail "~/forum" nav counts.
        model.addAttribute("threadCount", rows.size)
        model.addAttribute("personaCount", personaViews.size)
        // Right-rail feeds — shared with the thread page via RailFeeds so they read identically there.
        model.addAttribute("activeThreads", railFeeds.activeThreads())
        model.addAttribute("recentComments", railFeeds.recentComments())
        model.addAttribute("starredComments", railFeeds.starredComments())
        // Right-rail Shortcut box — the configured default query; hides itself when the integration is off.
        model.addAttribute("shortcut", shortcut.boxStories())
        return "index"
    }
}
