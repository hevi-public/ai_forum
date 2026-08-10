package com.aiforum.acceptance.support

/**
 * Tiny HTML probe for the stable data-* semantic hooks the JTE templates emit (see the
 * jte-spring-kotlin skill). We deliberately assert on data-* attributes, never CSS classes, so the
 * scenarios survive a visual redesign and can later be re-pointed at a real DOM driver.
 */
object Html {

    /** The value of [attr] on the element whose data-reply-id == [replyId], or null. */
    fun replyAttr(html: String, replyId: String, attr: String): String? {
        // find the <article ... data-reply-id="ID" ...> tag and read [attr] within it
        val tag = Regex("<[^>]*data-reply-id=\"${Regex.escape(replyId)}\"[^>]*>")
            .find(html)?.value ?: return null
        return Regex("$attr=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
    }

    /** The value of [attr] on the thread row whose data-thread-title == [title], or null. */
    fun threadRowAttr(html: String, title: String, attr: String): String? {
        val tag = Regex("<[^>]*data-thread-title=\"${Regex.escape(title)}\"[^>]*>").find(html)?.value ?: return null
        return Regex("${Regex.escape(attr)}=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
    }

    /** The value of [attr] on the member row whose data-persona-name == [name], or null. */
    fun memberRowAttr(html: String, name: String, attr: String): String? {
        val tag = Regex("<[^>]*data-persona-name=\"${Regex.escape(name)}\"[^>]*>").find(html)?.value ?: return null
        return Regex("${Regex.escape(attr)}=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
    }

    /** The text of the persona-profile dial <li data-dial="[key]">…</li> (e.g. "Verbosity (…): 8/10"),
     *  or null if that dial isn't rendered at all — e.g. before [key] is added to Dials.KEYS. */
    fun dialText(html: String, key: String): String? {
        val m = Regex("<li[^>]*data-dial=\"${Regex.escape(key)}\"[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        return m.groupValues[1].replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /** True if any element carries data-[name]="[value]". */
    fun hasAttr(html: String, name: String, value: String): Boolean =
        Regex("$name=\"${Regex.escape(value)}\"").containsMatchIn(html)

    /**
     * The value of [attr] on the MOST RECENT ambient-run row (admin_ambient.kte lists newest-first, so
     * the first `<li data-ambient-run="…">` in document order is the latest tick) — scoped to that one
     * row so an older run's detail can never satisfy an assertion meant for the newest tick. Null if no
     * ambient-run row exists yet, or if [attr] isn't rendered on it at all (e.g. `data-detail` before S5
     * wires the hook — the honest RED case article_source.feature pins).
     */
    fun latestAmbientRunAttr(html: String, attr: String): String? {
        val tag = Regex("<li\\b[^>]*data-ambient-run=\"[^\"]*\"[^>]*>").find(html)?.value ?: return null
        return Regex("${Regex.escape(attr)}=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
    }

    /**
     * The WHOLE `<li>…</li>` block of the most recent ambient-run row (admin_ambient.kte lists
     * newest-first, so the first `<li data-ambient-run="…">` in document order is the latest tick), or
     * null when no ambient-run row exists yet. Mirrors [latestStanceChangeRow]/[liBlock]: a row is read
     * as a self-contained unit so a probe meant to pin the ROW's own rendered cost can't be satisfied by
     * the usage strip's prose living elsewhere on the same page — both legitimately render the same
     * dollar figure after a single tick (a page-wide `Html.contains` measurably passed with the row's
     * own cost span deleted; this is the fix).
     */
    fun latestAmbientRunRow(html: String): String? = liBlock(html, "data-ambient-run=\"[^\"]*\"")

    /**
     * The WHOLE `<li>…</li>` block of the most recent stance-change row (S4a; admin_stances.kte lists
     * newest-first, so the first `<li data-stance-change="…">` in document order is the latest change),
     * or null when the history is empty. Returns the block rather than a single attribute because an
     * audit row is read as a unit — old text, new text, the cited exchange and the revert control all
     * have to be asserted against the SAME change, and a page-wide probe would happily satisfy an
     * assertion about the newest change with an older row's field.
     */
    fun latestStanceChangeRow(html: String): String? {
        val open = Regex("<li\\b[^>]*data-stance-change=\"[^\"]*\"[^>]*>").find(html) ?: return null
        val close = html.indexOf("</li>", open.range.last + 1)
        if (close < 0) return null
        return html.substring(open.range.first, close + "</li>".length)
    }

    /** Every distinct data-reply-id in document order — one for a summon, several for a fan-out. */
    fun allReplyIds(html: String): List<String> =
        Regex("data-reply-id=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.distinct().toList()

    fun contains(html: String, needle: String): Boolean = html.contains(needle, ignoreCase = true)

    /**
     * Just the reply-node markup of a fragment: from the first `<article … data-reply-id=…>` up to the
     * first out-of-band block (`hx-swap-oob`), which in a reply-list response is the rail's branch index.
     *
     * A page-wide [contains] over such a response is a probe that can stop failing: the branch index
     * renders a SNIPPET of each posted body, so "the fragment carries this reply" is satisfied by the rail
     * echoing it even if the reply list itself regressed to drafts-only. Scope the probe here instead.
     */
    fun replyNodes(html: String): String {
        val first = Regex("<article\\b[^>]*data-reply-id=").find(html)?.range?.first ?: return ""
        // Two terminators, so the same probe scopes either shape: a fragment response ends the list at its
        // out-of-band block (the rail's branch index), a FULL PAGE ends it at the rail <aside>. Without the
        // second, this widens to end-of-document on a page and re-admits the rail snippets it exists to
        // exclude.
        val end = listOfNotNull(
            Regex("<[^>]*hx-swap-oob=").find(html)?.range?.first,
            Regex("<aside\\b").find(html)?.range?.first,
        ).filter { it > first }.minOrNull() ?: html.length
        return html.substring(first, end)
    }

    /** Count of elements carrying data-[name]="[value]". */
    fun countAttr(html: String, name: String, value: String): Int =
        Regex("$name=\"${Regex.escape(value)}\"").findAll(html).count()

    /** Every value carried by [name]="…" in document order — e.g. the reply id on each rail entry. */
    fun attrValues(html: String, name: String): List<String> =
        Regex("${Regex.escape(name)}=\"([^\"]*)\"").findAll(html).map { it.groupValues[1] }.toList()

    /**
     * The text of the in-reply-to anchor belonging to the reply with data-reply-id=[childId], or null.
     * The anchor (data-in-reply-to="<parent id>") is the first one rendered inside the child's article
     * (before its body and before any nested children), so the first match after the child's opening
     * tag is the child's own anchor.
     */
    /** The visible text of the branch-index entry (data-branch-index-entry="[replyId]"), or null. */
    fun branchEntryText(html: String, replyId: String): String? {
        val a = Regex("<a\\b[^>]*data-branch-index-entry=\"${Regex.escape(replyId)}\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        return a.groupValues[1].replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /** The value of [attr] on the branch-index entry anchor (data-branch-index-entry="[replyId]"), or
     *  null. Reads e.g. data-starred, the rail's authoritative star marker the star feature asserts on. */
    fun branchEntryAttr(html: String, replyId: String, attr: String): String? {
        val tag = Regex("<a\\b[^>]*data-branch-index-entry=\"${Regex.escape(replyId)}\"[^>]*>").find(html)?.value ?: return null
        return Regex("${Regex.escape(attr)}=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
    }

    fun inReplyToText(html: String, childId: String): String? {
        val open = Regex("<article\\b[^>]*data-reply-id=\"${Regex.escape(childId)}\"[^>]*>").find(html) ?: return null
        val anchor = Regex("<a\\b[^>]*data-in-reply-to=\"[^\"]*\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            .find(html, open.range.last + 1) ?: return null
        return anchor.groupValues[1].replace(Regex("<[^>]*>"), "").trim()
    }

    /**
     * The substring of reply [replyId]'s OWN content — from its opening <article> tag up to its first
     * nested child article (or its matching </article> when it has no children). The reply's head, the
     * in-reply-to / forward-quotes strips, body and action bar live here; nested children do not. Null if
     * [replyId] isn't present. Lets the quote-graph probes read a reply's own anchors without catching a
     * child's or a sibling's.
     */
    private fun ownContent(html: String, replyId: String): String? {
        val open = Regex("<article\\b[^>]*data-reply-id=\"${Regex.escape(replyId)}\"[^>]*>").find(html) ?: return null
        val token = Regex("<article\\b|</article>")
        var i = open.range.last + 1
        var depth = 1
        var firstChildStart = -1
        var closeStart = html.length
        while (true) {
            val m = token.find(html, i) ?: break
            if (m.value == "</article>") {
                depth--
                if (depth == 0) { closeStart = m.range.first; break }
            } else {
                if (depth == 1 && firstChildStart < 0) firstChildStart = m.range.first
                depth++
            }
            i = m.range.last + 1
        }
        val end = if (firstChildStart in 0 until closeStart) firstChildStart else closeStart
        return html.substring(open.range.last + 1, end)
    }

    /** The target ids of every forward quote anchor (data-quote-source) in reply [srcId]'s own quotes
     *  strip, in document order. Empty if [srcId] quotes nothing (or isn't present). */
    fun quoteSources(html: String, srcId: String): List<String> {
        val span = ownContent(html, srcId) ?: return emptyList()
        return Regex("data-quote-source=\"([^\"]*)\"").findAll(span).map { it.groupValues[1] }.toList()
    }

    /** The visible text of [srcId]'s forward quote anchor pointing at [targetId] (data-quote-source=
     *  "[targetId]"), or null if [srcId] doesn't quote [targetId]. Mirrors [inReplyToText]. */
    fun quoteRefText(html: String, srcId: String, targetId: String): String? {
        val span = ownContent(html, srcId) ?: return null
        val anchor = Regex("<a\\b[^>]*data-quote-source=\"${Regex.escape(targetId)}\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            .find(span) ?: return null
        return anchor.groupValues[1].replace(Regex("<[^>]*>"), "").trim()
    }

    /** The "quoted by" count on [targetId]'s backward-backlink block (data-quoted-by-count), or 0 if the
     *  comment carries no backlinks. Read from the comment's own content (its SSR .reply__quoted-by). */
    fun quotedByCount(html: String, targetId: String): Int {
        val span = ownContent(html, targetId) ?: return 0
        return Regex("data-quoted-by-count=\"([0-9]+)\"").find(span)?.groupValues?.get(1)?.toInt() ?: 0
    }

    /** The src-comment ids of every quoter listed in [targetId]'s backlink block (data-backlink-src). */
    fun backlinkQuoters(html: String, targetId: String): List<String> {
        val span = ownContent(html, targetId) ?: return emptyList()
        return Regex("data-backlink-src=\"([^\"]*)\"").findAll(span).map { it.groupValues[1] }.toList()
    }

    /** The distinct quoted passages in [targetId]'s backlink block (data-backlink-text) — one per
     *  coalesced group, so the size is the number of distinct passages quoted. */
    fun backlinkPassages(html: String, targetId: String): List<String> {
        val span = ownContent(html, targetId) ?: return emptyList()
        return Regex("data-backlink-text=\"([^\"]*)\"").findAll(span).map { it.groupValues[1] }.toList()
    }

    /** The data-reply-id of the first <article> whose data-author == [author], or null. */
    fun replyIdWithAuthor(html: String, author: String): String? {
        val tag = Regex("<article\\b[^>]*data-author=\"${Regex.escape(author)}\"[^>]*>").find(html)?.value ?: return null
        return Regex("data-reply-id=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
    }

    /**
     * True if the <article> with data-reply-id=[childId] is nested INSIDE the one with
     * data-reply-id=[parentId] — genuine DOM containment, not merely both present on the page (which is
     * what the flat-rendering bug produced). Articles nest, so we balance <article>/</article> from the
     * parent's opening tag to find its matching close and look for the child only within that span.
     */
    fun isNestedUnder(html: String, childId: String, parentId: String): Boolean {
        val open = Regex("<article\\b[^>]*data-reply-id=\"${Regex.escape(parentId)}\"[^>]*>").find(html) ?: return false
        val token = Regex("<article\\b|</article>")
        var i = open.range.last + 1   // start scanning after the parent's opening tag (parent = depth 1)
        var depth = 1
        while (true) {
            val m = token.find(html, i) ?: return false
            if (m.value == "</article>") {
                depth--
                if (depth == 0) {     // parent's matching close — child must lie in the span before it
                    return html.substring(open.range.last + 1, m.range.first).contains("data-reply-id=\"$childId\"")
                }
            } else {
                depth++
            }
            i = m.range.last + 1
        }
    }

    /** The data-scope value on the composer element whose data-target-id == [targetId], or null. */
    fun composerScope(html: String, targetId: String): String? = composerAttr(html, targetId, "data-scope")

    /** The value of an arbitrary [attr] on the composer element whose data-target-id == [targetId], or
     *  null. Reads from the single opening tag carrying data-target-id, so it sees the hx-* wiring and
     *  data-* hooks that live together on the composer <form>. */
    fun composerAttr(html: String, targetId: String, attr: String): String? {
        val tag = Regex("<[^>]*data-target-id=\"${Regex.escape(targetId)}\"[^>]*>")
            .find(html)?.value ?: return null
        return Regex("${Regex.escape(attr)}=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
    }

    /**
     * The newest interest-change row as a self-contained block, sliced from its opening `<li>` to the
     * FIRST `</li>` (the log renders newest-first, so the first match in document order is the latest
     * drift), or null when the history is empty.
     *
     * A block rather than a single attribute for the S4a reason: an audit row is read as a unit — what
     * was set down, what was taken up, the cited words and the revert control all have to be asserted
     * against the SAME change, and a page-wide probe would happily satisfy a claim about the newest
     * drift with an older row's field.
     */
    fun latestInterestChangeRow(html: String): String? = liBlock(html, "data-interest-change")

    /**
     * The room-map row for one interest phrase, sliced the same way. The map's subject is a PHRASE and
     * the members holding it, so the row is keyed on the phrase rather than on a member.
     */
    fun roomMapRow(html: String, interest: String): String? =
        liBlock(html, "data-room-topic=\"${Regex.escape(interest)}\"")

    /**
     * The newest memory-change row (plan_docs/persona-memory.md §2.12; /admin/memory lists
     * newest-first, so the first `<li data-memory-change="…">` in document order is the latest), or
     * null when the history is empty. A whole block for the same reason as its two siblings above: an
     * audit row is read as a unit — the snapshot body, the cited evidence and the reverted marker all
     * have to be asserted against the SAME change, and a page-wide probe would happily satisfy a claim
     * about the newest change with an older row's field.
     */
    fun latestMemoryChangeRow(html: String): String? = liBlock(html, "data-memory-change")

    /**
     * One rail box as a self-contained block — its opening `<section … data-rail-box="[name]" …>`
     * through the FIRST `</section>` — or null when the page renders no such box.
     *
     * A block rather than a page-wide probe because a rail's CONTENT is not unique to its rail: the
     * thread page renders the same comment prose in the tree that the starred box quotes in the
     * margin, so an un-scoped `contains` there reads the tree. Measured, not assumed — with the
     * thread page's starred box rendered empty, the page-wide probe still passed and only the scoped
     * one reddened. Scoping is what makes "this box shows it" mean the box.
     *
     * Slicing to the first close is sound because every rail fragment is a single `<section>` with
     * nothing sectioned inside it — verified across all eight (`activeThreads`, `branchIndex`,
     * `contextLegend`, `forumNav`, `members`, `recentComments`, `shortcut`, `starredComments`), and
     * over the one sub-template a box includes (`storyCard`). A rail box that ever nests a
     * `<section>` would truncate here, which is why the fragments are the place to keep flat.
     */
    fun railBox(html: String, name: String): String? {
        val open = Regex("<section\\b[^>]*data-rail-box=\"${Regex.escape(name)}\"[^>]*>").find(html) ?: return null
        val close = html.indexOf("</section>", open.range.last + 1)
        if (close < 0) return null
        return html.substring(open.range.first, close + "</section>".length)
    }

    /**
     * One activity card as a self-contained block — its opening `<li … data-feed-event="[eventId]" …>`
     * through the FIRST `</li>` — or null when the stream renders no such card
     * (plan_docs/ambient-slice-6.md §2.5, I7).
     *
     * A block rather than a page-wide probe for the reason every sibling above has one: a stream card's
     * author, excerpt, unread marker and link-into-the-thread all have to be asserted against the SAME
     * card, and a page-wide probe would happily satisfy a claim about one card with a neighbour's field.
     * Slicing to the first `</li>` is sound only because the design forbids nesting anything list-shaped
     * inside a card — which is exactly why I7 pins the card's internals flat.
     */
    fun feedEventRow(html: String, eventId: String): String? =
        liBlock(html, "data-feed-event=\"${Regex.escape(eventId)}\"")

    /** Every activity card's event id, in document order — the stream's shape, which is what an
     *  ordering assertion reads and what "the stream shows nothing saying …" has to iterate. */
    fun feedEventIds(html: String): List<String> =
        Regex("data-feed-event=\"([^\"]*)\"").findAll(html).map { it.groupValues[1] }.toList()

    /**
     * The visible text of the `<span [attr]="[value]">…</span>` — tags stripped, whitespace collapsed —
     * or null when no such span is rendered. The [dialText] idiom, generalised to any hook.
     *
     * This is how the thread card's preview is read, and it is the whole reason the excerpt's prose is
     * CHILD TEXT rather than an attribute value (I2/D6): gg.jte does not escape `>` in attribute
     * context, so a prose excerpt in a hook would truncate the tag and make every hook after it
     * unreadable. Slicing to the first `</span>` is why I7 also forbids a nested `<span>` inside a
     * hooked one — the excerpt's byline is a `<b>`.
     */
    fun spanText(html: String, attr: String, value: String): String? {
        val m = Regex(
            "<span\\b[^>]*${Regex.escape(attr)}=\"${Regex.escape(value)}\"[^>]*>(.*?)</span>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(html) ?: return null
        return textOf(m.groupValues[1])
    }

    /** The visible text of a fragment: tags stripped, entities left alone, whitespace collapsed. */
    fun textOf(html: String): String =
        html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

    /** The `<li>` block whose opening tag matches [hookPattern], opening tag through first `</li>`. */
    private fun liBlock(html: String, hookPattern: String): String? {
        val open = Regex("<li\\b[^>]*$hookPattern[^>]*>").find(html) ?: return null
        val close = html.indexOf("</li>", open.range.last + 1)
        if (close < 0) return null
        return html.substring(open.range.first, close + "</li>".length)
    }

    /**
     * The value of [attr] on the /admin/ambient usage-aggregates strip (issue #16: rolling 24h/7d spend
     * + tool-call counts), or null when [attr] isn't rendered at all — the same absent-means-unknown
     * idiom [latestAmbientRunAttr] reads off a run row: an all-NULL cost window renders NO
     * data-cost-24h/7d attribute (never a claimed "0.0000"), while the tool-call counts always render
     * (0 is a real, known count — never "unknown"). Null also before the strip exists at all, the
     * honest RED case ahead of the section landing in admin_ambient.kte.
     */
    fun usageAggregatesAttr(html: String, attr: String): String? {
        val tag = Regex("<section\\b[^>]*data-usage-aggregates[^>]*>").find(html)?.value ?: return null
        return Regex("${Regex.escape(attr)}=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
    }

    /**
     * Every `<li data-tool-call=\"…\" data-tool-run=\"[runId]\" …>…</li>` block on /admin/tools
     * belonging to generation [runId], in document order (issue #16). A generation can make several
     * calls, one row each; `GenerationToolCallRepository.recent` renders newest-id-first, so a caller
     * wanting seq order should reverse this list. Empty when the generation made no calls, or when the
     * view renders no such rows yet — the honest RED case ahead of the route/template landing.
     */
    fun toolCallRowsForRun(html: String, runId: String): List<String> =
        Regex("<li\\b[^>]*data-tool-run=\"${Regex.escape(runId)}\"[^>]*>").findAll(html).mapNotNull { open ->
            val close = html.indexOf("</li>", open.range.last + 1)
            if (close < 0) null else html.substring(open.range.first, close + "</li>".length)
        }.toList()
}
