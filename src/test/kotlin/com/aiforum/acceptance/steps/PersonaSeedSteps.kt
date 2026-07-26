package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.Html
import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import com.aiforum.config.PersonaSeedProperties
import com.aiforum.config.PersonaSeeder
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.RelationStanceRepository
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Drives the real seeding logic (the un-gated [PersonaSeeder] bean) against the real config
 * ([PersonaSeedProperties], bound from application.yml) and asserts the outcome over HTTP on the real
 * members page. The startup trigger is `@Profile("!test")`, so the scenario invokes seedMissing()
 * directly — there is no HTTP surface for a startup concern — but everything it asserts is full-stack.
 *
 * Seeding is two phases now: the persona roster, then the relation stances (aiforum.seed.stances) that
 * are hand-authored alongside it. [personaRepo] and [stanceRepo] back a couple of scenario-only setup
 * steps (removing a persona, forging an owner edit) — never used to fake production logic, only to arrange
 * state the same way an owner action or a stale config entry would.
 *
 * NOT @Component: glue is instantiated by Cucumber, which injects these Spring beans (see the
 * cucumber-spring-bdd skill).
 */
class PersonaSeedSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
    private val seeder: PersonaSeeder,
    private val props: PersonaSeedProperties,
    private val personaRepo: PersonaRepository,
    private val stanceRepo: RelationStanceRepository,
) {
    @Given("an empty forum")
    fun anEmptyForum() {
        // The DB is wiped before every scenario (DatabaseResetHooks); assert the precondition so a
        // seeding scenario can never silently lean on personas left by something else.
        val body = http.get("/personas").body ?: ""
        props.personas.forEach { p ->
            assertTrue(!Html.hasAttr(body, "data-persona-id", p.id), "forum was not empty: ${p.id} already present")
        }
    }

    @When("the predefined personas are seeded")
    @When("the predefined personas are seeded again")
    @Given("the predefined personas have already been seeded")
    fun seedPredefinedPersonas() {
        // Persona phase first, stance phase second — a stance can only be seeded once both its endpoints
        // exist. world.lastSeedCount keeps meaning the PERSONA count exactly as before this slice (the
        // existing "no personas are added the second time" assertion depends on that); the stance count
        // gets its own field so the two idempotency stories don't collide.
        world.lastSeedCount = seeder.seedMissing()
        world.lastStanceSeedCount = seeder.seedMissingStances()
        // Interest phase third (S4b), same ordering rule as stances: a member must exist before its
        // phrases can hang off it. Not surfaced on `world` — no scenario asserts a count of interests,
        // and a field nobody reads is one more thing to keep true.
        seeder.seedMissingInterests()
    }

    @Then("every predefined persona appears in the members list")
    fun everyPredefinedPersonaAppears() {
        val body = http.get("/personas").body ?: ""
        assertTrue(props.personas.isNotEmpty(), "no predefined personas configured — nothing to assert")
        props.personas.forEach { p ->
            assertTrue(Html.hasAttr(body, "data-persona-id", p.id), "expected ${p.id} in the members list:\n$body")
        }
    }

    @Then("no personas are added the second time")
    fun noPersonasAddedSecondTime() {
        assertEquals(0, world.lastSeedCount, "expected the re-seed to add nothing")
    }

    @Then("every predefined persona appears exactly once in the members list")
    fun everyPredefinedPersonaAppearsOnce() {
        val body = http.get("/personas").body ?: ""
        props.personas.forEach { p ->
            assertEquals(1, Html.countAttr(body, "data-persona-id", p.id), "expected exactly one ${p.id} in:\n$body")
        }
    }

    /**
     * Simulates an owner hand-rewriting a relation in the edit form — writes straight through
     * [RelationStanceRepository] with SOURCE_OWNER provenance, the same marker the real edit-form POST
     * would apply, so re-seeding must see it as owner-authored and leave it alone.
     */
    @Given("the owner has rewritten the stance from {string} toward {string} as {string}")
    fun ownerRewritesStance(from: String, to: String, rewritten: String) {
        stanceRepo.upsert(from, to, rewritten, RelationStanceRepository.SOURCE_OWNER)
    }

    /**
     * Simulates the roster drifting out from under the stance config — e.g. the owner deletes a member
     * by hand without also pruning aiforum.seed.stances. Real deletion (not a stub): proves the seeder
     * has to actually check persona existence, not just assume config is internally consistent.
     */
    @Given("persona {string} has been removed from the roster")
    fun personaRemovedFromRoster(id: String) {
        personaRepo.delete(id)
    }

    /**
     * Re-runs ONLY the stance phase — deliberately not the combined [seedPredefinedPersonas], which
     * would immediately re-insert a persona just removed via [personaRemovedFromRoster] and defeat the
     * missing-persona scenario before it gets to prove anything.
     */
    @When("only the predefined stances are re-seeded")
    fun reseedStancesOnly() {
        world.lastStanceSeedCount = seeder.seedMissingStances()
    }
}
