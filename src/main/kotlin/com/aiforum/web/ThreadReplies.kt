package com.aiforum.web

import com.aiforum.dto.GenerationState
import com.aiforum.dto.ReplyView
import com.aiforum.repo.CommentRepository
import com.aiforum.service.GenerationService
import org.springframework.stereotype.Component

/**
 * One thread's renderable replies: the persisted tree plus the drafts that have no row yet. Every
 * surface that renders a thread's replies — the full page and the room poll — reads through here, so
 * the read ORDER below is stated once instead of being re-derived (and re-broken) per controller.
 */
@Component
class ThreadReplies(
    private val generation: GenerationService,
    private val comments: CommentRepository,
    private val replyTree: ReplyTreeAssembler,
) {

    /**
     * [tree] is the persisted nesting; [drafting] the in-flight nodes not (yet) in it; [anyPosted] says
     * whether the thread holds a POSTED row at all — the three things a caller needs without re-reading.
     */
    data class Assembled(
        val tree: List<ReplyView>,
        val drafting: List<ReplyView>,
        val anyPosted: Boolean,
    ) {
        /** What a reply list renders: the tree, then the drafts hanging off nothing persisted yet. */
        val all: List<ReplyView> get() = tree + drafting

        /** True when the thread has nothing to show — no persisted node and nothing in flight. */
        fun isEmpty(): Boolean = tree.isEmpty() && drafting.isEmpty()
    }

    /**
     * IN-FLIGHT FIRST, THEN THE DB — and the order is the whole point, not tidiness.
     *
     * These are two reads at two instants, and a settling node crosses between them: the worker
     * persists the row and then evicts the registry entry. Read the DB first and a node that persists
     * AND is evicted in the gap between the two reads appears in NEITHER — missing from the page
     * altogether, neither drafting nor posted, as though the persona never spoke. Read the registry
     * first and the same node appears in BOTH, which the dedupe below handles: the settled row wins and
     * the stale draft view is dropped.
     *
     * This is the ambient fan-out flake (how-we-work/context.md): a fast persona's node vanished from a
     * room the poller then judged quiescent. It failed roughly one run in four, only under a full suite,
     * and survived a 4x settle-deadline raise — because no deadline can wait for a node that is not
     * going to appear. A UI reader saw the same thing: a reply that generated fine, silently absent
     * until the next page load.
     */
    fun read(threadId: String): Assembled {
        val inFlight = generation.inFlightViews(threadId)
        val rows = comments.threadComments(threadId)
        val tree = replyTree.assemble(rows)
        val rendered = collectIds(tree)
        return Assembled(
            tree = tree,
            drafting = inFlight.filter { it.id !in rendered },
            anyPosted = rows.any { it.state == GenerationState.POSTED },
        )
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
