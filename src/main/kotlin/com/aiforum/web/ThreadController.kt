package com.aiforum.web

import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReplyView
import com.aiforum.dto.AttachmentView
import com.aiforum.dto.ScopeMode
import com.aiforum.markdown.MarkdownRenderer
import com.aiforum.repo.AttachmentRepository
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.ThreadReadRepository
import com.aiforum.repo.ThreadRepository
import com.aiforum.service.AttachmentService
import com.aiforum.service.GenerationService
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/** Request body for POST /threads. */
data class CreateThreadRequest(
    val title: String = "",
    val text: String = "",
    val personaIds: List<String> = emptyList(),
)

/**
 * Thread-level endpoints: create a thread and view its page. Creating a thread immediately summons the
 * room — a "Whole Topic + Anyone" call: the AI dispatcher reads the opening post and picks who weighs in,
 * then the chosen persona(s) reply (§2). The summon is async, so the page surfaces the in-flight drafts
 * (which self-poll to settle) rather than the old "waiting on the room" empty state.
 */
@Controller
class ThreadController(
    private val threads: ThreadRepository,
    private val comments: CommentRepository,
    private val personas: PersonaRepository,
    private val threadReads: ThreadReadRepository,
    private val generation: GenerationService,
    private val railFeeds: RailFeeds,
    private val replyTree: ReplyTreeAssembler,
    private val attachments: AttachmentService,
    private val attachmentRepo: AttachmentRepository,
    private val branchIndex: BranchIndexBuilder,
    private val shortcut: com.aiforum.shortcut.ShortcutService,
) {

    // Two bindings, one creation path: the browser's new-thread form posts form-urlencoded and wants a
    // PRG redirect onto the fresh thread page; the acceptance suite / API client posts JSON and asserts
    // on the returned thread HTML. Both go through [newThread] so the behaviour can't drift.
    @PostMapping("/threads", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createJson(@RequestBody req: CreateThreadRequest, model: Model): String {
        val id = newThread(req.title, req.text)
        // Owner-created via the API → author null (no persona byline). The ambient path opens threads with
        // a persona author; those render their byline on the GET below.
        return renderThread(id, req.title, req.text, edited = false, author = null, model = model)
    }

    @PostMapping("/threads", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun createForm(req: CreateThreadRequest): String {
        val id = newThread(req.title, req.text)
        // Post/Redirect/Get: land the browser on the new thread (correct URL, refresh-safe), where the
        // room — summoned on create (see newThread) — is already drafting its replies.
        return "redirect:/threads/$id"
    }

    // The browser new-thread form posts multipart when it can carry an image (enctype set in index.kte).
    // Same PRG as createForm; the images attach to the opening post (thread-scoped). The urlencoded /
    // JSON handlers above stay for the acceptance suite and API clients.
    @PostMapping("/threads", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createMultipart(
        req: CreateThreadRequest,
        @RequestParam(name = "images", required = false) images: List<MultipartFile>?,
    ): String {
        val id = newThread(req.title, req.text)
        images?.toUploads()?.takeIf { it.isNotEmpty() }?.let { attachments.attachToThread(id, it) }
        return "redirect:/threads/$id"
    }

    // text is the opening post's body — split out from the title on the new-thread form (§2). Optional;
    // blank for the title-only API/browser paths.
    private fun newThread(title: String, body: String): String {
        val id = UUID.randomUUID().toString()
        threads.insert(id, title, body)
        // Summon the room on creation: a "Whole Topic + Anyone" call. AUTO_PERSONA is the "Anyone"
        // sentinel (the AI dispatcher picks who replies); WHOLE_THREAD for both scopes is "Whole Topic"
        // — the dispatcher reads the whole topic (the opening post) to route, and the chosen persona then
        // reads the whole topic too. No owner message to post (the opening post lives on the thread body
        // and seeds context via the OP node), so postAsOwner stays false.
        //
        // summonAsync (not startGeneration): the dispatcher's routing LLM call runs on the worker too, so
        // POST /threads returns at once instead of blocking on the model — critical with a slow local
        // backend (LM Studio). The thread page polls /threads/{id}/room and swaps the drafts in once
        // routing picks who replies; each draft then self-polls to settle.
        generation.summonAsync(
            threadId = id,
            parentId = null,
            personaIds = listOf(GenerationService.AUTO_PERSONA),
            text = "",
            scope = ScopeMode.WHOLE_THREAD,
            routingScope = ScopeMode.WHOLE_THREAD,
        )
        return id
    }

    /**
     * Delete a thread and everything that hangs off it (§8): its comments (and their votes), the owner's
     * read marker, then the thread row. The home-page button outerHTML-swaps the thread row away with this
     * empty response, mirroring the cascade in the DB. Dependents go first — comment.thread_id and
     * thread_read.thread_id both reference thread(id).
     */
    @PostMapping("/threads/{id}/delete")
    @ResponseBody
    fun delete(@PathVariable id: String): String {
        comments.deleteByThread(id)
        threadReads.delete(id)
        threads.delete(id)
        return ""
    }

    @GetMapping("/threads/{id}")
    fun view(@PathVariable id: String, model: Model): String {
        val thread = threads.find(id) ?: return "redirect:/"
        threadReads.markRead(id)
        return renderThread(thread.id, thread.title, thread.body, thread.edited, thread.authorId, model)
    }

    /**
     * Edit the opening post (§7): the owner revises the thread title and/or body. The OP edit form
     * (rendered inline in the post block) outerHTML-swaps the post block with the re-rendered fragment,
     * so the marker and updated text appear in place. A blank title is rejected — the OP must keep a
     * title — by re-rendering the post unchanged; the body may be emptied.
     */
    @PostMapping("/threads/{id}/edit", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun editOp(
        @PathVariable id: String,
        @RequestParam(required = false) title: String?,
        @RequestParam(required = false) text: String?,
        model: Model,
    ): String {
        val thread = threads.find(id) ?: return "redirect:/"
        val newTitle = title?.trim().orEmpty()
        if (newTitle.isNotBlank()) {
            threads.updateOp(id, newTitle, text.orEmpty())
        }
        val updated = threads.find(id) ?: thread
        return renderOp(updated, model)
    }

    private fun renderOp(thread: ThreadRepository.Thread, model: Model): String {
        model.addAttribute("threadId", thread.id)
        model.addAttribute("title", thread.title)
        model.addAttribute("body", thread.body)
        model.addAttribute("bodyHtml", MarkdownRenderer.render(thread.body))
        model.addAttribute("edited", thread.edited)
        model.addAttribute("attachments", opAttachments(thread.id))
        // Carry the OP attribution + roster so a re-rendered persona-authored OP keeps its byline (and its
        // monogram hue) after an edit; owner-authored (null) stays byline-less.
        model.addAttribute("author", thread.authorId)
        model.addAttribute("personas", personas.findAll().map { PersonaView(it.id, it.name, it.descriptor, it.slug, colorIndex = it.colorIndex) })
        return "fragments/threadOp"
    }

    /** The opening post's images (thread-scoped), as gallery views — the OP is always owner-authored. */
    private fun opAttachments(threadId: String): List<AttachmentView> =
        attachmentRepo.forThread(threadId).map(AttachmentView::of)

    private fun renderThread(id: String, title: String, body: String, edited: Boolean, author: String?, model: Model): String {
        val all = comments.threadComments(id)
        model.addAttribute("threadId", id)
        model.addAttribute("title", title)
        model.addAttribute("body", body)
        model.addAttribute("bodyHtml", MarkdownRenderer.render(body))
        model.addAttribute("edited", edited)
        // The OP attribution (V20): a persona id for an ambient-authored thread, null for owner-authored —
        // threadOp renders the byline + data-thread-author hook only when non-null.
        model.addAttribute("author", author)
        model.addAttribute("opAttachments", opAttachments(id))
        // Nest replies under their parents so the page reflects the comment tree (a persona reply sits
        // under the message it answered). replyNode.kte renders reply.children recursively; the flat
        // list it gets here was rendering every node at level 0. Children keep their repository order
        // (depth, created_at), so siblings stay chronological.
        val tree = replyTree.assemble(all)
        // The room is summoned on creation (async); its DRAFTING replies live only in the in-flight
        // registry until they settle — no DRAFTING DB row exists. Surface them at the top level so a plain
        // page load (e.g. the PRG redirect after create) shows the room responding: each drafting node
        // self-polls /replies/{id} and settles in place. Dedupe by id against the DB tree to avoid a double
        // render in the brief window after a draft's settle-write but before it's evicted from in-flight.
        val rendered = collectIds(tree)
        val drafting = generation.inFlightViews(id).filter { it.id !in rendered }
        model.addAttribute("replies", tree + drafting)
        // A create-time summon routes (the dispatcher's "who replies" LLM call) on a worker, so right
        // after the create redirect there may be no drafts yet. While that routing is in flight, the page
        // shows a poller (see thread.kte) that swaps the drafts in once they land, instead of the static
        // waiting state.
        val summoning = generation.isSummoning(id)
        model.addAttribute("summoning", summoning)
        // Persona views carry each persona's stored colour slot, so the branch-index dots resolve to the
        // same hue as the reply monograms (see AuthorColor).
        val personaViews = personas.findAll().map { PersonaView(it.id, it.name, it.descriptor, it.slug, colorIndex = it.colorIndex) }
        // Branch index for the side rail: the posted nodes flattened in the same depth-first order the
        // page renders them, so the rail reads top-to-bottom alongside the thread. Drafting nodes are not
        // posted, so they stay out of the rail until they settle.
        model.addAttribute("branchIndex", branchIndex.fromTree(tree, personaViews))
        // "Waiting on the room" only when nothing has posted, nothing is drafting, AND no summon is
        // routing — i.e. the room was never summoned (no personas to route to). With the create-time
        // summon a fresh thread normally shows the summoning poller (then the drafts), so this empty state
        // is reserved for threads with no room to route to.
        model.addAttribute("waitingOnRoom", all.none { it.state == GenerationState.POSTED } && drafting.isEmpty() && !summoning)
        model.addAttribute("personas", personaViews)
        // Right-rail forum-wide feeds — the same boxes (same side) the home page carries, via RailFeeds.
        model.addAttribute("activeThreads", railFeeds.activeThreads())
        model.addAttribute("recentComments", railFeeds.recentComments())
        model.addAttribute("starredComments", railFeeds.starredComments())
        // Right-rail Shortcut box — the configured default query; hides itself when the integration is off.
        model.addAttribute("shortcut", shortcut.boxStories())
        return "thread"
    }

    /** Every reply id in the rendered tree (all depths) — so surfaced in-flight drafts aren't double-rendered. */
    private fun collectIds(tree: List<ReplyView>): Set<String> {
        val ids = mutableSetOf<String>()
        fun walk(node: ReplyView) {
            ids += node.id
            node.children.forEach(::walk)
        }
        tree.forEach(::walk)
        return ids
    }
}
