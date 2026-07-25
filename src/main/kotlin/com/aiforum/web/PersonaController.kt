package com.aiforum.web

import com.aiforum.persona.Abilities
import com.aiforum.persona.Dials
import com.aiforum.persona.Interests
import com.aiforum.persona.PersonaPromptRefresher
import com.aiforum.persona.PersonaSpec
import com.aiforum.persona.PriorComposition
import com.aiforum.persona.PromptComposer
import com.aiforum.repo.PersonaInterestRepository
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.RelationStanceRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

/**
 * One phrase a member is currently into, as the profile renders it (S4b,
 * plan_docs/ambient-slice-4b.md D9). Carries [source] because the profile's whole job here is to make the
 * mutable/immutable split legible: a phrase the owner typed is frozen for good, a seeded or drifted one
 * is what the weekly pass may move.
 *
 * There is deliberately no strength, no age and no "held since" magnitude — [phrase] is the entire
 * interest, and nothing on a member's page may be a number the owner (or a model) could rank members by
 * (V27 header).
 */
data class InterestTagView(
    val phrase: String,
    val source: String,
) {
    /**
     * The tag's classes, decided here so the template carries no conditional and — the reason that
     * matters — so app.css can style the pinned case off a CLASS rather than off `data-interest-source`.
     * The data-* hooks are the acceptance probe's surface (jte-spring-kotlin: *style with `class=`, never
     * with `data-*`*), and a stylesheet that starts depending on one turns a test hook into a visual
     * contract nobody knows they are holding.
     */
    val tagClass: String get() =
        if (source == PersonaInterestRepository.SOURCE_OWNER) "tag tag--interest tag--pinned"
        else "tag tag--interest"
}

data class PersonaView(
    val id: String,
    val name: String,
    val descriptor: String,
    val slug: String,
    val model: String = "",
    val colorIndex: Int = 0,
    val abilities: List<String> = emptyList(),
    val dials: Map<String, Int> = emptyMap(),
    val systemPrompt: String = "",
    // Trailing and defaulted so every other construction site (the members list, the create/compose paths)
    // keeps compiling unchanged and simply renders no interest block — which is also the honest reading
    // for a member the owner authored none for.
    val interests: List<InterestTagView> = emptyList(),
) {
    /**
     * The phrases as one comma-joined string for the profile's `data-persona-interests` hook, computed
     * here rather than in the template: JTE expressions carrying a lambda (`joinToString { it.phrase }`)
     * are brace-counting territory in a parser that already has two documented traps, and the join is
     * view formatting either way — [CitedEngagementView.permalink] precomputes for the same reason.
     */
    val interestPhrases: String get() = interests.joinToString(", ") { it.phrase }
}

/**
 * One outgoing stance as the profile renders it (S3, plan_docs/ambient-slice-3.md §2.5). Carries the
 * target's id AND its display name because the page needs both for different jobs: the id is the stable
 * `data-stance-to` hook, the name is the prose the owner reads. There is deliberately no strength/score
 * field — [text] is the whole relation, and the model has no numeric place to grow one.
 */
data class StanceView(
    val toId: String,
    val toName: String,
    val text: String,
)

/**
 * One row of the edit form's Relations fieldset: every OTHER member gets a field, whether or not a
 * stance exists yet ([text] is blank when it doesn't), because the form is how a stance is CREATED.
 *
 * Prepared here rather than handed to the template as a roster plus a lookup map: JTE parses `@param`
 * declarations itself, and a generic carrying a comma (`Map<String, String>`) breaks that parse. A flat
 * list of ready-to-render rows keeps the template to a single loop with no lookups.
 */
data class StanceFieldView(
    val toId: String,
    val toName: String,
    val text: String,
)

/**
 * One row of the edit form's "Currently into" fieldset (S4b D11): a `name="interest_<n>"` input, its
 * current value, and the provenance note beside it.
 *
 * The field name is an INDEX, not the phrase — the phrase is what the owner is editing, so keying the
 * field by it would make "typography" → "kernel scheduling" arrive as a delete of one field and an insert
 * of another with no way to tell that apart from a hand-crafted POST. The server reconciles the whole
 * submitted set against what the member holds instead ([PersonaController.applyInterestEdits]), so the
 * indexes only have to be unique.
 *
 * [note] is prepared here rather than decided in the template because it states a RULE the owner needs
 * before they type: an interest they write is skipped by the pass for good, and there is no unpin button.
 */
data class InterestFieldView(
    val name: String,
    val value: String,
    val note: String,
)

@Controller
class PersonaController(
    private val personas: PersonaRepository,
    private val composer: PromptComposer,
    private val stances: RelationStanceRepository,
    // Owns "recompose this persona from scratch" — shared with the stance-evolution pass, which refreshes
    // a persona whose relations just moved (plan_docs/ambient-slice-4a.md D11).
    private val refresher: PersonaPromptRefresher,
    // The mutable half of a member's character (S4b). Read on both persona GETs and written by the edit
    // form; the drift pass writes the same table from its own service.
    private val interests: PersonaInterestRepository,
) {

    // Profile URLs use the slug (V5) so multi-word names ("Ada Lovelace") work without %20 noise.
    @GetMapping("/personas/{slug}")
    fun profile(@PathVariable slug: String, model: Model): String {
        val persona = personas.findBySlug(slug) ?: return "redirect:/personas"
        // The profile is where an owner actually reads what a member is into, so it is also where the
        // pass's effect becomes visible — the acceptance scenarios assert the swap on this page rather
        // than on a repository, because an interest that is only right in the database is not the one the
        // owner reads.
        model.addAttribute("persona", view(persona, interestTags(persona.id)))
        // Only the persona's OUTGOING stances: a profile shows what this member thinks of the room, not
        // what the room thinks of them — the same asymmetry the generation prompt uses (a persona is
        // never handed the others' opinions of it).
        model.addAttribute("stances", stanceViews(persona.id))
        return "persona"
    }

    @GetMapping("/personas")
    fun list(model: Model): String {
        model.addAttribute("personas", personas.findAll().map { view(it) })
        model.addAttribute("dialKeys", Dials.KEYS)
        model.addAttribute("dialDefault", Dials.DEFAULT)
        return "personas"
    }

    @PostMapping("/personas")
    fun create(
        @RequestParam name: String,
        @RequestParam(defaultValue = "") descriptor: String,
        @RequestParam(defaultValue = "") model: String,
        @RequestParam(defaultValue = "") abilities: String,
        @RequestParam(defaultValue = "") systemPrompt: String,
        @RequestParam allParams: Map<String, String>,
    ): String {
        // The abilities + dials are the structured inputs the prompt is composed from. Save-what-you-see:
        // a prompt the owner already previewed/edited is persisted as-is; we only compose (a paid call)
        // when none was supplied — the one-shot create path.
        // No stances are passed: a persona that doesn't exist yet holds no relations, and the create form
        // deliberately carries no stance fields (relations are an edit-time concern, §2.5).
        val spec = PersonaSpec(name, descriptor, Abilities.parse(abilities), dialsFrom(allParams))
        val prompt = systemPrompt.ifBlank { composer.compose(spec) }
        personas.insert(name, name, descriptor, model, systemPrompt = prompt, abilities = spec.abilities, dials = spec.dials)
        return "redirect:/personas/${PersonaRepository.slugFor(name)}"
    }

    /** Compose a prompt from the form inputs WITHOUT persisting — backs the "Preview / Regenerate"
     *  button so the owner can see (and then tweak) the prompt before paying to save it. Like [create]
     *  it passes no stances: the persona being previewed has no relations to read yet. */
    @PostMapping("/personas/compose")
    @ResponseBody
    fun composePreview(
        @RequestParam name: String,
        @RequestParam(defaultValue = "") descriptor: String,
        @RequestParam(defaultValue = "") abilities: String,
        @RequestParam allParams: Map<String, String>,
    ): String = composer.compose(PersonaSpec(name, descriptor, Abilities.parse(abilities), dialsFrom(allParams)))

    /**
     * Delete a persona (modelled on ThreadController.delete, §8). Resolved by slug like the other
     * persona routes; the members-list button outerHTML-swaps the member row away with this empty
     * response. Historical comments keep their byline — authorship is a plain attribution string, not an
     * FK — but the persona's relations do NOT survive: V24 `persona_stance` foreign-keys both endpoints
     * with ON DELETE CASCADE, so this drops the member's own stances and everyone's stances about it.
     * No-op if the slug is unknown.
     */
    @PostMapping("/personas/{slug}/delete")
    @ResponseBody
    fun delete(@PathVariable slug: String): String {
        personas.findBySlug(slug)?.let { personas.delete(it.id) }
        return ""
    }

    @GetMapping("/personas/{slug}/edit")
    fun editForm(@PathVariable slug: String, model: Model): String {
        val persona = personas.findBySlug(slug) ?: return "redirect:/personas"
        val heldInterests = interests.of(persona.id)
        model.addAttribute("persona", view(persona, heldInterests.map { InterestTagView(it.interest, it.source) }))
        model.addAttribute("dialKeys", Dials.KEYS)
        // One field per phrase the member holds, plus ONE blank to add with. Not a blank per free slot:
        // an owner filling four empty boxes at once is not the interaction this is for, and the ceiling
        // is enforced on the way in anyway ([applyInterestEdits]).
        model.addAttribute(
            "interestFields",
            heldInterests.mapIndexed { i, row ->
                InterestFieldView("$INTEREST_PARAM_PREFIX$i", row.interest, noteFor(row.source))
            } + InterestFieldView("$INTEREST_PARAM_PREFIX${heldInterests.size}", "", "blank — add one here"),
        )
        // The relations half of the form: one field per OTHER member (a persona holds no stance about
        // itself — V24 CHECKs from <> to). Driven by the ROSTER, not by the stance rows, so a member the
        // persona has no view of still gets an empty field to write one into.
        val held = stances.from(persona.id).associate { it.toPersona to it.stance }
        model.addAttribute(
            "stanceFields",
            personas.findAll()
                .filter { it.id != persona.id }
                .map { StanceFieldView(it.id, it.name, held[it.id].orEmpty()) },
        )
        return "persona_edit"
    }

    @PostMapping("/personas/{slug}/edit")
    fun edit(
        @PathVariable slug: String,
        @RequestParam(defaultValue = "") descriptor: String,
        @RequestParam(defaultValue = "") model: String,
        @RequestParam(defaultValue = "") abilities: String,
        @RequestParam(defaultValue = "") systemPrompt: String,
        @RequestParam allParams: Map<String, String>,
    ): String {
        val existing = personas.findBySlug(slug) ?: return "redirect:/personas"
        // Relations are written FIRST so a compose triggered below reads the stances the owner just
        // submitted, not the previous ones. Deliberately NOT folded into [inputsChanged]: a stance reaches
        // generation dynamically at reply time (GenerationService assembles it per request), so it never
        // makes a stored prompt stale — and gating Save behind a paid Regenerate for a stance edit would
        // charge the owner for a change the prompt doesn't even carry.
        applyStanceEdits(existing.id, allParams)
        // Interests are written here for the same reason and with the same posture as stances, and for one
        // more: they never enter the composed prompt at all (D7 injects them at generation time), so an
        // interest edit must not gate Save behind a paid Regenerate either — which is why they are absent
        // from [inputsChanged] below.
        applyInterestEdits(existing.id, allParams)
        val nextSpec = PersonaSpec(existing.name, descriptor, Abilities.parse(abilities), dialsFrom(allParams))
        // Save-what-you-see, with a resync backstop (see plan_docs/persona-prompt-edit-ux.md):
        //  - blank prompt            → compose (the one-shot path)
        //  - prompt == stored, but a composer input changed → STALE, recompose rather than persist
        //    a prompt that no longer matches the dials (protects a JS-off / bypassed submit)
        //  - otherwise (hand-edited / freshly regenerated) → persist verbatim, no LLM call
        val inputsChanged = nextSpec.dials != existing.dials ||
            nextSpec.abilities != existing.abilities ||
            descriptor != existing.descriptor
        val prompt = when {
            systemPrompt.isBlank() -> composer.compose(nextSpec, priorOf(existing), refresher.storedStances(existing.id))
            systemPrompt == existing.systemPrompt && inputsChanged ->
                composer.compose(nextSpec, priorOf(existing), refresher.storedStances(existing.id))
            else -> systemPrompt
        }
        personas.update(existing.id, existing.name, descriptor, model, prompt, nextSpec.abilities, nextSpec.dials)
        return "redirect:/personas/${existing.slug}"
    }

    /** Re-compose from the form inputs against the persona's PREVIOUS values + prompt, without saving. */
    @PostMapping("/personas/{slug}/compose")
    @ResponseBody
    fun composeEditPreview(
        @PathVariable slug: String,
        @RequestParam(defaultValue = "") descriptor: String,
        @RequestParam(defaultValue = "") abilities: String,
        @RequestParam allParams: Map<String, String>,
    ): String {
        val existing = personas.findBySlug(slug) ?: return ""
        val nextSpec = PersonaSpec(existing.name, descriptor, Abilities.parse(abilities), dialsFrom(allParams))
        return composer.compose(nextSpec, priorOf(existing), refresher.storedStances(existing.id))
    }

    /**
     * The owner's explicit, paid catch-up: rewrite EVERY member's stored system prompt from its current
     * inputs + stances. The behaviour, and why it composes fresh rather than adjusting a prior, lives on
     * [PersonaPromptRefresher] — the evolution pass runs the same code, and one copy of that reasoning is
     * the point of the extraction.
     */
    @PostMapping("/personas/recompose")
    fun recomposeAll(): String {
        refresher.refreshAll()
        return "redirect:/personas"
    }

    /** The profile's view of what [personaId] thinks of the room — outgoing edges, target names resolved. */
    private fun stanceViews(personaId: String): List<StanceView> =
        stances.from(personaId).map { s ->
            StanceView(s.toPersona, personas.find(s.toPersona)?.name ?: s.toPersona, s.stance)
        }

    /**
     * Apply the form's `stance_<targetId>` fields: blank retracts the view (delete the edge), non-blank
     * writes it as owner-authored. Only SUBMITTED keys are touched, so a form that carries no stance
     * fields at all — or a targeted POST from a test — leaves the relation graph alone rather than
     * silently wiping it. [RelationStanceRepository.SOURCE_OWNER] is what marks these rows as the owner's
     * own words, which is the provenance a later auto-evolving pass must not overwrite.
     */
    private fun applyStanceEdits(personaId: String, params: Map<String, String>) {
        val submitted = params.filterKeys { it.startsWith(STANCE_PARAM_PREFIX) }
        if (submitted.isEmpty()) return
        // One roster read for the whole form rather than a lookup per field.
        val known = personas.findAll().mapTo(mutableSetOf()) { it.id }
        submitted.forEach { (key, value) ->
            val target = key.removePrefix(STANCE_PARAM_PREFIX)
            // Three targets that must degrade to a no-op instead of a 500, because each is reachable
            // without any hand-crafting: blank, self (rejected by the V24 CHECK), and a persona that no
            // longer exists (rejected by the V24 foreign key). The last one just needs a stale form —
            // open it, delete that member in another tab, submit. It matters more than it looks: stance
            // writes run BEFORE the prompt logic, so an exception here aborts the whole save and the
            // owner silently loses their descriptor and dial edits too. Same guard the seeder applies
            // to a configured edge naming an unknown persona.
            if (target.isBlank() || target == personaId || target !in known) return@forEach
            if (value.isBlank()) stances.delete(personaId, target)
            else stances.upsert(personaId, target, value.trim(), RelationStanceRepository.SOURCE_OWNER)
        }
    }

    /**
     * Apply the form's `interest_<n>` fields (S4b D11 — this is how an owner pins an interest).
     *
     * **Prefix-scanned out of `allParams`, never bound as `@RequestParam(defaultValue = "")`, and that is
     * load-bearing rather than stylistic.** `POST /personas/{slug}/edit` binds every declared param to `""`
     * when it is absent, and `PersonaSteps.saveStanceOnly`
     * (`src/test/kotlin/com/aiforum/acceptance/steps/PersonaSteps.kt:341-355`) replays a FIXED field list —
     * so a bound interest param would arrive blank on every stance-only save, be read as a retraction, and
     * (because this path stamps `owner`) permanently mute that member's drift with nothing on any page to
     * say so. Scanning means a form that carries no interest fields — a targeted POST, that step — leaves
     * the member's interests alone, exactly as [applyStanceEdits] leaves the relation graph alone.
     *
     * **The fieldset is the whole set.** Every submitted value is reconciled against what the member holds:
     * a phrase that is no longer submitted is retracted (which is how blanking a field deletes), a phrase
     * that is new is written as the owner's. Reconciling the set rather than trusting the field INDEX is
     * what keeps a stale form honest — the pass may have moved a phrase since the page rendered, and index
     * arithmetic would then delete a phrase the owner never saw.
     *
     * **A resubmitted, unchanged phrase keeps its existing `source`** — only a new or changed phrase is
     * stamped [PersonaInterestRepository.SOURCE_OWNER]. Without that rule the form, which prefills the
     * member's current interests, would freeze every one of them the first time the owner opened it and
     * pressed Save. Owner provenance is a permanent skip for the pass, so that is not a decision the owner
     * made — pinning has to be "type a phrase", not "visit this page".
     *
     * Three no-op guards, the same posture as [applyStanceEdits]'s and for a sharper version of the same
     * reason: these writes run BEFORE the prompt logic in [edit], so an exception here aborts the whole
     * save and the owner silently loses their descriptor and dial edits too.
     *  1. a blank key suffix (`interest_=…`) — reachable from any hand-crafted POST;
     *  2. a phrase [Interests.validate] refuses — V27's length CHECK is enforced in SQL and would throw a
     *     `DataAccessException` out of the middle of the save, so it is skipped rather than handed on;
     *  3. the member's ceiling already full — the extra phrase is dropped, never the existing ones.
     */
    private fun applyInterestEdits(personaId: String, params: Map<String, String>) {
        val submitted = params.filterKeys { it.startsWith(INTEREST_PARAM_PREFIX) }
        if (submitted.isEmpty()) return
        val typed = submitted
            .filterKeys { it.removePrefix(INTEREST_PARAM_PREFIX).isNotBlank() }
            .values
            // Cleaned exactly ONCE, and only on this side of the comparison: the stored phrase already came
            // through `upsert`, the one door that cleans, and `Interests.clean` is deliberately not
            // idempotent on quotes — cleaning it again would strip a pair the owner meant to keep.
            .map { Interests.clean(it) }
            .filter { it.isNotBlank() && Interests.validate(it) == null }
            // Two fields carrying the same phrase are one interest; the PRIMARY KEY folds case, so this
            // must too, or the second field would upsert over the first and restamp its provenance.
            .distinctBy { it.lowercase() }
        val typedKeys = typed.mapTo(mutableSetOf()) { it.lowercase() }
        val held = interests.of(personaId)
        held.filter { it.interest.lowercase() !in typedKeys }
            .forEach { interests.delete(personaId, it.interest) }
        val heldKeys = held.mapTo(mutableSetOf()) { it.interest.lowercase() }
        // Counted after the retractions above, so swapping one phrase for another on a full member works —
        // the ceiling is about what the member ends up holding, not about how many fields were submitted.
        var holding = held.count { it.interest.lowercase() in typedKeys }
        typed.forEach { phrase ->
            if (phrase.lowercase() in heldKeys) return@forEach
            if (holding >= MAX_INTERESTS) return@forEach
            interests.upsert(personaId, phrase, PersonaInterestRepository.SOURCE_OWNER)
            holding++
        }
    }

    /** The member's current interests as profile tags, in the repository's stable `ORDER BY interest`. */
    private fun interestTags(personaId: String): List<InterestTagView> =
        interests.of(personaId).map { InterestTagView(it.interest, it.source) }

    /**
     * What the edit form says beside a prefilled field. The owner-authored case states the rule that has no
     * undo button — there is deliberately no "unpin" control (D11), and the documented way back is to blank
     * the field, which deletes the row.
     */
    private fun noteFor(source: String): String =
        if (source == PersonaInterestRepository.SOURCE_OWNER) "yours — the drift pass skips it for good"
        else "open to drift — retype it to make it yours"

    // Hand the model the PREVIOUS values + prompt so an edit adjusts rather than regenerates (continuity).
    private fun priorOf(existing: PersonaRepository.Persona) =
        PriorComposition(
            PersonaSpec(existing.name, existing.descriptor, existing.abilities, existing.dials),
            existing.systemPrompt,
        )

    // Pull the fixed-schema dials out of the form (each rendered as a `dial_<key>` range input);
    // missing/blank fall back to the neutral default and PersonaRepository normalizes on the way in.
    private fun dialsFrom(params: Map<String, String>): Map<String, Int> =
        Dials.KEYS.associateWith { key -> params["dial_$key"]?.toIntOrNull() ?: Dials.DEFAULT }

    // Positional, so [tags] is trailing and defaulted: the members list and the create/compose paths render
    // no interest block and must not pay a repository read to say so.
    private fun view(p: PersonaRepository.Persona, tags: List<InterestTagView> = emptyList()) =
        PersonaView(
            p.id, p.name, p.descriptor, p.slug, p.model, p.colorIndex, p.abilities, p.dials, p.systemPrompt,
            tags,
        )

    private companion object {
        /** Edit-form field prefix; the suffix is the TARGET persona's id (see [applyStanceEdits]). */
        const val STANCE_PARAM_PREFIX = "stance_"

        /** Edit-form field prefix; the suffix is a row INDEX, not a phrase (see [applyInterestEdits]). */
        const val INTEREST_PARAM_PREFIX = "interest_"

        /**
         * The per-member authoring ceiling. Four phrases is a preoccupation; a dozen is a tag cloud, and a
         * tag cloud is the thing D1 refused to let interests become.
         *
         * A constant here rather than `aiforum.interest-drift.max-interests` off the bound properties bean:
         * this controller has no other reason to depend on the drift pass's configuration, and the value it
         * needs is the *authoring* ceiling the form enforces, which is a property of this write surface.
         * If the two ever have to move together, bind the bean here and delete this — the swap is one line.
         */
        const val MAX_INTERESTS = 4
    }
}
