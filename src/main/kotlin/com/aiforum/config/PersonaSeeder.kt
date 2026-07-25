package com.aiforum.config

import com.aiforum.persona.Interests
import com.aiforum.repo.PersonaInterestRepository
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
    private val interests: PersonaInterestRepository,
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

    /**
     * Insert every configured interest phrase a member does not already hold (S4b, the `interests:` key on
     * each `aiforum.seed.personas` entry); returns the number added. The roster's and the stance list's
     * first-seed-only contract, third time: an owner who rewrote — or deliberately deleted then re-typed —
     * a phrase must not have it clobbered on reboot, and a phrase the owner *deleted* does come back on the
     * next boot, matching the resurrect-on-reseed behaviour the other two phases already have. That
     * resurrection is the documented way back from a pin (D11b): blank the field, and the seed restores the
     * phrase as `seeded` so it is free to drift again.
     *
     * A configured phrase naming a persona that does not exist is skipped with a warning rather than
     * failing, exactly as [seedMissingStances] skips an unknown endpoint: V27's foreign key would otherwise
     * turn one stale id in hand-authored config into a startup crash that takes the other six members'
     * interests down with it.
     *
     * Presence is checked against [Interests.clean]ed, case-folded text because that is the key the row is
     * actually stored under — `persona_interest.interest` is `COLLATE NOCASE` and
     * [PersonaInterestRepository.upsert] cleans on the way in. Comparing raw YAML (which carries whatever
     * indentation and casing the file has) against stored text would read every phrase as missing on every
     * boot, and each re-"insert" would restamp provenance — quietly relabelling an owner-pinned phrase back
     * to `seeded` and un-pinning it. The set is MUTABLE and grows as phrases go in, so a phrase listed
     * twice under one member is counted and written once.
     *
     * **No phrase configured here may contain the substring `vote`** — which also rules out *devoted*,
     * *pivoted*, *voting* — nor a digit. These phrases are injected verbatim into generation prompts (D7),
     * and `OwnerControlSteps.noVoteSignal` lowercases the whole system prompt and asserts `vote` is absent;
     * V27's `interest NOT GLOB '*[0-9]*'` CHECK refuses a digit outright for every source but `owner`.
     * Same rule, same reason, as the one recorded at `TestData.kt:37-45`.
     *
     * A digit-carrying configured phrase therefore throws out of this method at boot, and that is
     * deliberate rather than an oversight: unlike an unknown persona id — where the *other* members'
     * interests are still perfectly seedable, so skipping loses nothing — a refused phrase has no valid
     * outcome, and skipping it would leave the owner's authored interest silently absent from the room
     * forever with only a log line to say so. The unknown-persona skip buys the other six members their
     * interests; a digit skip would buy nothing but a quieter failure.
     */
    fun seedMissingInterests(): Int = props.personas.sumOf(::interestsSeededFor)

    /** [seedMissingInterests] for one member; split out so `sumOf` has a declared `Int` to resolve on. */
    private fun interestsSeededFor(seed: PersonaSeedProperties.SeedPersona): Int {
        if (personas.find(seed.id) == null) {
            log.warn("event=seed.interest.skipped persona={} reason=unknown-persona", seed.id)
            return 0
        }
        val held = interests.phrasesOf(seed.id).mapTo(mutableSetOf()) { key(it) }
        return seed.interests.count { phrase ->
            // `add` returns true only when the phrase was genuinely absent — the missing test and the
            // guard against a duplicated config entry in one statement.
            held.add(key(phrase)).also { missing ->
                if (missing) interests.upsert(seed.id, phrase, PersonaInterestRepository.SOURCE_SEEDED)
            }
        }
    }

    /** The identity a stored interest row actually has: [Interests.clean]ed, then folded like `NOCASE`. */
    private fun key(phrase: String): String = Interests.clean(phrase).lowercase()
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
        // Interests third, for the same ordering reason stances go second: every row references a persona
        // through V27's foreign key, so the roster has to be in place first. On a fresh DB all three phases
        // run in the same boot and this ordering is what makes that work.
        val interests = seeder.seedMissingInterests()
        if (interests > 0) log.info("Seeded {} predefined persona interest(s).", interests)
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
        // S4b (plan_docs/ambient-slice-4b.md D11): the MUTABLE half of a member — short prose phrases the
        // weekly drift pass may swap one-for-one, as against the fixed `descriptor`/`abilities`/`dials`
        // above. Seeded `source='seeded'`, so every one of them is open to drift from the first boot; an
        // owner pins one by typing it into the edit form, which restamps it `owner`. The field and the yml
        // key must land together: Spring silently ignores an unknown property, so an `interests:` block
        // with no field here would bind to NOTHING and the room would boot interest-less with no error.
        val interests: List<String> = emptyList(),
    )

    /** One directed edge: what [from] thinks of [to], as prose. Ids must match [SeedPersona.id]. */
    data class SeedStance(
        val from: String = "",
        val to: String = "",
        val stance: String = "",
    )
}
