package com.aiforum.tier1.repo

import com.aiforum.repo.MemoryChangeRepository
import com.aiforum.repo.PersonaRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Tier-1: [MemoryChangeRepository] against the real test SQLite DB (V28 `memory_change`). The pass
 * auto-applies with no approval queue, so this table is the owner's whole control — the assertions
 * are about the control still working: the row round-trips its snapshots (body, parent_body, cited
 * — the record itself may be reverted or deleted and the audit row must still say what was
 * written), `read_at` round-trips the CALLER's pre-query instant so the read-instant contract
 * (bed019fe) is auditable per row rather than trusted, the list stays newest-first, and a revert
 * stamps exactly once in SQL.
 *
 * What is NOT here, deliberately: no `lastStandingChangeAt` — this table is NOT a window boundary,
 * unlike `interest_change`. NOTHING is the designed steady state of the scribe and writes no audit
 * row, so an audit-derived window is exactly the V26 defect; `persona.memory_judged_at` (pinned in
 * [PersonaMemoryRepositoryTest]) is the only window. And no aggregate of any kind — an audit table
 * that can be summed is a memory-health score wearing an auditor's badge (§4 Stays-Cut).
 *
 * Cleanup wipes `memory_change` before `persona` (child first) in both hooks, the sibling-class
 * discipline. The cascade test is real: the datasource URL carries `foreign_keys=on`.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class MemoryChangeRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var personas: PersonaRepository
    @Autowired lateinit var changes: MemoryChangeRepository

    // The test profile pins Clock to this instant (FixedClockConfig), so changed_at is exactly assertable.
    private val fixedNow = "2026-01-01T12:00:00Z"

    // The run's pre-query evidence-read instant: passed by the CALLER, deliberately different from
    // fixedNow — a record() that stamped read_at from the clock instead would redden the round-trip.
    private val readAt = "2026-01-01T05:00:00Z"

    @BeforeEach @AfterEach
    fun clean() {
        listOf("memory_change", "persona").forEach { jdbc.update("DELETE FROM $it") }
    }

    private fun seedRoster() {
        personas.insert("vex", "Vex", "systems contrarian")
        personas.insert("sol", "Sol", "index whisperer")
    }

    private fun backdate(id: Long, at: String) {
        jdbc.update("UPDATE memory_change SET changed_at = ? WHERE id = ?", at, id)
    }

    @Test
    fun `record returns the id of the row it just wrote, which reads back with its snapshots and read_at`() {
        // The returned id is what the Revert button posts to; two writes under one fixed clock
        // instant is the case that would catch a read-back by timestamp.
        seedRoster()

        val id = changes.record(
            personaId = "vex",
            memoryId = "m-1",
            body = "Learned that preemption arguments never really end",
            parentBody = "Fell down a fsync rabbit hole two winters back",
            cited = "C7\tT3\tNobody benchmarks the wake-up path",
            readAt = readAt,
        )
        val other = changes.record("sol", "m-2", "Second row", null, "C9\tT4\tOther evidence", readAt)

        val found = changes.find(id)!!
        assertEquals(id, found.id)
        assertEquals("vex", found.personaId)
        assertEquals("m-1", found.memoryId)
        assertEquals("Learned that preemption arguments never really end", found.body)
        assertEquals("Fell down a fsync rabbit hole two winters back", found.parentBody)
        assertEquals("C7\tT3\tNobody benchmarks the wake-up path", found.cited)
        // The read-instant contract, auditable per row: the value stored is the caller's pre-query
        // instant, not a clock read taken after a minute of LLM latency.
        assertEquals(readAt, found.readAt)
        assertEquals(fixedNow, found.changedAt)
        assertNull(found.revertedAt, "a freshly recorded write has not been reverted")
        val topLevel = changes.find(other)!!
        assertNull(topLevel.parentBody, "a top-level record has no antecedent snapshot")
        assertEquals("Second row", topLevel.body, "the second id addresses the second row")
    }

    @Test
    fun `recent returns writes newest-first, with same-instant rows ordered by id`() {
        seedRoster()
        val oldest = changes.record("vex", "m-1", "oldest", null, "C1\tT1\toldest", readAt)
        backdate(oldest, "2026-01-01T09:00:00Z")
        val middle = changes.record("sol", "m-2", "middle", null, "C2\tT1\tmiddle", readAt)
        backdate(middle, "2026-01-01T10:00:00Z")
        // These two keep the fixed clock stamp — one run writes every member it moved under one
        // instant, so the id tiebreak is what keeps the page from reshuffling between requests.
        val sameFirst = changes.record("vex", "m-3", "same instant", null, "C3\tT1\tsame", readAt)
        val sameSecond = changes.record("sol", "m-4", "same instant too", null, "C4\tT1\tsame", readAt)

        assertEquals(
            listOf(sameSecond, sameFirst, middle, oldest),
            changes.recent(10).map { it.id },
        )
        assertEquals(
            listOf(sameSecond, sameFirst),
            changes.recent(2).map { it.id },
            "the limit takes the newest, not an arbitrary page",
        )
    }

    @Test
    fun `markReverted stamps once and the reverted_at IS NULL guard blocks a second stamp`() {
        // The null IS the double-revert guard, in SQL rather than by caller convention: re-stamping
        // would move the record of WHEN the owner intervened to whenever the button was last
        // double-clicked. Backdating first makes the no-op observable under a fixed clock.
        seedRoster()
        val id = changes.record("vex", "m-1", "A memory to undo", null, "C1\tT1\tthe exchange", readAt)

        changes.markReverted(id)

        assertEquals(fixedNow, changes.find(id)?.revertedAt, "the revert stamp comes from the injected clock")
        assertEquals("A memory to undo", changes.find(id)?.body, "the snapshot survives the revert")

        jdbc.update("UPDATE memory_change SET reverted_at = ? WHERE id = ?", "2020-01-01T00:00:00Z", id)
        changes.markReverted(id)

        assertEquals("2020-01-01T00:00:00Z", changes.find(id)?.revertedAt)
        changes.markReverted(9999L) // unknown ids are a no-op too
    }

    @Test
    fun `deleting a persona cascades its audit rows and spares the rest`() {
        seedRoster()
        changes.record("vex", "m-1", "vex wrote one", null, "C1\tT1\tvex evidence", readAt)
        changes.record("vex", "m-2", "vex wrote another", null, "C2\tT2\tmore vex evidence", readAt)
        val untouched = changes.record("sol", "m-3", "sol's own", null, "C3\tT3\tsol evidence", readAt)

        personas.delete("vex")

        assertEquals(
            listOf(untouched),
            changes.recent(10).map { it.id },
            "the persona FK cascades — an audit row for a departed member has nothing left to revert",
        )
        assertEquals("sol", changes.find(untouched)?.personaId)
    }
}
