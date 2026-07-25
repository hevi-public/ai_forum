Feature: Admin statistics dashboard — /admin
  GET /admin shows a read-only snapshot of forum-wide statistics. The page is public (the app has
  no auth layer) and only reads — no settings, no mutations in this slice. Numbers are asserted via
  the stable data-stat hooks so the scenarios survive a visual redesign.

  Scenario: The dashboard renders with zeroes on an empty forum
    When the owner visits the admin page
    Then the admin dashboard is shown
    And the admin statistic "threads" is 0
    And the admin statistic "personas" is 0
    And the admin statistic "comments-total" is 0
    And the admin statistic "comments-posted" is 0

  Scenario: The dashboard counts threads, personas and posted comments
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    And a posted reply from "sol" saying "Indexes help here"
    When the owner visits the admin page
    Then the admin statistic "threads" is 1
    And the admin statistic "personas" is 1
    And the admin statistic "comments-total" is 1
    And the admin statistic "comments-posted" is 1

  # The ambient loop (plan_docs/ambient-slice-1.md) adds a run counter and splits the thread count by
  # authorship: `a thread {string} exists` seeds via TestData, which leaves author_id NULL — the
  # owner-authored path every existing thread takes today.
  Scenario: The dashboard counts ambient runs and splits owner- vs persona-authored threads
    Given a thread "Scaling SQLite" exists
    When the owner visits the admin page
    Then the admin statistic "ambient-runs" is 0
    And the admin statistic "owner-threads" is 1
    And the admin statistic "persona-threads" is 0

  # The relation-stance evolution pass (plan_docs/ambient-slice-4a.md) auto-applies with no approval
  # queue, so its audit log at /admin/stances is the owner's whole control over it — and the dashboard
  # has to offer a way in, or the only surface that can undo a change is one nothing links to.
  #
  # Read the figure narrowly: it counts audit ROWS, i.e. how often the pass has acted. It is not a
  # measure of any relationship. Stances are prose by hard guardrail, and the same count grouped by
  # persona pair would be a relationship score, which is precisely the reward economy this design cut.
  Scenario: The dashboard counts audited stance changes and links to their log
    Given a persona "sol" exists
    When the owner visits the admin page
    Then the admin statistic "stance-changes" is 0
    And the admin statistic "stance-changes" links to "/admin/stances"
