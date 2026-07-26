package com.aiforum.tier1.repo

import com.aiforum.repo.InterestChangeRepository
import com.aiforum.repo.PersonaInterestRepository.Companion.SOURCE_DRIFTED
import com.aiforum.repo.PersonaInterestRepository.Companion.SOURCE_SEEDED
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
 * Tier-1: [InterestChangeRepository] against the real test SQLite DB (V27 `interest_change`). The drift pass
 * auto-applies with no approval queue, so this table is the owner's only control — the assertions below are
 * therefore about *the control still working*: the row round-trips both phrases and the provenance a revert
 * has to restore, the list stays newest-first, a revert stamps exactly once, and the per-member window
 * boundary the next run reads is the real MAX of that member's standing swaps.
 *
 * What is NOT pinned here, because it deliberately lives elsewhere: this table only records swaps, and a NONE
 * verdict writes no row by design. The watermark that keeps a settled member from re-buying the same
 * judgment every run is `persona.interests_judged_at`, pinned in [PersonaInterestRepositoryTest]. A reader
 * who takes the boundary below as the whole cost story will reintroduce the defect V26 was written to fix.
 *
 * The cascade assertion lives here rather than in `persona_deletion.feature` on S4a's precedent: building an
 * audit row through the acceptance layer means running a whole pass to set up a foreign-key assertion. The
 * §-numbered requirement is satisfied in substance; only its address moves. It is also a real assertion, not
 * decorative — the test datasource URL carries `foreign_keys=on` (application-test.yml), so SQLite enforces
 * the V27 foreign key per connection. Without that pragma SQLite ignores FKs entirely and the cascade test
 * would pass vacuously.
 *
 * Cleanup wipes `interest_change` before `persona` (child first) in both @BeforeEach and @AfterEach: the
 * CASCADE would cover it, but the sibling tier-1 classes wipe `persona` directly and must never find rows of
 * ours hanging off it.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class InterestChangeRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var personas: PersonaRepository
    @Autowired lateinit var changes: InterestChangeRepository

    // The test profile pins Clock to this instant (FixedClockConfig), so changed_at is exactly assertable.
    private val fixedNow = "2026-01-01T12:00:00Z"

    @BeforeEach @AfterEach
    fun clean() {
        listOf("interest_change", "persona").forEach { jdbc.update("DELETE FROM $it") }
    }

    private fun seedRoster() {
        personas.insert("vex", "Vex", "systems contrarian")
        personas.insert("sol", "Sol", "index whisperer")
        personas.insert("lune", "Lune", "quiet synthesiser")
    }

    /** The test clock is fixed, so a row can only be given a distinct age by hand. */
    private fun backdate(id: Long, at: String) {
        jdbc.update("UPDATE interest_change SET changed_at = ? WHERE id = ?", at, id)
    }

    @Test
    fun `record returns the id of the row it just wrote, which reads back with its provenance`() {
        // The returned id is what the Revert button posts to, so handing back a sibling's id would revert
        // someone else's swap. Two writes under the same fixed clock instant is the case that would catch a
        // read-back by timestamp — one run stamps every member it moved identically.
        seedRoster()

        val id = changes.record(
            personaId = "vex",
            dropped = "typography",
            droppedSource = SOURCE_SEEDED,
            takenUp = "release engineering",
            cited = "C7\tT3\tThis benchmark measures the wrong thing entirely",
        )
        val other = changes.record("sol", "index design", SOURCE_DRIFTED, "vector search", "C9\tT4\tSecond row")

        val found = changes.find(id)!!
        assertEquals(id, found.id)
        assertEquals("vex", found.personaId)
        assertEquals("typography", found.dropped)
        assertEquals("release engineering", found.takenUp)
        // The whole point of capturing dropped_source: a revert must put the phrase back on the footing it
        // had, not relabel a seeded interest 'drifted' on the way home.
        assertEquals(SOURCE_SEEDED, found.droppedSource)
        assertEquals("C7\tT3\tThis benchmark measures the wrong thing entirely", found.cited)
        assertEquals(fixedNow, found.changedAt)
        assertNull(found.revertedAt, "a freshly recorded swap has not been reverted")
        assertEquals("vector search", changes.find(other)?.takenUp, "the second id addresses the second row")
    }

    @Test
    fun `recent returns swaps newest-first, with same-instant rows ordered by id`() {
        seedRoster()
        val oldest = changes.record("vex", "old", SOURCE_SEEDED, "new", "C1\tT1\toldest")
        backdate(oldest, "2026-01-01T09:00:00Z")
        val middle = changes.record("sol", "old", SOURCE_SEEDED, "new", "C2\tT1\tmiddle")
        backdate(middle, "2026-01-01T10:00:00Z")
        // These two keep the fixed clock stamp — one run writes every member it moved under a single instant,
        // so the id tiebreak is what keeps the page from reshuffling between requests.
        val sameInstantFirst = changes.record("lune", "old", SOURCE_SEEDED, "new", "C3\tT1\tsame instant")
        val sameInstantSecond = changes.record("vex", "new", SOURCE_DRIFTED, "newer", "C4\tT1\tsame instant")

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
    fun `markReverted stamps once and a second call leaves that stamp alone`() {
        // reverted_at records WHEN the owner intervened, and the `reverted_at IS NULL` guard is what makes a
        // double-click a no-op in SQL rather than by caller convention. Re-stamping would move that moment to
        // whenever the button was last pressed — and, worse, the caller would restore the dropped phrase a
        // second time, costing the member whatever they have taken up since. Backdating first makes the
        // no-op observable under a fixed clock.
        seedRoster()
        val id = changes.record("vex", "typography", SOURCE_SEEDED, "release engineering", "C1\tT1\tthe exchange")

        changes.markReverted(id)

        assertEquals(fixedNow, changes.find(id)?.revertedAt, "the revert stamp comes from the injected clock")
        assertEquals("typography", changes.find(id)?.dropped, "the row still carries what to restore")

        jdbc.update("UPDATE interest_change SET reverted_at = ? WHERE id = ?", "2020-01-01T00:00:00Z", id)
        changes.markReverted(id)

        assertEquals("2020-01-01T00:00:00Z", changes.find(id)?.revertedAt)
    }

    @Test
    fun `lastStandingChangeAt ignores reverted rows and another member's swaps`() {
        // The defect this pins: with one global boundary, sol drifting at 12:00 would push vex's window
        // forward too, and the engagements vex still had to be judged on would sit behind it for good —
        // silently, and precisely for the member whose judgment failed or never ran. The reverted row gives up
        // its claim for the same reason a revert clears the watermark: revert undoes, it does not freeze.
        seedRoster()
        val standing = changes.record("vex", "typography", SOURCE_SEEDED, "release engineering", "C1\tT1\tstands")
        backdate(standing, "2026-01-01T09:00:00Z")
        val undone = changes.record("vex", "release engineering", SOURCE_DRIFTED, "supply chains", "C2\tT2\tundone")
        changes.record("sol", "index design", SOURCE_SEEDED, "vector search", "C3\tT3\ta different member, later")

        changes.markReverted(undone)

        assertEquals("2026-01-01T09:00:00Z", changes.lastStandingChangeAt("vex"), "the newest STANDING swap")
        assertEquals(fixedNow, changes.lastStandingChangeAt("sol"), "each member carries their own boundary")
        assertNull(changes.lastStandingChangeAt("lune"), "a member who never drifted imposes no boundary at all")
    }

    @Test
    fun `deleting a persona cascades its audit rows and spares the rest`() {
        seedRoster()
        changes.record("vex", "typography", SOURCE_SEEDED, "release engineering", "C1\tT1\tvex moved")
        changes.record("vex", "release engineering", SOURCE_DRIFTED, "supply chains", "C2\tT2\tvex moved again")
        val untouched = changes.record("sol", "index design", SOURCE_SEEDED, "vector search", "C3\tT3\tnothing to do with vex")

        personas.delete("vex")

        assertEquals(
            listOf(untouched),
            changes.recent(10).map { it.id },
            "the persona FK cascades — an audit row for a departed member has nothing left to revert onto",
        )
        assertEquals("sol", changes.find(untouched)?.personaId, "rows for surviving members are untouched")
    }
}
