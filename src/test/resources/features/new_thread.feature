Feature: New thread creation
  The owner starts a thread with a title and an opening question. Creating it immediately summons the
  room — a "Whole Topic + Anyone" call: the AI dispatcher reads the opening post (the whole topic) and
  picks who weighs in, then the chosen persona(s) reply. The opening question IS the topic, so it heads
  every summoned persona's context — otherwise the room answers a blank transcript (§2).

  This feature scopes to the owner-initiated flow specifically; ambient/persona-initiated thread
  creation is specified separately in ambient_tick.feature (plan_docs/ai-driven-forum-direction.md §10).

  Background:
    Given a persona "sol" exists
    And a persona "vex" exists

  Scenario: Creating a thread summons the room (Whole Topic + Anyone)
    # First scripted response is the dispatcher's pick; the second is the chosen persona's reply.
    Given the LLM will respond with "sol"
    And the LLM will respond with "Indexes are the trick"
    When the owner creates a thread "Why is SQLite fast?" asking "explain the design" of "sol, vex"
    Then the thread exists with title "Why is SQLite fast?"
    # The question is the OP's body, not chrome: it must render on the post and seed the room's context.
    # A regression that drops the opening text "on the way in" fails here.
    And the thread shows the opening post "explain the design"
    # "Anyone": the dispatcher reads the whole topic — title AND body — to decide who replies.
    And the dispatcher's context mentions "Why is SQLite fast?"
    And the dispatcher's context mentions "explain the design"
    # The chosen persona's reply lands on the thread.
    And the reply body contains "Indexes are the trick"

  # The opening question IS the topic, so the summoned persona must see it in context — otherwise the room
  # answers a blank transcript and emits a generic opener. Both the title and the body are injected as the
  # post node at the head of context (it is NOT a posted comment).
  Scenario: The opening post — title and body — seeds the room's context
    Given the LLM will respond with "sol"
    And the LLM will respond with "Indexes help here"
    When the owner creates a thread "Why is SQLite fast?" asking "explain the design" of "sol"
    Then the model context mentions "Why is SQLite fast?"
    And the model context mentions "explain the design"

  # The browser path: the home page's new-thread form posts form-urlencoded (not the JSON the API uses)
  # and the owner is redirected onto the fresh thread page. Pins the form binding the JSON scenario above
  # doesn't exercise (mirrors composer_submit for the generate endpoint), and that the form path summons
  # the room too.
  Scenario: Owner starts a thread from the browser form
    When the owner starts a thread titled "Scaling SQLite" from the browser
    Then the thread exists with title "Scaling SQLite"
    And the room was summoned

  # The new-thread form splits title from body: the body is the actual content of the post, rendered in
  # the thread's opening post (distinct from the room's replies in the comment tree). The dispatcher reads
  # that body to route, confirming the opening post reaches the room on the form path.
  Scenario: Owner starts a thread with a title and a body from the browser form
    When the owner starts a thread titled "Indexing strategy" with body "B-trees vs LSM — which fits our write pattern?" from the browser
    Then the thread exists with title "Indexing strategy"
    And the thread page shows the post body "B-trees vs LSM — which fits our write pattern?"
    And the dispatcher's context mentions "Indexing strategy"
    And the dispatcher's context mentions "B-trees vs LSM — which fits our write pattern?"
