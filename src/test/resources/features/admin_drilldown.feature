Feature: Admin drill-downs — stat links and list pages
  Each figure on /admin links to the items behind it. The comment drill-downs list the matching
  comments, each linking to its permalink in the thread. Empty filters show an empty state.

  Scenario: Stat figures link to their drill-downs
    When the owner visits the admin page
    Then the admin statistic "threads" links to "/"
    And the admin statistic "personas" links to "/personas"
    And the admin statistic "comments-total" links to "/admin/comments"
    And the admin statistic "comments-posted" links to "/admin/comments?state=POSTED"
    And the admin statistic "votes" links to "/admin/comments?voted=true"
    And the admin statistic "leak-actual" links to "/admin/comments?leak=ACTUAL"

  Scenario: The posted-comments drill-down lists matching comments linking to their permalinks
    Given a thread "Scaling SQLite" exists
    And a persona "sol" exists
    And a posted reply from "sol" saying "Indexes help here"
    When the owner navigates to "/admin/comments?state=POSTED"
    Then the comments list has an entry for "sol"'s reply
    And the comments list entry for "sol"'s reply links to its thread

  Scenario: A drill-down with no matches shows an empty state
    When the owner navigates to "/admin/comments?state=FAILED"
    Then the comments list is empty

  # The ambient loop (plan_docs/ambient-slice-1.md) gets its own drill-down: the run log at
  # /admin/ambient (recent ticks + the manual-trigger button), linked from its /admin stat tile.
  Scenario: The ambient-runs statistic links to its drill-down
    When the owner visits the admin page
    Then the admin statistic "ambient-runs" links to "/admin/ambient"
