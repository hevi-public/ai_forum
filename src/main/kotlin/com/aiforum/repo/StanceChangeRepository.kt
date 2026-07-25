package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * One audited stance rewrite (V25 `stance_change`, plan_docs/ambient-slice-4a.md D8).
 *
 * [oldStance]/[oldSource] are the pair the revert path restores — *both*, because `persona_stance.upsert`
 * overwrites text and provenance in one statement, and restoring only the text would relabel a seeded row
 * `evolved` (see [RelationStanceRepository.SOURCE_SEEDED]). [cited] is the snapshotted evidence the
 * judgment was made from — comment ids plus the prose as it read at the time, never a count of anything.
 *
 * [changedAt]/[revertedAt] stay raw ISO-8601 [String]s rather than `Instant`s for the same reason the rest
 * of persistence does it: the columns are TEXT under SQLite's dynamic typing, and callers only ever
 * display them or compare them lexicographically (which is chronological for ISO-8601 UTC stamps).
 * [revertedAt] is null until the owner reverts, and that null IS the double-revert guard.
 */
data class StanceChange(
    val id: Long,
    val fromPersona: String,
    val toPersona: String,
    val oldStance: String,
    val newStance: String,
    val oldSource: String,
    val cited: String,
    val changedAt: String,
    val revertedAt: String?,
)

/**
 * The append-only audit log for the stance evolution pass (plan_docs/ambient-slice-4a.md), shaped like
 * [AmbientRunRepository]: plain `JdbcTemplate` + injected [Clock] (no `Instant.now()`, so a fixed test
 * clock makes `changed_at` exactly assertable), an AUTOINCREMENT id, and every read explicitly ordered.
 *
 * Because S4a auto-applies with no approval queue, this log carries the owner's entire control surface:
 * [recent] renders /admin/stances, [find] + [markReverted] back the revert button, and [lastChangeAt] is
 * the *window boundary* for the next run — the pass judges only exchanges newer than the last recorded
 * change, so a quiet forum re-judges nothing and costs nothing.
 *
 * Note what this class deliberately does NOT offer: no count, no per-pair tally, no aggregate of any kind.
 * The V25 header explains why — an audit table that can be summed is a scoreboard, and a scoreboard is the
 * reward economy this design cut. Rows are read, never reduced.
 */
@Repository
class StanceChangeRepository(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {

    private val mapper = RowMapper { rs, _ ->
        StanceChange(
            id = rs.getLong("id"),
            fromPersona = rs.getString("from_persona"),
            toPersona = rs.getString("to_persona"),
            oldStance = rs.getString("old_stance"),
            newStance = rs.getString("new_stance"),
            oldSource = rs.getString("old_source"),
            cited = rs.getString("cited"),
            changedAt = rs.getString("changed_at"),
            revertedAt = rs.getString("reverted_at"),
        )
    }

    private val columns =
        "id, from_persona, to_persona, old_stance, new_stance, old_source, cited, changed_at, reverted_at"

    /**
     * Append one audited change and return its generated id, which the caller needs to build the revert
     * link. [oldSource] is the provenance the row carried *before* the evolution wrote `evolved` over it;
     * [cited] is the snapshotted evidence text (ids + prose), never a count.
     *
     * `@Transactional` is load-bearing rather than tidiness: SQLite's `last_insert_rowid()` is scoped to
     * the *connection*, and prod/dev run a Hikari pool of 5. A bare follow-up `SELECT last_insert_rowid()`
     * could therefore be served by a different connection and return another writer's rowid — or 0 on a
     * connection that has never inserted — silently attaching the owner's Revert button to the wrong row.
     * Inside a transaction both statements are bound to the same connection, so the id is the one we just
     * wrote. (This is also why the id is not read back by `WHERE changed_at = …`: the clock is coarse and
     * two edges of the same run share a stamp.)
     */
    @Transactional
    fun record(
        fromPersona: String,
        toPersona: String,
        oldStance: String,
        newStance: String,
        oldSource: String,
        cited: String,
    ): Long {
        jdbc.update(
            "INSERT INTO stance_change(from_persona, to_persona, old_stance, new_stance, old_source, cited, changed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            fromPersona, toPersona, oldStance, newStance, oldSource, cited, clock.instant().toString(),
        )
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long::class.java) ?: 0L
    }

    /**
     * The most recent audited changes, newest first — the /admin/stances list. The `id DESC` tiebreak is
     * not cosmetic: one evolution run writes every affected edge under a single injected-Clock instant, so
     * `changed_at` alone leaves a whole run's rows in undefined order and the page would reshuffle between
     * requests. Same ordering pair as [AmbientRunRepository.recent].
     */
    fun recent(limit: Int): List<StanceChange> =
        jdbc.query(
            "SELECT $columns FROM stance_change ORDER BY changed_at DESC, id DESC LIMIT ?",
            mapper, limit,
        )

    /** One audited change by id — the revert path's lookup. Null when the id is unknown. */
    fun find(id: Long): StanceChange? =
        jdbc.query("SELECT $columns FROM stance_change WHERE id = ?", mapper, id).firstOrNull()

    /**
     * Stamp [id] as reverted from the injected [Clock]. The `reverted_at IS NULL` guard makes a second
     * revert a genuine no-op at the storage layer rather than merely a caller convention: re-stamping
     * would move the audit row's revert time to whenever someone last double-clicked the button, and the
     * row would stop recording when the owner actually intervened. Unknown ids are a no-op too.
     */
    fun markReverted(id: Long) {
        jdbc.update(
            "UPDATE stance_change SET reverted_at = ? WHERE id = ? AND reverted_at IS NULL",
            clock.instant().toString(), id,
        )
    }

    /**
     * The newest recorded `changed_at`, or null when nothing has ever evolved — the evolution pass's
     * window boundary. Null deliberately means "all time": the first run reads the whole comment history
     * once, and every later run only sees what happened since it last changed something.
     *
     * Reverted rows still count. A revert undoes the *stance*, not the fact that the pass already read and
     * judged those exchanges; excluding them would make the next run re-judge the same conversation and
     * (given the same evidence) most likely re-apply the change the owner just rejected.
     */
    fun lastChangeAt(): String? =
        jdbc.queryForObject("SELECT MAX(changed_at) FROM stance_change", String::class.java)
}
