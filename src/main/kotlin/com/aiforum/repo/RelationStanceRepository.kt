package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.Clock

/**
 * One directed persona->persona stance (V24 `persona_stance`). [stance] is FREE TEXT — a sentence the
 * prompt renderer drops into prose — and is deliberately never a number: see the migration header on why
 * a scored relation re-imports the cut reward economy. [source] is provenance only
 * ([RelationStanceRepository.SOURCE_SEEDED] / [RelationStanceRepository.SOURCE_OWNER] /
 * [RelationStanceRepository.SOURCE_EVOLVED]); nothing reads it yet.
 *
 * [updatedAt] stays a raw ISO-8601 [String] rather than an `Instant` for the same reason the other
 * repositories do it: the column is TEXT under SQLite's dynamic typing, and callers here only ever
 * display or compare it verbatim.
 */
data class Stance(
    val fromPersona: String,
    val toPersona: String,
    val stance: String,
    val source: String,
    val updatedAt: String,
)

/**
 * The qualitative relation graph (plan_docs/ambient-slice-3.md): what each persona thinks of each other
 * persona, in prose, for injection into generation prompts. Shaped like [QuoteRepository] — plain
 * `JdbcTemplate` + injected [Clock] (no `Instant.now()`, so a fixed test clock makes `updated_at`
 * assertable).
 *
 * Two properties callers depend on:
 * - **Every read is explicitly ordered.** Prompt text must be byte-stable across runs or an unrelated
 *   row insertion silently rewrites a prompt (and invalidates any prompt caching); rowid order would
 *   drift with edits, so the ORDER BY is on the key columns.
 * - **There is no `upsertAll`/merge that could combine stances.** A stance is replaced wholesale by its
 *   author; stances are never blended, accumulated, or aggregated — aggregation is the road back to a
 *   score.
 */
@Repository
class RelationStanceRepository(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {

    private val mapper = RowMapper { rs, _ ->
        Stance(
            fromPersona = rs.getString("from_persona"),
            toPersona = rs.getString("to_persona"),
            stance = rs.getString("stance"),
            source = rs.getString("source"),
            updatedAt = rs.getString("updated_at"),
        )
    }

    private val columns = "from_persona, to_persona, stance, source, updated_at"

    /**
     * Write the stance [from] holds about [to], replacing any existing one for that ordered pair. Upsert
     * rather than insert-or-update-by-query because the edge is identified by its (from, to) PRIMARY KEY,
     * so re-authoring is the normal case, not the exception: seeding runs on every startup, and the owner
     * edits an existing edge far more often than they create one. `ON CONFLICT … DO UPDATE` makes that a
     * single statement — no read-then-write race, and no reliance on a caught constraint violation.
     *
     * [source] overwrites the stored provenance deliberately: an owner edit over a seeded row must leave
     * the row marked owner-authored, which is the whole point of keeping the column (a later evolving
     * pass reads it to know which rows it may rewrite).
     *
     * Two inputs are rejected by the V24 CHECKs as a thrown `DataAccessException`, never a silent no-op:
     * a self-stance (`from == to`), and any [source] outside the three constants below.
     */
    fun upsert(from: String, to: String, stance: String, source: String) {
        jdbc.update(
            """INSERT INTO persona_stance($columns) VALUES (?,?,?,?,?)
               ON CONFLICT(from_persona, to_persona) DO UPDATE SET
                   stance     = excluded.stance,
                   source     = excluded.source,
                   updated_at = excluded.updated_at""",
            from, to, stance, source, clock.instant().toString(),
        )
    }

    /** The stance [from] holds about [to], or null if that persona has never formed one. */
    fun find(from: String, to: String): Stance? =
        jdbc.query(
            "SELECT $columns FROM persona_stance WHERE from_persona = ? AND to_persona = ?",
            mapper, from, to,
        ).firstOrNull()

    /**
     * Everything [fromId] thinks about everyone else — the outgoing edges, which is exactly the slice the
     * prompt renderer needs when generating AS that persona (a persona's prompt carries their own views,
     * never the room's views of them). Ordered by `to_persona` so the rendered prose is stable.
     */
    fun from(fromId: String): List<Stance> =
        jdbc.query(
            "SELECT $columns FROM persona_stance WHERE from_persona = ? ORDER BY to_persona",
            mapper, fromId,
        )

    /** The whole relation graph, ordered by (from, to) — for the admin overview and for seeding checks. */
    fun findAll(): List<Stance> =
        jdbc.query("SELECT $columns FROM persona_stance ORDER BY from_persona, to_persona", mapper)

    /**
     * Drop the single edge [from] -> [to] (the owner retracting a view). One direction only: the reverse
     * edge is an independent opinion held by someone else and must survive. No-op if it doesn't exist.
     */
    fun delete(from: String, to: String) {
        jdbc.update("DELETE FROM persona_stance WHERE from_persona = ? AND to_persona = ?", from, to)
    }

    companion object {
        /** Written by the startup seeder from the hand-authored persona config — safe for a later pass to replace. */
        const val SOURCE_SEEDED = "seeded"

        /** Hand-authored by the owner; the provenance a future auto-evolving pass must not overwrite. */
        const val SOURCE_OWNER = "owner"

        /** Derived by the system from observed conversation (no writer yet — the column is here so it can exist later). */
        const val SOURCE_EVOLVED = "evolved"
    }
}
