package com.aiforum.tier0

import com.aiforum.dto.Avatar
import com.aiforum.repo.ActivityEvent
import com.aiforum.repo.FeedThread
import com.aiforum.web.FeedCards
import com.aiforum.web.PersonaView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tier-0: the projection from a repository row to a rendered card (plan_docs/ambient-slice-6.md §2.1).
 *
 * [FeedCards] is an `object` and does no IO, which is what puts it here rather than behind a Spring
 * context — and what makes the three rules worth pinning cheap to pin: the link a card is (one href
 * shape for both kinds, because a thread opening's event id IS its thread id), the byline the preview
 * carries (or deliberately does not), and the degrade when a stored stamp will not parse.
 *
 * The corrupt-stamp tests are the ONLY place that branch is exercised end to end for the feed: no
 * fixture in the suite seeds an unparsable `created_at` (§11), so these stand between a hand-edited row
 * and a 500 on the only page the forum has.
 */
@Tag("tier0")
class FeedCardsTest {

    private val now: Instant = Instant.parse("2026-07-27T12:00:00Z")
    private fun stamp(agoSeconds: Long): String = now.minusSeconds(agoSeconds).toString()

    private fun thread(
        id: String = "t1",
        title: String = "Scaling SQLite",
        authorId: String? = null,
        lastActivity: String = stamp(300),
        excerptBody: String = "the opening post",
        excerptAuthor: String? = null,
        excerptIsReply: Boolean = false,
        unreadCount: Int = 0,
    ) = FeedThread(id, title, authorId, lastActivity, excerptBody, excerptAuthor, excerptIsReply, unreadCount)

    private fun event(
        isPost: Boolean = false,
        id: String = "c1",
        threadId: String = "t1",
        threadTitle: String = "Scaling SQLite",
        authorId: String = "sol",
        body: String = "indexes help here",
        createdAt: String = stamp(300),
        unread: Boolean = false,
    ) = ActivityEvent(isPost, id, threadId, threadTitle, authorId, body, createdAt, unread)

    // --- the link a stream card is -----------------------------------------------------------------

    @Test
    fun `a comment card links into its thread at that comment`() {
        val card = FeedCards.activityCard(event(id = "c1", threadId = "t1"), emptyList(), now)
        assertEquals("/threads/t1#reply-c1", card.href)
        assertEquals("comment", card.kind)
    }

    @Test
    fun `a post card's event id is its thread id, so it lands on the opening post's own anchor`() {
        // threadOp.kte already emits id="reply-<threadId>", so the thread leg giving an opening the same
        // value for both fields is what lets ONE href shape serve both kinds — there is no second link
        // shape to keep in step, and no anchor to add.
        val card = FeedCards.activityCard(event(isPost = true, id = "t1", threadId = "t1"), emptyList(), now)
        assertEquals("/threads/t1#reply-t1", card.href)
        assertEquals("post", card.kind)
    }

    // --- who a card credits -------------------------------------------------------------------------

    @Test
    fun `a thread previewing its own owner-authored opening post credits nobody`() {
        // A byline here would be the page telling the owner who the owner is. A comment always carries an
        // author, so a null excerpt author can only have come from the thread's own OP.
        val card = FeedCards.threadCard(thread(excerptBody = "Which index wins?", excerptAuthor = null), now)
        assertEquals("Which index wins?", card.excerpt)
        assertNull(card.excerptBy)
    }

    @Test
    fun `a preview from a reply names the voice it came from`() {
        val card = FeedCards.threadCard(
            thread(excerptBody = "indexes help here", excerptAuthor = "sol", excerptIsReply = true), now,
        )
        assertEquals("sol", card.excerptBy)
    }

    @Test
    fun `a persona's reply-less thread previews its own opening and credits nobody`() {
        // The ambient article thread the moment it lands: the card already wears "sol" as its attribution
        // badge, so crediting the preview to Sol as well would name the same voice twice (§7). Owner and
        // persona OPs now behave alike — it is being the thread's OWN opening that suppresses the byline,
        // not who wrote it.
        val card = FeedCards.threadCard(
            thread(authorId = "sol", excerptBody = "The summary", excerptAuthor = "sol"), now,
        )
        assertEquals("The summary", card.excerpt)
        assertNull(card.excerptBy)
    }

    @Test
    fun `a persona replying to its own thread is still credited, which an author comparison would miss`() {
        // THE DISCRIMINATOR, and the reason FeedThread.excerptIsReply has to exist. Here excerptAuthor ==
        // authorId exactly as in the reply-less case above, so a rule written as `excerptAuthor != authorId`
        // would suppress this byline too — hiding genuinely new speech and making the card look unchanged
        // since it was opened. Only the flag tells the two apart.
        val card = FeedCards.threadCard(
            thread(
                authorId = "sol", excerptBody = "One more thing I missed",
                excerptAuthor = "sol", excerptIsReply = true,
            ),
            now,
        )
        assertEquals("sol", card.excerptBy)
    }

    @Test
    fun `a title-only thread previews nothing and so credits nobody`() {
        val card = FeedCards.threadCard(thread(authorId = "sol", excerptBody = "", excerptAuthor = "sol"), now)
        assertEquals("", card.excerpt)
        assertNull(card.excerptBy, "with nothing to preview there is no voice to credit")
    }

    @Test
    fun `the thread card keeps the RAW author id, mapping nothing`() {
        // data-thread-author is what the ambient attribution probes read, and they read the stored id.
        // Only the visible byline is mapped, and that happens in the fragment.
        assertEquals("gh:octocat", FeedCards.threadCard(thread(authorId = "gh:octocat"), now).author)
    }

    // --- who a stream card says it is ----------------------------------------------------------------

    @Test
    fun `a github author reads as its login while the hook keeps the stored id`() {
        val card = FeedCards.activityCard(event(authorId = "gh:octocat"), emptyList(), now)
        assertEquals("gh:octocat", card.author, "the hook carries what the DB stores")
        assertEquals("@octocat", card.byline)
        assertEquals("OC", card.monogram, "the login's initials, not a \"GH\" monogram")
    }

    @Test
    fun `an author with no persona row still gets a byline and a stable hue`() {
        // Attribution is a plain string, never a foreign key, so a byline outlives its persona's deletion
        // (V20). A card that resolved its voice through the roster would lose both the name and the
        // colour the moment a member was removed.
        val card = FeedCards.activityCard(event(authorId = "sol"), personas = emptyList(), now = now)
        assertEquals("sol", card.byline)
        assertEquals(Avatar.reservedHue("sol"), card.hue)
        assertEquals(card.hue, FeedCards.activityCard(event(authorId = "sol"), emptyList(), now).hue)
    }

    @Test
    fun `a persona on the roster uses its own stored colour slot`() {
        val roster = listOf(PersonaView("sol", "Sol", "the pragmatist", "sol", colorIndex = 3))
        val card = FeedCards.activityCard(event(authorId = "sol"), roster, now)
        assertEquals(Avatar.hueForIndex(3), card.hue)
        assertNotEquals(Avatar.reservedHue("sol"), card.hue, "the roster's slot must win over the hashed hue")
    }

    // --- the stamp -----------------------------------------------------------------------------------

    @Test
    fun `a card reads its age off the stored stamp`() {
        assertEquals("5m", FeedCards.threadCard(thread(lastActivity = stamp(300)), now).ago)
        assertEquals("5m", FeedCards.activityCard(event(createdAt = stamp(300)), emptyList(), now).ago)
    }

    @Test
    fun `a stamp that will not parse costs the card its timestamp and nothing else`() {
        // Empty string, not null and not a throw: one hand-edited row loses its own "time ago" instead of
        // costing the whole front page a 500. FeedCards owns this coercion so the template stays dumb.
        val card = FeedCards.threadCard(thread(lastActivity = "2026-07-27 12:00:00", excerptBody = "still here"), now)
        assertEquals("", card.ago)
        assertEquals("still here", card.excerpt, "the rest of the card survives")

        val streamCard = FeedCards.activityCard(event(createdAt = "not-a-timestamp"), emptyList(), now)
        assertEquals("", streamCard.ago)
        assertEquals("indexes help here", streamCard.excerpt)
    }

    // --- the preview ---------------------------------------------------------------------------------

    @Test
    fun `a preview drops the bare url an ambient article opening trails`() {
        // The body FeedExcerpt exists for: AmbientTickService opens an article thread with
        // "<summary>\n\n<url>", and a reply-less thread previews its own opening post.
        val card = FeedCards.threadCard(
            thread(excerptBody = "Rust 1.90 ships a smaller borrow checker.\n\nhttps://blog.rust-lang.org/1.90"),
            now,
        )
        assertEquals("Rust 1.90 ships a smaller borrow checker.", card.excerpt)
    }
}
