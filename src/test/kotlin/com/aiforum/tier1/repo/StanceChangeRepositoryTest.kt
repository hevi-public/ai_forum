package com.aiforum.tier1.repo

import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.RelationStanceRepository.Companion.SOURCE_OWNER
import com.aiforum.repo.RelationStanceRepository.Companion.SOURCE_SEEDED
import com.aiforum.repo.StanceChangeRepository
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
 * Tier-1: [StanceChangeRepository] against the real test SQLite DB (V25 `stance_change`). The evolution
 * pass auto-applies with no approval queue, so this table is the owner's only control — the assertions
 * below are therefore about *the control still working*: the row round-trips both texts and the old
 * provenance a revert has to restore, the list stays newest-first, a revert stamps exactly once, and the
 * per-edge window boundary the next run reads is the real MAX of that pair's standing changes.
 *
 * What is NOT pinned here, because it deliberately lives elsewhere: this table only records changes, and
 * an "unchanged" judgment writes no row by design. The watermark that keeps a settled pair from re-buying
 * the same judgment every run is `persona_stance.judged_at` (V26), pinned in [RelationStanceRepositoryTest].
 * A reader who takes the boundary below as the whole cost story will reintroduce that defect.
 *
 * The cascade assertion is real, not decorative: the test datasource URL carries `foreign_keys=on`
 * (application-test.yml), so SQLite enforces the V25 foreign keys per connection. Without that pragma
 * SQLite ignores FKs entirely and the cascade test would pass vacuously — if it ever disappears from the
 * URL, `deleting a persona cascades…` is the test that should be trusted to fail.
 *
 * Cleanup wipes `stance_change` before `persona` (child first) in both @BeforeEach and @AfterEach: the
 * CASCADE would cover it, but the sibling tier-1 classes wipe `persona` directly and must never find rows
 * of ours hanging off it.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class StanceChangeRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var personas: PersonaRepository
    @Autowired lateinit var changes: StanceChangeRepository

    // The test profile pins Clock to this instant (FixedClockConfig), so changed_at is exactly assertable.
    private val fixedNow = "2026-01-01T12:00:00Z"

    @BeforeEach @AfterEach
    fun clean() {
        listOf("stance_change", "persona").forEach { jdbc.update("DELETE FROM $it") }
    }

    private fun seedRoster() {
        personas.insert("vex", "Vex", "systems contrarian")
        personas.insert("sol", "Sol", "index whisperer")
        personas.insert("lune", "Lune", "quiet synthesiser")
    }

    /** The test clock is fixed, so a row can only be given a distinct age by hand. */
    private fun backdate(id: Long, at: String) {
        jdbc.update("UPDATE stance_change SET changed_at = ? WHERE id = ?", at, id)
    }

    @Test
    fun `record round-trips every field and stamps changed_at from the injected clock`() {
        seedRoster()

        val id = changes.record(
            fromPersona = "vex",
            toPersona = "sol",
            oldStance = "kindred pessimist, quietly enjoys catching him out",
            newStance = "has started treating his posts as claims to be checked",
            oldSource = SOURCE_SEEDED,
            cited = "C7: This benchmark measures the wrong thing entirely",
        )

        val found = changes.find(id)!!
        assertEquals(id, found.id)
        assertEquals("vex", found.fromPersona)
        assertEquals("sol", found.toPersona)
        assertEquals("kindred pessimist, quietly enjoys catching him out", found.oldStance)
        assertEquals("has started treating his posts as claims to be checked", found.newStance)
        // The whole point of capturing old_source: a revert must put the row back on the footing it had,
        // not relabel a seeded stance 'evolved' on the way out.
        assertEquals(SOURCE_SEEDED, found.oldSource)
        assertEquals("C7: This benchmark measures the wrong thing entirely", found.cited)
        assertEquals(fixedNow, found.changedAt)
        assertNull(found.revertedAt, "a freshly recorded change has not been reverted")
    }

    @Test
    fun `record returns the id of the row it just wrote, not of a sibling`() {
        // The returned id is what the Revert button posts to, so handing back the wrong row would revert
        // someone else's change. Two writes under the same fixed clock instant is the case that would
        // catch a read-back by timestamp.
        seedRoster()

        val first = changes.record("vex", "sol", "old A", "new A", SOURCE_SEEDED, "C1: first")
        val second = changes.record("sol", "vex", "old B", "new B", SOURCE_OWNER, "C2: second")

        assertEquals("new A", changes.find(first)?.newStance)
        assertEquals("new B", changes.find(second)?.newStance)
        assertEquals(fixedNow, changes.find(first)?.changedAt)
        assertEquals(fixedNow, changes.find(second)?.changedAt, "both rows share the fixed clock instant")
    }

    @Test
    fun `find returns null for an id that was never recorded`() {
        assertNull(changes.find(4242L))
    }

    @Test
    fun `recent returns changes newest-first, with same-instant rows ordered by id`() {
        seedRoster()
        val oldest = changes.record("vex", "sol", "old", "new", SOURCE_SEEDED, "C1: oldest")
        backdate(oldest, "2026-01-01T09:00:00Z")
        val middle = changes.record("sol", "vex", "old", "new", SOURCE_SEEDED, "C2: middle")
        backdate(middle, "2026-01-01T10:00:00Z")
        // These two keep the fixed clock stamp — one evolution run writes every affected edge under a
        // single instant, so the id tiebreak is what keeps the page from reshuffling between requests.
        val sameInstantFirst = changes.record("lune", "sol", "old", "new", SOURCE_SEEDED, "C3: same instant")
        val sameInstantSecond = changes.record("sol", "lune", "old", "new", SOURCE_SEEDED, "C4: same instant")

        assertEquals(
            listOf(sameInstantSecond, sameInstantFirst, middle, oldest),
            changes.recent(10).map { it.id },
        )
        assertEquals(
            listOf(sameInstantSecond, sameInstantFirst),
            changes.recent(2).map { it.id },
            "the limit takes the newest, not an arbitrary page",
        )
    }

    @Test
    fun `markReverted stamps reverted_at and find reflects it`() {
        seedRoster()
        val id = changes.record("vex", "sol", "old view", "new view", SOURCE_SEEDED, "C1: the exchange")

        changes.markReverted(id)

        assertEquals(fixedNow, changes.find(id)?.revertedAt, "the revert stamp comes from the injected clock")
        assertEquals("old view", changes.find(id)?.oldStance, "the audit row still carries what to restore")
    }

    @Test
    fun `a second markReverted leaves the original revert stamp alone`() {
        // reverted_at records WHEN the owner intervened. Re-stamping it on a double-click (or a replayed
        // form post) would move that moment to whenever the button was last pressed, and the row would
        // stop being evidence of anything. Backdating first makes the no-op observable under a fixed clock.
        seedRoster()
        val id = changes.record("vex", "sol", "old view", "new view", SOURCE_SEEDED, "C1: the exchange")
        changes.markReverted(id)
        jdbc.update("UPDATE stance_change SET reverted_at = ? WHERE id = ?", "2020-01-01T00:00:00Z", id)

        changes.markReverted(id)

        assertEquals("2020-01-01T00:00:00Z", changes.find(id)?.revertedAt)
    }

    @Test
    fun `markReverted on an unknown id is a no-op`() {
        seedRoster()
        val id = changes.record("vex", "sol", "old view", "new view", SOURCE_SEEDED, "C1: the exchange")

        changes.markReverted(9999L)

        assertNull(changes.find(id)?.revertedAt, "the real row was left standing")
    }

    @Test
    fun `an edge with no history has no boundary, so it is judged over all of it`() {
        seedRoster()
        assertNull(changes.lastStandingChangeAt("vex", "sol"))
    }

    @Test
    fun `each edge carries its OWN boundary, unmoved by another pair's change`() {
        // The defect this pins: with one global watermark, sol→vex changing at 12:00 would push vex→sol's
        // boundary forward too, and the exchanges vex→sol still had to be judged on would sit behind it
        // for good — silently, and precisely for the pair whose judgment failed or never ran.
        seedRoster()
        val older = changes.record("vex", "sol", "old", "new", SOURCE_SEEDED, "C1: vex on sol")
        backdate(older, "2026-01-01T09:00:00Z")
        changes.record("sol", "vex", "old", "new", SOURCE_SEEDED, "C2: a different pair, later")

        assertEquals("2026-01-01T09:00:00Z", changes.lastStandingChangeAt("vex", "sol"))
        assertEquals(fixedNow, changes.lastStandingChangeAt("sol", "vex"))
    }

    @Test
    fun `a reverted change gives up its claim on the window`() {
        // D10: revert undoes, it does not freeze. If a reverted row kept the boundary, the evidence behind
        // the judgment the owner rejected would be walled off and that edge could never be reconsidered
        // from it — the acceptance scenario "A reverted stance is free to drift again" is the same rule.
        seedRoster()
        val id = changes.record("vex", "sol", "old", "new", SOURCE_SEEDED, "C1: judged once")

        changes.markReverted(id)

        assertNull(changes.lastStandingChangeAt("vex", "sol"), "the edge is open to that evidence again")
    }

    @Test
    fun `the boundary is the newest STANDING change, not merely the newest`() {
        seedRoster()
        val standing = changes.record("vex", "sol", "a", "b", SOURCE_SEEDED, "C1: stands")
        backdate(standing, "2026-01-01T09:00:00Z")
        val later = changes.record("vex", "sol", "b", "c", SOURCE_SEEDED, "C2: undone")

        changes.markReverted(later)

        assertEquals("2026-01-01T09:00:00Z", changes.lastStandingChangeAt("vex", "sol"))
    }

    @Test
    fun `deleting a persona cascades its audit rows in both directions and spares the rest`() {
        seedRoster()
        changes.record("vex", "sol", "old", "new", SOURCE_SEEDED, "C1: vex on sol")
        changes.record("sol", "vex", "old", "new", SOURCE_SEEDED, "C2: sol on vex")
        val untouched = changes.record("lune", "sol", "old", "new", SOURCE_SEEDED, "C3: nothing to do with vex")

        personas.delete("vex")

        assertEquals(
            listOf(untouched),
            changes.recent(10).map { it.id },
            "both endpoint FKs cascade — an audit row for a departed persona has nothing left to revert onto",
        )
        assertEquals("lune", changes.find(untouched)?.fromPersona, "rows between surviving personas are untouched")
    }
}
