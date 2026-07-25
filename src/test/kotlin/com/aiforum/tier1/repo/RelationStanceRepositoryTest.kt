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
 * Tier-1: [RelationStanceRepository] against the real test SQLite DB (V24 `persona_stance`, V26
 * `judged_at`). Pins the four things the qualitative relation model is built on — the (from, to) upsert
 * identity, the `source` provenance contract the evolving pass depends on, the CHECK/CASCADE guards that
 * keep a stance graph from outliving its personas or pointing at itself, and the V26 judgment watermark.
 *
 * The watermark assertions are about *cost*, not tidiness. `judged_at` is what stops a settled pair — one
 * whose judge keeps answering "unchanged", which writes no audit row by design — from re-buying the same
 * LLM judgment on every nightly run. Two of its rules are only ever enforced here: `upsert` must not
 * stamp it (or an owner editing a stance, or the revert path putting old text back, would silently
 * declare the edge freshly judged), and a null must CLEAR it rather than be ignored (or a reverted edge
 * could never be reconsidered from the evidence the owner rejected).
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

    // markJudged takes its stamp from the CALLER (the instant the evidence window was read), not from the
    // clock, so the watermark tests pass an instant deliberately different from `fixedNow` — the 04:00
    // scheduled hour. Anything asserting this value could not be satisfied by an accidental `updated_at`.
    private val judgedAt = "2026-01-01T04:00:00Z"

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
        assertNull(found.judgedAt, "a seeded or owner-authored stance has never been judged (V26 NULL)")
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
    fun `markJudged stamps the watermark and every read carries it`() {
        // All three readers share one column list, so a judged_at that reaches `find` but not `from` would
        // mean the pass and the admin surface disagree about when an edge was last looked at.
        seedRoster()
        stances.upsert("vex", "sol", "Cautious to a fault.", SOURCE_SEEDED)

        stances.markJudged("vex", "sol", judgedAt)

        assertEquals(judgedAt, stances.find("vex", "sol")?.judgedAt)
        assertEquals(judgedAt, stances.from("vex").single().judgedAt, "the outgoing-edge read carries it")
        assertEquals(judgedAt, stances.findAll().single().judgedAt, "and so does the whole-graph read")
        assertEquals(
            fixedNow, stances.find("vex", "sol")?.updatedAt,
            "judging an edge is not authoring it — the text is untouched, so updated_at must not move",
        )
    }

    @Test
    fun `markJudged with null clears the watermark, reopening the edge to its own history`() {
        // Null CLEARS rather than being ignored, because that is what a revert needs: an edge whose change
        // the owner undid must be judgeable again from the very exchanges that produced it (D10 — revert
        // undoes, it does not freeze). A no-op-on-null would wall that evidence off permanently.
        seedRoster()
        stances.upsert("vex", "sol", "Cautious to a fault.", SOURCE_SEEDED)
        stances.markJudged("vex", "sol", judgedAt)

        stances.markJudged("vex", "sol", null)

        assertNull(stances.find("vex", "sol")?.judgedAt, "back to never-judged: judge it over all of it")
    }

    @Test
    fun `re-authoring a stance never marks the edge as judged`() {
        // The seeder and the owner form both go through upsert. If that statement stamped judged_at, every
        // startup would declare the whole graph freshly judged and the pass would go quiet until brand-new
        // exchanges arrived — a cost fix turned into a silence bug.
        seedRoster()
        stances.upsert("vex", "sol", "Cautious to a fault.", SOURCE_SEEDED)
        stances.upsert("vex", "sol", "Cautious, and usually right about it.", SOURCE_OWNER)

        assertNull(stances.find("vex", "sol")?.judgedAt, "upsert writes prose and provenance, never judged_at")
    }

    @Test
    fun `an owner edit leaves a standing watermark exactly where it was`() {
        // The other half of the same guarantee, and the one an owner edit relies on: rewriting the words on
        // an edge the pass has already judged must not move the watermark in EITHER direction — not stamp
        // it fresh, not clear it. The owner asked for different prose, not for a re-judgment.
        seedRoster()
        stances.upsert("vex", "sol", "Cautious to a fault.", SOURCE_SEEDED)
        stances.markJudged("vex", "sol", judgedAt)

        stances.upsert("vex", "sol", "What I actually think.", SOURCE_OWNER)

        val found = stances.find("vex", "sol")!!
        assertEquals(judgedAt, found.judgedAt, "the watermark survived an unrelated write to the same row")
        assertEquals("What I actually think.", found.stance, "and the edit itself landed")
        assertEquals(SOURCE_OWNER, found.source)
    }

    @Test
    fun `markJudged touches only the named edge`() {
        // The window is per edge precisely so one pair's judgment cannot speak for another's; a stamp that
        // leaked onto the reverse edge would re-import the global watermark this design replaced.
        seedRoster()
        stances.upsert("vex", "sol", "Too tidy.", SOURCE_SEEDED)
        stances.upsert("sol", "vex", "Too loud.", SOURCE_SEEDED)
        stances.upsert("lune", "sol", "Says little; all of it lands.", SOURCE_SEEDED)

        stances.markJudged("vex", "sol", judgedAt)

        assertEquals(judgedAt, stances.find("vex", "sol")?.judgedAt)
        assertNull(stances.find("sol", "vex")?.judgedAt, "the reverse edge is a separate opinion, unjudged")
        assertNull(stances.find("lune", "sol")?.judgedAt, "and an unrelated edge is untouched")
    }

    @Test
    fun `markJudged on a pair with no stance row creates nothing`() {
        // An UPDATE, not an upsert, and deliberately: S4a evolves stances, it never invents edges the seed
        // did not author (and a deleted edge must not be resurrected as a stanceless watermark row).
        seedRoster()

        stances.markJudged("vex", "sol", judgedAt)

        assertEquals(0, rowCount(), "no stance row, nothing to judge, nothing written")
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
