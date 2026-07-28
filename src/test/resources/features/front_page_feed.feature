Feature: The front page is two readings of one forum, and it remembers which one you chose

  plan_docs/ambient-slice-6.md, made executable. The front page stops being one list: the thread
  index becomes a card carrying the persona attribution, the relative time of last activity, the
  "N new" unread delta and a one-line preview of the newest comment — activity-sorted, which it is
  not today — and behind a toggle sits an Activity stream where every thread opening and every
  settled comment is its own card, newest first, linking into the thread at that comment. The choice
  persists, so it survives leaving and coming back.

  The stream is called **Activity and never Ambient** (D1/I6). The schema cannot express provenance:
  an ambient comment goes through the same summon path an owner's does, carrying no marker, and
  `ambient_run` records no comment id on purpose. So the honest superset ships — all settled
  activity, author-agnostic, owner posts included — and an ambient-only stream is deferred to a
  slice that first makes provenance representable.

  Two conventions run through every scenario here. **Ages are explicit, in seconds**: the test clock
  is fixed and TestData stamps it verbatim, so an ordering assertion over rows that share one
  `created_at` is really asserting an arbitrary UUID tie-break — worse than no test (§8). And
  **every absence-shaped claim carries a positive twin on the same page**, so "shows no thread
  cards" can never pass because the page failed to render at all.

  # 1. The default view, which is the named guard for the nine front-page scenarios that stay
  # untouched: a fresh DB holds no owner_pref row, and absence IS the thread cards (D4). Reddens on
  # exactly one mutation — FeedView.DEFAULT = ACTIVITY — which is what makes it their guard rather
  # than nine copies of a step that can never fail. The nav-item clause is D12's half of the pin for
  # this card type: presence only, because no tier drives nav.js.
  Scenario: The front page opens on the thread-card view
    Given a thread "Scaling SQLite" was opened 600 seconds ago with the opening post "How do we scale this?"
    When the owner opens the front page
    Then the front page is showing the "threads" view
    And the front page shows a thread card for "Scaling SQLite"
    And the thread card for "Scaling SQLite" is a keyboard nav item

  # 2. The control itself, on the page with the least to show — an empty forum, where the toggle is
  # the only way back. Marks the current view as an explicit aria-pressed STRING on both controls,
  # because JTE drops a Boolean-valued attribute when false and an unselected control that stopped
  # rendering the marker would otherwise pass by omission (§2.5). Deleting feedToggle.kte reddens
  # this and #3.
  Scenario: The front page offers a control for each view and marks the one it is showing
    When the owner opens the front page
    Then the front page is showing the "threads" view
    And the front page offers the "threads" view control
    And the front page offers the "activity" view control
    And the "threads" view control is marked as showing
    And the "activity" view control is not marked as showing
    When the owner switches the front page to the "activity" view
    Then the front page offers the "threads" view control
    And the front page offers the "activity" view control
    And the "activity" view control is marked as showing
    And the "threads" view control is not marked as showing
    And the front page shows the "no-activity" empty state

  # 3. Switching redraws the page as the OTHER view, not as both. "Shows no thread cards" is I1's
  # teeth — the two hook vocabularies are disjoint, so a stream card can never satisfy a thread-card
  # assertion — and it is twinned with the comment card it must show instead, so it cannot pass on a
  # page that rendered nothing. Emitting data-thread-title on an activity card reddens the second
  # clause. The nav-item clause is D12's other half: the hook goes on BOTH card types, or j/k works
  # in one view and silently dies in the other.
  Scenario: Switching to the activity view redraws the front page and shows no thread cards
    Given a persona "Sol" exists
    And a thread "Scaling SQLite" was opened 600 seconds ago with the opening post "How do we scale this?"
    And the thread "Scaling SQLite" received a reply from "Sol" saying "Indexes help here" 60 seconds ago
    When the owner opens the front page
    And the owner switches the front page to the "activity" view
    Then the front page is showing the "activity" view
    And the activity stream shows a comment from "Sol" saying "Indexes help here"
    And the activity card saying "Indexes help here" is a keyboard nav item
    And the front page shows no thread cards

  # 4. The other direction, so a one-way mutation cannot pass. The starting view is seeded straight
  # into owner_pref rather than clicked, because a Given must not drive the surface a scenario is
  # specifying. Its own disjointness clause mirrors #3's, from the thread-card side.
  Scenario: Switching back to threads restores the thread cards
    Given a persona "Sol" exists
    And a thread "Scaling SQLite" was opened 600 seconds ago with the opening post "How do we scale this?"
    And the thread "Scaling SQLite" received a reply from "Sol" saying "Indexes help here" 60 seconds ago
    And the front page view is set to "activity"
    When the owner opens the front page
    And the owner switches the front page to the "threads" view
    Then the front page is showing the "threads" view
    And the front page shows a thread card for "Scaling SQLite"
    And the front page shows no activity cards

  # 5. Persistence, as a SECOND independent GET — which is the whole reason the toggle is a
  # server-side form (§2.4): localStorage is invisible to a suite that drives no browser, and
  # HttpClient keeps no cookie jar, so both alternatives would fail this against a correct
  # implementation.
  Scenario: The chosen view survives leaving and coming back
    Given a thread "Scaling SQLite" was opened 600 seconds ago with the opening post "How do we scale this?"
    When the owner opens the front page
    And the owner switches the front page to the "activity" view
    And the owner opens the front page
    Then the front page is showing the "activity" view
    And the activity stream shows the opening of "Scaling SQLite" by "owner"

  # 6. The refusal, and that it refused without writing. Three layers guard the stored value and this
  # pins the middle one (FeedView.of's 400 at the endpoint); the Kotlin enum and V29's CHECK are
  # pinned at Tier 0 and Tier 1, where the DDL can be shown standing on its own. The submission
  # reuses a REAL control's action and parameter name — an unknown view has no control of its own —
  # so the step cannot pass by inventing an endpoint nobody renders.
  Scenario: An unknown view name is refused and the stored view is unchanged
    Given a thread "Scaling SQLite" was opened 600 seconds ago with the opening post "How do we scale this?"
    When the owner opens the front page
    And the owner submits "chronological" as the front page view
    Then the response status is 400
    When the owner opens the front page
    Then the front page is showing the "threads" view
    And the front page shows a thread card for "Scaling SQLite"

  # 7. The preview is the NEWEST settled comment: the newer one present and the older one absent, on
  # one page, so "shows a preview" cannot stand in for "shows the right preview". Reversing the
  # excerpt subquery's DESC reddens it.
  Scenario: A thread card previews the newest comment in its thread
    Given a persona "Sol" exists
    And a persona "Mira" exists
    And a thread "Scaling SQLite" was opened 900 seconds ago with the opening post "How do we scale this?"
    And the thread "Scaling SQLite" received a reply from "Sol" saying "Indexes help here" 300 seconds ago
    And the thread "Scaling SQLite" received a reply from "Mira" saying "Partition by tenant" 60 seconds ago
    When the owner opens the front page
    Then the thread card for "Scaling SQLite" previews "Partition by tenant"
    And the thread card for "Scaling SQLite" does not preview "Indexes help here"

  # 8. The fallback that makes a fresh ambient article thread show its summary instead of an empty
  # slot — the most valuable small-forum behaviour in the design. Twinned with a title-only thread,
  # which has nothing to fall back TO, so the fallback cannot be a blanket "always show something".
  # Dropping the excerpt's COALESCE to t.body reddens the first clause.
  Scenario: A thread with no replies previews its own opening post
    Given a thread "Scaling SQLite" was opened 600 seconds ago with the opening post "How do we scale this?"
    And a thread "Indexing strategies" was opened 300 seconds ago
    When the owner opens the front page
    Then the thread card for "Scaling SQLite" previews exactly "How do we scale this?"
    And the thread card for "Indexing strategies" shows no preview

  # 9. The preview names the voice it came from — and only when there is a voice to name. The twin is
  # an owner thread previewing its OWN opening post, where a byline would be the page telling the
  # owner who the owner is; asserted as an EXACT preview, so a byline that crept in fails.
  Scenario: A thread card names the voice its preview came from
    Given a persona "Sol" exists
    And a thread "Scaling SQLite" was opened 900 seconds ago with the opening post "How do we scale this?"
    And the thread "Scaling SQLite" received a reply from "Sol" saying "Indexes help here" 60 seconds ago
    And a thread "Indexing strategies" was opened 300 seconds ago with the opening post "Which index wins?"
    When the owner opens the front page
    Then the thread card for "Scaling SQLite" previews "Indexes help here" credited to "Sol"
    And the thread card for "Indexing strategies" previews exactly "Which index wins?"

  # 9b. Named once, never twice. A card already wears its author as an attribution badge, so previewing
  # the thread's OWN opening post must not credit that same voice a second time — while a REPLY from that
  # very same persona still is credited. That pair is the whole point: it pins that the rule reads "where
  # the preview came from", not "whose name is on it". A rule written as `excerptAuthor != authorId`
  # passes the first half and fails the second, which is why FeedThread.excerptIsReply has to exist.
  # The first Then also pins that suppressing the byline did not suppress the BADGE: named once, not zero.
  Scenario: A persona's own opening post is not credited under its own badge
    Given a persona "Sol" exists
    And a thread "Scaling SQLite" was opened 600 seconds ago with the opening post "The summary"
    And the thread was authored by "Sol"
    And a thread "Indexing strategies" was opened 300 seconds ago with the opening post "Which index wins?"
    And the thread was authored by "Sol"
    And the thread "Indexing strategies" received a reply from "Sol" saying "Indexes help here" 60 seconds ago
    When the owner opens the front page
    Then the home rail shows thread "Scaling SQLite" authored by "Sol"
    And the thread card for "Scaling SQLite" previews exactly "The summary"
    And the thread card for "Indexing strategies" previews "Indexes help here" credited to "Sol"

  # 10. Last activity as the owner reads it. The arithmetic is checkable from the Gherkin: the thread
  # opened an hour ago, its newest settled comment landed 300 seconds ago, and the card says "5m" —
  # so a card that timed itself off the thread's creation would say "1h" and fail here.
  Scenario: A thread card shows how long ago the thread was last active
    Given a persona "Sol" exists
    And a thread "Scaling SQLite" was opened 3600 seconds ago with the opening post "How do we scale this?"
    And the thread "Scaling SQLite" received a reply from "Sol" saying "Indexes help here" 300 seconds ago
    When the owner opens the front page
    Then the thread card for "Scaling SQLite" shows it was last active "5m"

  # 11. Activity order, asserted in BOTH arrangements. The fixture is built so activity order is the
  # REVERSE of creation order, and then a reply to the other thread flips it — so no static ordering
  # satisfies both reads, and ORDER BY last_activity → t.created_at DESC reddens the pair.
  Scenario: Thread cards are ordered by last activity, not by creation
    Given a persona "Sol" exists
    And a persona "Mira" exists
    And a thread "Scaling SQLite" was opened 3000 seconds ago with the opening post "How do we scale this?"
    And a thread "Indexing strategies" was opened 600 seconds ago with the opening post "Which index wins?"
    And the thread "Scaling SQLite" received a reply from "Sol" saying "Indexes help here" 60 seconds ago
    When the owner opens the front page
    Then the front page lists the thread cards in order: "Scaling SQLite, Indexing strategies"
    Given the thread "Indexing strategies" received a reply from "Mira" saying "Partition by tenant" 10 seconds ago
    When the owner opens the front page
    Then the front page lists the thread cards in order: "Indexing strategies, Scaling SQLite"

  # 12. The stream is ONE reverse-chronological list across both legs of the UNION, not posts then
  # comments. The expected shape names a kind and an author per card, in document order, so dropping
  # the thread leg — or sorting the legs apart — reddens it where a per-card assertion would not.
  Scenario: The activity view interleaves posts and comments, newest first
    Given a persona "Sol" exists
    And a persona "Mira" exists
    And a persona "Vex" exists
    And a thread "Indexing strategies" was opened 1200 seconds ago with the opening post "Which index wins?"
    And a thread "Scaling SQLite" was opened by "Sol" 600 seconds ago with the opening post "SQLite handles this fine"
    And the thread "Indexing strategies" received a reply from "Vex" saying "Batching helps a lot" 900 seconds ago
    And the thread "Scaling SQLite" received a reply from "Mira" saying "Partition by tenant" 60 seconds ago
    And the front page view is set to "activity"
    When the owner opens the front page
    Then the activity stream lists in order: "comment:Mira, post:Sol, comment:Vex, post:owner"

  # 13. The no-author-predicate pin. Excluding owner posts would make a brand-new forum with three
  # owner threads render "nothing has happened yet" with three threads one click away — so the
  # owner's own opening is a card like any other. Twinned with a persona-opened thread, so the
  # assertion cannot pass on a stream that shows every row indiscriminately misattributed.
  # Adding WHERE t.author_id IS NOT NULL reddens the first clause.
  Scenario: A thread the owner opened is its own card in the activity view too
    Given a persona "Sol" exists
    And a thread "Indexing strategies" was opened 300 seconds ago with the opening post "Which index wins?"
    And a thread "Scaling SQLite" was opened by "Sol" 600 seconds ago with the opening post "SQLite handles this fine"
    And the front page view is set to "activity"
    When the owner opens the front page
    Then the activity stream shows the opening of "Indexing strategies" by "owner"
    And the activity stream shows the opening of "Scaling SQLite" by "Sol"

  # 14. Only SETTLED comments are activity. The POSTED reply present and the two unsettled ones
  # absent, on ONE page, so the absence halves cannot pass against a stream that rendered nothing.
  # Dropping state = 'POSTED' reddens it.
  Scenario: Unsettled replies never reach the activity stream
    Given a persona "Sol" exists
    And a thread "Scaling SQLite" was opened 900 seconds ago with the opening post "How do we scale this?"
    And the thread "Scaling SQLite" received a reply from "Sol" saying "Indexes help here" 300 seconds ago
    And the thread "Scaling SQLite" holds a FAILED reply from "Sol" saying "This draft never settled" 200 seconds ago
    And the thread "Scaling SQLite" holds a CANCELLED reply from "Sol" saying "This draft was called off" 100 seconds ago
    And the front page view is set to "activity"
    When the owner opens the front page
    Then the activity stream shows a comment from "Sol" saying "Indexes help here"
    And the activity stream shows nothing saying "This draft never settled"
    And the activity stream shows nothing saying "This draft was called off"

  # 15. A stream card is a way IN, not a notice board: the link lands on the comment's own anchor in
  # its thread. The expected href is composed from the card's own event id and the thread the fixture
  # seeded, so a card linking at the wrong comment — or at the thread's top — fails.
  Scenario: An activity card links into its thread at that comment
    Given a persona "Sol" exists
    And a persona "Mira" exists
    And a thread "Scaling SQLite" was opened by "Sol" 600 seconds ago with the opening post "SQLite handles this fine"
    And the thread "Scaling SQLite" received a reply from "Mira" saying "Partition by tenant" 60 seconds ago
    And the front page view is set to "activity"
    When the owner opens the front page
    Then the activity card saying "Partition by tenant" links into "Scaling SQLite" at that comment

  # 16. The coherence pin (I5): one fixture, both views, and "N new" has to mean the same thing in
  # each — two comments after the owner's read marker, the one before it read, and the thread's own
  # opening never unread at all, because "N new" is about replies the owner has not read and an
  # opening is not one of them. Flagging post cards unread reddens the last clause.
  Scenario: Unread means the same thing in both views
    Given a persona "Sol" exists
    And a persona "Mira" exists
    And a persona "Vex" exists
    And a thread "Scaling SQLite" was opened by "Sol" 900 seconds ago with the opening post "SQLite handles this fine"
    And the thread "Scaling SQLite" received a reply from "Mira" saying "Batching helps a lot" 600 seconds ago
    And the owner read the thread "Scaling SQLite" 300 seconds ago
    And the thread "Scaling SQLite" received a reply from "Mira" saying "Partition by tenant" 120 seconds ago
    And the thread "Scaling SQLite" received a reply from "Vex" saying "The checkpoint is the stall" 60 seconds ago
    When the owner opens the front page
    Then the thread card for "Scaling SQLite" shows 2 unread
    When the owner switches the front page to the "activity" view
    Then the activity stream marks 2 cards unread
    And the activity card saying "Partition by tenant" is unread
    And the activity card saying "The checkpoint is the stall" is unread
    And the activity card saying "Batching helps a lot" is not unread
    And the activity card opening "Scaling SQLite" is not unread

  # 17. D11, the owner's call on the right rail: the recent-comments box is a strict subset of the
  # stream, so showing both is the same five comments twice on one screen — it is suppressed in the
  # Activity view and ONLY there. The three positive clauses are what stop this passing by failing to
  # render the rail at all. Deliberately three and not four: asserting that every box renders in this
  # view would convert an accident into a contract (§10.2).
  Scenario: The activity view hides the recent-comments box and still shows the other three rails
    Given the Shortcut integration is active
    And a persona "Sol" exists
    And a thread "Scaling SQLite" was opened 600 seconds ago with the opening post "How do we scale this?"
    And the thread "Scaling SQLite" received a reply from "Sol" saying "Indexes help here" 60 seconds ago
    And the front page view is set to "activity"
    When the owner opens the front page
    Then the front page is showing the "activity" view
    And the page does not show the "recent-comments" rail box
    And the page shows the "starred-comments" rail box
    And the page shows the "active-threads" rail box
    And the page shows the "shortcut" rail box
