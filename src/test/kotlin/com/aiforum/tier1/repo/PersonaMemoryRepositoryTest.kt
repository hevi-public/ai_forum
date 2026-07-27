package com.aiforum.tier1.repo

import com.aiforum.repo.PersonaMemoryRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.testsupport.LogCapture
import ch.qos.logback.classic.Level
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * Tier-1: [PersonaMemoryRepository] against the real test SQLite DB (V28 `persona_memory`, and
 * `persona.memory_judged_at`). What these tests pin is the slice's guardrail layer — the things
 * plan_docs/persona-memory.md puts in the DATABASE so no later caller can argue with them:
 *
 * - **The composite same-persona FK** makes a cross-persona parent link unrepresentable (I1's DDL
 *   half; the parent hop is the mechanism that would otherwise carry one member's memory into
 *   another's prompt). The repository's own parent check is belt over that — both are pinned, on
 *   different exception types, because they are different guards.
 * - **The root is owner-only, single, and parentless in DDL** (§2.3's recorded owner call: the
 *   CHECKs were only available at table birth). "A record may never extend the root" is the ONE
 *   rule SQLite cannot express (a CHECK sees only its own row), so its repository site is pinned
 *   here — the hop filter and (next phase) the pickers are the other sites.
 * - **The scoped length CHECK** binds the pass and exempts the owner, both directions on one body —
 *   and the body is emoji-bearing on purpose: SQLite `length()` counts code points, so a
 *   300-code-point body that is 600 UTF-16 units inserting cleanly IS the I5 agreement asserted as
 *   a database fact, not a comment.
 * - **Reparent-then-delete** preserves a chain (§2.10) — the composite FK's CASCADE is the
 *   persona-cascade backstop and must never fire on a single-record delete.
 * - **The watermark**: `markJudged` stamps the CALLER's instant (the pre-query read instant, the
 *   bed019fe rule), never the clock's; a malformed stamp reads as NULL with a WARN, because a
 *   corrupt stamp must mean "look again", not a broken weekly run.
 *
 * The cascade assertions are real, not decorative: the test datasource URL carries
 * `foreign_keys=on` (application-test.yml) — without that pragma SQLite ignores FKs entirely and
 * both the refusal and the cascade tests would pass or fail vacuously.
 *
 * Cleanup wipes `persona_memory` before `persona` (child first) in both @BeforeEach and @AfterEach:
 * the CASCADE would cover it, but sibling tier-1 classes wipe `persona` directly and must never
 * find rows of ours hanging off it.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class PersonaMemoryRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var personas: PersonaRepository
    @Autowired lateinit var memories: PersonaMemoryRepository

    // The test profile pins Clock to this instant (FixedClockConfig), so created_at is exactly assertable.
    private val fixedNow = "2026-01-01T12:00:00Z"

    // markJudged takes its stamp from the CALLER (the pre-query evidence-read instant), not from the
    // clock — deliberately different from fixedNow, so nothing here can pass off an accidental
    // clock read as the watermark (the PersonaInterestRepositoryTest discipline, carried).
    private val readAt = "2026-01-01T05:00:00Z"

    @BeforeEach @AfterEach
    fun clean() {
        listOf("persona_memory", "persona").forEach { jdbc.update("DELETE FROM $it") }
    }

    private fun seedRoster() {
        personas.insert("vex", "Vex", "systems contrarian")
        personas.insert("sol", "Sol", "index whisperer")
    }

    private fun rowCount(): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM persona_memory", Int::class.java) ?: 0

    private fun backdate(id: String, at: String) {
        jdbc.update("UPDATE persona_memory SET created_at = ? WHERE id = ?", at, id)
    }

    @Test
    fun `a record round-trips verbatim and recordsOf orders newest-first with an id tiebreak`() {
        // The ordering is load-bearing: this list feeds recall, the scribe's letter labels and the
        // profile, so rowid drift would reshuffle a prompt. Seeded out of order on purpose; the two
        // same-instant rows are what the fixed clock always produces in one run, so the id tiebreak
        // is what keeps the order real.
        seedRoster()
        memories.insertOwnerRecord("vex", "Watched checkpoint tuning eat a weekend", parentId = null, id = "m-b")
        memories.insertOwnerRecord("vex", "Still suspicious of defaults", parentId = null, id = "m-a")
        memories.insertScribeRecord("vex", "Learned that arguments never end", parentId = null, id = "m-old")
        backdate("m-old", "2026-01-01T09:00:00Z")

        val held = memories.recordsOf("vex")

        assertEquals(listOf("m-a", "m-b", "m-old"), held.map { it.id })
        val row = held.single { it.id == "m-b" }
        assertEquals("vex", row.personaId)
        assertNull(row.parentId, "a top-level record has no antecedent")
        assertEquals(PersonaMemoryRepository.KIND_RECORD, row.kind)
        assertEquals("Watched checkpoint tuning eat a weekend", row.body, "the door stores its argument verbatim")
        assertEquals(PersonaMemoryRepository.SOURCE_OWNER, row.source)
        assertEquals(fixedNow, row.createdAt)
        assertEquals(
            PersonaMemoryRepository.SOURCE_SCRIBE, held.single { it.id == "m-old" }.source,
            "the scribe door hard-codes its provenance",
        )
    }

    @Test
    fun `recordsOf is a record-only world and rootOf finds the root`() {
        // The first site of §2.2's parent-candidate rule: every consumer of recordsOf — recall, the
        // letter list, the parent picker — must never see the root in it.
        seedRoster()
        assertNull(memories.rootOf("vex"), "the root is born absent (§2.3)")
        memories.insertOwnerRecord("vex", "A record", parentId = null, id = "m-1")
        memories.insertRoot("vex", "Grew up fixing farm machinery", id = "r-1")

        assertEquals(listOf("m-1"), memories.recordsOf("vex").map { it.id })
        val root = memories.rootOf("vex")!!
        assertEquals(PersonaMemoryRepository.KIND_ROOT, root.kind)
        assertEquals(PersonaMemoryRepository.SOURCE_OWNER, root.source)
        assertNull(root.parentId)
        assertNull(memories.rootOf("sol"), "the root is per member")
    }

    @Test
    fun `the composite FK rejects a cross-persona parent even below the repository's belt`() {
        // I1's DDL half. Raw SQL on purpose: this write bypasses the repository check entirely, so
        // what refuses it can only be SQLite's composite (persona_id, parent_id) FK. If this test
        // ever starts passing vacuously, check foreign_keys=on in the test datasource URL first.
        seedRoster()
        memories.insertOwnerRecord("vex", "Vex's own memory", parentId = null, id = "m-vex")

        assertThrows(DataAccessException::class.java) {
            jdbc.update(
                "INSERT INTO persona_memory(id, persona_id, parent_id, kind, body, source, created_at) " +
                    "VALUES ('m-sol', 'sol', 'm-vex', 'record', 'A cross-persona link', 'owner', ?)",
                fixedNow,
            )
        }
        assertEquals(1, rowCount(), "the refused write left nothing behind")
    }

    @Test
    fun `the repository belt refuses a cross-persona or unknown parent readably`() {
        // The belt over the DDL: same rule, but as an IllegalArgumentException a caller can show,
        // not a driver exception out of a form save.
        seedRoster()
        memories.insertOwnerRecord("vex", "Vex's own memory", parentId = null, id = "m-vex")

        assertThrows(IllegalArgumentException::class.java) {
            memories.insertOwnerRecord("sol", "Extending another member's memory", parentId = "m-vex")
        }
        assertThrows(IllegalArgumentException::class.java) {
            memories.insertScribeRecord("sol", "Extending a ghost", parentId = "m-gone")
        }
        assertEquals(1, rowCount())
    }

    @Test
    fun `a second root is refused by the partial unique index`() {
        seedRoster()
        memories.insertRoot("vex", "The first root", id = "r-1")

        assertThrows(DataAccessException::class.java) { memories.insertRoot("vex", "A second root", id = "r-2") }

        assertEquals("The first root", memories.rootOf("vex")?.body, "the standing root is untouched")
        // Another member's root is not collateral — the index is per persona.
        memories.insertRoot("sol", "Sol's own root", id = "r-sol")
        assertEquals(2, rowCount())
    }

    @Test
    fun `the DDL refuses a scribe-written root and a parented root`() {
        // §2.3: no pass can ever write identity, and the root heads the tree. Raw SQL again — these
        // shapes are unbuildable through the repository (insertRoot hard-codes owner and takes no
        // parent), so only hand SQL can even attempt them, and the CHECKs are what stand there.
        seedRoster()
        memories.insertOwnerRecord("vex", "A record to point at", parentId = null, id = "m-1")

        assertThrows(DataAccessException::class.java) {
            jdbc.update(
                "INSERT INTO persona_memory(id, persona_id, parent_id, kind, body, source, created_at) " +
                    "VALUES ('r-bad', 'vex', NULL, 'root', 'A scribe-authored identity', 'scribe', ?)",
                fixedNow,
            )
        }
        assertThrows(DataAccessException::class.java) {
            jdbc.update(
                "INSERT INTO persona_memory(id, persona_id, parent_id, kind, body, source, created_at) " +
                    "VALUES ('r-bad', 'vex', 'm-1', 'root', 'A root hanging off a record', 'owner', ?)",
                fixedNow,
            )
        }
        assertEquals(1, rowCount())
    }

    @Test
    fun `a record with the root as its parent is refused at the repository`() {
        // §2.2's repository site — the ONE parent-candidate site DDL cannot express (a CHECK sees
        // only its own row, and "my parent's kind is record" is a cross-row predicate). Without
        // this, the hop would drag the root into a prompt and a root delete would cascade the tree.
        seedRoster()
        memories.insertRoot("vex", "Grew up fixing farm machinery", id = "r-1")

        assertThrows(IllegalArgumentException::class.java) {
            memories.insertOwnerRecord("vex", "Extending the root", parentId = "r-1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            memories.insertScribeRecord("vex", "The pass extending the root", parentId = "r-1")
        }
        assertEquals(1, rowCount())
    }

    @Test
    fun `the scoped length CHECK binds the pass at 301 code points and exempts the owner, in code points`() {
        // Both directions on ONE body (the V27 scoped-CHECK discipline), and the bodies carry
        // surrogate pairs on purpose: 301 emoji are 602 UTF-16 units but 301 to SQLite's length()
        // and 301 to MemoryText.codePoints — the I5 agreement as a database fact. If SQLite counted
        // UTF-16 units, the 300-emoji scribe row below would trip the CHECK and redden this.
        seedRoster()
        val over = "🙂".repeat(301)

        assertThrows(DataAccessException::class.java) {
            memories.insertScribeRecord("vex", over, parentId = null, id = "m-over")
        }
        assertEquals(0, rowCount(), "the rejected write left nothing behind")

        memories.insertScribeRecord("vex", "🙂".repeat(300), parentId = null, id = "m-at-bound")
        memories.insertOwnerRecord("vex", over, parentId = null, id = "m-owner-long")

        assertEquals(2, rowCount(), "300 code points pass the pass's CHECK; the owner is exempt entirely")
        assertEquals(over, memories.find("m-owner-long")?.body, "stored verbatim, truncation is MemoryProse's job")
    }

    @Test
    fun `deleteRecord reparents children to the grandparent and the chain survives`() {
        // §2.10: the composite FK's CASCADE is the persona-cascade backstop and must never fire on
        // this path — a cascade here would silently destroy the chain the owner meant to prune.
        seedRoster()
        memories.insertOwnerRecord("vex", "Started with one broken drive", parentId = null, id = "m-a")
        memories.insertOwnerRecord("vex", "Kept digging into commit behaviour", parentId = "m-a", id = "m-b")
        memories.insertOwnerRecord("vex", "Ended up distrusting defaults", parentId = "m-b", id = "m-c")

        memories.deleteRecord("m-b")

        assertEquals(listOf("m-a", "m-c"), memories.recordsOf("vex").map { it.id }.sorted())
        assertEquals("m-a", memories.find("m-c")?.parentId, "the orphan is handed to its grandparent")

        // Deleting the (new) head reparents to top level — the "top-level at worst" half.
        memories.deleteRecord("m-a")
        assertNull(memories.find("m-c")?.parentId)
        assertEquals(1, rowCount())
    }

    @Test
    fun `deleteRecord of a vanished row is a no-op`() {
        // The action-site re-read belongs to the caller (§2.10); the door just refuses to invent
        // work — and must not throw, because "already gone" is the superseded case, not an error.
        seedRoster()
        memories.deleteRecord("m-never-existed")
        assertEquals(0, rowCount())
    }

    @Test
    fun `deleting a persona cascades the whole tree, including through the self-FK, and spares the rest`() {
        seedRoster()
        memories.insertRoot("vex", "Vex's root", id = "r-vex")
        memories.insertOwnerRecord("vex", "Head of a chain", parentId = null, id = "m-a")
        memories.insertOwnerRecord("vex", "Middle of the chain", parentId = "m-a", id = "m-b")
        memories.insertOwnerRecord("vex", "End of the chain", parentId = "m-b", id = "m-c")
        memories.insertOwnerRecord("sol", "Sol remembers alone", parentId = null, id = "m-sol")

        personas.delete("vex")

        assertEquals(
            emptyList<String>(), memories.recordsOf("vex").map { it.id },
            "memory is live state — once the member is gone there is nothing left for a row to mean",
        )
        assertNull(memories.rootOf("vex"))
        assertEquals(listOf("m-sol"), memories.recordsOf("sol").map { it.id })
        assertEquals(1, rowCount())
    }

    @Test
    fun `markJudged stamps the passed instant and judgedAt reads it back parsed`() {
        // The stamp is the CALLER's pre-query read instant (bed019fe): a value deliberately not
        // fixedNow, so an implementation that read the injected clock instead would redden here.
        seedRoster()
        assertNull(memories.judgedAt("vex"), "a member the pass has never looked at reads null")

        memories.markJudged("vex", readAt)

        assertEquals(Instant.parse(readAt), memories.judgedAt("vex"))
        assertNull(memories.judgedAt("sol"), "the window is per member")
        assertNull(memories.judgedAt("nobody"), "an unknown member reads null, never throws — a mid-run delete is a race, not a bug")
    }

    @Test
    fun `a malformed watermark reads as null with a warning, never a throw`() {
        // §2.2: a corrupt stamp must degrade to "look again" (bounded by the 90-day horizon), not
        // break the weekly run. The WARN is the operator's breadcrumb and part of the contract.
        seedRoster()
        jdbc.update("UPDATE persona SET memory_judged_at = ? WHERE id = 'vex'", "not-a-timestamp")

        LogCapture.on(PersonaMemoryRepository::class.java).use { logs ->
            assertNull(memories.judgedAt("vex"))
            val event = logs.withEvent("memory.watermark.malformed").single()
            assertEquals(Level.WARN, event.level)
            assertEquals("vex", logs.keyValue(event, "personaId"))
        }
    }
}
