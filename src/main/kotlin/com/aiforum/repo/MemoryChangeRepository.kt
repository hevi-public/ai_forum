package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * One audited memory write (V28 `memory_change`, plan_docs/persona-memory.md §2.2): the pass gave
 * [personaId] the record [body], possibly extending the record snapshotted in [parentBody], on the
 * evidence in [cited].
 *
 * [body] and [parentBody] are snapshots, not joins: the record may be reverted or owner-deleted and
 * the audit row must survive holding what was written (`memory_id` is a bare id with NO foreign key
 * — the cited/quoted_text pattern, V25/V27). [cited] is one line per cited engagement —
 * `commentId \t threadId \t snippet` — snapshotted prose plus bare ids, because `comment.body` is
 * mutable in place and evidence must not change under the record the owner is judging.
 *
 * [readAt] is the run's PRE-QUERY evidence-read instant (design A's graft): the value the member's
 * watermark was stamped with, carried onto every audit row so the read-instant contract (bed019fe)
 * is auditable per row rather than trusted. [revertedAt] is null until the owner reverts, and that
 * null IS the double-revert guard, enforced in SQL.
 */
data class MemoryChange(
    val id: Long,
    val personaId: String,
    val memoryId: String,
    val body: String,
    val parentBody: String?,
    val cited: String,
    val readAt: String,
    val changedAt: String,
    val revertedAt: String?,
)

/**
 * The append-only audit log for the Memory Scribe pass (plan_docs/persona-memory.md), shaped like
 * [InterestChangeRepository]: plain `JdbcTemplate` + injected [Clock], an AUTOINCREMENT id, every
 * read explicitly ordered. The pass auto-applies with no approval queue, so this table carries the
 * owner's whole control surface: [recent] renders /admin/memory, [find] + [markReverted] back the
 * revert button.
 *
 * **This table is NOT a window boundary**, and that is a deliberate difference from
 * [InterestChangeRepository.lastStandingChangeAt]: NOTHING is the designed steady state here and
 * writes no audit row, so an audit-derived window is exactly the V26 defect (§2.6). The per-member
 * watermark `persona.memory_judged_at` ([PersonaMemoryRepository.judgedAt]) is the only window, and
 * no method here feeds one.
 *
 * Note what this class deliberately does NOT offer: no count, no per-member tally, no aggregate of
 * any kind (§4 Stays-Cut — an audit table that can be summed is a memory-health score wearing an
 * auditor's badge). Rows are read, never reduced.
 */
@Repository
class MemoryChangeRepository(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {

    private val mapper = RowMapper { rs, _ ->
        MemoryChange(
            id = rs.getLong("id"),
            personaId = rs.getString("persona_id"),
            memoryId = rs.getString("memory_id"),
            body = rs.getString("body"),
            parentBody = rs.getString("parent_body"),
            cited = rs.getString("cited"),
            readAt = rs.getString("read_at"),
            changedAt = rs.getString("changed_at"),
            revertedAt = rs.getString("reverted_at"),
        )
    }

    private val columns =
        "id, persona_id, memory_id, body, parent_body, cited, read_at, changed_at, reverted_at"

    /**
     * Append one audited write and return its generated id, which the caller needs to build the
     * revert link. [readAt] is the run's pre-query read instant — passed in, never read from the
     * clock here, so the row records the instant the watermark was actually stamped with (§2.2).
     *
     * `@Transactional` is load-bearing rather than tidiness (the [InterestChangeRepository.record]
     * argument, verbatim): SQLite's `last_insert_rowid()` is scoped to the *connection*, and
     * prod/dev run a Hikari pool of 5 — a bare follow-up SELECT could be served by a different
     * connection and hand the owner's Revert button another writer's rowid. Inside a transaction
     * both statements bind to one connection; with PROPAGATION_REQUIRED it composes with the pass's
     * outer `TransactionTemplate` (audit row + insert + stamp as one unit — the S4a b3 defect,
     * where an audit row committing alone showed the owner a change that never happened).
     */
    @Transactional
    fun record(
        personaId: String,
        memoryId: String,
        body: String,
        parentBody: String?,
        cited: String,
        readAt: String,
    ): Long {
        jdbc.update(
            "INSERT INTO memory_change(persona_id, memory_id, body, parent_body, cited, read_at, changed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            personaId, memoryId, body, parentBody, cited, readAt, clock.instant().toString(),
        )
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long::class.java) ?: 0L
    }

    /**
     * The most recent audited writes, newest first — the /admin/memory log. The `id DESC` tiebreak
     * is not cosmetic: one run stamps every member it moved under a single injected-Clock instant,
     * so `changed_at` alone would leave a run's rows in undefined order and the page would
     * reshuffle between requests (the [InterestChangeRepository.recent] pair).
     */
    fun recent(limit: Int): List<MemoryChange> =
        jdbc.query(
            "SELECT $columns FROM memory_change ORDER BY changed_at DESC, id DESC LIMIT ?",
            mapper, limit,
        )

    /** One audited write by id — the revert path's lookup. Null when the id is unknown. */
    fun find(id: Long): MemoryChange? =
        jdbc.query("SELECT $columns FROM memory_change WHERE id = ?", mapper, id).firstOrNull()

    /**
     * Stamp [id] as reverted from the injected [Clock]. The `reverted_at IS NULL` guard makes a
     * second revert a genuine no-op at the storage layer rather than a caller convention:
     * re-stamping would move the row's record of WHEN the owner intervened to whenever the button
     * was last double-clicked. Unknown ids are a no-op too.
     */
    fun markReverted(id: Long) {
        jdbc.update(
            "UPDATE memory_change SET reverted_at = ? WHERE id = ? AND reverted_at IS NULL",
            clock.instant().toString(), id,
        )
    }
}
