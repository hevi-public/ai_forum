package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.acceptance.support.TestData
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The feed front page (plan_docs/ambient-slice-6.md): the thread-card view, the activity stream behind
 * the toggle, and the preference that remembers which of the two the owner is looking at.
 *
 * **Every seeding Given here takes an explicit age**, and that is not a convenience. The test `Clock` is
 * FIXED and `TestData` stamps it verbatim, so without an age every seeded row shares one `created_at` and
 * an ordering assertion is really reading an arbitrary UUID tie-break — green today, red tomorrow on an
 * unrelated id change, and reported as an S6 regression by whoever is unlucky (§8). A global monotonic
 * stagger was refused for its blast radius (D9), so the age is per call and the feature file says it out
 * loud, in seconds, where a reader can check the arithmetic against the "5m" the card renders.
 *
 * **Seeding writes SQL directly** (the `TestData` house rule) — with two consequences worth naming. The
 * `Given` that pre-sets a view uses `TestData.setFeedView`, never the toggle endpoint: a Given must not
 * drive the surface a scenario may be specifying. And every thread-seeding Given records BOTH
 * `world.threadId` and `world.threadIds[title]`, because `HomeSteps.threadRowShowsBadge` scopes the
 * unread badge to one card by reverse-looking-up the title, and a thread seeded through a step that
 * skipped the map fails there with "no title recorded for thread <id>".
 *
 * **The view-switch step drives the CONTROL, not a URL it builds.** It reads the rendered option form's
 * own `action` and its hidden input's `name`/`value` off the page in hand and POSTs those — the
 * `data-memory-revert` discipline. A step that hardcoded `/feed-view` would stay green with the entire
 * toggle deleted, which is the one thing §2.4 argues the toggle has to be a server-side form for.
 *
 * **Arranging and asserting wordings are held apart** (`was opened … seconds ago` seeds a thread; `shows
 * a thread card for …` reads one; `the front page view is set to …` seeds a preference; `the front page
 * is showing the … view` reads one), because Cucumber resolves a step by its TEXT and not by its
 * Given/When/Then keyword — the S4b trap, where a `Given` silently invoked an assertion and arranged
 * nothing.
 *
 * NOT @Component; no mutable fields — per-scenario state lives in ScenarioWorld and the DB only.
 */
class FeedSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val data: TestData,
) {

    // --- seeding: threads, replies, read markers and the stored view, all directly into the DB --------

    @Given("a thread {string} was opened {int} seconds ago")
    fun threadOpenedAgo(title: String, agoSeconds: Int) = seedThread(title, null, "", agoSeconds)

    @Given("a thread {string} was opened {int} seconds ago with the opening post {string}")
    fun threadOpenedAgoWithOp(title: String, agoSeconds: Int, body: String) = seedThread(title, null, body, agoSeconds)

    @Given("a thread {string} was opened by {string} {int} seconds ago")
    fun threadOpenedByAgo(title: String, author: String, agoSeconds: Int) = seedThread(title, author, "", agoSeconds)

    @Given("a thread {string} was opened by {string} {int} seconds ago with the opening post {string}")
    fun threadOpenedByAgoWithOp(title: String, author: String, agoSeconds: Int, body: String) =
        seedThread(title, author, body, agoSeconds)

    /**
     * A settled reply in a NAMED thread — named rather than "the current thread", because every
     * ordering, excerpt and unread scenario here needs two threads on one page and the shared
     * `world.threadId` would silently point at whichever was seeded last.
     */
    @Given("the thread {string} received a reply from {string} saying {string} {int} seconds ago")
    fun threadReceivedReply(title: String, author: String, body: String, agoSeconds: Int) {
        data.insertComment(
            threadId = threadIdOf(title), authorId = author, body = body, agoSeconds = agoSeconds.toLong(),
        )
    }

    /** The same, with the generation state spelled out — the unsettled rows scenario 14 needs to be able
     *  to seed and then find absent (`FAILED`, `CANCELLED`), which no green path would ever create. */
    @Given("the thread {string} holds a {word} reply from {string} saying {string} {int} seconds ago")
    fun threadHoldsReplyInState(title: String, state: String, author: String, body: String, agoSeconds: Int) {
        data.insertComment(
            threadId = threadIdOf(title), authorId = author, body = body,
            state = state, agoSeconds = agoSeconds.toLong(),
        )
    }

    @Given("the owner read the thread {string} {int} seconds ago")
    fun ownerReadThreadAgo(title: String, agoSeconds: Int) =
        data.markReadAgo(threadIdOf(title), agoSeconds.toLong())

    /** The stored preference, written straight into `owner_pref` — never through the toggle. */
    @Given("the front page view is set to {string}")
    fun frontPageViewIsSetTo(slug: String) = data.setFeedView(slug)

    // --- drivers ------------------------------------------------------------------------------------

    /**
     * Switch the view by submitting the control the page actually renders: its own form `action` and its
     * own hidden `name`/`value`, read off the page in hand. Then re-GET `/` explicitly, so what the
     * following assertions read is a fresh render of the front page rather than whatever the POST
     * happened to return.
     *
     * What this does NOT distinguish, said plainly rather than claimed: because the step re-reads `/`
     * after the POST, it cannot tell "the preference was not stored" from "the response did not redraw".
     * §7's ledger row expecting a no-op `setFeedView` to redden the persistence scenario and NOT the
     * switch scenario is therefore wrong about this step — a no-op reddens both.
     */
    @When("the owner switches the front page to the {string} view")
    fun switchView(slug: String) {
        val form = optionForm(slug)
        val action = attrIn(form, "action")
            ?: error("the \"$slug\" view control renders no action to POST:\n$form")
        val hidden = Regex("<input\\b[^>]*type=\"hidden\"[^>]*>").find(form)?.value
            ?: error("the \"$slug\" view control carries no hidden field naming the view:\n$form")
        val name = attrIn(hidden, "name") ?: error("the \"$slug\" view control's hidden field has no name:\n$hidden")
        val value = attrIn(hidden, "value") ?: error("the \"$slug\" view control's hidden field has no value:\n$hidden")
        val resp = http.postForm(action, mapOf(name to value))
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
        openFrontPage()
    }

    /**
     * Submit a view name the toggle never offers. The endpoint and the parameter name still come off a
     * REAL control — only the value is the unknown one — because an unknown view has no control of its
     * own: it can only ever arrive from a stale page or a hand-crafted POST, and a step that invented
     * the whole request would be pinning a path rather than the refusal.
     *
     * Deliberately does NOT re-read `/`, so `the response status is …` reads the refusal itself. The
     * scenario re-opens the front page in its own step to check nothing was stored.
     */
    @When("the owner submits {string} as the front page view")
    fun submitUnknownView(slug: String) {
        val form = optionForm(FeedSlug.THREADS)
        val action = attrIn(form, "action")
            ?: error("the view control renders no action to POST:\n$form")
        val hidden = Regex("<input\\b[^>]*type=\"hidden\"[^>]*>").find(form)?.value
            ?: error("the view control carries no hidden field naming the view:\n$form")
        val name = attrIn(hidden, "name") ?: error("the view control's hidden field has no name:\n$hidden")
        val resp = http.postForm(action, mapOf(name to slug))
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    // --- the page and its toggle ---------------------------------------------------------------------

    @Then("the front page is showing the {string} view")
    fun frontPageIsShowingView(slug: String) {
        assertEquals(
            slug, Html.attrValues(body(), "data-feed-view").firstOrNull(),
            "expected the front page to declare data-feed-view=\"$slug\" in:\n${body()}",
        )
    }

    @Then("the front page offers the {string} view control")
    fun frontPageOffersViewControl(slug: String) {
        assertTrue(
            Html.contains(body(), "data-feed-toggle"),
            "expected the view toggle (data-feed-toggle) on the front page in:\n${body()}",
        )
        assertTrue(
            Html.hasAttr(body(), "data-feed-option", slug),
            "expected a \"$slug\" view control (data-feed-option=\"$slug\") in:\n${body()}",
        )
    }

    /** Read as the rendered string, never as mere presence: JTE DROPS a Boolean-valued attribute when
     *  false (§2.5), so an unselected control that stopped rendering `aria-pressed` at all would satisfy
     *  a presence check and pass the "not marked" half by omission. */
    @Then("the {string} view control is marked as showing")
    fun viewControlIsMarked(slug: String) =
        assertEquals("true", ariaPressed(slug), "expected the \"$slug\" control to be aria-pressed=\"true\"")

    @Then("the {string} view control is not marked as showing")
    fun viewControlIsNotMarked(slug: String) =
        assertEquals("false", ariaPressed(slug), "expected the \"$slug\" control to be aria-pressed=\"false\"")

    /** The two views are empty for different reasons — "no threads yet" is not "nothing has happened
     *  yet" — so the key is part of the contract, not decoration. */
    @Then("the front page shows the {string} empty state")
    fun frontPageShowsEmptyState(key: String) {
        assertTrue(
            Html.hasAttr(body(), "data-empty-state", key),
            "expected data-empty-state=\"$key\" in:\n${body()}",
        )
    }

    // --- thread cards ---------------------------------------------------------------------------------

    @Then("the front page shows a thread card for {string}")
    fun frontPageShowsThreadCard(title: String) {
        assertEquals(
            threadIdOf(title), Html.threadRowAttr(body(), title, "data-thread-id"),
            "expected a thread card for \"$title\" (data-thread-title + data-thread-id) in:\n${body()}",
        )
    }

    /**
     * I1's teeth: the two card vocabularies are DISJOINT, so an activity card can never satisfy a
     * thread-card assertion. Scanning for the whole `data-thread-` prefix rather than for one hook is
     * what makes that a property instead of a spot check — a stream card that grew a single thread-card
     * hook fails here.
     */
    @Then("the front page shows no thread cards")
    fun frontPageShowsNoThreadCards() {
        val leaked = Regex("data-thread-[a-z-]+").findAll(body()).map { it.value }.distinct().toList()
        assertTrue(
            leaked.isEmpty(),
            "expected NO thread-card hooks on the activity view, found $leaked in:\n${body()}",
        )
    }

    /** The mirror of the above, so a one-way mutation cannot pass: the thread-card view carries no
     *  stream cards either. */
    @Then("the front page shows no activity cards")
    fun frontPageShowsNoActivityCards() {
        val ids = Html.feedEventIds(body())
        assertTrue(ids.isEmpty(), "expected NO activity cards on the thread-card view, found $ids in:\n${body()}")
    }

    @Then("the front page lists the thread cards in order: {string}")
    fun threadCardsInOrder(expected: String) {
        assertEquals(
            expected.split(", "), Html.attrValues(body(), "data-thread-title"),
            "thread cards are ordered by LAST ACTIVITY, not by creation (§2.2), in:\n${body()}",
        )
    }

    @Then("the thread card for {string} previews {string}")
    fun cardPreviews(title: String, text: String) {
        val excerpt = excerptOf(title)
        assertTrue(excerpt.contains(text, ignoreCase = true), "expected \"$title\"'s preview to show \"$text\", was: \"$excerpt\"")
    }

    /** Equality, not containment — this is the wording the twin halves lean on: an excerpt that grew a
     *  byline it should not have (scenario 9) or fell back to the wrong body (scenario 8) fails here. */
    @Then("the thread card for {string} previews exactly {string}")
    fun cardPreviewsExactly(title: String, text: String) =
        assertEquals(text, excerptOf(title), "expected \"$title\"'s preview to be exactly \"$text\"")

    @Then("the thread card for {string} previews {string} credited to {string}")
    fun cardPreviewsCredited(title: String, text: String, voice: String) {
        val excerpt = excerptOf(title)
        assertTrue(excerpt.contains(text, ignoreCase = true), "expected \"$title\"'s preview to show \"$text\", was: \"$excerpt\"")
        assertTrue(excerpt.contains(voice, ignoreCase = true), "expected \"$title\"'s preview to name \"$voice\", was: \"$excerpt\"")
    }

    @Then("the thread card for {string} does not preview {string}")
    fun cardDoesNotPreview(title: String, text: String) {
        val excerpt = excerptOf(title)
        assertTrue(
            !excerpt.contains(text, ignoreCase = true),
            "\"$title\"'s preview must show the NEWEST comment only, but showed \"$text\": \"$excerpt\"",
        )
    }

    @Then("the thread card for {string} shows no preview")
    fun cardShowsNoPreview(title: String) {
        val excerpt = Html.spanText(body(), "data-thread-excerpt", threadIdOf(title))
        assertTrue(
            excerpt.isNullOrBlank(),
            "a title-only thread has nothing to preview, but \"$title\"'s card showed \"$excerpt\"",
        )
    }

    @Then("the thread card for {string} shows it was last active {string}")
    fun cardShowsAgo(title: String, label: String) =
        assertEquals(
            label, Html.threadRowAttr(body(), title, "data-thread-ago"),
            "expected \"$title\"'s card to carry data-thread-ago=\"$label\" in:\n${body()}",
        )

    @Then("the thread card for {string} shows {int} unread")
    fun cardShowsUnread(title: String, count: Int) =
        assertEquals(
            count.toString(), Html.threadRowAttr(body(), title, "data-unread-count"),
            "expected \"$title\"'s card to carry data-unread-count=\"$count\" in:\n${body()}",
        )

    @Then("the thread card for {string} is attributed to {string}")
    fun cardAttributedTo(title: String, voice: String) =
        assertEquals(
            voice, Html.threadRowAttr(body(), title, "data-thread-author"),
            "expected \"$title\"'s card to carry data-thread-author=\"$voice\" in:\n${body()}",
        )

    /** The owner half of the attribution pair. `data-thread-author` is a JTE smart attribute, so an
     *  owner-authored thread omits it entirely rather than rendering an empty one. */
    @Then("the thread card for {string} carries no attribution")
    fun cardCarriesNoAttribution(title: String) {
        assertEquals(
            threadIdOf(title), Html.threadRowAttr(body(), title, "data-thread-id"),
            "expected a thread card for \"$title\" at all, in:\n${body()}",
        )
        assertEquals(
            null, Html.threadRowAttr(body(), title, "data-thread-author"),
            "an owner-authored thread must carry no persona attribution, in:\n${body()}",
        )
    }

    /** D12: j/k reaches the front page. The pin goes exactly this far — the attribute is present on
     *  both card types — because the acceptance suite drives no browser and no tier drives `nav.js`
     *  (§11); whether navigation WORKS on these cards is a standing §10.4 gap, not something this
     *  step claims. */
    @Then("the thread card for {string} is a keyboard nav item")
    fun threadCardIsNavItem(title: String) {
        assertTrue(
            Html.threadRowAttr(body(), title, "data-nav-item") != null,
            "expected \"$title\"'s card to carry data-nav-item so j/k reaches it, in:\n${body()}",
        )
    }

    // --- the activity stream --------------------------------------------------------------------------

    @Then("the activity stream shows a comment from {string} saying {string}")
    fun streamShowsComment(author: String, text: String) {
        val row = cardSaying(text)
        assertEquals("comment", attrIn(rowTag(row), "data-feed-kind"), "expected a comment card, got:\n$row")
        assertEquals(author, attrIn(rowTag(row), "data-feed-author"), "expected the card to be $author's, got:\n$row")
    }

    /** The no-author-predicate pin: a thread opening is a card whoever wrote it, and a post card's event
     *  id IS its thread id, so it is looked up by that (§2.2, and the Tier-0 href rule). */
    @Then("the activity stream shows the opening of {string} by {string}")
    fun streamShowsOpening(title: String, author: String) {
        val threadId = threadIdOf(title)
        val row = Html.feedEventRow(body(), threadId)
            ?: error("no activity card for the opening of \"$title\"; the stream showed:\n${streamSummary()}")
        assertEquals("post", attrIn(rowTag(row), "data-feed-kind"), "expected a post card, got:\n$row")
        assertEquals(author, attrIn(rowTag(row), "data-feed-author"), "expected the card to be $author's, got:\n$row")
        assertEquals(threadId, attrIn(rowTag(row), "data-feed-thread"), "expected the card to name its thread, got:\n$row")
    }

    @Then("the activity stream shows nothing saying {string}")
    fun streamShowsNothingSaying(text: String) {
        val matches = rows().filter { Html.textOf(it).contains(text, ignoreCase = true) }
        assertTrue(
            matches.isEmpty(),
            "an unsettled reply must never reach the stream, but a card said \"$text\":\n${matches.joinToString("\n")}",
        )
    }

    /**
     * The stream's shape in document order, each card as `kind:author` — which is what "interleaves
     * posts and comments, newest first" means and what a per-card assertion cannot say. Both legs of the
     * UNION appear in the expected string, so dropping either one fails here.
     */
    @Then("the activity stream lists in order: {string}")
    fun streamListsInOrder(expected: String) {
        val actual = rows().map { "${attrIn(rowTag(it), "data-feed-kind")}:${attrIn(rowTag(it), "data-feed-author")}" }
        assertEquals(expected.split(", "), actual, "the stream is reverse-chronological across both legs, in:\n${body()}")
    }

    /** The link is composed from the card's OWN event id and the thread the fixture seeded — not read
     *  back from the same href it is checking — so a card that linked at the wrong comment fails. */
    @Then("the activity card saying {string} links into {string} at that comment")
    fun cardLinksIntoThread(text: String, title: String) {
        val row = cardSaying(text)
        val threadId = threadIdOf(title)
        val eventId = attrIn(rowTag(row), "data-feed-event") ?: error("the card carries no event id:\n$row")
        assertEquals(threadId, attrIn(rowTag(row), "data-feed-thread"), "expected the card to name \"$title\", got:\n$row")
        assertTrue(
            row.contains("href=\"/threads/$threadId#reply-$eventId\""),
            "expected a link into \"$title\" at that comment (/threads/$threadId#reply-$eventId) in:\n$row",
        )
    }

    /**
     * The thread title's own destination: the conversation, UNANCHORED.
     *
     * Asserted as the exact quoted string `href="/threads/<id>"` — the closing quote is what makes it an
     * assertion at all, because `/threads/<id>` is a prefix of `/threads/<id>#reply-<e>` and a
     * containment check would pass against the comment link sitting in the same card.
     */
    @Then("the activity card saying {string} links to the thread {string} itself")
    fun cardLinksToThreadItself(text: String, title: String) {
        val row = cardSaying(text)
        val threadId = threadIdOf(title)
        assertTrue(
            row.contains("href=\"/threads/$threadId\""),
            "expected an unanchored link to \"$title\" (/threads/$threadId) in:\n$row",
        )
    }

    /** Read as the explicit string on both sides (§2.5): a Boolean-valued attribute is DROPPED by JTE
     *  when false, so "not unread" asserted as absence would pass on a card that lost the hook. */
    @Then("the activity card saying {string} is unread")
    fun cardSayingIsUnread(text: String) =
        assertEquals("true", attrIn(rowTag(cardSaying(text)), "data-feed-unread"), "expected the card to be marked unread")

    @Then("the activity card saying {string} is not unread")
    fun cardSayingIsNotUnread(text: String) =
        assertEquals("false", attrIn(rowTag(cardSaying(text)), "data-feed-unread"), "expected the card to be marked read")

    /** "N new" is about replies the owner has not read (V2); a thread opening is not one of them (I5). */
    @Then("the activity card opening {string} is not unread")
    fun cardOpeningIsNotUnread(title: String) {
        val row = Html.feedEventRow(body(), threadIdOf(title))
            ?: error("no activity card for the opening of \"$title\"; the stream showed:\n${streamSummary()}")
        assertEquals("false", attrIn(rowTag(row), "data-feed-unread"), "a post card is never unread, got:\n$row")
    }

    @Then("the activity stream marks {int} cards unread")
    fun streamMarksUnread(count: Int) {
        val unread = rows().filter { attrIn(rowTag(it), "data-feed-unread") == "true" }
        assertEquals(
            count, unread.size,
            "\"N new\" must mean the same thing in both views (I5); the marked cards were:\n" +
                unread.joinToString("\n") { Html.textOf(it) },
        )
    }

    /** D12's other half — the hook goes on BOTH card types, or j/k works in one view and dies in the
     *  other. Presence only, for the reason spelled out on the thread-card sibling. */
    @Then("the activity card saying {string} is a keyboard nav item")
    fun activityCardIsNavItem(text: String) {
        val row = cardSaying(text)
        assertTrue(
            rowTag(row).contains("data-nav-item"),
            "expected the card to carry data-nav-item so j/k reaches it, got:\n$row",
        )
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private fun body(): String = world.lastBody ?: ""

    private fun seedThread(title: String, author: String?, opBody: String, agoSeconds: Int) {
        val id = data.insertThread(title, authorId = author, body = opBody, agoSeconds = agoSeconds.toLong())
        world.threadId = id
        // BOTH, always: HomeSteps scopes the unread badge to one card by reverse-looking-up this map.
        world.threadIds[title] = id
    }

    private fun threadIdOf(title: String): String =
        world.threadIds[title] ?: error("no thread \"$title\" was seeded in this scenario")

    private fun openFrontPage() {
        val resp = http.get("/")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    /**
     * One view control as a block — its opening `<form … data-feed-option="[slug]" …>` through the first
     * `</form>`. §2.4 settles the toggle as two plain PRG forms with fixed actions and fixed hidden
     * values (localStorage is invisible to this suite, a cookie is dropped by `HttpClient`, and an htmx
     * form whose target flips with state shipped broken once with no tier to catch it), so a form is
     * what a step is entitled to look for.
     */
    private fun optionForm(slug: String): String {
        val html = body()
        val open = Regex("<form\\b[^>]*data-feed-option=\"${Regex.escape(slug)}\"[^>]*>").find(html)
            ?: error("the front page offers no \"$slug\" view control (<form data-feed-option=\"$slug\">) in:\n$html")
        val close = html.indexOf("</form>", open.range.last + 1)
        if (close < 0) error("the \"$slug\" view control is never closed in:\n$html")
        return html.substring(open.range.first, close + "</form>".length)
    }

    /** The control's pressed state, read anywhere inside its own block — the marker may sit on the form
     *  or on the button it wraps, and which of the two is styling, not contract. */
    private fun ariaPressed(slug: String): String? =
        Regex("aria-pressed=\"([^\"]*)\"").find(optionForm(slug))?.groupValues?.get(1)

    private fun attrIn(fragment: String, attr: String): String? =
        Regex("${Regex.escape(attr)}=\"([^\"]*)\"").find(fragment)?.groupValues?.get(1)

    /** A card's own opening `<li>` tag — every stream hook lives there, and reading the whole block
     *  would let a nested element's attribute answer a question about the card. */
    private fun rowTag(row: String): String = Regex("^<li\\b[^>]*>").find(row)?.value ?: row

    private fun rows(): List<String> {
        val html = body()
        return Html.feedEventIds(html).mapNotNull { Html.feedEventRow(html, it) }
    }

    /**
     * The one stream card showing [text] — `singleOrNull`, so a fixture whose two cards both match fails
     * loudly here instead of quietly asserting against whichever came first.
     */
    private fun cardSaying(text: String): String {
        val matches = rows().filter { Html.textOf(it).contains(text, ignoreCase = true) }
        return matches.singleOrNull()
            ?: error(
                "expected exactly one activity card saying \"$text\", found ${matches.size}; " +
                    "the stream showed:\n${streamSummary()}",
            )
    }

    private fun streamSummary(): String =
        rows().joinToString("\n") { Html.textOf(it) }.ifEmpty { "(no activity cards at all)" }

    /** The card's preview prose. Child text, never an attribute value: gg.jte does not escape `>` in
     *  attribute context, so a prose excerpt in a hook would truncate the tag and make every hook after
     *  it unreadable (I2/D6) — which is why the hook carries the thread id and this reads the span. */
    private fun excerptOf(title: String): String =
        Html.spanText(body(), "data-thread-excerpt", threadIdOf(title))
            ?: error("\"$title\"'s card renders no preview (data-thread-excerpt) in:\n${body()}")

    private object FeedSlug {
        const val THREADS = "threads"
    }
}
