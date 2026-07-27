package com.aiforum.acceptance.support

import com.aiforum.repo.PersonaRepository
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/**
 * Seeds preconditions directly into the real test SQLite DB (decision #3) so `Given` steps are
 * deterministic and independent of the create endpoints. `When` steps then drive the HTTP API.
 */
@Component
@Profile("test")
class TestData(private val jdbc: JdbcTemplate, private val clock: Clock) {

    fun newId(): String = UUID.randomUUID().toString()

    fun insertPersona(
        id: String,
        name: String,
        systemPrompt: String = "You are $name.",
        abilities: List<String> = emptyList(),
        dials: Map<String, Int> = emptyMap(),
    ) {
        // Mirror the repo: assign the next free colour slot so seeded personas get distinct avatars.
        val colorIndex = jdbc.queryForObject("SELECT COALESCE(MAX(color_index), -1) + 1 FROM persona", Int::class.java) ?: 0
        jdbc.update(
            "INSERT INTO persona(id, name, handle, descriptor, system_prompt, signature, slug, color_index, abilities, dials) VALUES (?,?,?,?,?,?,?,?,?,?)",
            id, name, name.lowercase(), "$name descriptor", systemPrompt, "— $name", PersonaRepository.slugFor(name), colorIndex,
            abilities.joinToString(prefix = "[", postfix = "]") { "\"$it\"" },
            dials.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":${it.value}" },
        )
    }

    /**
     * Seed one directed persona→persona stance edge (persona_stance, V24) the same way [insertPersona]
     * seeds a member: straight INSERT, so a `Given` never has to drive the edit form to establish a
     * relation. [source] records provenance ('seeded' | 'owner' | 'evolved'); [stance] is free text and
     * must stay that way — no number ever stands in for a relationship (plan_docs/ambient-slice-3.md §1).
     * Note the guardrail the firewall scan imposes on stance text: it is injected into
     * personaSystemPrompt, which owner_controls_firewall greps for "+1"/"vote", so no stance string
     * (here or in the features) may contain that substring — "devoted"/"pivoted" included.
     */
    fun insertStance(from: String, to: String, stance: String, source: String = "seeded") {
        jdbc.update(
            "INSERT INTO persona_stance(from_persona, to_persona, stance, source, updated_at) VALUES (?,?,?,?,?)",
            from, to, stance, source, clock.instant().toString(),
        )
    }

    /**
     * The instant a seeded row is stamped with: the fixed test [clock], minus [agoSeconds].
     *
     * The test clock is FIXED, so without an age every seeded row shares one `created_at` and any
     * "newest" or ordering assertion is really reading an arbitrary UUID tie-break — green today, red
     * tomorrow on an unrelated id change, and reported as a regression in whatever slice happens to be
     * open (plan_docs/ambient-slice-6.md §8). Ages are per call rather than a global monotonic stagger
     * on purpose (D9): a stagger would move the unread boundary under every scenario that seeds a
     * comment and then reads it, which is a change to fixtures this slice does not own.
     */
    private fun stampedAt(agoSeconds: Long): String = clock.instant().minusSeconds(agoSeconds).toString()

    /**
     * Seed a thread; returns its id. [authorId] is the OP's attribution (V20) — null is an owner-authored
     * thread, a persona id is one the ambient loop opened — and [body] is the opening post.
     *
     * All three extra parameters are DEFAULTED to what the original one-argument helper wrote (owner, no
     * body, stamped now), so every existing call site keeps its exact meaning.
     */
    fun insertThread(title: String, authorId: String? = null, body: String = "", agoSeconds: Long = 0): String {
        val id = newId()
        jdbc.update(
            "INSERT INTO thread(id, title, body, author_id, created_at) VALUES (?,?,?,?,?)",
            id, title, body, authorId, stampedAt(agoSeconds),
        )
        return id
    }

    /** Insert a comment node; returns its id. parentId null → top-level under the thread.
     *  [agoSeconds] backdates `created_at` off the fixed clock — see [stampedAt] for why an ordering
     *  fixture must set it rather than let three rows share one instant. */
    fun insertComment(
        threadId: String,
        authorId: String,
        body: String,
        parentId: String? = null,
        state: String = "POSTED",
        depth: Int = if (parentId == null) 0 else 1,
        depthBudget: Int = 0,
        agoSeconds: Long = 0,
    ): String {
        val id = newId()
        jdbc.update(
            """INSERT INTO comment(id, thread_id, parent_id, author_id, body, state, depth, depth_budget, created_at)
               VALUES (?,?,?,?,?,?,?,?,?)""",
            id, threadId, parentId, authorId, body, state, depth, depthBudget, stampedAt(agoSeconds),
        )
        return id
    }

    /**
     * The owner's read marker for a thread (V2 `thread_read`), planted [agoSeconds] before the fixed
     * clock's now — a straight INSERT, for the same reason [setFeedView] is one.
     *
     * [com.aiforum.repo.ThreadReadRepository.markRead] cannot serve here: it stamps the clock's NOW, and
     * the clock is FIXED, so every reply the same scenario seeds shares that instant and
     * `created_at > last_read_at` is false for all of it. A scenario needing a reply on the UNREAD side
     * of a marker therefore has no choice but an explicitly aged marker with its replies aged around it
     * (plan_docs/ambient-slice-6.md §8; the Tier-1 `markReadAgo` helper is the same shape one tier down).
     *
     * A plain INSERT with no upsert: the reset hook wipes `thread_read` before every scenario, and a
     * scenario that marks one thread read twice has stopped saying what it means.
     */
    fun markReadAgo(threadId: String, agoSeconds: Long) {
        jdbc.update(
            "INSERT INTO thread_read(thread_id, last_read_at) VALUES (?,?)",
            threadId, stampedAt(agoSeconds),
        )
    }

    /**
     * Seed the owner's persisted front-page view (V29 `owner_pref`, plan_docs/ambient-slice-6.md §2.3) —
     * a straight INSERT, the way [insertStance] seeds a relation, so a `Given` that needs the activity
     * view can establish it without driving the toggle it may be the scenario's job to test.
     *
     * Takes the raw slug rather than `FeedView`, because a fixture should speak the Gherkin's own word.
     * That is not a hole in the enum guard: the guard exists on the path the app writes through
     * (`OwnerPrefRepository.setFeedView`), and this helper is test-only code that already bypasses every
     * production door by design. V29's CHECK still refuses a slug that names no view, here as anywhere.
     *
     * A plain INSERT with no upsert: the reset hook wipes `owner_pref` before every scenario, so there is
     * never a row to collide with — and a scenario seeding the view twice is one that has stopped saying
     * what it means.
     */
    fun setFeedView(slug: String) {
        jdbc.update(
            "INSERT INTO owner_pref(id, feed_view, updated_at) VALUES (1,?,?)",
            slug, clock.instant().toString(),
        )
    }

    /**
     * S4b: an interest row, written directly. [source] is `seeded` for an open interest the pass may set
     * down and `owner` for one the owner pinned — the per-interest provenance that makes the immutable
     * core per-persona rather than global.
     *
     * Direct SQL rather than the persona edit form on purpose: that form composes a prompt, so a
     * form-driven Given would buy an LLM call and every `no LLM call was made` scenario in the drift
     * feature would assert against a spy that had already seen one.
     */
    fun insertInterest(personaId: String, interest: String, source: String = "seeded") {
        jdbc.update(
            "INSERT INTO persona_interest(persona_id, interest, source, updated_at) VALUES (?,?,?,?)",
            personaId, interest, source, clock.instant().toString(),
        )
    }

    /** Every interest a member holds, for the steps that need the room's state rather than one page. */
    fun interestsOf(personaId: String): List<String> =
        jdbc.queryForList(
            "SELECT interest FROM persona_interest WHERE persona_id = ? ORDER BY interest",
            String::class.java, personaId,
        ).filterNotNull()

    /**
     * Persona memory (V28, plan_docs/persona-memory.md): one memory record — or, with
     * [kind] = `root`, the owner-only root — written directly, the same way [insertInterest] seeds an
     * interest: straight INSERT, so a `Given` never buys an LLM call and never depends on the owner
     * surface it may be specifying. [source] defaults to `owner` because every seeded row models
     * something the owner typed (the scribe's rows are what the PASS writes, and scenarios that need
     * one run the pass for real). Returns the new row's id.
     *
     * `created_at` is staggered by the member's existing row count: the test [clock] is FIXED, so two
     * rows inserted back to back would otherwise share one instant and leave "newest-first" — the
     * retrieval cap ordering AND the scribe's letter labelling — to a random UUID tiebreak. With the
     * stagger, seeding order IS age order: the first memory a scenario seeds is the member's oldest.
     *
     * FIXTURE HYGIENE, the S4b stance warning one slice on (plan doc §4): no memory body — and no
     * scripted scribe answer — may contain the substring `vote` (the firewall's noVoteSignal greps
     * substrings, so "devoted"/"pivoted"/"voting" all trip it), and none may contain another member's
     * name (the prompt-spy steps select calls by `persona.name` and scan prompt text, so a name inside
     * a body poisons name-filtered selection and cross-member absence assertions).
     */
    fun insertMemory(
        personaId: String,
        body: String,
        source: String = "owner",
        parentId: String? = null,
        kind: String = "record",
    ): String {
        val existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM persona_memory WHERE persona_id = ?", Int::class.java, personaId,
        ) ?: 0
        val id = newId()
        jdbc.update(
            "INSERT INTO persona_memory(id, persona_id, parent_id, kind, body, source, created_at) VALUES (?,?,?,?,?,?,?)",
            id, personaId, parentId, kind, body, source,
            clock.instant().plusSeconds(existing.toLong()).toString(),
        )
        return id
    }

    /** The id of the one memory row holding exactly [body] — for steps that act on a record by its
     *  prose (the way the owner recognises it) but drive an endpoint that speaks ids. */
    fun memoryIdOf(personaId: String, body: String): String =
        jdbc.queryForList(
            "SELECT id FROM persona_memory WHERE persona_id = ? AND body = ?",
            String::class.java, personaId, body,
        ).singleOrNull() ?: error("expected exactly one persona_memory row for \"$body\" of $personaId")
}
