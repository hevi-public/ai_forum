package com.aiforum.service

import com.aiforum.domain.Attachment
import com.aiforum.domain.Comment
import com.aiforum.domain.budget.DepthBudget
import com.aiforum.domain.context.ContextAssembler
import com.aiforum.agui.AguiEventSink
import com.aiforum.domain.lifecycle.GenerationStateMachine
import com.aiforum.dto.FailureCategory
import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReplyView
import com.aiforum.dto.ScopeMode
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.llm.PromptContext
import com.aiforum.persona.InterestProse
import com.aiforum.persona.MemoryProse
import com.aiforum.persona.MemoryRecall
import com.aiforum.persona.StanceProse
import com.aiforum.repo.AttachmentRepository
import com.aiforum.repo.CommentRepository
import com.aiforum.repo.PersonaInterestRepository
import com.aiforum.repo.PersonaMemoryRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.RelationStanceRepository
import com.aiforum.repo.ThreadRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

/**
 * Orchestrates generation through the single LlmClient seam (Tier 2 running real Tier-0/1 below it,
 * see the bdd-tiered-testing skill). Sequential fan-out for M1: each persona generates in turn, and
 * one persona failing does not abort the others (partial-roomful).
 *
 * The summon path is **async** (§4): [startGeneration] returns DRAFTING nodes immediately and settles
 * them on a worker thread held by [InFlightGenerations], so a later `POST /replies/{id}/cancel` can trip
 * the in-flight token. [generate] is the synchronous variant (used by the Tier-2 test); [autoGrow] and
 * [retry] stay synchronous (M1 cancel targets in-flight summon drafts only).
 */
@Service
class GenerationService(
    private val llm: LlmClient,
    private val comments: CommentRepository,
    private val personas: PersonaRepository,
    // Default keeps the 3-arg Tier-2 construction compiling; Spring injects the real @Component bean
    // (a single primary constructor means Spring passes all args, so the default is never used in app).
    private val inFlight: InFlightGenerations = InFlightGenerations(),
    // The "Anyone" dispatcher (defaulted for the same reason; Spring injects the @Component). Shares the
    // single LlmClient seam, so routing is just another call through the same boundary the tests fake.
    private val router: PersonaRouter = PersonaRouter(llm),
    // The opening post lives on the thread (thread.body), not as a comment, so the context-assembly path
    // needs to read it to seed the room. Nullable-defaulted so the 3/4-arg Tier-2 constructions (which
    // don't exercise OP context) keep compiling; Spring injects the real bean.
    private val threads: ThreadRepository? = null,
    // Image attachments fold their captions into context (caption-only path). Nullable-defaulted for the
    // same reason as [threads]; when null no captions are injected (the existing text-only behaviour).
    private val attachments: AttachmentRepository? = null,
    // The qualitative relation graph, injected as prose into each persona's system prompt at generation
    // time (see [assembleContext]). Nullable-defaulted like [threads]/[attachments] so the positional
    // 3/4-arg Tier-2 constructions keep compiling; when null nothing is appended and the prompt is
    // byte-identical to the pre-relations behaviour. Deliberately read HERE rather than baked into
    // `persona.system_prompt` at authoring time: a stance is edited far more often than a persona is
    // composed, and baking it in would make every stance edit a re-compose (an LLM call) plus leave every
    // stored prompt stale the moment the evolution pass rewrites an edge.
    private val stances: RelationStanceRepository? = null,
    // S4b (plan_docs/ambient-slice-4b.md D7): what each member is currently INTO, appended as prose in the
    // same seam the stances are ([withPersonaContext]). Nullable-defaulted for the identical reason — every
    // construction that doesn't wire it yields `persona.systemPrompt` byte-identical, so no existing test
    // moves. Injected here rather than baked into `system_prompt` for a sharper reason than the stances':
    // an interest is a TOPIC that the weekly drift pass rewrites, and a topic frozen into a stored prompt
    // is the "frozen roster naming members who aren't even in the thread" failure ComposerPrompts.kt:56-61
    // was written against. Injection also means a drift never buys a recompose, and the seven seeded
    // members — whose stored prompts came from the trait-less template — get their interests with no owner
    // click. Read INSIDE this call, which runs under [GenPlan.contextOf] at settle time, so an interest
    // written between two replies of one fan-out reaches the second; a column on the captured Persona row
    // would not.
    private val interests: PersonaInterestRepository? = null,
    // Persona memory (plan_docs/persona-memory.md §2.9): the member's own resurfaced records,
    // appended as the FOURTH block in the same seam as the two above ([withPersonaContext]).
    // Nullable-defaulted for the identical reason — every construction that doesn't wire it yields
    // a byte-identical prompt, so no existing test moves. Retrieval is MemoryRecall's binary
    // whole-word overlap against the scoped context, records-only, one associative hop, capped —
    // never an LLM call, never a persisted magnitude. Read INSIDE this call, which runs under
    // [GenPlan.contextOf] at settle time, so recall is live per reply: a record written or deleted
    // between two replies of one fan-out is honored by the second (never a plan-mint snapshot).
    private val memories: PersonaMemoryRepository? = null,
) {
    private val timeout = Duration.ofSeconds(120)
    private val log = LoggerFactory.getLogger(GenerationService::class.java)

    companion object {
        // Sentinel the composer's default "Anyone" option submits instead of a persona id: it hands the
        // pick to the AI dispatcher ([PersonaRouter]) rather than naming who replies. An explicit
        // persona selection never carries it, so the routing call only happens on the "Anyone" path.
        // Public so the auto-summon-on-create path (ThreadController) names the same "Anyone" sentinel.
        const val AUTO_PERSONA = "auto"

        // Runaway backstop for autoGrow; real growth always drains in ≤ DepthBudget.DEFAULT_GRANT rounds.
        private const val GROWTH_ROUND_CAP = 100
        // The author id under which the owner's own composer messages are persisted (matches the seeded
        // "owner" nodes the firewall/context scenarios use).
        private const val OWNER_AUTHOR = "owner"
    }

    /** A resolved unit of work: one persona's reply, with its id minted up front so it is cancellable. */
    private data class GenPlan(
        val id: String,
        val threadId: String,
        val parentId: String?,
        val persona: PersonaRepository.Persona,
        val depth: Int,
        val budget: Int,
        // Context is assembled LAZILY at settle time, not when the plan is minted. Sequential fan-out
        // persists each persona's reply before the next settles, so re-reading the thread here lets a
        // later persona in the round see the earlier ones' replies (see [roundContext]) — the room reads
        // as a conversation rather than N blind takes of the same opening snapshot.
        val contextOf: () -> PromptContext,
    )

    /**
     * Async summon/fan-out (§4): register a DRAFTING node + token per persona, hand the room to a single
     * worker that settles each persona IN ORDER (preserving sequential fan-out and the deque-scripted
     * behaviours), and return the DRAFTING views immediately so the browser can render them and offer a
     * Cancel control. Each node settles to exactly one DB row; until then it lives only in [inFlight].
     */
    fun startGeneration(
        threadId: String,
        parentId: String?,
        personaIds: List<String>,
        text: String,
        scope: ScopeMode = ScopeMode.WHOLE_THREAD,
        includeSiblings: Boolean = false,
        postAsOwner: Boolean = false,
        routingScope: ScopeMode = ScopeMode.WHOLE_THREAD,
    ): List<ReplyView> {
        // The composer authors the owner's message: persist it as the owner's node first, then summon
        // BENEATH it, so the personas reply to it and it flows into their context (§4/§5).
        val owner = ownerComment(threadId, parentId, text, postAsOwner)
        val anchorId = owner?.id ?: parentId
        // Resolve AFTER persisting the owner's message so the dispatcher routes on the new topic too.
        val resolvedIds = resolvePersonas(threadId, anchorId, routingScope, personaIds, text)
        val started = planGeneration(threadId, anchorId, resolvedIds, scope, includeSiblings).map { plan ->
            val draft = draftView(plan)
            val token = inFlight.register(plan.id, plan.threadId, draft)
            Triple(plan, token, draft)
        }
        inFlight.submit {
            started.forEach { (plan, token, _) ->
                try {
                    settleOne(plan, token)
                } finally {
                    inFlight.markDone(plan.id)
                }
            }
        }
        // Return the owner's freshly-posted node with the DRAFTING persona node(s) NESTED inside it, so
        // the htmx swap appends a subtree that mirrors the tree: each reply sits under the owner message
        // it answers, not as a flat sibling. A bare summon (no owner node) returns the drafts flat.
        val drafts = started.map { it.third }
        return owner?.let { listOf(it.toReplyView(children = drafts)) } ?: drafts
    }

    /**
     * Fully-async summon (§4): unlike [startGeneration], the dispatcher's routing call ALSO runs on the
     * worker, so the request thread never blocks on the LLM and the create page can render/redirect at
     * once. The thread is marked "summoning" until routing finishes and the per-persona drafts are
     * registered, then each settles. Used by the create path (ThreadController): the room is summoned
     * "Whole Topic + Anyone" and the thread page polls /threads/{id}/room, swapping the drafts in as they
     * appear. Returns nothing — there are no synchronously-known drafts to hand back.
     *
     * Without this, [resolvePersonas] (the "Anyone" dispatcher's LLM call) ran on the request thread, so
     * a slow model left the new-thread page blank until it answered — the one place the otherwise-async
     * summon path wasn't actually async.
     */
    fun summonAsync(
        threadId: String,
        parentId: String?,
        personaIds: List<String>,
        text: String,
        scope: ScopeMode = ScopeMode.WHOLE_THREAD,
        includeSiblings: Boolean = false,
        postAsOwner: Boolean = false,
        routingScope: ScopeMode = ScopeMode.WHOLE_THREAD,
        // Optional starting depth budget (plan_docs/ambient-slice-2.md §2): the ambient tick's comment
        // action passes DepthBudget.AMBIENT_GRANT so its top-level comment is born fuelled for a bounded
        // mini-discussion instead of the childBudget(0)=0 a fresh top-level summon gets. Null (every existing
        // call site, via the Kotlin default) keeps the inherited-from-parent behaviour unchanged.
        initialBudget: Int? = null,
        // Optional post-settle hook, run on the SAME worker after every persona in this summon has settled
        // (§2: "the ambient comment's settle triggers the same growth round the owner-grant paths get"),
        // handed the ids of the nodes this summon just settled. The ambient comment action hands a
        // BRANCH-SCOPED autoGrow in here — keyed on those ids — so its AMBIENT_GRANT is consumed without
        // owner attention while owner-granted branches elsewhere in the thread stay untouched; every other
        // call site passes nothing and behaves exactly as before.
        onSettled: ((settledIds: List<String>) -> Unit)? = null,
    ) {
        inFlight.beginSummon(threadId)
        inFlight.submit {
            val started = try {
                val owner = ownerComment(threadId, parentId, text, postAsOwner)
                val anchorId = owner?.id ?: parentId
                val resolvedIds = resolvePersonas(threadId, anchorId, routingScope, personaIds, text)
                planGeneration(threadId, anchorId, resolvedIds, scope, includeSiblings, initialBudget).map { plan ->
                    plan to inFlight.register(plan.id, plan.threadId, draftView(plan))
                }
            } catch (_: Throwable) {
                // Routing/planning failed before any draft was registered — nothing to settle; the page's
                // poller drops once `summoning` clears in the finally below.
                emptyList()
            } finally {
                // Routing phase over: the drafts (if any) are now visible to the room poller, so it stops
                // showing "summoning" and swaps them in.
                inFlight.endSummon(threadId)
            }
            started.forEach { (plan, token) ->
                try {
                    settleOne(plan, token)
                } finally {
                    inFlight.markDone(plan.id)
                }
            }
            // Everything in this summon has settled (or nothing was planned) — run the post-settle hook on
            // this same worker, handed the settled node ids so the caller can scope follow-up work to
            // exactly the nodes this summon produced. Isolated in its own catch: a growth failure must
            // neither propagate (killing the worker task) nor retro-mark the already-settled nodes failed —
            // the nodes are persisted and the follow-up discussion is best-effort (the owner's /auto-grow
            // button still exists).
            onSettled?.let { hook ->
                try {
                    hook(started.map { (plan, _) -> plan.id })
                } catch (e: Exception) {
                    log.warn("post-settle hook for thread {} failed", threadId, e)
                }
            }
        }
    }

    /** Trip the in-flight token for [replyId] and wait (bounded) for the worker to settle it (§4). */
    fun cancel(replyId: String) = inFlight.cancel(replyId)

    /**
     * Subscribe to a drafting node's AG-UI event stream (the SSE endpoint). Returns null when the node
     * isn't in flight (unknown or already settled) — the caller then falls back to the poll, since the
     * settled row exists. Delegates to [InFlightGenerations]; the service owns the in-flight registry.
     */
    fun subscribeEvents(replyId: String, listener: com.aiforum.agui.AguiEventListener) =
        inFlight.subscribe(replyId, listener)

    /** The transient DRAFTING view while a node is still in flight — the poll endpoint's DB-first fallback. */
    fun inFlightView(replyId: String): ReplyView? = inFlight.view(replyId)

    /**
     * The DRAFTING nodes still in flight for [threadId] — surfaced on the thread page so an async summon's
     * replies appear (and self-poll to settle) on a plain page load, before any row exists. Used by the
     * auto-summon-on-create path, where the room is summoned and the browser then lands on the thread via
     * a PRG redirect with no fragment to carry the drafts.
     */
    fun inFlightViews(threadId: String): List<ReplyView> = inFlight.viewsFor(threadId)

    /** True while a create-time summon's dispatcher routing is still in flight (no drafts registered yet). */
    fun isSummoning(threadId: String): Boolean = inFlight.isSummoning(threadId)

    /**
     * Synchronous summon/fan-out: settle every persona inline and return the settled views. Kept for the
     * Tier-2 service test, which pins the couldn't-save path on the same persist logic [startGeneration]
     * uses.
     */
    fun generate(
        threadId: String,
        parentId: String?,
        personaIds: List<String>,
        text: String,
        scope: ScopeMode = ScopeMode.WHOLE_THREAD,
        includeSiblings: Boolean = false,
        postAsOwner: Boolean = false,
        routingScope: ScopeMode = ScopeMode.WHOLE_THREAD,
    ): List<ReplyView> {
        val owner = ownerComment(threadId, parentId, text, postAsOwner)
        val anchorId = owner?.id ?: parentId
        val resolvedIds = resolvePersonas(threadId, anchorId, routingScope, personaIds, text)
        val replies = planGeneration(threadId, anchorId, resolvedIds, scope, includeSiblings)
            .map { settleOne(it, CancellationToken()) }
        return owner?.let { listOf(it.toReplyView(children = replies)) } ?: replies
    }

    /**
     * Bounded autonomous growth (§4): repeatedly extend every POSTED leaf that still has depth budget
     * by one auto-reply, until the frontier runs dry — the concrete "run-K-turns-then-stop". Because
     * budget is carried per node, a branch grows ~3–4 levels past its last owner comment / `/more`
     * grant and then stalls, and a re-grant on one branch never wakes a quiet sibling. Returns only the
     * nodes created this run. The iteration cap is a runaway backstop: budget never exceeds the grant,
     * so a healthy tree drains in ≤ DEFAULT_GRANT rounds.
     *
     * [withinSubtreeOf] narrows the frontier to one comment's subtree (itself + descendants). The ambient
     * comment's settle-triggered growth passes its own id here (plan_docs/ambient-slice-2.md §2), so it
     * consumes ONLY its own AMBIENT_GRANT — an owner-granted branch elsewhere in the thread that the owner
     * deliberately left un-grown must not have its fuel spent at an ambient-triggered moment. Null (the
     * owner's explicit /auto-grow) keeps the thread-wide semantics unchanged.
     */
    fun autoGrow(threadId: String, withinSubtreeOf: String? = null): List<ReplyView> {
        val pool = personas.findAll()
        if (pool.isEmpty()) return emptyList()
        val created = mutableListOf<ReplyView>()
        var round = 0
        while (round++ < GROWTH_ROUND_CAP) {
            val frontier = comments.growableLeaves(threadId, withinSubtreeOf)
            if (frontier.isEmpty()) break
            // Snapshot the thread once per round; freshly-granted /more directives are already in it, so
            // the directive flows into the context handed to the model (§7).
            val context = comments.threadComments(threadId)
            frontier.forEach { leaf ->
                val persona = pool[created.size % pool.size]
                val plan = GenPlan(
                    id = UUID.randomUUID().toString(),
                    threadId = threadId,
                    parentId = leaf.id,
                    persona = persona,
                    depth = leaf.depth + 1,
                    budget = DepthBudget.childBudget(leaf.depthBudget),
                    // autoGrow keeps its own per-round snapshot ([context]); each leaf gets a distinct
                    // persona/target, so it doesn't share the summon round's settle-time re-read.
                    contextOf = { assembleContext(threadId, persona, withOpeningPost(threadId, context), targetId = leaf.id) },
                )
                created += settleOne(plan, CancellationToken())
            }
        }
        return created
    }

    fun retry(replyId: String): ReplyView {
        val existing = comments.findById(replyId) ?: error("no reply $replyId")
        val persona = personas.find(existing.authorId) ?: error("unknown persona ${existing.authorId}")
        val ctx = assembleContext(existing.threadId, persona, withOpeningPost(existing.threadId, comments.threadComments(existing.threadId)), targetId = existing.parentId)
        val updated = try {
            val resp = llm.generate(LlmRequest(ctx, PersonaRef(persona.id, persona.name, persona.model), timeout), CancellationToken())
            resp.reasoningLeak?.let { log.warn("reasoning leak ({}) on retry of reply {} by persona {}", it, replyId, persona.id) }
            // Overwrite the flag with this regeneration's verdict (it may now be clean → null).
            existing.copy(body = resp.text, state = GenerationState.POSTED, failureCategory = null, reason = null, retryAfterSeconds = null, reasoningLeak = resp.reasoningLeak)
        } catch (e: Throwable) {
            val o = GenerationStateMachine.classify(e)
            existing.copy(body = "", state = o.state, failureCategory = o.failureCategory, reason = o.reason, retryAfterSeconds = o.retryAfterSeconds, reasoningLeak = null)
        }
        comments.update(updated)
        return updated.toReplyView()
    }

    /**
     * Regenerate a POSTED persona reply (§7), KEEPING every prior take. Unlike [retry] (which overwrites a
     * dead-end draft in place), this appends a content revision and points the node at it, so the owner can
     * step back through earlier versions via the ‹ › switcher. The FIRST regenerate also stores the body
     * being replaced as revision 0, so the original is never lost; thereafter the table already holds it.
     *
     * Children are untouched — the node's body changes, its subtree stays. A transient generation failure
     * leaves the current take in place (no revision appended) and returns the node unchanged, so a flaky
     * model can never destroy a good reply. Returns the re-rendered node (its body now the new revision).
     *
     * @Transactional: once the model has answered, the seed-idx0 + addRevision + selectRevision writes are
     * one atomic unit — a crash or SQLITE_BUSY between them must not leave the revision history half-built
     * (e.g. a new revision appended but never selected). The on-failure early return happens BEFORE any
     * write, so the LLM call holds no write lock (SQLite defers the lock to the first statement). Called
     * through the Spring proxy from the controller, so the boundary is active.
     */
    @Transactional
    fun regenerate(replyId: String): ReplyView {
        val existing = comments.findById(replyId) ?: error("no reply $replyId")
        require(existing.state == GenerationState.POSTED) { "only a posted reply can be regenerated" }
        val persona = personas.find(existing.authorId)
            ?: error("reply $replyId is not a persona reply (author ${existing.authorId})")
        // Same caption-aware context path as [retry], so a regenerated take sees the thread's image
        // captions exactly as the original generation did.
        val ctx = assembleContext(existing.threadId, persona, withOpeningPost(existing.threadId, comments.threadComments(existing.threadId)), targetId = existing.parentId)
        val resp = try {
            llm.generate(LlmRequest(ctx, PersonaRef(persona.id, persona.name, persona.model), timeout), CancellationToken())
        } catch (e: Throwable) {
            // Regeneration is non-destructive: on failure we keep what's there and re-render it unchanged.
            log.warn("regenerate of reply {} by persona {} failed; keeping current take", replyId, persona.id, e)
            return existing.toReplyView()
        }
        resp.reasoningLeak?.let { log.warn("reasoning leak ({}) on regenerate of reply {} by persona {}", it, replyId, persona.id) }
        // Append the new take. Seed revision 0 with the body we're replacing the first time, so the
        // original survives; the appended index is the prior count (which already includes idx 0 after
        // the first regenerate). Then select it so `comment.body` becomes this take for the rest of the app.
        val count = comments.revisionCount(replyId)
        if (count == 0) comments.addRevision(replyId, 0, existing.body, existing.reasoningLeak, editedAt = existing.updatedAt)
        val newIdx = if (count == 0) 1 else count
        comments.addRevision(replyId, newIdx, resp.text, resp.reasoningLeak)
        comments.selectRevision(replyId, newIdx)
        return comments.findById(replyId)!!.toReplyView()
    }

    /**
     * Persist the owner's composed message as their own POSTED node (§4/§5) and return its id, so the
     * summon that follows parents under it. This is what makes the owner's words both APPEAR in the tree
     * and reach every persona's context — without it the room only ever sees a blank transcript and
     * emits a generic opener. The node GRANTS a fresh depth budget so the branch can auto-grow past it
     * (mirrors a seeded owner comment / `/more`). Returns null when there is nothing to author (a bare
     * summon, or an empty message), leaving the summon parented exactly as before.
     */
    private fun ownerComment(threadId: String, parentId: String?, text: String, postAsOwner: Boolean): Comment? {
        if (!postAsOwner || text.isBlank()) return null
        val parent = parentId?.let { comments.findById(it) }
        val owner = Comment(
            id = UUID.randomUUID().toString(),
            threadId = threadId,
            parentId = parentId,
            authorId = OWNER_AUTHOR,
            body = text.trim(),
            state = GenerationState.POSTED,
            failureCategory = null,
            depth = parent?.let { it.depth + 1 } ?: 0,
            depthBudget = DepthBudget.granted(),
        )
        comments.insert(owner)
        return owner
    }

    /**
     * Turn the requested selection into concrete persona ids. A normal selection passes straight through;
     * the composer's default "Anyone" option submits [AUTO_PERSONA], which hands the choice to the AI
     * dispatcher so it picks who weighs in based on the topic. An empty selection is NOT auto — the
     * controller already rejects that as a validation error — so the routing call is confined to the
     * deliberate "Anyone" path and never fires on the explicit-persona scenarios.
     *
     * [routingScope] is the owner's own "looking at" selector (default whole topic): BRANCH_ONLY narrows
     * the dispatcher to the ancestor path of [anchorId] (the branch being replied to) so the pick reflects
     * that sub-discussion, not the whole tree. It is independent of the generation [scope] the chosen
     * persona then reads.
     *
     * On the "Anyone" path the owner can still steer WHO replies without naming them in the dropdown by
     * @mentioning personas in [text] (the composer's "type @ to summon" affordance): an explicit mention
     * is a deliberate summon, so it takes precedence over the dispatcher. Breadth follows who's tagged:
     * a named chip / @mention resolves to exactly that set; the "Anyone" dispatcher picks the room.
     */
    private fun resolvePersonas(
        threadId: String,
        anchorId: String?,
        routingScope: ScopeMode,
        requested: List<String>,
        text: String,
    ): List<String> {
        // An explicit dropdown/chip selection passes straight through (mentions don't override a named
        // pick — naming someone IS the summon); only the deliberate "Anyone" sentinel routes.
        if (requested.none { it == AUTO_PERSONA }) return requested
        val roster = personas.findAll()
        if (roster.isEmpty()) return emptyList()
        // @mentions summon deterministically — they pre-empt the dispatcher when present.
        MentionParser.parse(text, roster).takeIf { it.isNotEmpty() }?.let { return it }
        val context = if (routingScope == ScopeMode.BRANCH_ONLY && anchorId != null) {
            comments.ancestorPath(anchorId)
        } else {
            comments.threadComments(threadId)
        }
        return router.pick(roster, withOpeningPost(threadId, context), routingScope).map { it.id }
    }

    /** Resolve personas into cancellable plans; each carries a settle-time context supplier (§5). */
    private fun planGeneration(
        threadId: String,
        parentId: String?,
        personaIds: List<String>,
        scope: ScopeMode,
        includeSiblings: Boolean,
        // An explicit starting budget (plan_docs/ambient-slice-2.md §2) overrides the inherited-from-parent
        // one — the ambient comment carries DepthBudget.AMBIENT_GRANT so it can auto-grow a couple of levels.
        // Null (every non-ambient call site) keeps the childBudget(parent) behaviour exactly as before.
        initialBudget: Int? = null,
    ): List<GenPlan> {
        val parent = parentId?.let { comments.findById(it) }
        val baseDepth = parent?.let { it.depth + 1 } ?: 0
        // A reply continues its parent branch's depth budget (§4); a top-level reply starts unfuelled —
        // UNLESS an initialBudget is handed in (the ambient comment's non-renewing grant).
        val baseBudget = initialBudget ?: DepthBudget.childBudget(parent?.depthBudget ?: 0)
        // Mint every reply's id up front so each persona's context can fold in the OTHERS in this round
        // (the ones already settled by the time it generates) — but only this round's replies, never the
        // target's pre-existing children.
        val roundIds = personaIds.map { UUID.randomUUID().toString() }
        return personaIds.mapIndexed { i, personaId ->
            val persona = personas.find(personaId) ?: error("unknown persona $personaId")
            GenPlan(
                id = roundIds[i],
                threadId = threadId,
                parentId = parentId,
                persona = persona,
                depth = baseDepth,
                budget = baseBudget,
                // Re-read at settle time so a later persona in the round sees the earlier ones' replies.
                contextOf = {
                    val live = roundContext(threadId, parentId, parent, scope, includeSiblings, roundIds)
                    assembleContext(threadId, persona, withOpeningPost(threadId, live), targetId = parentId)
                },
            )
        }
    }

    /**
     * The live context for one persona at settle time (§5). Re-read per persona so sequential fan-out
     * becomes a conversation: a later persona in the round sees the earlier ones' replies, which are
     * POSTED rows by the time it settles. The scope differentiator stays — branch-only = root→parent
     * ancestor path (recursive CTE), whole-thread = the full tree, with the reply target's siblings folded
     * in for branch-only only when the owner opts in. On TOP of that, this round's OWN already-posted
     * replies ([roundIds]) are injected in EVERY scope — branch-only's ancestor path would otherwise
     * exclude these same-round siblings. We fold in only the round's minted ids (not every child of the
     * target), so a pre-existing child of the reply target stays out of a branch-only view. Non-POSTED
     * nodes (failed/cancelled drafts, including a sibling that just failed earlier in this round) are
     * dropped so an empty marker never enters the transcript.
     */
    private fun roundContext(
        threadId: String,
        parentId: String?,
        parent: Comment?,
        scope: ScopeMode,
        includeSiblings: Boolean,
        roundIds: List<String>,
    ): List<Comment> {
        val base = if (scope == ScopeMode.BRANCH_ONLY && parentId != null) {
            val path = comments.ancestorPath(parentId)
            val withTargetSiblings = if (includeSiblings) path + comments.childrenOf(parent?.parentId) else path
            // The round's earlier replies aren't on the ancestor path; fold them in so even a narrowed
            // view reads as a live exchange. Whole-thread's full tree already contains them.
            withTargetSiblings + roundIds.mapNotNull { comments.findById(it) }
        } else {
            comments.threadComments(threadId)
        }
        return base.filter { it.state == GenerationState.POSTED }.distinctBy { it.id }
    }

    /**
     * The opening post is the topic itself — its **title AND body** — and lives on the thread (the
     * `thread` row), rendered as the post node (id == threadId), NOT as a persisted comment. Inject it at
     * the HEAD of every persona's (and the dispatcher's) context so the room engages with the actual
     * question instead of a blank transcript (the "dropped on the way in" bug). Both fields go in: the
     * title is the topic, the body its detail, joined as one post (blank-line separated) — so a title-only
     * quick-create still seeds the topic rather than handing the room nothing. Null only when [threads]
     * isn't wired (the Tier-2 construction) or the thread has neither. The synthetic node carries the
     * post's canonical id (threadId), depth 0, no parent — exactly how the page models the OP.
     * Owner-authored, like any composer message already in context: the firewall is about VOTES, not the
     * "owner" label.
     */
    private fun openingPost(threadId: String): Comment? =
        threads?.find(threadId)?.let { thread ->
            listOf(thread.title, thread.body).filter { it.isNotBlank() }.joinToString("\n\n")
                .takeIf { it.isNotBlank() }
                // Attribute the OP node to its actual author: a persona for an ambient-opened thread (V20
                // thread.author_id), the owner otherwise — so the dispatcher and summoned personas see the
                // article OP as the persona's, not the owner's. The firewall is about VOTES, not the label.
                ?.let { Comment(threadId, threadId, null, thread.authorId ?: OWNER_AUTHOR, it, GenerationState.POSTED, null, 0) }
        }

    /**
     * Assemble context with image captions folded in (caption-only path) and the generating persona's
     * relation stances appended to its system prompt. Reads the attachments for the context comments plus
     * the thread's own (the OP synthetic node carries id == threadId), and hands the map to the firewall
     * boundary [ContextAssembler]. When [attachments] isn't wired (Tier-2 constructions), the map is
     * empty and this is exactly the old text-only assemble.
     *
     * The persona-context blocks (stances, then interests, then memories) are appended HERE, one step BEFORE
     * [ContextAssembler.assemble], on purpose: the firewall's single job is keeping owner VOTE signal out
     * of the transcript, and it stays a pure function that receives an already-final system prompt string.
     * Injecting inside it would put prompt authoring into the boundary whose Tier-0 test exists to pin
     * exclusion, so a relations or interests change could turn a firewall test red for reasons that have
     * nothing to do with votes.
     */
    private fun assembleContext(
        threadId: String,
        persona: PersonaRepository.Persona,
        contextComments: List<Comment>,
        targetId: String?,
    ) = ContextAssembler.assemble(
        // The thread title is threaded through because §2.7 matches memories against exactly what
        // the persona is about to read — the scoped bodies plus the title, which is the topic
        // signal and not otherwise in withPersonaContext's scope (plan_docs/persona-memory.md §8
        // item 7). One parameter is cheaper than pretending the title was already there.
        withPersonaContext(persona, contextComments, threads?.find(threadId)?.title.orEmpty()),
        contextComments,
        targetId,
        attachmentMap(threadId, contextComments),
    )

    /**
     * [persona]'s system prompt plus the two blocks that make it *this* member on *this* turn: its stances
     * toward the personas ACTUALLY PRESENT in this scoped context, and what it is currently into.
     *
     * **Stances.** Its outgoing edges only (a persona's prompt carries its own views, never the room's
     * views of it), filtered to the distinct author ids of [contextComments]. Present-filtering is doing
     * two things at once. It keeps the prompt small: the seeded graph is 42 edges, and pasting the whole
     * roster's opinions into every generation is bulk noise about people who never spoke. And it makes
     * scope narrowing free — a BRANCH_ONLY summon carries fewer authors, so the stance set narrows with
     * it, with no scope-awareness in this code at all. The owner's author id can never match an edge
     * (edges exist only between personas), so an owner-heavy context simply yields fewer stances rather
     * than needing a special case.
     *
     * **Interests** (S4b, D7) are NOT filtered by anything: a stance is *about* somebody, so it is only
     * worth prompt space when that somebody is in the room, whereas an interest is about the member
     * itself and colours every reply it writes. The list arrives `ORDER BY interest`
     * ([PersonaInterestRepository.of]) so the prompt text is byte-stable across runs — an unrelated
     * insertion must never silently rewrite a prompt — and as bare phrases, never rows: [InterestProse]
     * has no parameter to pass `source` through, so a model can never learn which of its interests the
     * owner pinned and therefore has no lever on its own drift.
     *
     * Both blocks read their repository HERE, per reply, rather than being captured when [GenPlan] was
     * minted: the pass that rewrites either one runs on its own cadence, and a prompt assembled from a
     * snapshot would serve a member's old character for the rest of the fan-out.
     *
     * **Memories** (persona-memory §2.9) are the member's OWN records resurfaced against exactly what
     * it is about to read: [MemoryRecall.select] over the scoped context bodies plus [threadTitle] —
     * binary whole-word overlap, records only (the root never enters), one associative hop, capped at
     * five. BRANCH_ONLY composes for free: a narrower scoped context is a narrower match text. The
     * bodies-only handoff to [MemoryProse.block] is the guardrail — no ids, no provenance, no parent
     * structure can reach the prompt, because there is no parameter to pass them through.
     *
     * When all three blocks render empty (each renderer returns null for an empty list — including
     * every construction where [stances], [interests] or [memories] isn't wired) the result is
     * `persona.systemPrompt` unchanged, byte for byte: relations, interests and memories must be
     * invisible where there are none, not a dangling header. Order is stances, then interests, then
     * memories — fixed by this list, not by which repository happens to be wired.
     */
    private fun withPersonaContext(
        persona: PersonaRepository.Persona,
        contextComments: List<Comment>,
        threadTitle: String = "",
    ): String {
        val present = contextComments.mapTo(mutableSetOf()) { it.authorId }
        val named = stances?.from(persona.id).orEmpty()
            .filter { it.toPersona in present }
            // The edge stores ids; the prompt must name people the way the transcript does, so the model
            // can attach the attitude to a byline it can see. Falling back to the id keeps a stance
            // toward a since-renamed/unreadable persona readable rather than dropping it silently.
            .map { StanceProse.NamedStance(personas.find(it.toPersona)?.name ?: it.toPersona, it.stance) }
        // The scoped context as one match text: what the member is about to read, title included.
        // Recall is unrankable by construction — the select is binary, the tie-break a clock — and
        // it reads the repository HERE, at settle time, never off a plan-mint snapshot.
        val recalled = memories?.let { repo ->
            MemoryRecall.select(
                repo.recordsOf(persona.id),
                (contextComments.map { it.body } + threadTitle).joinToString(" "),
            )
        }.orEmpty()
        val blocks = listOfNotNull(
            StanceProse.block(persona.name, named),
            InterestProse.block(persona.name, interests?.phrasesOf(persona.id).orEmpty()),
            MemoryProse.block(recalled.map { it.body }),
        )
        return (listOf(persona.systemPrompt) + blocks).joinToString("\n\n")
    }

    /** comment id -> its attachments, including the thread's own keyed under threadId (the OP node id). */
    private fun attachmentMap(threadId: String, contextComments: List<Comment>): Map<String, List<Attachment>> {
        val repo = attachments ?: return emptyMap()
        // The OP synthetic node's id IS the threadId; its images live in thread-scoped rows, fetched
        // separately. Every other node is a real comment, batch-read by id.
        val byComment = repo.forComments(contextComments.map { it.id }.filter { it != threadId })
        val opAttachments = repo.forThread(threadId)
        return if (opAttachments.isEmpty()) byComment else byComment + (threadId to opAttachments)
    }

    /** Prepend the opening post to [comments] (deduped) so it heads the context handed to the model. */
    private fun withOpeningPost(threadId: String, comments: List<Comment>): List<Comment> =
        openingPost(threadId)?.takeIf { op -> comments.none { it.id == op.id } }
            ?.let { listOf(it) + comments } ?: comments

    /** The transient view shown while a node drafts — never persisted (no DRAFTING DB row). */
    private fun draftView(plan: GenPlan): ReplyView =
        Comment(plan.id, plan.threadId, plan.parentId, plan.persona.id, "", GenerationState.DRAFTING, null, plan.depth, depthBudget = plan.budget)
            .toReplyView()

    /** Run one persona's reply against the seam with [token], classify any failure, and persist it. */
    private fun settleOne(plan: GenPlan, token: CancellationToken): ReplyView {
        // Stream AG-UI events to the node's in-flight channel as the reply generates (a no-op for the
        // synchronous generate/autoGrow paths, which register no holder). runId == the node id so the SSE
        // endpoint /replies/{id}/stream and the channel route to the right drafting node.
        val sink = AguiEventSink { inFlight.publish(plan.id, it) }
        val comment = try {
            val resp = llm.generate(LlmRequest(plan.contextOf(), PersonaRef(plan.persona.id, plan.persona.name, plan.persona.model), timeout, runId = plan.id), token, sink)
            resp.reasoningLeak?.let { log.warn("reasoning leak ({}) in reply {} by persona {}", it, plan.id, plan.persona.id) }
            Comment(plan.id, plan.threadId, plan.parentId, plan.persona.id, resp.text, GenerationState.POSTED, null, plan.depth, depthBudget = plan.budget, reasoningLeak = resp.reasoningLeak)
        } catch (e: Throwable) {
            val o = GenerationStateMachine.classify(e)
            Comment(plan.id, plan.threadId, plan.parentId, plan.persona.id, "", o.state, o.failureCategory, plan.depth, o.reason, o.retryAfterSeconds, depthBudget = plan.budget)
        }
        return persist(comment)
    }

    /**
     * Persist the node. If the write fails (UX state E, §4) the generation itself already succeeded, so
     * the drafted body must NOT be lost: we keep it, surface COULDNT_SAVE, and persist a failure marker
     * (the write fault is a one-shot transient blip) so the owner can retry from a real row.
     */
    private fun persist(comment: Comment): ReplyView = try {
        comments.insert(comment)
        comment.toReplyView()
    } catch (e: Throwable) {
        val marker = comment.copy(
            state = GenerationState.FAILED,
            failureCategory = FailureCategory.COULDNT_SAVE,
            reason = "couldn't save — draft kept",
            retryAfterSeconds = null,
        )
        comments.insert(marker)
        marker.toReplyView()
    }
}
