package com.aiforum.config

import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.RelationStanceRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Idempotent seeding of the predefined persona roster (`aiforum.seed.personas`). [seedMissing] inserts
 * any configured persona that doesn't already exist — matched by id — and returns how many it added, so
 * a reboot never duplicates and an owner's edits are never clobbered.
 *
 * The startup *trigger* is split out into [PersonaSeedRunner] (which is `@Profile("!test")`): this bean
 * carries only the testable logic and exists in every profile, so the acceptance suite can drive it
 * against the real DB + real config + real members page. That mirrors the §14 skill's rule for a
 * `@Profile("!test")` adapter — keep the un-fakeable trigger thin, test the logic above the seam.
 */
@Component
@EnableConfigurationProperties(PersonaSeedProperties::class)
class PersonaSeeder(
    private val personas: PersonaRepository,
    private val stances: RelationStanceRepository,
    private val props: PersonaSeedProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Insert every configured persona that isn't already present (by id); returns the number added. */
    fun seedMissing(): Int =
        props.personas.count { persona ->
            (personas.find(persona.id) == null).also { missing ->
                // First-seed only: an owner's later edits to abilities/dials are never clobbered on reboot
                // (idempotency preserved). The hand-authored abilities/dials (S2) matter for a fresh boot —
                // with empty abilities relevance is permanently 0, so no ambient comment could ever fire.
                if (missing) personas.insert(
                    persona.id, persona.name, persona.descriptor, persona.model,
                    abilities = persona.abilities, dials = persona.dials,
                )
            }
        }

    /**
     * Insert every configured stance whose directed edge is absent (S3, `aiforum.seed.stances`); returns
     * the number added. Insert-only, never update: the same first-seed rule the roster follows, so an
     * owner's rewritten stance survives every reboot. A row the owner *deleted* does come back — matching
     * the roster's existing resurrect-on-reseed behaviour rather than inventing a tombstone.
     *
     * A configured edge naming a persona that doesn't exist is skipped with a warning instead of failing:
     * the seed list is hand-authored config, and one stale id must not abort the pass (or, via the runner,
     * the boot) for the other 41 valid edges. Both endpoints are checked because the V24 foreign keys
     * would otherwise turn a typo into a startup crash.
     */
    fun seedMissingStances(): Int =
        props.stances.count { seed ->
            val endpointsExist = personas.find(seed.from) != null && personas.find(seed.to) != null
            if (!endpointsExist) {
                log.warn(
                    "event=seed.stance.skipped from={} to={} reason=unknown-persona",
                    seed.from, seed.to,
                )
                return@count false
            }
            (stances.find(seed.from, seed.to) == null).also { missing ->
                if (missing) stances.upsert(
                    seed.from, seed.to, seed.stance, RelationStanceRepository.SOURCE_SEEDED,
                )
            }
        }
}

/**
 * Runs [PersonaSeeder.seedMissing] once at startup so a fresh DB comes up with a usable team rather than
 * forcing the owner to hand-author personas first. Disabled under the `test` profile — acceptance
 * scenarios drive seeding explicitly against a per-scenario-wiped DB, so auto-seeding at context start
 * would just be noise.
 */
@Component
@Profile("!test")
class PersonaSeedRunner(private val seeder: PersonaSeeder) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val seeded = seeder.seedMissing()
        if (seeded > 0) log.info("Seeded {} predefined persona(s) into the forum.", seeded)
        // Stances second: every edge references two personas, so the roster has to be in place first —
        // on a fresh DB both phases run in the same boot and the ordering is what makes that work.
        val stances = seeder.seedMissingStances()
        if (stances > 0) log.info("Seeded {} predefined persona stance(s).", stances)
    }
}

/**
 * `aiforum.seed.personas` — the predefined roster. Each entry carries the same fields the admin create
 * form collects; `slug` and `system_prompt` are derived by [PersonaRepository.insert]. A blank `model`
 * falls back to `aiforum.llm.default-model`.
 */
@ConfigurationProperties(prefix = "aiforum.seed")
data class PersonaSeedProperties(
    val personas: List<SeedPersona> = emptyList(),
    // S3 (plan_docs/ambient-slice-3.md §2.6): the hand-authored relation graph. Free text only — a stance
    // that became a number would re-import the cut reward economy.
    val stances: List<SeedStance> = emptyList(),
) {
    data class SeedPersona(
        val id: String = "",
        val name: String = "",
        val descriptor: String = "",
        val model: String = "",
        // S2 (plan_docs/ambient-slice-2.md §3): hand-authored ability tags (drive ambient RELEVANCE) and
        // dials (esp. `talkativeness`, P(comment)). Applied on first seed only; missing → empty/neutral.
        val abilities: List<String> = emptyList(),
        val dials: Map<String, Int> = emptyMap(),
    )

    /** One directed edge: what [from] thinks of [to], as prose. Ids must match [SeedPersona.id]. */
    data class SeedStance(
        val from: String = "",
        val to: String = "",
        val stance: String = "",
    )
}
