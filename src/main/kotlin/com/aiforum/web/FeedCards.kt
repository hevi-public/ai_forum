package com.aiforum.web

import com.aiforum.dto.FeedExcerpt
import com.aiforum.repo.ActivityEvent
import com.aiforum.repo.FeedThread
import java.time.Instant

/**
 * The projection from a repository row to the card the front page renders
 * (plan_docs/ambient-slice-6.md §2.1).
 *
 * **An `object`, not a `@Component`** — deliberately, and the reason is a testing one: nothing here does
 * IO, so as a plain object it is Tier 0 by the testing skill's own definition, and `FeedCardsTest` can
 * pin the href shape, the byline rule and the corrupt-stamp degrade without a Spring context.
 *
 * Two functions and two card types, never one shared shape (D5): [ThreadRow] and [ActivityRow] are
 * separate, so JTE's typed `@param` makes handing a stream card to the thread-card fragment a *build*
 * failure rather than something a reviewer has to notice.
 */
object FeedCards {

    /**
     * How many stream cards the activity view asks for. Lives here rather than in the controller because
     * the template also needs it: a page holding exactly this many events is a page that may be hiding
     * more, which is what `data-feed-more` discloses (§5, D7 — there is no pagination, so the honest
     * move is to say the list is cut rather than to offer a "load more" that would need a cursor).
     */
    const val STREAM_LIMIT = 50

    /** Long enough for a sentence of preview, short enough that a card stays one line of the index. */
    private const val EXCERPT_LEN = 120

    /**
     * The stream's excerpt runs longer than the index's **because the two views are read differently**:
     * a thread card is one line of an index you scan for the thread you want, while a stream card is the
     * thing itself and is read in place. Three wrapped lines is the budget the CSS clamps to, and 300
     * characters is what fills them at the card's width without the clamp usually having to bite — a cap
     * that routinely truncated mid-second-line would make the clamp, not the text, the thing you notice.
     */
    private const val STREAM_EXCERPT_LEN = 300

    /**
     * One thread's card.
     *
     * [ThreadRow.author] stays the **raw** stored author id, because `data-thread-author` is what
     * `AmbientSteps` and the attribution scenario read and a display mapping there would change what the
     * hook means (D2). Only the *visible* byline goes through [AuthorLabel.display], in the fragment.
     *
     * [ThreadRow.ago] coerces [RelativeTime.agoOrNull]'s null to `""` here rather than leaving it nullable:
     * a card whose stamp will not parse loses its timestamp, and nothing else. That degrade is pinned at
     * Tier 0 only — no fixture in the suite drives a corrupt stamp end to end (§11).
     *
     * [ThreadRow.excerptBy] is the voice behind the preview, and the rule is **don't name the same voice
     * twice** (§7): a card already wears its author as an attribution badge, so when the preview is the
     * thread's *own opening post* the byline would say what the badge just said. It is null in exactly two
     * cases — the excerpt is the card's own OP, whoever wrote it, and there is nothing to preview at all
     * (a title-only thread has no voice to credit).
     *
     * **[FeedThread.excerptIsReply] is what makes that decidable, and comparing the two author ids would
     * not be.** A persona replying to its own article thread yields `excerptAuthor == authorId` while the
     * preview is genuinely new speech, so an id comparison would silently swallow the byline in the one
     * case that most needs it. The flag separates *who* from *where it came from*; only the OP case is
     * redundant, and a reply is credited even when it is the same voice as the badge.
     */
    fun threadCard(row: FeedThread, now: Instant): ThreadRow {
        val excerpt = FeedExcerpt.of(row.excerptBody, EXCERPT_LEN)
        return ThreadRow(
            id = row.id,
            title = row.title,
            unreadCount = row.unreadCount,
            author = row.authorId,
            ago = RelativeTime.agoOrNull(row.lastActivity, now) ?: "",
            excerpt = excerpt,
            excerptBy = row.excerptAuthor?.takeIf { excerpt.isNotEmpty() && row.excerptIsReply },
        )
    }

    /**
     * One stream card.
     *
     * **The href has one shape for both kinds**, and that is the point of the repository giving a thread
     * opening its own id as its event id: `#reply-<threadId>` is the anchor `threadOp.kte` already emits,
     * so a post card lands on the opening post and a comment card lands on the comment, with no second
     * link shape to keep in step.
     *
     * **[ActivityRow.threadHref] is a SECOND destination, not a duplicate.** A card answers two different
     * questions and the owner asks them separately: the card body opens *this event* ([href], anchored),
     * while the thread title opens *the conversation* ([threadHref], its top). Both are computed here
     * rather than assembled in the template, so the pair is pinned at Tier 0 and cannot drift apart.
     *
     * The byline, monogram and hue all resolve from the raw author id, so a card whose author has no
     * persona row (a deleted member — attribution is a plain string, never an FK) still names a voice and
     * still gets a stable colour, the same way a reply node does.
     */
    fun activityCard(event: ActivityEvent, personas: List<PersonaView>, now: Instant): ActivityRow =
        ActivityRow(
            kind = if (event.isPost) "post" else "comment",
            id = event.id,
            threadId = event.threadId,
            threadTitle = event.threadTitle,
            author = event.authorId,
            byline = AuthorLabel.display(event.authorId),
            monogram = AuthorLabel.monogram(event.authorId),
            hue = AuthorColor.hue(event.authorId, personas),
            excerpt = FeedExcerpt.of(event.body, STREAM_EXCERPT_LEN),
            ago = RelativeTime.agoOrNull(event.createdAt, now) ?: "",
            unread = event.unread,
            href = "/threads/${event.threadId}#reply-${event.id}",
            threadHref = "/threads/${event.threadId}",
        )
}
