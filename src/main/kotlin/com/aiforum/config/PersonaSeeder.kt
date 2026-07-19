package com.aiforum.config

import com.aiforum.repo.PersonaRepository
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
    private val props: PersonaSeedProperties,
) {
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
}
