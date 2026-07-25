package com.aiforum.tier1.repo

import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.RelationStanceRepository
import com.aiforum.repo.RelationStanceRepository.Companion.SOURCE_EVOLVED
import com.aiforum.repo.RelationStanceRepository.Companion.SOURCE_OWNER
import com.aiforum.repo.RelationStanceRepository.Companion.SOURCE_SEEDED
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Tier-1: [RelationStanceRepository] against the real test SQLite DB (V24 `persona_stance`). Pins the
 * three things the qualitative relation model is built on — the (from, to) upsert identity, the
 * `source` provenance contract a later evolving pass depends on, and the CHECK/CASCADE guards that keep
 * a stance graph from outliving its personas or pointing at itself.
 *
 * The cascade assertion is real, not decorative: the test datasource URL carries `foreign_keys=on`
 * (application-test.yml), so SQLite enforces the V24 foreign keys per connection. Without that pragma
 * SQLite ignores FKs entirely and the cascade test would pass vacuously — if it ever disappears from the
 * URL, `deleting a persona cascades…` is the test that should be trusted to fail.
 *
 * Cleanup wipes `persona_stance` before `persona` (child first) in both @BeforeEach and @AfterEach: the
 * CASCADE would cover it, but the sibling tier-1 classes wipe `persona` directly and must never find
 * rows of ours hanging off it.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class RelationStanceRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var personas: PersonaRepository
    @Autowired lateinit var stances: RelationStanceRepository

    // The test profile pins Clock to this instant (FixedClockConfig), so updated_at is exactly assertable.
    private val fixedNow = "2026-01-01T12:00:00Z"

    @BeforeEach @AfterEach
    fun clean() {
        listOf("persona_stance", "persona").forEach { jdbc.update("DELETE FROM $it") }
    }

    private fun seedRoster() {
        personas.insert("vex", "Vex", "systems contrarian")
        personas.insert("sol", "Sol", "index whisperer")
        personas.insert("lune", "Lune", "quiet synthesiser")
    }

    private fun rowCount(): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM persona_stance", Int::class.java) ?: 0

    @Test
    fun `find round-trips every field of a stance`() {
        seedRoster()
        stances.upsert("vex", "sol", "Reads the room better than anyone; I lean on that.", SOURCE_SEEDED)

        val found = stances.find("vex", "sol")!!
        assertEquals("vex", found.fromPersona)
        assertEquals("sol", found.toPersona)
        assertEquals("Reads the room better than anyone; I lean on that.", found.stance)
        assertEquals(SOURCE_SEEDED, found.source)
        assertEquals(fixedNow, found.updatedAt)
    }

    @Test
    fun `a second upsert on the same pair updates in place rather than duplicating`() {
        seedRoster()
        stances.upsert("vex", "sol", "Cautious to a fault.", SOURCE_SEEDED)
        // Backdate the stored stamp by hand so the refresh is observable: the test clock is fixed, so two
        // repo writes would otherwise be indistinguishable and `updated_at = excluded.updated_at` could
        // silently rot without a test noticing.
        jdbc.update("UPDATE persona_stance SET updated_at = ? WHERE from_persona = 'vex'", "2020-01-01T00:00:00Z")

        stances.upsert("vex", "sol", "Cautious, and usually right about it.", SOURCE_SEEDED)

        assertEquals(1, rowCount(), "the (from, to) PRIMARY KEY collapses the re-author into one row")
        val found = stances.find("vex", "sol")!!
        assertEquals("Cautious, and usually right about it.", found.stance)
        assertEquals(fixedNow, found.updatedAt, "the upsert restamps updated_at from the injected clock")
    }

    @Test
    fun `an owner upsert over a seeded row flips the provenance to owner`() {
        // The S4a provenance contract: a later auto-evolving pass reads `source` to know which rows carry
        // the owner's own wording and must be left alone. An owner edit therefore has to overwrite the
        // seeded marker, not preserve it.
        seedRoster()
        stances.upsert("vex", "sol", "Seeded view.", SOURCE_SEEDED)

        stances.upsert("vex", "sol", "What I actually think.", SOURCE_OWNER)

        val found = stances.find("vex", "sol")!!
        assertEquals(SOURCE_OWNER, found.source)
        assertEquals("What I actually think.", found.stance)
    }

    @Test
    fun `an unknown source value is rejected by the CHECK constraint`() {
        seedRoster()
        // Broad DataAccessException rather than DataIntegrityViolationException: the constraint is what's
        // under test, and Spring's SQLite exception translation is a driver detail we don't want to pin.
        assertThrows(DataAccessException::class.java) {
            stances.upsert("vex", "sol", "Admires greatly.", "admiration")
        }
        assertEquals(0, rowCount(), "the rejected write left nothing behind")
    }

    @Test
    fun `a self-stance is rejected by the CHECK constraint`() {
        seedRoster()
        assertThrows(DataAccessException::class.java) {
            stances.upsert("vex", "vex", "I am my own harshest critic.", SOURCE_SEEDED)
        }
        assertEquals(0, rowCount(), "a persona can hold no view about itself")
    }

    @Test
    fun `from returns only that persona's outgoing edges, ordered by target`() {
        // Outgoing only, because a persona's prompt carries the views they HOLD, never the room's views
        // of them; the ordering keeps the rendered prose byte-stable across runs.
        seedRoster()
        stances.upsert("sol", "vex", "Argumentative, but never lazy.", SOURCE_SEEDED)
        stances.upsert("sol", "lune", "Says little; all of it lands.", SOURCE_SEEDED)
        stances.upsert("vex", "sol", "Too tidy for my taste.", SOURCE_SEEDED)

        val outgoing = stances.from("sol")

        assertEquals(listOf("lune", "vex"), outgoing.map { it.toPersona })
        assertEquals(listOf("sol", "sol"), outgoing.map { it.fromPersona }, "no incoming edge leaked in")
    }

    @Test
    fun `findAll returns the whole graph ordered by from then to`() {
        seedRoster()
        stances.upsert("sol", "vex", "b", SOURCE_SEEDED)
        stances.upsert("vex", "lune", "a", SOURCE_OWNER)
        stances.upsert("lune", "sol", "c", SOURCE_EVOLVED)

        assertEquals(
            listOf("lune" to "sol", "sol" to "vex", "vex" to "lune"),
            stances.findAll().map { it.fromPersona to it.toPersona },
        )
    }

    @Test
    fun `delete removes exactly one directed edge and leaves the reverse standing`() {
        // The reverse edge is an independent opinion held by someone else — retracting one view must not
        // silently retract the other.
        seedRoster()
        stances.upsert("vex", "sol", "Too tidy.", SOURCE_SEEDED)
        stances.upsert("sol", "vex", "Too loud.", SOURCE_SEEDED)

        stances.delete("vex", "sol")

        assertNull(stances.find("vex", "sol"))
        assertEquals("Too loud.", stances.find("sol", "vex")?.stance)
    }

    @Test
    fun `deleting a persona cascades its stances in both directions and spares the rest`() {
        seedRoster()
        stances.upsert("vex", "sol", "outgoing from vex", SOURCE_SEEDED)
        stances.upsert("sol", "vex", "incoming to vex", SOURCE_SEEDED)
        stances.upsert("lune", "sol", "unrelated to vex", SOURCE_SEEDED)
        stances.upsert("sol", "lune", "also unrelated", SOURCE_SEEDED)

        personas.delete("vex")

        assertNull(stances.find("vex", "sol"), "vex's outgoing view died with vex")
        assertNull(stances.find("sol", "vex"), "and so did the room's view OF vex — a stance is live state")
        assertEquals(
            listOf("lune" to "sol", "sol" to "lune"),
            stances.findAll().map { it.fromPersona to it.toPersona },
            "edges between the surviving personas are untouched",
        )
    }
}
