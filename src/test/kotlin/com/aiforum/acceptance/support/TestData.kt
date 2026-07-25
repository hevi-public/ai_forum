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

    fun insertThread(title: String): String {
        val id = newId()
        jdbc.update("INSERT INTO thread(id, title, created_at) VALUES (?,?,?)", id, title, clock.instant().toString())
        return id
    }

    /** Insert a comment node; returns its id. parentId null → top-level under the thread. */
    fun insertComment(
        threadId: String,
        authorId: String,
        body: String,
        parentId: String? = null,
        state: String = "POSTED",
        depth: Int = if (parentId == null) 0 else 1,
        depthBudget: Int = 0,
    ): String {
        val id = newId()
        jdbc.update(
            """INSERT INTO comment(id, thread_id, parent_id, author_id, body, state, depth, depth_budget, created_at)
               VALUES (?,?,?,?,?,?,?,?,?)""",
            id, threadId, parentId, authorId, body, state, depth, depthBudget, clock.instant().toString(),
        )
        return id
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
}
