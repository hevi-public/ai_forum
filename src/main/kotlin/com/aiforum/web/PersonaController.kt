package com.aiforum.web

import com.aiforum.persona.Abilities
import com.aiforum.persona.Dials
import com.aiforum.persona.PersonaPromptRefresher
import com.aiforum.persona.PersonaSpec
import com.aiforum.persona.PriorComposition
import com.aiforum.persona.PromptComposer
import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.RelationStanceRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

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
)

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

@Controller
class PersonaController(
    private val personas: PersonaRepository,
    private val composer: PromptComposer,
    private val stances: RelationStanceRepository,
    // Owns "recompose this persona from scratch" — shared with the stance-evolution pass, which refreshes
    // a persona whose relations just moved (plan_docs/ambient-slice-4a.md D11).
    private val refresher: PersonaPromptRefresher,
) {

    // Profile URLs use the slug (V5) so multi-word names ("Ada Lovelace") work without %20 noise.
    @GetMapping("/personas/{slug}")
    fun profile(@PathVariable slug: String, model: Model): String {
        val persona = personas.findBySlug(slug) ?: return "redirect:/personas"
        model.addAttribute("persona", view(persona))
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
        model.addAttribute("persona", view(persona))
        model.addAttribute("dialKeys", Dials.KEYS)
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

    private fun view(p: PersonaRepository.Persona) =
        PersonaView(p.id, p.name, p.descriptor, p.slug, p.model, p.colorIndex, p.abilities, p.dials, p.systemPrompt)

    private companion object {
        /** Edit-form field prefix; the suffix is the TARGET persona's id (see [applyStanceEdits]). */
        const val STANCE_PARAM_PREFIX = "stance_"
    }
}
