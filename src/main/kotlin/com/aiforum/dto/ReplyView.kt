package com.aiforum.dto

/**
 * View-model the controllers return and the JTE templates render (the frozen contract). The template
 * emits stable data-* hooks from these fields (see the jte-spring-kotlin skill).
 */
data class ReplyView(
    val id: String,
    val authorId: String,
    // Raw markdown source — kept for non-display uses (e.g. the "in reply to" quote) and assertions.
    val body: String,
    val state: GenerationState,
    val failureCategory: FailureCategory?,
    val reason: String?,
    val retryable: Boolean,
    val retryAfterSeconds: Long?,
    val voteCount: Int,
    val depth: Int,
    val depthBudget: Int = 0,
    // Owner's star bookmark — drives the filled-star control on the node and the star marker in the rail.
    val starred: Boolean = false,
    // When set, the model leaked chain-of-thought into this reply; the body is already cleaned. Drives a
    // "reasoning leak" badge + the data-reasoning-leak hook on the node. Null => clean.
    val reasoningLeak: ReasoningLeak? = null,
    // True once the owner has edited this body (§7) — drives the subtle "(edited)" marker and the
    // data-edited hook. Trailing default so positional constructions stay valid.
    val edited: Boolean = false,
    // Content-revision state (V14). [revisionIndex] is 1-based and [revisionCount] the total, so the node
    // can render a "2/3" switcher (shown only when revisionCount > 1). [regeneratable] is true on a POSTED
    // persona reply — the only nodes that offer the Regenerate control. Defaults keep a node at 1-of-1.
    val revisionIndex: Int = 1,
    val revisionCount: Int = 1,
    val regeneratable: Boolean = false,
    // The comment this reply answers, for the "in reply to" anchor. Null for top-level replies (they
    // answer the post, which has no comment node). Populated on the full thread-page render.
    val parent: ParentRef? = null,
    // Comments this reply QUOTES (the forward direction of the quote graph, §3 of comment-quotes.md):
    // each is a "↗ author" anchor to the source comment. Empty for a reply with no quotes (the common
    // case). Populated by ReplyTreeAssembler; trailing default so bare toReplyView() calls carry none.
    val quotes: List<QuoteRef> = emptyList(),
    // Comments that QUOTE this reply (the backward direction): one entry per distinct quoted passage
    // (per-exact-span coalescing), each carrying its quoters. Drives the SSR "quoted by" fallback + the
    // client-side inline marks/cone. Empty for a reply nobody has quoted. Populated by the assembler.
    val quotedBy: List<QuoteBacklink> = emptyList(),
    val children: List<ReplyView> = emptyList(),
    // [body] rendered from GitHub-flavoured markdown to trusted HTML (see MarkdownRenderer); the template
    // emits this via $unsafe{}. Trailing default so positional constructions stay valid. Empty for
    // bodiless nodes (validation errors), which never display a body.
    val bodyHtml: String = "",
    // Owner-attached images rendered as a gallery under the body. Trailing default so positional
    // constructions stay valid; populated on the full thread render (and the just-posted note node).
    val attachments: List<AttachmentView> = emptyList(),
    // What THIS generation cost, carried from settle time out to the ambient tick's post-settle hook so
    // the run row can be priced (issue #15). NO TEMPLATE RENDERS IT: it is a settle-time carrier on the
    // view the hook already receives, not a display field — a per-reply price tag on a node is exactly
    // the member-attached magnitude the V24→V28 guardrail refuses. Null => the provider reported no
    // cost, which stays UNKNOWN and never becomes a claimed $0. Final trailing default so every
    // positional ReplyView construction (and copy) in the app compiles untouched.
    val costUsd: Double? = null,
)
