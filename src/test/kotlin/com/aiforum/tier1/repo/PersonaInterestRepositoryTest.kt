package com.aiforum.tier1.repo

import com.aiforum.repo.PersonaInterestRepository
import com.aiforum.repo.PersonaInterestRepository.Companion.SOURCE_DRIFTED
import com.aiforum.repo.PersonaInterestRepository.Companion.SOURCE_OWNER
import com.aiforum.repo.PersonaInterestRepository.Companion.SOURCE_SEEDED
import com.aiforum.repo.PersonaRepository
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
 * Tier-1: [PersonaInterestRepository] against the real test SQLite DB (V27 `persona_interest`, and
 * `persona.interests_judged_at`). Pins the four things the mutable half of a persona's character rests on —
 * the (persona, phrase) upsert identity under NOCASE, the `source` provenance that makes pinning work, the
 * CHECK/CASCADE guards, and the per-member judgment watermark.
 *
 * **The two CHECK tests are the guardrail itself, not constraint trivia.** `interest NOT GLOB '*[0-9]*'` is
 * the first point where "no number ever reaches what a member is into" is enforced by the database rather
 * than by a parser a future writer could bypass — and it is deliberately SCOPED to non-owner rows, so an
 * owner typing "http/3" is not collateral damage. Unscoped it would throw inside `PersonaController.edit`,
 * where interest writes run before the prompt logic, and the owner would silently lose their descriptor and
 * dial edits too. Both halves of that decision are asserted below, in one test, on the same phrase.
 *
 * The watermark assertions are about *cost*. `interests_judged_at` is what stops a settled member — one
 * whose judge keeps answering NONE, which writes no audit row by design — from re-buying the same LLM
 * judgment on every run. Two of its rules are only ever enforced here: no interest write may move it (or an
 * owner pinning a phrase would declare the member freshly judged and mute drift), and a null must CLEAR it
 * rather than be ignored (or a reverted member could never be reconsidered from the evidence the owner
 * rejected).
 *
 * `max-interests` is deliberately NOT pinned here: SQLite cannot express "at most four rows per persona_id"
 * in a CHECK, so the ceiling is kept by the swap-only write path and the controller's guard, and it is
 * asserted at Tier 2 and in acceptance. A reader who expects a fifth `upsert` to fail will not find that
 * here, because it does not fail.
 *
 * The cascade assertion is real, not decorative: the test datasource URL carries `foreign_keys=on`
 * (application-test.yml), so SQLite enforces the V27 foreign key per connection. Without that pragma SQLite
 * ignores FKs entirely and the cascade test would pass vacuously — if it ever disappears from the URL,
 * `deleting a persona cascades…` is the test that should be trusted to fail.
 *
 * Cleanup wipes `persona_interest` before `persona` (child first) in both @BeforeEach and @AfterEach: the
 * CASCADE would cover it, but the sibling tier-1 classes wipe `persona` directly and must never find rows of
 * ours hanging off it.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class PersonaInterestRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var personas: PersonaRepository
    @Autowired lateinit var interests: PersonaInterestRepository

    // The test profile pins Clock to this instant (FixedClockConfig), so updated_at is exactly assertable.
    private val fixedNow = "2026-01-01T12:00:00Z"

    // markJudged takes its stamp from the CALLER (the instant the evidence window was read), not from the
    // clock, so the watermark tests pass an instant deliberately different from `fixedNow` — the 04:00
    // scheduled hour. Anything asserting this value could not be satisfied by an accidental `updated_at`.
    private val judgedAt = "2026-01-01T04:00:00Z"

    @BeforeEach @AfterEach
    fun clean() {
        listOf("persona_interest", "persona").forEach { jdbc.update("DELETE FROM $it") }
    }

    private fun seedRoster() {
        personas.insert("vex", "Vex", "systems contrarian")
        personas.insert("sol", "Sol", "index whisperer")
        personas.insert("lune", "Lune", "quiet synthesiser")
    }

    private fun rowCount(): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM persona_interest", Int::class.java) ?: 0

    @Test
    fun `an interest round-trips with its provenance and reads back in phrase order`() {
        // The order is load-bearing, not cosmetic: this list is rendered into the member's generation prompt,
        // so an unstable order would hand two runs two different prompts for no reason. Written here in the
        // wrong order on purpose, so a dropped ORDER BY would show up.
        seedRoster()
        interests.upsert("vex", "storage engines", SOURCE_SEEDED)
        interests.upsert("vex", "boring technology choices", SOURCE_DRIFTED)

        val held = interests.of("vex")

        assertEquals(listOf("boring technology choices", "storage engines"), held.map { it.interest })
        // The literal list, NOT `held.map { it.interest }`: phrasesOf IS that expression, so comparing
        // the two restates the implementation and cannot fail while the one-liner stands.
        assertEquals(
            listOf("boring technology choices", "storage engines"), interests.phrasesOf("vex"),
            "phrasesOf renders the member's phrases in the same stable order the prompt block needs",
        )
        val drifted = held.first()
        assertEquals("vex", drifted.personaId)
        assertEquals(SOURCE_DRIFTED, drifted.source)
        assertEquals(fixedNow, drifted.updatedAt)
    }

    @Test
    fun `an upsert replaces the phrase's provenance and leaves the judgment watermark where it was`() {
        // Pinning IS this write: the owner typing a phrase the seeder authored must leave the row marked
        // owner-authored, or it stays a candidate the pass may overwrite. And the watermark must not move —
        // an owner fixing one phrase asked for that phrase to stop drifting, not for the member to fall
        // silent until brand-new engagement arrives.
        seedRoster()
        interests.upsert("vex", "storage engines", SOURCE_SEEDED)
        interests.markJudged("vex", judgedAt)
        // Backdate the stored stamp by hand so the refresh is observable: the test clock is fixed, so two
        // repo writes would otherwise be indistinguishable and `updated_at = excluded.updated_at` could
        // silently rot without a test noticing.
        jdbc.update("UPDATE persona_interest SET updated_at = ? WHERE persona_id = 'vex'", "2020-01-01T00:00:00Z")

        interests.upsert("vex", "storage engines", SOURCE_OWNER)

        assertEquals(1, rowCount(), "the (persona_id, interest) PRIMARY KEY collapses the re-write into one row")
        val held = interests.of("vex").single()
        assertEquals(SOURCE_OWNER, held.source)
        assertEquals(fixedNow, held.updatedAt, "the upsert restamps updated_at from the injected clock")
        assertEquals(judgedAt, interests.judgedAt("vex"), "an interest write must never touch the watermark")
    }

    @Test
    fun `delete removes exactly one phrase and leaves the member's others standing`() {
        // A member's interests are independent of each other, and of everyone else's: retracting one phrase
        // must not quietly cost the member the rest of what they are into.
        seedRoster()
        interests.upsert("vex", "storage engines", SOURCE_SEEDED)
        interests.upsert("vex", "boring technology choices", SOURCE_DRIFTED)
        interests.upsert("sol", "index design", SOURCE_SEEDED)

        interests.delete("vex", "storage engines")

        assertEquals(listOf("boring technology choices"), interests.phrasesOf("vex"))
        assertEquals(listOf("index design"), interests.phrasesOf("sol"), "another member's phrase is untouched")
    }

    @Test
    fun `the digit CHECK refuses a drifted phrase carrying a number but admits the owner's own`() {
        // The scoped-CHECK decision, pinned on ONE phrase written twice: the rule exists to stop a MODEL
        // smuggling a magnitude in, and an owner typing "http/3" is not that. If a later "consistency"
        // tidy-up drops the `source = 'owner' OR` clause, the second half of this test reddens — which is
        // far better than an owner losing their descriptor edit to a DataAccessException in
        // PersonaController.edit, where the interest writes run first.
        seedRoster()

        // Broad DataAccessException rather than DataIntegrityViolationException: the constraint is what's
        // under test, and Spring's SQLite exception translation is a driver detail we don't want to pin.
        assertThrows(DataAccessException::class.java) {
            interests.upsert("vex", "http/3 head-of-line blocking", SOURCE_DRIFTED)
        }
        assertEquals(0, rowCount(), "the rejected write left nothing behind")

        interests.upsert("vex", "http/3 head-of-line blocking", SOURCE_OWNER)

        assertEquals(SOURCE_OWNER, interests.of("vex").single().source, "the same phrase is fine from the owner")
    }

    @Test
    fun `the length CHECK refuses a one-character phrase and one over eighty`() {
        // Both bounds, because both are agreements with a pure validator the owner path uses instead of this
        // CHECK (Interests.MIN_CHARS / MAX_CHARS) — if the two ever disagree, the owner form starts handing
        // SQL a phrase it will throw on.
        seedRoster()

        assertThrows(DataAccessException::class.java) { interests.upsert("vex", "a", SOURCE_SEEDED) }
        assertThrows(DataAccessException::class.java) { interests.upsert("vex", "e".repeat(81), SOURCE_SEEDED) }
        assertEquals(0, rowCount())

        // Two characters is admitted on purpose — the bound exists to catch a stray letter, not to outlaw
        // "Go" and "AI".
        interests.upsert("vex", "AI", SOURCE_SEEDED)

        assertEquals(listOf("AI"), interests.phrasesOf("vex"))
    }

    @Test
    fun `COLLATE NOCASE makes two casings of one phrase the same row, not two`() {
        // Storage has to agree with the case-insensitive already-held refusal in the drift parse. If these
        // were two rows a member could hold "Storage engines" and "storage engines" at once, the count
        // invariant would leak, and the prompt would name one interest twice.
        seedRoster()
        interests.upsert("vex", "Storage engines", SOURCE_SEEDED)

        interests.upsert("vex", "storage engines", SOURCE_DRIFTED)

        assertEquals(1, rowCount())
        val held = interests.of("vex").single()
        assertEquals(SOURCE_DRIFTED, held.source, "the second write landed on the row that was already there")
        assertEquals(
            "Storage engines", held.interest,
            "the row keeps the casing it was created with — the upsert deliberately never rewrites the key",
        )

        // Delete matches the same way, which is what lets the drift path echo back a phrase in whatever
        // casing the model wrote it and still remove the row that exists.
        interests.delete("vex", "STORAGE ENGINES")

        assertEquals(0, rowCount())
    }

    @Test
    fun `deleting a persona cascades its interests away and spares everyone else's`() {
        seedRoster()
        interests.upsert("vex", "storage engines", SOURCE_SEEDED)
        interests.upsert("vex", "boring technology choices", SOURCE_DRIFTED)
        interests.upsert("sol", "index design", SOURCE_SEEDED)

        personas.delete("vex")

        assertEquals(
            emptyList<String>(), interests.phrasesOf("vex"),
            "an interest is live state — once the member is gone the phrase has nothing left to mean",
        )
        assertEquals(listOf("sol" to "index design"), interests.findAll().map { it.personaId to it.interest })
    }

    @Test
    fun `markJudged stamps this member's watermark and no interest write moves it`() {
        // The watermark is the whole cost story for a member who has never drifted: the audit table gets a
        // row only when something MOVED, and "nothing moved" is the designed steady state. Only markJudged
        // may write it, or an owner's Save (upsert) and a revert's restore (upsert) would both masquerade as
        // a fresh judgment.
        seedRoster()
        interests.upsert("vex", "storage engines", SOURCE_SEEDED)
        assertNull(interests.judgedAt("vex"), "a member the pass has never looked at: judge them over all of it")

        interests.markJudged("vex", judgedAt)
        interests.upsert("vex", "boring technology choices", SOURCE_DRIFTED)
        interests.delete("vex", "storage engines")

        assertEquals(judgedAt, interests.judgedAt("vex"), "the swap wrote phrases, never the watermark")
        assertNull(interests.judgedAt("sol"), "the window is per member — one judgment cannot speak for another")
    }

    @Test
    fun `markJudged with null clears the watermark, reopening the member's whole history`() {
        // Null CLEARS rather than being ignored, because that is what a revert needs: the member whose drift
        // the owner undid must be judgeable again from the very engagements that produced it (D10 — revert
        // undoes, it does not freeze). A no-op-on-null would wall that evidence off permanently.
        seedRoster()
        interests.markJudged("vex", judgedAt)

        interests.markJudged("vex", null)

        assertNull(interests.judgedAt("vex"))
    }

    @Test
    fun `sharedInterests maps every phrase to its holders, phrases ordered and holders ordered`() {
        // The raw material for the room map, and deliberately raw: the repository hands back rows and lets a
        // pure function decide what "shared" means, because a HAVING COUNT(*) here would be a number about
        // members living in SQL — one "let's store it too" away from a rankable score. Note that a phrase
        // held by exactly one member is present as well; the map is not pre-filtered.
        seedRoster()
        interests.upsert("sol", "boring technology choices", SOURCE_SEEDED)
        interests.upsert("vex", "boring technology choices", SOURCE_DRIFTED)
        interests.upsert("lune", "index design", SOURCE_SEEDED)
        interests.upsert("vex", "storage engines", SOURCE_SEEDED)

        val holders = interests.sharedInterests()

        assertEquals(
            listOf("boring technology choices", "index design", "storage engines"),
            holders.keys.toList(),
        )
        assertEquals(listOf("sol", "vex"), holders["boring technology choices"])
        assertEquals(listOf("lune"), holders["index design"])
        assertEquals(listOf("vex"), holders["storage engines"])
    }

    @Test
    fun `a phrase two members hold under different casings is one phrase in the room map`() {
        // The readout exists to make lexical convergence visible; two casings reading as two phrases would
        // let convergence hide behind a capital letter. The surviving key is the lowest-ordered holder's
        // casing, which the ORDER BY makes deterministic rather than whichever row SQLite happened to visit.
        seedRoster()
        interests.upsert("sol", "Agents", SOURCE_SEEDED)
        interests.upsert("vex", "agents", SOURCE_DRIFTED)

        val holders = interests.sharedInterests()

        assertEquals(listOf("Agents"), holders.keys.toList())
        assertEquals(listOf("sol", "vex"), holders["Agents"])
    }
}
