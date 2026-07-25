package com.aiforum.tier1.repo

import com.aiforum.acceptance.support.TestData
import com.aiforum.repo.PersonaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Tier-1: PersonaRepository against the real test SQLite DB (see the bdd-tiered-testing skill). Pins the
 * V4 per-persona `model` column — it must round-trip through insert/find/findAll, and a persona created
 * without one defaults to blank (the aiforum.llm.default-model fallback applies downstream).
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class PersonaRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var data: TestData
    @Autowired lateinit var personas: PersonaRepository

    @BeforeEach
    fun clean() {
        // persona_stance (V24) before persona: its FKs would CASCADE the rows away anyway, but the house
        // rule is child-first and explicit, so a future FK arriving without CASCADE can't silently block.
        listOf("vote", "event_log", "comment", "thread", "persona_stance", "persona")
            .forEach { jdbc.update("DELETE FROM $it") }
    }

    @Test
    fun `a pinned model round-trips through insert and find`() {
        personas.insert("vex", "Vex", "systems contrarian", model = "opus")
        assertEquals("opus", personas.find("vex")?.model)
    }

    @Test
    fun `a persona inserted without a model defaults to blank`() {
        personas.insert("sol", "Sol", "index whisperer")
        assertEquals("", personas.find("sol")?.model)
    }

    @Test
    fun `findAll carries each persona's model`() {
        personas.insert("vex", "Vex", "systems contrarian", model = "opus")
        personas.insert("sol", "Sol", "index whisperer")
        assertEquals(mapOf("vex" to "opus", "sol" to ""), personas.findAll().associate { it.id to it.model })
    }

    @Test
    fun `a persona seeded with the pre-V4 columns reads back a blank model`() {
        // TestData.insertPersona omits the model column, exercising the DEFAULT '' the migration sets.
        data.insertPersona(id = "lune", name = "Lune")
        assertEquals("", personas.find("lune")?.model)
    }

    @Test
    fun `abilities and dials round-trip through insert and find`() {
        personas.insert(
            "lune", "Lune", "",
            systemPrompt = "composed",
            abilities = listOf("kotlin", "systems"),
            dials = mapOf("verbosity" to 1, "agreeableness" to 2),
        )
        val found = personas.find("lune")!!
        assertEquals(listOf("kotlin", "systems"), found.abilities)
        assertEquals(1, found.dials["verbosity"])
        assertEquals(2, found.dials["agreeableness"])
    }

    @Test
    fun `insert normalizes the dials to the fixed schema`() {
        // off-schema key dropped, out-of-range clamped, missing axes defaulted.
        personas.insert("vex", "Vex", "", dials = mapOf("charisma" to 7, "verbosity" to 99))
        val dials = personas.find("vex")!!.dials
        assertEquals(com.aiforum.persona.Dials.KEYS.toSet(), dials.keys)
        assertEquals(com.aiforum.persona.Dials.MAX, dials["verbosity"])
        assertEquals(com.aiforum.persona.Dials.DEFAULT, dials["rigor"])
    }

    @Test
    fun `update rewrites traits and prompt but keeps the colour slot`() {
        personas.insert("vex", "Vex", "", systemPrompt = "OLD", abilities = listOf("a"), dials = mapOf("warmth" to 1))
        val colour = personas.find("vex")!!.colorIndex

        personas.update("vex", "Vex", "", model = "", systemPrompt = "NEW", abilities = listOf("b", "c"), dials = mapOf("warmth" to 9))

        val updated = personas.find("vex")!!
        assertEquals("NEW", updated.systemPrompt)
        assertEquals(listOf("b", "c"), updated.abilities)
        assertEquals(9, updated.dials["warmth"])
        assertEquals(colour, updated.colorIndex, "the avatar colour slot is stable across edits")
    }

    @Test
    fun `delete removes the persona and leaves the others intact`() {
        personas.insert("vex", "Vex", "systems contrarian")
        personas.insert("sol", "Sol", "index whisperer")

        personas.delete("vex")

        assertEquals(null, personas.find("vex"))
        assertEquals(listOf("Sol"), personas.findAll().map { it.name })
    }

    @Test
    fun `delete is a no-op when the persona does not exist`() {
        personas.insert("sol", "Sol", "index whisperer")
        personas.delete("ghost")
        assertEquals(listOf("Sol"), personas.findAll().map { it.name })
    }

    @Test
    fun `two personas with the same name get distinct slugs and each resolves by slug`() {
        // V16 makes persona.slug UNIQUE; insert suffixes collisions (ada, ada-2, ada-3) so two
        // same-named personas never collide on their profile link and findBySlug resolves each.
        personas.insert("ada1", "Ada", "first")
        personas.insert("ada2", "Ada", "second")
        personas.insert("ada3", "Ada", "third")

        assertEquals("ada", personas.find("ada1")?.slug, "the first Ada keeps the bare slug")
        assertEquals("ada-2", personas.find("ada2")?.slug)
        assertEquals("ada-3", personas.find("ada3")?.slug)

        assertEquals("ada1", personas.findBySlug("ada")?.id)
        assertEquals("ada2", personas.findBySlug("ada-2")?.id)
        assertEquals("ada3", personas.findBySlug("ada-3")?.id)
    }

    @Test
    fun `a sole persona keeps its bare slug with no suffix`() {
        personas.insert("ada", "Ada", "only")
        assertEquals("ada", personas.find("ada")?.slug, "a non-colliding insert is unsuffixed")
    }

    @Test
    fun `a persona seeded with the pre-V10 columns reads back empty abilities and dials`() {
        // The DEFAULT '[]' / '{}' the migration sets must deserialize to empty collections.
        jdbc.update(
            "INSERT INTO persona(id, name, handle, system_prompt, slug, color_index) VALUES (?,?,?,?,?,?)",
            "old", "Old", "old", "You are Old.", "old", 0,
        )
        val found = personas.find("old")!!
        assertEquals(emptyList<String>(), found.abilities)
        assertEquals(emptyMap<String, Int>(), found.dials)
    }
}
