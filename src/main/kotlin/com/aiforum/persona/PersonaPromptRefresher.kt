package com.aiforum.persona

import com.aiforum.repo.PersonaRepository
import com.aiforum.repo.RelationStanceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * The single definition of "recompose this persona from scratch": its CURRENT name/descriptor/abilities/
 * dials plus its CURRENT stances, composed into a new system prompt and persisted.
 *
 * Two callers share it and must not drift apart (plan_docs/ambient-slice-4a.md D11): the owner's bulk
 * `POST /personas/recompose` button, and the stance-evolution pass, which refreshes a persona right after
 * one of its relations moved. Extracted from `PersonaController.recomposeAll` rather than copied, so
 * there is one answer to "fresh or prior-based?", one isolation policy, and one set of
 * `event=persona.recompose.*` log lines for the operator to read.
 *
 * Composed FRESH (`prior = null`) rather than as an adjustment: the point of a refresh is to REPLACE a
 * framing — a reworded composer, a stance that has since evolved — and handing the model the old prompt
 * as prior invites it to preserve exactly what we are trying to replace. The per-persona
 * edit → Regenerate → Save path still exists for surgical changes that should keep continuity.
 *
 * Why the owner's button (and now the evolution pass) exists at all: seeding never clobbers a stored
 * prompt, so a forum running since before a framing change keeps serving the old wording forever.
 * Rejected alternative: rewriting prompts at startup for rows matching the old template — that mutates
 * owner data at boot with no consent, and silently misses any prompt the owner had already hand-edited.
 */
@Service
class PersonaPromptRefresher(
    private val personas: PersonaRepository,
    private val composer: PromptComposer,
    private val stances: RelationStanceRepository,
) {

    private val log = LoggerFactory.getLogger(PersonaPromptRefresher::class.java)

    /**
     * A persona's outgoing edges shaped for the composer, which speaks in display names rather than ids.
     * Public because the persona edit/preview paths need the same list to compose against — one lookup
     * rule for "what relations does this persona hold", wherever a prompt is being written.
     */
    fun storedStances(personaId: String): List<StanceProse.NamedStance> =
        stances.from(personaId).map { s ->
            StanceProse.NamedStance(personas.find(s.toPersona)?.name ?: s.toPersona, s.stance)
        }

    /**
     * Recompose one persona and store the result; returns whether the prompt actually moved.
     *
     * The whole thing sits inside a `runCatching` because both callers are batches: a flaky seam on
     * member three must not cost members four through seven their refresh, and — for the evolution pass —
     * a failed compose must not undo a stance change that is already committed with its audit row. A
     * failure therefore leaves this persona's stored prompt untouched rather than half-written, and is
     * reported as `false` plus a logged line, never as a thrown exception the caller has to think about.
     */
    fun refresh(personaId: String): Boolean {
        val persona = personas.find(personaId)
        if (persona == null) {
            // Reachable without hand-crafting: the roster is re-read per call, so a persona deleted
            // mid-batch (or between an evolution write and its recompose) lands here. Same event id as a
            // seam failure — from the operator's side it is the same outcome, one member not refreshed.
            log.warn("event=persona.recompose.failed persona={} reason=unknown-persona", personaId)
            return false
        }
        return runCatching {
            val spec = PersonaSpec(persona.name, persona.descriptor, persona.abilities, persona.dials)
            composer.compose(spec, prior = null, stances = storedStances(persona.id))
        }.onSuccess { prompt ->
            personas.update(
                persona.id, persona.name, persona.descriptor, persona.model, prompt, persona.abilities, persona.dials,
            )
        }.onFailure { e ->
            log.warn("event=persona.recompose.failed persona={} reason={}", persona.id, e.toString())
        }.isSuccess
    }

    /**
     * Refresh EVERY member, returning how many prompts were rewritten. One LLM call per member, which is
     * why the surface driving this is a button with the cost stated on it rather than something that
     * happens quietly. Synchronous is fine — single-user PoC, and the owner is watching the click.
     *
     * Walks [PersonaRepository.findAll] (name order), which the acceptance suite pairs scripted responses
     * against; changing the order would silently re-pair them.
     */
    fun refreshAll(): Int {
        val refreshed = personas.findAll().count { refresh(it.id) }
        log.info("event=persona.recompose.ok count={}", refreshed)
        return refreshed
    }
}
