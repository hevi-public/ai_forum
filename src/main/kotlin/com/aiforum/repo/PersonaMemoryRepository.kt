package com.aiforum.repo

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * One node of a persona's private memory tree (V28 `persona_memory`, plan_docs/persona-memory.md
 * §2.2): a prose memory record, or — with [kind] = `root` — the member's optional, owner-only §6.3
 * root (motivation, background, identity; storage only this slice, injected never).
 *
 * [parentId] is the associative link: this record extends one earlier record OF THE SAME PERSONA.
 * The composite same-persona FK makes a cross-persona link unrepresentable in DDL, and the
 * parent-candidate rule (§2.2: parents are `kind='record'`, everywhere) is enforced at this
 * repository as the one site SQLite cannot express.
 *
 * [createdAt] stays a raw ISO-8601 [String] for the house reason: the column is TEXT under SQLite's
 * dynamic typing, and callers only display it or order by it (lexicographic == chronological for
 * UTC stamps). Note what this row deliberately has NO room for — no salience, no recall count, no
 * strength. A magnitude attached to what a member remembers is comparable, therefore rankable,
 * therefore optimisable (V28 header, the fifth slice running).
 */
data class PersonaMemory(
    val id: String,
    val personaId: String,
    val parentId: String?,
    val kind: String,
    val body: String,
    val source: String,
    val createdAt: String,
)

/**
 * The private, per-persona memory tree (plan_docs/persona-memory.md). Shaped like
 * [PersonaInterestRepository] — plain `JdbcTemplate` + injected [Clock] (no `Instant.now()`, so a
 * fixed test clock makes `created_at` assertable) — with three postures of its own:
 *
 * - **Provenance by construction.** There is no `insert(row)` taking a caller-chosen `source` or
 *   `kind`: [insertScribeRecord] hard-codes `scribe`+`record`, [insertOwnerRecord] hard-codes
 *   `owner`+`record`, [insertRoot] hard-codes `root`+`owner` and has no parent parameter at all.
 *   The pass cannot write `owner`, cannot write a root, and cannot relabel anything, because no
 *   method shape exists to say it with (§2.2's "unbackfillable at birth", third occurrence).
 * - **The door stores its argument VERBATIM.** Callers validate with `MemoryText` — whose
 *   fixed-point refusal guarantees the validated string IS the stored string — and this class never
 *   re-cleans (the S4b defect class: cleaning at two sites is how a value compares as one string
 *   and stores as another, 4b §10.3 item 3). Insert-only for records: no update path exists, so a
 *   stored body can never drift from its audit snapshot.
 * - **Every read is explicitly ordered** (`created_at DESC, id` — newest first, id tiebreak,
 *   because the fixed test clock and a single pass run both produce same-instant rows). These rows
 *   feed a generation prompt and the scribe's letter list; rowid order would reshuffle both.
 *
 * The per-member record ceiling (`MAX_SCRIBE_MEMORIES`, §2.11) is deliberately NOT enforced here —
 * SQLite cannot express "at most N rows per persona" in a CHECK, and no count is offered by this
 * class (no aggregate of any kind, §4 Stays-Cut): the pass counts the rows it already loaded.
 */
@Repository
class PersonaMemoryRepository(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(PersonaMemoryRepository::class.java)

    private val mapper = RowMapper { rs, _ ->
        PersonaMemory(
            id = rs.getString("id"),
            personaId = rs.getString("persona_id"),
            parentId = rs.getString("parent_id"),
            kind = rs.getString("kind"),
            body = rs.getString("body"),
            source = rs.getString("source"),
            createdAt = rs.getString("created_at"),
        )
    }

    private val columns = "id, persona_id, parent_id, kind, body, source, created_at"

    /**
     * Every memory RECORD [personaId] holds, newest first with an id tiebreak. `kind = 'record'` in
     * the SQL is the first of the parent-candidate rule's sites (§2.2): every consumer of this list —
     * retrieval, the scribe's letter list, the profile's parent picker — gets a record-only world,
     * so the root cannot ride into any of them by way of a lazy caller.
     */
    fun recordsOf(personaId: String): List<PersonaMemory> =
        jdbc.query(
            "SELECT $columns FROM persona_memory WHERE persona_id = ? AND kind = ? " +
                "ORDER BY created_at DESC, id",
            mapper, personaId, KIND_RECORD,
        )

    /** The member's §6.3 root, or null — most members most of the time (born absent, §2.3). */
    fun rootOf(personaId: String): PersonaMemory? =
        jdbc.query(
            "SELECT $columns FROM persona_memory WHERE persona_id = ? AND kind = ?",
            mapper, personaId, KIND_ROOT,
        ).firstOrNull()

    /** One node by id — the delete/revert paths' action-site re-read. Null when already gone. */
    fun find(id: String): PersonaMemory? =
        jdbc.query("SELECT $columns FROM persona_memory WHERE id = ?", mapper, id).firstOrNull()

    /**
     * The pass's one write door: hard-codes `source='scribe'`, `kind='record'` — provenance at
     * birth, no parameter to say otherwise. [body] is stored VERBATIM; the caller has already
     * validated it with `MemoryText` (whose fixed-point refusal is what makes verbatim safe), and
     * V28's scoped CHECK (1..300 code points on scribe rows) is the database's backstop, thrown as
     * a `DataAccessException` rather than silently truncated.
     */
    fun insertScribeRecord(
        personaId: String,
        body: String,
        parentId: String?,
        id: String = UUID.randomUUID().toString(),
    ): String = insertRecord(id, personaId, parentId, body, SOURCE_SCRIBE)

    /** The owner's write door: `source='owner'`, `kind='record'`. Same verbatim-storage contract;
     *  the owner path is exempt from the scoped length CHECK by scoping (§2.2), so an over-long
     *  body is refused politely by `MemoryText.validate` at the form, never by SQL mid-write. */
    fun insertOwnerRecord(
        personaId: String,
        body: String,
        parentId: String?,
        id: String = UUID.randomUUID().toString(),
    ): String = insertRecord(id, personaId, parentId, body, SOURCE_OWNER)

    /**
     * The §6.3 root, owner-only in DDL and in method shape: `kind='root'`, `source='owner'`, no
     * parent parameter (the root has no parent, V28 CHECK). At most one per member — a second
     * insert trips the partial unique index as a `DataAccessException`. The scribe service must
     * hold no reachable path to this method (I3; the Tier-2 failing fake pins it).
     */
    fun insertRoot(personaId: String, body: String, id: String = UUID.randomUUID().toString()): String {
        jdbc.update(
            "INSERT INTO persona_memory($columns) VALUES (?,?,?,?,?,?,?)",
            id, personaId, null, KIND_ROOT, body, SOURCE_OWNER, clock.instant().toString(),
        )
        return id
    }

    /**
     * Delete one record, reparenting its children to their grandparent first (top-level at worst) —
     * §2.10's chain-preserving discipline in one transaction. The composite FK's CASCADE must never
     * fire on this path; it is the persona-cascade backstop only. `@Transactional` joins a caller's
     * transaction (the revert path wraps this plus `markReverted` in one unit) or opens its own for
     * the profile's plain delete. No-op when [id] is already gone: the action-site re-read is the
     * caller's job, and a vanished row has no children to lose.
     */
    @Transactional
    fun deleteRecord(id: String) {
        val row = find(id) ?: return
        jdbc.update("UPDATE persona_memory SET parent_id = ? WHERE parent_id = ?", row.parentId, id)
        jdbc.update("DELETE FROM persona_memory WHERE id = ?", id)
    }

    /**
     * When the scribe pass last LOOKED at [personaId] (V28 `persona.memory_judged_at`), or null when
     * it never has — which the 90-day horizon (§2.6) bounds, never an all-time read. Parsed to an
     * [Instant] because the pass's window narrowing works on parsed instants (the lexicographic
     * sub-second rule); a malformed stamp reads as NULL with a WARN, never a throw — a corrupt
     * stamp must degrade to "look again", not break a whole run (§2.2).
     *
     * The column lives on `persona` but is read and written only here (the V27 placement argument):
     * `PersonaRepository.update` deliberately never learns this column exists, so an owner pressing
     * Save can never masquerade as a fresh judgment.
     */
    fun judgedAt(personaId: String): Instant? {
        val raw = jdbc.query(
            "SELECT memory_judged_at FROM persona WHERE id = ?",
            RowMapper<String?> { rs, _ -> rs.getString("memory_judged_at") },
            personaId,
        ).firstOrNull() ?: return null
        return try {
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            log.atWarn().setMessage("memory watermark for {} is malformed and reads as never-judged: {}")
                .addArgument(personaId).addArgument(raw)
                .addKeyValue("event", "memory.watermark.malformed").addKeyValue("personaId", personaId)
                .log()
            null
        }
    }

    /**
     * Stamp [personaId]'s consolidation watermark with [at] — the caller's PRE-QUERY evidence-read
     * instant, passed in rather than read from the [Clock] here, because a clock read inside this
     * method would sit after the judgment call, a minute of LLM latency later, and anything the
     * member posted in that gap would fall behind a watermark that never saw it (§2.6, the
     * bed019fe read-instant rule).
     *
     * Deliberately NO null-clears overload: revert does not roll the watermark back (§2.10, the
     * argued departure from S4a/S4b — rollback here would re-manufacture the row the owner just
     * killed), so a clear path would be an unused affordance inviting exactly that. Unknown ids are
     * a no-op — a member deleted mid-run is a race, not a bug.
     */
    fun markJudged(personaId: String, at: String) {
        jdbc.update("UPDATE persona SET memory_judged_at = ? WHERE id = ?", at, personaId)
    }

    /**
     * The belt under the DDL (§2.2's repository site): [parentId] must exist, belong to
     * [personaId], and be a `kind='record'` row. The composite FK already makes a cross-persona
     * link unrepresentable — that half is re-checked here only so the failure is a readable
     * `IllegalArgumentException` instead of a driver exception — but "the parent is not the root"
     * is a cross-row predicate SQLite cannot CHECK, so this is its one storage-side enforcement.
     */
    private fun insertRecord(id: String, personaId: String, parentId: String?, body: String, source: String): String {
        if (parentId != null) {
            val parent = find(parentId)
            require(parent != null) { "a memory cannot extend a record that does not exist" }
            require(parent.personaId == personaId) { "a memory may only extend a record of the same persona" }
            require(parent.kind == KIND_RECORD) { "a memory may never extend the root" }
        }
        jdbc.update(
            "INSERT INTO persona_memory($columns) VALUES (?,?,?,?,?,?,?)",
            id, personaId, parentId, KIND_RECORD, body, source, clock.instant().toString(),
        )
        return id
    }

    companion object {
        /** The §6.3 root post: at most one per member, owner-only in DDL, never a parent, never injected. */
        const val KIND_ROOT = "root"

        /** A memory record — the only kind retrieval, the letter list and the parent picker may see. */
        const val KIND_RECORD = "record"

        /** Authored on the persona profile: permanently protected — no pass path updates or deletes it. */
        const val SOURCE_OWNER = "owner"

        /** Written by the Memory Scribe pass — bounded by the scoped 300-code-point CHECK and the ceiling. */
        const val SOURCE_SCRIBE = "scribe"
    }
}
