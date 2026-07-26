package com.aiforum.repo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * One audited interest swap (V27 `interest_change`, plan_docs/ambient-slice-4b.md D6): the member set
 * [dropped] down and took [takenUp] up, on the evidence in [cited].
 *
 * [dropped] and [takenUp] are stored rather than derived from the two interest sets. They are the row's
 * headline on /admin/interests ("set down *typography*, took up *release engineering*"), and a template that
 * had to reconstruct a diff is a template no test can reach — plus the old phrase exists nowhere else once
 * the swap has been written, so a change not captured here is UNREVERTABLE.
 *
 * [droppedSource] is what a revert RESTORES, not decoration: put the text back without it and a seeded
 * phrase comes home labelled `drifted`, so the next pass reads a lie about who wrote it
 * ([PersonaInterestRepository.SOURCE_SEEDED]).
 *
 * [cited] is the snapshotted evidence — one line per engagement, comment/thread ids plus the prose as it
 * read at the time — never a count of anything. `comment.body` is mutable in place, so citing by id alone
 * would let the evidence change under the record the owner is judging.
 *
 * [changedAt]/[revertedAt] stay raw ISO-8601 [String]s rather than `Instant`s for the reason the rest of
 * persistence does it: the columns are TEXT under SQLite's dynamic typing, and callers only display them or
 * compare them lexicographically (which is chronological for ISO-8601 UTC stamps). [revertedAt] is null
 * until the owner reverts, and that null IS the double-revert guard.
 */
data class InterestChange(
    val id: Long,
    val personaId: String,
    val dropped: String,
    val droppedSource: String,
    val takenUp: String,
    val cited: String,
    val changedAt: String,
    val revertedAt: String?,
)

/**
 * The append-only audit log for the interest drift pass (plan_docs/ambient-slice-4b.md), shaped like
 * [StanceChangeRepository]: plain `JdbcTemplate` + injected [Clock] (no `Instant.now()`, so a fixed test
 * clock makes `changed_at` exactly assertable), an AUTOINCREMENT id, and every read explicitly ordered.
 *
 * The pass auto-applies with no approval queue, so this table carries the owner's whole control surface:
 * [recent] renders /admin/interests, [find] + [markReverted] back the revert button, and
 * [lastStandingChangeAt] is *one half* of the next run's window boundary.
 *
 * **Only half, and the other half is not optional.** This table gets a row only when an interest actually
 * MOVED, and moving is the minority outcome — the judge is told to answer NONE when the member's own words
 * do not pull them anywhere, and for most members most weeks that is the true answer. A window read from
 * this table alone would therefore never advance for a settled member, the same engagements would re-qualify
 * on every run, and that member would buy another LLM judgment nightly, forever. The per-member watermark in
 * `persona.interests_judged_at` ([PersonaInterestRepository.markJudged]) is what closes that hole; it is
 * stamped on any usable verdict, moved or not. "A quiet forum re-judges nothing and costs nothing" is a
 * promise the two keep together: this table keeps it for a member who has drifted at least once, the
 * watermark for every member who never has. That split is V26's post-review lesson, applied here from the
 * first commit rather than after one.
 *
 * Note what this class deliberately does NOT offer: no count, no per-member tally, no aggregate of any kind
 * (the sole `MAX` is over timestamps, and a moment is not a magnitude). The V27 header explains why — an
 * audit table that can be summed is a scoreboard wearing an auditor's badge, and "how much has this member
 * drifted" is exactly the number the design cut. Rows are read, never reduced. The room map on
 * /admin/interests takes its material from [PersonaInterestRepository.sharedInterests] for the same reason.
 */
@Repository
class InterestChangeRepository(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {

    private val mapper = RowMapper { rs, _ ->
        InterestChange(
            id = rs.getLong("id"),
            personaId = rs.getString("persona_id"),
            dropped = rs.getString("dropped"),
            droppedSource = rs.getString("dropped_source"),
            takenUp = rs.getString("taken_up"),
            cited = rs.getString("cited"),
            changedAt = rs.getString("changed_at"),
            revertedAt = rs.getString("reverted_at"),
        )
    }

    private val columns =
        "id, persona_id, dropped, dropped_source, taken_up, cited, changed_at, reverted_at"

    /**
     * Append one audited swap and return its generated id, which the caller needs to build the revert link.
     * [droppedSource] is the provenance the dropped phrase carried *before* the swap; [cited] is the
     * snapshotted evidence text (ids + prose), never a count.
     *
     * `@Transactional` is load-bearing rather than tidiness: SQLite's `last_insert_rowid()` is scoped to the
     * *connection*, and prod/dev run a Hikari pool of 5. A bare follow-up `SELECT last_insert_rowid()` could
     * therefore be served by a different connection and return another writer's rowid — or 0 on a connection
     * that has never inserted — silently attaching the owner's Revert button to the wrong row. Inside a
     * transaction both statements are bound to the same connection, so the id is the one we just wrote.
     * (This is also why the id is not read back by `WHERE changed_at = …`: the clock is coarse and two
     * members judged in the same run share a stamp.)
     *
     * It composes rather than competes with D6's outer `TransactionTemplate`: the drift write is one unit of
     * four statements — record, delete, insert, stamp — and this method joining the caller's transaction
     * (PROPAGATION_REQUIRED) is what keeps S4a's b3 defect out, where an audit row committed alone showed
     * the owner a change that never happened *and* became the window boundary.
     */
    @Transactional
    fun record(
        personaId: String,
        dropped: String,
        droppedSource: String,
        takenUp: String,
        cited: String,
    ): Long {
        jdbc.update(
            "INSERT INTO interest_change(persona_id, dropped, dropped_source, taken_up, cited, changed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            personaId, dropped, droppedSource, takenUp, cited, clock.instant().toString(),
        )
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long::class.java) ?: 0L
    }

    /**
     * The most recent audited swaps, newest first — the /admin/interests list, which is also the second and
     * stronger half of the convergence readout: a chronological list of every TAKE in the room is legible to
     * a reader with no metric involved (D12).
     *
     * The `id DESC` tiebreak is not cosmetic: one run writes every member it moved under a single
     * injected-Clock instant, so `changed_at` alone leaves a whole run's rows in undefined order and the page
     * would reshuffle between requests. Same ordering pair as [StanceChangeRepository.recent].
     */
    fun recent(limit: Int): List<InterestChange> =
        jdbc.query(
            "SELECT $columns FROM interest_change ORDER BY changed_at DESC, id DESC LIMIT ?",
            mapper, limit,
        )

    /** One audited swap by id — the revert path's lookup. Null when the id is unknown. */
    fun find(id: Long): InterestChange? =
        jdbc.query("SELECT $columns FROM interest_change WHERE id = ?", mapper, id).firstOrNull()

    /**
     * Stamp [id] as reverted from the injected [Clock]. The `reverted_at IS NULL` guard makes a second revert
     * a genuine no-op at the storage layer rather than merely a caller convention: re-stamping would move the
     * audit row's revert time to whenever someone last double-clicked the button, and the row would stop
     * recording when the owner actually intervened. It is also what stops the second revert re-restoring a
     * phrase that is already back, which would cost the member the interest it has since taken up. Unknown
     * ids are a no-op too.
     */
    fun markReverted(id: Long) {
        jdbc.update(
            "UPDATE interest_change SET reverted_at = ? WHERE id = ? AND reverted_at IS NULL",
            clock.instant().toString(), id,
        )
    }

    /**
     * The window boundary for ONE member: when their interests last actually moved and stayed moved, or null
     * if they never have — in which case this table imposes no boundary at all and the member's
     * `persona.interests_judged_at` watermark is what bounds them. A member with neither is judged over all
     * of their history, once.
     *
     * **Per member, not one global watermark**, and the difference is not academic. A single boundary that
     * advances whenever *anyone* drifts silently disinherits every other member in the same run: the
     * judgment that rate-limited at 04:00, the member the per-run cap did not reach, the one whose answer
     * came back with a digit in it — all would find their evidence sitting behind a boundary moved by
     * somebody else's success, and would never be judged on those engagements again.
     *
     * `reverted_at IS NULL` for the same reason it appears on the read path, and it is why the revert path
     * calls this *after* [markReverted] (D10): a revert gives up the change's claim on the window too, or the
     * engagements behind a judgment the owner rejected are walled off for good and that member can never be
     * reconsidered from them. Revert undoes; it does not freeze — freezing is what pinning is for.
     */
    fun lastStandingChangeAt(personaId: String): String? =
        jdbc.queryForObject(
            "SELECT MAX(changed_at) FROM interest_change WHERE persona_id = ? AND reverted_at IS NULL",
            String::class.java, personaId,
        )
}
