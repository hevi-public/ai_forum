package com.aiforum.web

import com.aiforum.agui.AguiEvent
import com.aiforum.agui.AguiEventListener
import com.aiforum.agui.AguiWire
import com.aiforum.domain.Comment
import com.aiforum.domain.budget.DepthBudget
import com.aiforum.dto.AttachmentView
import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import com.aiforum.dto.QuoteSpec
import com.aiforum.dto.ReplyView
import com.aiforum.dto.ScopeMode
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.QuoteRepository
import com.aiforum.service.AttachmentService
import com.aiforum.service.GenerationService
import jakarta.servlet.http.HttpServletResponse
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Request body for POST /threads/{id}/generate. */
data class GenerateRequest(
    val personaIds: List<String> = emptyList(),
    val text: String = "",
    val scope: String? = null,
    // Scope the "Anyone" dispatcher reads when deciding WHO replies (its own selector in the composer):
    // WHOLE_THREAD = the whole topic, BRANCH_ONLY = just the branch being replied to. Independent of
    // [scope] (which scopes what the chosen persona then READS). Null/unset => WHOLE_THREAD.
    val routingScope: String? = null,
    // non-null with a default — works because the Jackson 3 Kotlin module applies Kotlin defaults to
    // omitted fields (without that module this would 400 on "Cannot map null into type boolean").
    val includeSiblings: Boolean = false,
    val triggerMode: String? = null,
    val parentId: String? = null,
    // True when this carries the owner's own message (the composer path): persist `text` as the owner's
    // node before fanning out, so it appears in the tree AND seeds every summoned persona's context. A
    // bare API summon leaves it false — the personas just weigh in on the existing discussion.
    val postAsOwner: Boolean = false,
    // Pending quotes captured by the composer: a JSON array of {targetId, text} (see comment-quotes.md).
    // One field carries them over both the browser form path and the JSON API, avoiding fragile nested
    // list binding. Recorded as quote edges (this reply -> each target) once the owner node exists; a
    // malformed / blank payload records nothing (never a 400). Null/absent for a reply with no quotes.
    val quotesJson: String? = null,
)

/**
 * Generation endpoints. Returns the rendered reply-node fragment(s) so acceptance steps can assert on
 * the data-* hooks. Real depth-budget autonomy and roomful concurrency are deferred to the team behind
 * this pinned contract.
 */
@Controller
class GenerationController(
    private val generation: GenerationService,
    private val personas: PersonaRepository,
    private val comments: CommentRepository,
    private val attachments: AttachmentService,
    private val branchIndex: BranchIndexBuilder,
    // Regenerate/revision-nav re-render the node WITH its nested replies intact (a persona reply can have
    // children), so they go through the subtree assembler like the edit path — not the leaf renderNode.
    private val replyTree: ReplyTreeAssembler,
    // Quote edges from the composer's quotesJson are recorded against the freshly-posted owner node.
    private val quotes: QuoteRepository,
    // The room poll renders the same union the thread page does (persisted tree + in-flight drafts),
    // through the same seam — see ThreadReplies for why the read order is load-bearing.
    private val threadReplies: ThreadReplies,
    private val objectMapper: ObjectMapper,
) {

    // Two handlers, one body type each: the browser composer posts application/x-www-form-urlencoded
    // (htmx default — bound by model attribute), while the acceptance suite and any API client post
    // JSON (@RequestBody). Both delegate to one [respond] so the behaviour can't drift between them.
    @PostMapping("/threads/{threadId}/generate", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun generateJson(@PathVariable threadId: String, @RequestBody req: GenerateRequest, model: Model): String =
        respond(threadId, req, model)

    @PostMapping("/threads/{threadId}/generate", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun generateForm(@PathVariable threadId: String, req: GenerateRequest, model: Model): String =
        respond(threadId, req, model)

    /**
     * The browser composer posts multipart when it carries an image (enctype set in composer.kte). With
     * no file selected this is exactly the urlencoded path. With an image we persist the owner's message
     * as their node first (so the image has an owner to hang off — the firewall keeps images owner-only),
     * attach the image to it, THEN summon beneath it (postAsOwner=false, the node already exists). The
     * personas pick up the image via its caption once the owner describes it; raw bytes never reach them.
     */
    @PostMapping("/threads/{threadId}/generate", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun generateMultipart(
        @PathVariable threadId: String,
        req: GenerateRequest,
        @RequestParam(name = "images", required = false) images: List<MultipartFile>?,
        model: Model,
    ): String {
        val uploads = images?.toUploads().orEmpty()
        // No image → behave exactly like the urlencoded composer submit (owner message + summon).
        if (uploads.isEmpty()) return respond(threadId, req, model)
        val ownerNode = postOwnerNode(threadId, req.parentId, req.text)
        recordQuotes(threadId, ownerNode.id, parseQuotes(req.quotesJson))
        val attViews = attachments.attachToComment(ownerNode.id, uploads).map(AttachmentView::of)
        // Summon under the freshly-posted owner node. An empty selection (the owner deselected Anyone)
        // just posts the image as a note — nothing to summon — rather than erroring.
        val drafts = if (req.personaIds.isEmpty()) emptyList() else generation.startGeneration(
            threadId, ownerNode.id, req.personaIds, "",
            parseScope(req.scope), req.includeSiblings, postAsOwner = false, parseScope(req.routingScope),
        )
        model.addAttribute("replies", listOf(ownerNode.toReplyView(children = drafts, attachments = attViews)))
        model.addAttribute("threadId", threadId)
        model.addAttribute("personas", personaViews())
        // The owner's node posts immediately — refresh the rail's branch index as an out-of-band swap.
        model.addAttribute("branchIndex", branchIndex.forThread(threadId))
        return "fragments/replyList"
    }

    private fun respond(threadId: String, req: GenerateRequest, model: Model): String {
        // Validation BEFORE spending an LLM call (§4): reject empty question / no persona at the
        // controller tier; no node is created and the LlmClient is never touched. The error fragment
        // carries only the system node — no composer under it (threadId/personas left unset).
        validationError(req)?.let {
            model.addAttribute("replies", listOf(it))
            return "fragments/replyList"
        }
        val scope = req.scope?.let { runCatching { ScopeMode.valueOf(it) }.getOrNull() } ?: ScopeMode.WHOLE_THREAD
        val routingScope = req.routingScope?.let { runCatching { ScopeMode.valueOf(it) }.getOrNull() } ?: ScopeMode.WHOLE_THREAD
        // Async (§4): start drafting and return the DRAFTING node(s) at once. Each node self-polls
        // GET /replies/{id} and carries a Cancel control; it settles to POSTED|FAILED|CANCELLED later.
        val replies = generation.startGeneration(threadId, req.parentId, req.personaIds, req.text, scope, req.includeSiblings, req.postAsOwner, routingScope)
        model.addAttribute("replies", replies)
        // Record any quotes the composer carried against the owner's freshly-posted node (its root view).
        recordQuotes(threadId, ownerNodeIdFrom(replies), parseQuotes(req.quotesJson))
        // Hand the fragment what its composers need so freshly-rendered nodes can be replied to.
        model.addAttribute("threadId", threadId)
        model.addAttribute("personas", personaViews())
        // The owner's own message (postAsOwner) posts immediately, so refresh the rail's branch index as
        // an out-of-band swap alongside the appended nodes. (The summoned personas are still DRAFTING, so
        // they enter the index later, when each settles via the poll endpoint.)
        model.addAttribute("branchIndex", branchIndex.forThread(threadId))
        return "fragments/replyList"
    }

    private fun personaViews(): List<PersonaView> =
        personas.findAll().map { PersonaView(it.id, it.name, it.descriptor, it.slug, colorIndex = it.colorIndex) }

    private fun validationError(req: GenerateRequest): ReplyView? {
        val reason = when {
            req.text.isBlank() -> "Please add a question."
            req.personaIds.isEmpty() -> "Select at least one persona."
            else -> return null
        }
        return ReplyView(
            id = UUID.randomUUID().toString(),
            authorId = "system",
            body = "",
            state = GenerationState.FAILED,
            failureCategory = FailureCategory.VALIDATION,
            reason = reason,
            retryable = false,
            retryAfterSeconds = null,
            voteCount = 0,
            depth = 0,
        )
    }

    // Re-generate a FAILED/CANCELLED draft, then render the single node via the enriched subtree path (a
    // lone <article> root for the Retry button's outerHTML swap). Going through the assembler means a node
    // that retries to POSTED re-renders WITH its inline composer AND its Regenerate control + revision
    // state; a re-failed node stays composer-less via the template's POSTED guard and just offers Retry.
    @PostMapping("/replies/{id}/retry")
    fun retry(@PathVariable id: String, model: Model): String {
        generation.retry(id)
        return renderSubtree(model, id)
    }

    /**
     * Regenerate a POSTED persona reply (§7), keeping prior versions: the service appends a content
     * revision and shows it. The browser "Yes, regenerate" button outerHTML-swaps the closest <article>,
     * so we re-render the WHOLE subtree (replyTree.subtree) — the new body lands while the nested replies
     * survive the swap (a bare node would drop them). The revision count rises, so the node now shows the
     * ‹ › switcher.
     */
    @PostMapping("/replies/{id}/regenerate")
    fun regenerate(@PathVariable id: String, model: Model): String {
        generation.regenerate(id)
        return renderSubtree(model, id)
    }

    /**
     * Switch a reply to a stored revision [idx] (0-based) — the ‹ › switcher. Pure DB (no LLM): the
     * selected take's body becomes the live body, then the node re-renders with its subtree intact. A
     * no-op for an out-of-range index (the node re-renders unchanged).
     */
    @PostMapping("/replies/{id}/revision/{idx}")
    fun revision(@PathVariable id: String, @PathVariable idx: Int, model: Model): String {
        comments.selectRevision(id, idx)
        return renderSubtree(model, id)
    }

    /**
     * The owner posts a note — an ordinary visible comment that flows into generation context like any
     * owner comment, but does not summon any persona. Mirrors the thread-scoped URL of /generate so the
     * main composer can route here without knowing the branch root node ID up front.
     */
    @PostMapping("/threads/{threadId}/note", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun noteForm(
        @PathVariable threadId: String,
        @RequestParam(required = false) text: String?,
        @RequestParam(required = false) parentId: String?,
        @RequestParam(required = false) quotesJson: String?,
        model: Model,
    ): String {
        if (text.isNullOrBlank()) {
            model.addAttribute("replies", emptyList<ReplyView>())
            return "fragments/replyList"
        }
        val node = postOwnerNode(threadId, parentId, text)
        recordQuotes(threadId, node.id, parseQuotes(quotesJson))
        model.addAttribute("replies", listOf(node.toReplyView()))
        model.addAttribute("threadId", threadId)
        model.addAttribute("personas", personaViews())
        // The note posts immediately — refresh the rail's branch index as an out-of-band swap.
        model.addAttribute("branchIndex", branchIndex.forThread(threadId))
        return "fragments/replyList"
    }

    /**
     * Multipart variant of [noteForm] — the composer in note mode posts here when it carries an image.
     * Posts the owner note (no AI summon) and attaches the image(s). Either the text or an image must be
     * present; an image with no text is a valid image-only note.
     */
    @PostMapping("/threads/{threadId}/note", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun noteMultipart(
        @PathVariable threadId: String,
        @RequestParam(required = false) text: String?,
        @RequestParam(required = false) parentId: String?,
        @RequestParam(required = false) quotesJson: String?,
        @RequestParam(name = "images", required = false) images: List<MultipartFile>?,
        model: Model,
    ): String {
        val uploads = images?.toUploads().orEmpty()
        if (text.isNullOrBlank() && uploads.isEmpty()) {
            model.addAttribute("replies", emptyList<ReplyView>())
            return "fragments/replyList"
        }
        val node = postOwnerNode(threadId, parentId, text.orEmpty())
        recordQuotes(threadId, node.id, parseQuotes(quotesJson))
        val attViews = if (uploads.isEmpty()) emptyList()
        else attachments.attachToComment(node.id, uploads).map(AttachmentView::of)
        model.addAttribute("replies", listOf(node.toReplyView(attachments = attViews)))
        model.addAttribute("threadId", threadId)
        model.addAttribute("personas", personaViews())
        // The note posts immediately — refresh the rail's branch index as an out-of-band swap.
        model.addAttribute("branchIndex", branchIndex.forThread(threadId))
        return "fragments/replyList"
    }

    /** Persist the owner's message as their own POSTED node (the /note + image-bearing composer paths). */
    private fun postOwnerNode(threadId: String, parentId: String?, text: String): Comment {
        val parentDepth = parentId?.let { comments.findById(it)?.depth } ?: 0
        val node = Comment(
            id = UUID.randomUUID().toString(),
            threadId = threadId,
            parentId = parentId,
            authorId = "owner",
            body = text,
            state = GenerationState.POSTED,
            failureCategory = null,
            depth = parentDepth + 1,
            depthBudget = DepthBudget.granted(),
        )
        comments.insert(node)
        return node
    }

    // The owner's own node is the root of the views the /generate paths return
    // (owner.toReplyView(children = drafts)); a bare summon returns drafts flat with no owner node. So
    // the quote source is the first POSTED "owner" root view, or null when there is none.
    private fun ownerNodeIdFrom(replies: List<ReplyView>): String? =
        replies.firstOrNull { it.authorId == "owner" && it.state == GenerationState.POSTED }?.id

    /** Parse the composer's quotesJson into specs; a null / malformed payload yields none (never a 400). */
    private fun parseQuotes(quotesJson: String?): List<QuoteSpec> {
        if (quotesJson.isNullOrBlank()) return emptyList()
        return runCatching {
            objectMapper.readValue(quotesJson, object : TypeReference<List<QuoteSpec>>() {})
        }.getOrDefault(emptyList())
    }

    /**
     * Persist quote edges from the just-posted owner comment [srcCommentId] to each cited comment. Drops
     * entries with a blank target/text, a self-reference, an unknown target, or a target in another
     * thread (defensive — a quote is within a thread), and de-dupes identical (target, text) pairs. A
     * null src (a bare summon posted no owner node) records nothing.
     */
    private fun recordQuotes(threadId: String, srcCommentId: String?, specs: List<QuoteSpec>) {
        if (srcCommentId == null || specs.isEmpty()) return
        val seen = HashSet<Pair<String, String>>()
        for (spec in specs) {
            val targetId = spec.targetId.trim()
            val text = spec.text.trim()
            if (targetId.isBlank() || text.isBlank() || targetId == srcCommentId) continue
            if (!seen.add(targetId to text)) continue
            val target = comments.findById(targetId) ?: continue
            if (target.threadId != threadId) continue
            quotes.insert(threadId, srcCommentId, targetId, text)
        }
    }

    /** Parse a ScopeMode name, defaulting to WHOLE_THREAD for null/unknown (the composer's default). */
    private fun parseScope(raw: String?): ScopeMode =
        raw?.let { runCatching { ScopeMode.valueOf(it) }.getOrNull() } ?: ScopeMode.WHOLE_THREAD

    /**
     * Drive bounded autonomous growth (§4): the room auto-replies down each branch that still has depth
     * budget, then stalls. Returns the freshly-grown nodes as the reply-list fragment.
     */
    @PostMapping("/threads/{threadId}/auto-grow")
    fun autoGrow(@PathVariable threadId: String, model: Model): String {
        model.addAttribute("replies", generation.autoGrow(threadId))
        model.addAttribute("threadId", threadId)
        model.addAttribute("personas", personaViews())
        // Newly-grown nodes are posted — refresh the rail's branch index as an out-of-band swap.
        model.addAttribute("branchIndex", branchIndex.forThread(threadId))
        return "fragments/replyList"
    }

    /**
     * Poll the create-time room summon (§4). The thread page shows a "Summoning the room…" poller while a
     * summon is routing (the dispatcher's "who replies" call, on a worker); it hits this every second.
     *
     * **The ROUTING WINDOW decides, not the emptiness of the thread.** While `isSummoning` holds, the
     * answer is the poller and only the poller — it replaces itself and touches nothing else on the page.
     * Once routing has concluded this answers with what the room produced, and that response is terminal:
     * it retargets the whole reply list, so the poller goes with it and the polling stops.
     *
     * Both halves are bought failures. Reading the registry ALONE (before the fix) lost a room whose
     * drafts all settled before the first poll, because a node leaves the registry the moment it settles —
     * hence [ThreadReplies], which unions registry and DB in the one order that cannot drop a settling
     * node. But then deciding on CONTENT alone lost the room a second way: a note the owner posts
     * mid-routing is a POSTED row, so the union goes non-empty while the room has produced nothing, and a
     * terminal response there swaps the poller away before the drafts ever land. Neither read is wrong;
     * the question each answers is "has the room produced anything", and only `isSummoning` answers
     * "is more still coming".
     */
    @GetMapping("/threads/{threadId}/room")
    fun room(@PathVariable threadId: String, response: HttpServletResponse, model: Model): String {
        // Routing first, and before the reads: a summon that concludes between this check and the reads
        // costs one more poll (the next one carries it), where the reverse order costs the room entirely.
        if (generation.isSummoning(threadId)) {
            model.addAttribute("threadId", threadId)
            model.addAttribute("summoning", true)
            return "fragments/roomPoller"
        }
        val replies = threadReplies.read(threadId)
        if (!replies.isEmpty()) {
            // RETARGET THE WHOLE LIST, don't just replace the poller. This response carries persisted rows,
            // which include anything the owner posted from the composer while the room was summoning — and
            // that node is already in the page's reply list. Swapping this fragment in over the poller alone
            // would leave the browser holding it twice; replacing the list wholesale makes the server render
            // authoritative. Reswap is stated rather than inherited so the swap style can't drift out from
            // under the retarget if the poller's own hx-swap changes.
            response.setHeader(HX_RETARGET, ".reply-list")
            response.setHeader(HX_RESWAP, "outerHTML")
            model.addAttribute("replies", replies.all)
            model.addAttribute("threadId", threadId)
            val personaViews = personaViews()
            model.addAttribute("personas", personaViews)
            // Settled nodes are in the rail's remit (drafting ones aren't), and this response is the one
            // that puts them on the page — so the rail's TOC refreshes with them, out of band. Built from
            // the tree already in hand: `forThread` would re-read the thread and re-assemble it (a second
            // whole-vote-table GROUP BY), and from a THIRD read instant the swapped list wouldn't match.
            model.addAttribute("branchIndex", branchIndex.fromTree(replies.tree, personaViews))
            return "fragments/replyList"
        }
        // Nothing routing and nothing produced: a summon that ended empty (routing failed / empty roster).
        // The poller drops itself so htmx stops.
        model.addAttribute("threadId", threadId)
        model.addAttribute("summoning", false)
        return "fragments/roomPoller"
    }

    /**
     * Poll a single node (§4). The DRAFTING fragment self-polls every second; once the node settles, the
     * returned fragment drops the poll trigger so htmx stops. DB-first: a persisted row is the source of
     * truth, so we only fall back to the transient in-flight view while no row exists yet (this closes
     * the brief window between the settle write and the worker releasing the cancel latch).
     */
    @GetMapping("/replies/{id}")
    fun poll(@PathVariable id: String, model: Model): String {
        // A settled row renders through the enriched subtree path (same as the full page), so a freshly
        // settled persona reply carries its Regenerate control + revision/attachment state at once — the
        // bare view omitted them, so they only appeared after a reload. Fall back to the transient
        // in-flight view (no DB row yet) while the node still drafts.
        if (comments.findById(id) != null) return renderSubtree(model, id)
        generation.inFlightView(id)?.let { return renderNode(model, it, threadId = null) }
        return emptyNode(model)
    }

    /**
     * Stream a drafting node's generation as AG-UI events (Server-Sent Events). The client (stream.js)
     * appends TextDelta text live, shows tool-call status, and on the terminal RUN_FINISHED/RUN_ERROR
     * re-fetches the server-rendered fragment via the poll endpoint. Each event is sent with its AG-UI
     * type as the SSE `event:` name and [AguiWire]-encoded JSON as the data.
     *
     * Additive, not a replacement: if the node isn't in flight (unknown or already settled/evicted), the
     * emitter completes at once and the client falls back to the existing `every 1s` poll, which serves the
     * settled row. So this never has to be reached for correctness — it's purely for liveness.
     */
    @GetMapping("/replies/{id}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @ResponseBody
    fun stream(@PathVariable id: String): SseEmitter {
        val emitter = SseEmitter(STREAM_TIMEOUT_MS)
        val subscription = generation.subscribeEvents(id, object : AguiEventListener {
            override fun onEvent(event: AguiEvent) {
                try {
                    emitter.send(SseEmitter.event().name(AguiWire.type(event)).data(AguiWire.encode(event)))
                } catch (e: Exception) {
                    // Client gone mid-stream — finish the emitter; onCompletion below detaches the listener.
                    runCatching { emitter.completeWithError(e) }
                }
            }

            override fun onComplete() {
                runCatching { emitter.complete() }
            }
        })
        // Unknown/evicted run: nothing to stream, complete so the browser drops to the poll.
        if (subscription == null) {
            emitter.complete()
            return emitter
        }
        emitter.onCompletion { subscription.cancel() }
        emitter.onTimeout { subscription.cancel(); emitter.complete() }
        emitter.onError { subscription.cancel() }
        return emitter
    }

    /**
     * Cancel an in-flight draft (§4): trip the shared token and wait for the worker to settle the node to
     * CANCELLED, then render the now-persisted row. A no-op (renders the current state) if the node is
     * unknown or already settled.
     */
    @PostMapping("/replies/{id}/cancel")
    fun cancel(@PathVariable id: String, model: Model): String {
        generation.cancel(id)
        if (comments.findById(id) == null) return emptyNode(model)
        return renderSubtree(model, id)
    }

    // Single-node fragment for the transient in-flight DRAFTING view only (no DB row yet, so it can't go
    // through the assembler). Persisted nodes render via [renderSubtree], which enriches them. Called with
    // threadId=null, so no composer/rail wiring — a drafting node has nothing to reply to or index.
    private fun renderNode(model: Model, reply: ReplyView, threadId: String?): String {
        model.addAttribute("reply", reply)
        // Personas ALWAYS ride along: the monogram hue resolves through the persona's stored colour slot
        // (AuthorColor), and a poll re-render without the roster fell back to the hashed hue — the
        // drafting avatar visibly changed colour for a second, then changed back on settle.
        model.addAttribute("personas", personaViews())
        if (threadId != null) {
            model.addAttribute("threadId", threadId)
            model.addAttribute("branchIndex", branchIndex.forThread(threadId))
        }
        return "fragments/replyNode"
    }

    // Like [renderNode] but preserves the node's nested replies through the outerHTML swap (regenerate /
    // revision-nav can target a node that has children). Mirrors the edit path: assemble the subtree, then
    // carry a fresh branch index OOB so the rail's snippet follows the now-changed body.
    private fun renderSubtree(model: Model, id: String): String {
        val node = replyTree.subtree(id) ?: return emptyNode(model)
        model.addAttribute("reply", node)
        comments.findById(id)?.threadId?.let { threadId ->
            model.addAttribute("threadId", threadId)
            model.addAttribute("personas", personaViews())
            model.addAttribute("branchIndex", branchIndex.forThread(threadId))
        }
        return "fragments/replyNode"
    }

    private fun emptyNode(model: Model): String {
        model.addAttribute("replies", emptyList<ReplyView>())
        return "fragments/replyList"
    }

    private companion object {
        // Comfortably above the 120s generation timeout so the SSE doesn't lapse mid-generation; on timeout
        // the client still has the poll fallback. The emitter completes earlier on the terminal event.
        const val STREAM_TIMEOUT_MS = 300_000L

        // htmx's per-response swap overrides: where the fragment lands, and how. Named here rather than
        // inlined because the pair is a contract two branches of [room] must agree on — the terminal
        // response sets both, the poller sets neither, and pinning that is what stops a "simplify" edit
        // from hoisting them out of the branch (RoomPollTest).
        const val HX_RETARGET = "HX-Retarget"
        const val HX_RESWAP = "HX-Reswap"
    }
}
