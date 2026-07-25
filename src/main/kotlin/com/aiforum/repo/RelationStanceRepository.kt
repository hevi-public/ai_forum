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
 *
 * [judgedAt] (V26) is the evolution pass's per-edge watermark: when this pair was last *looked at* by the
 * judge, whatever the judge concluded. It is null for an edge that has never been judged, which reads as
 * "judge it over all of its history". It is separate from [updatedAt] because the two answer different
 * questions — `updated_at` says when the text last CHANGED, and the majority of judgments deliberately
 * change nothing (the judge is told to repeat a standing view when the exchanges do not move it). Tracking
 * only changes is what made a settled pair re-buy the same judgment on every run; see the V26 header.
 * A timestamp, never a tally — the no-numbers guardrail covers this table's new column too.
 */
data class Stance(
    val fromPersona: String,
    val toPersona: String,
    val stance: String,
    val source: String,
    val updatedAt: String,
    val judgedAt: String? = null,
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
 * - **Authoring a stance and judging an edge are separate writes.** [upsert] owns the prose and its
 *   provenance; [markJudged] owns the V26 `judged_at` watermark, and neither statement writes the other's
 *   columns. Merging them would make an owner's edit — or a revert — masquerade as a fresh judgment.
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
            judgedAt = rs.getString("judged_at"),
        )
    }

    /** What every read selects, in [mapper] order. */
    private val columns = "from_persona, to_persona, stance, source, updated_at, judged_at"

    /**
     * What an AUTHORED write owns — deliberately NOT [columns]. `judged_at` is missing from this list and
     * must stay missing: see [upsert] for what breaks if a future tidy-up merges the two.
     */
    private val writeColumns = "from_persona, to_persona, stance, source, updated_at"

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
     *
     * **This statement must never touch `judged_at`** — which is why it writes [writeColumns] rather than
     * the [columns] every read uses, and why the `DO UPDATE SET` list stays three lines long. `judged_at`
     * (V26) records when the evolution pass last *looked at* this edge, and only [markJudged] may move it.
     * Folding it into the upsert is exactly the kind of symmetry a later refactor would call a tidy-up, and
     * it would break two things quietly: an owner editing a stance on the admin form would stamp the edge
     * as freshly judged, muting it until brand-new exchanges appear (the owner asked for different words,
     * not for the judge's opinion); and the revert path, which upserts the old text back, would re-stamp
     * the very watermark the revert is meant to clear, so the edge could never be reconsidered from the
     * evidence the owner just rejected (D10 — revert undoes, it does not freeze). An INSERT of a brand-new
     * edge leaves `judged_at` NULL by the column's own nullability, which is the correct never-judged read.
     */
    fun upsert(from: String, to: String, stance: String, source: String) {
        jdbc.update(
            """INSERT INTO persona_stance($writeColumns) VALUES (?,?,?,?,?)
               ON CONFLICT(from_persona, to_persona) DO UPDATE SET
                   stance     = excluded.stance,
                   source     = excluded.source,
                   updated_at = excluded.updated_at""",
            from, to, stance, source, clock.instant().toString(),
        )
    }

    /**
     * Move the per-edge judgment watermark for [from] -> [to] to [at], or clear it when [at] is null.
     *
     * This is the fix for the defect the audit table could not solve: `stance_change` records only what
     * CHANGED, but the judge is instructed to repeat a standing view unchanged when the exchanges do not
     * move it, so a settled pair produced no audit row, never advanced its window, and re-bought the same
     * LLM judgment on every run — forever, and per edge. The caller therefore stamps this on any *usable*
     * verdict, changed or unchanged, and deliberately leaves it alone after a rejected answer or a seam
     * failure, both of which left the evidence genuinely unjudged and deserve another look.
     *
     * **[at] is passed in rather than read from the injected [Clock] here**, unlike [upsert]'s
     * `updated_at`, and that is load-bearing: the watermark must be the instant the evidence window was
     * *read*, not the later instant the row was written. A clock read inside this method would sit after
     * the judgment call — seconds or minutes of LLM latency later — and any comment posted in that gap
     * would fall behind the watermark without ever having been judged. Vanishingly rare, permanently
     * invisible, and exactly the class of bug the per-edge window exists to prevent.
     *
     * Null clears rather than being ignored, so a caller that must put an edge back to never-judged (the
     * revert path's "this edge is open to that evidence again") has a way to say so.
     *
     * Unknown pairs are a no-op: an edge with no stance row has nothing to evolve, so there is nothing to
     * stamp. The value stays a raw ISO-8601 [String] for the same reason [Stance.updatedAt] does.
     */
    fun markJudged(from: String, to: String, at: String?) {
        jdbc.update(
            "UPDATE persona_stance SET judged_at = ? WHERE from_persona = ? AND to_persona = ?",
            at, from, to,
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
