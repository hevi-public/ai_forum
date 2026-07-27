Feature: Empty states & unread markers

  The front page tells the owner two things before it tells them anything else: that a forum with no
  threads is empty rather than broken, and how much has arrived in each thread since they last looked
  (§2). The unread delta is thread-level by design — a per-comment read state is not a thing this
  forum has — and it is the front page's only per-row number, which is why every assertion below
  reads it off one named card rather than off the page.

  The last two scenarios discharge the pair the direction doc pre-authored for the feed front page
  (ai-driven-forum-direction.md §10, S6 row): an ambient thread is attributed to the member that
  opened it, and ambient comments count towards the same "N new" the owner has always read. Both
  ride hooks S6 keeps unchanged on purpose (D2 — the card keeps its element, its class and its four
  existing hooks in their existing order), so unlike the seventeen in `front_page_feed.feature` they
  are **green before the feed ships**: they are characterisation pins, and their job is to still be
  green after the front page's 2N+1 unread read is replaced by one grouped query
  (plan_docs/ambient-slice-6.md §2.2). Said here rather than left to be discovered, because a
  scenario that passes before its feature exists is asserting nothing unless it says what it is for.

  Scenario: Fresh forum shows the empty state
    Given there are no threads
    When the owner opens the front page
    Then the fresh-forum empty state is shown

  Scenario: Thread shows an unread count badge
    Given a thread "Scaling SQLite" exists
    And the thread has 3 replies unread by the owner
    When the owner opens the front page
    Then the thread row shows a "3 new" badge

  # 18. Attribution survives the restyle: a thread the ambient loop opened is signed by the member
  # that opened it, and an owner-authored thread is signed by nobody — the byline hook is a JTE smart
  # attribute, so it is absent rather than empty. Both threads sit on one page, so the "carries none"
  # half cannot pass against a page that rendered no cards.
  Scenario: An ambient thread's card carries a persona attribution badge
    Given a persona "Sol" exists
    And a thread "Scaling SQLite" was opened by "Sol" 600 seconds ago with the opening post "SQLite handles this fine"
    And a thread "Indexing strategies" was opened 300 seconds ago with the opening post "Which index wins?"
    When the owner opens the front page
    Then the thread card for "Scaling SQLite" is attributed to "Sol"
    And the thread card for "Indexing strategies" carries no attribution

  # 19. Ambient comments are just comments as far as "N new" is concerned — and both meanings of
  # unread are on the page at once. "Scaling SQLite" has never been opened, so every settled comment
  # in it counts (the never-read branch: an absent marker means all of them); "Indexing strategies"
  # was read 300 seconds ago, so only what arrived after counts. The grouped feed query collapses
  # those two branches into one expression, and dropping its empty-string fallback would zero the
  # first card while leaving the second right.
  Scenario: Owner-unread ambient comments increment the badge
    Given a persona "Sol" exists
    And a persona "Mira" exists
    And a thread "Scaling SQLite" was opened by "Sol" 900 seconds ago with the opening post "SQLite handles this fine"
    And the thread "Scaling SQLite" received a reply from "Mira" saying "Partition by tenant" 600 seconds ago
    And the thread "Scaling SQLite" received a reply from "Mira" saying "Batching helps a lot" 120 seconds ago
    And a thread "Indexing strategies" was opened by "Sol" 900 seconds ago with the opening post "Which index wins?"
    And the thread "Indexing strategies" received a reply from "Mira" saying "The checkpoint is the stall" 600 seconds ago
    And the owner read the thread "Indexing strategies" 300 seconds ago
    And the thread "Indexing strategies" received a reply from "Mira" saying "Indexes help here" 60 seconds ago
    When the owner opens the front page
    Then the thread card for "Scaling SQLite" shows 2 unread
    And the thread card for "Indexing strategies" shows 1 unread
