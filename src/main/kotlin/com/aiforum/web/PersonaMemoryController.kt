package com.aiforum.web

import com.aiforum.persona.MemoryText
import com.aiforum.repo.PersonaMemoryRepository
import com.aiforum.repo.PersonaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The owner's write surface for a member's private memory tree (plan_docs/persona-memory.md §2.12)
 * — the forms on the persona profile post here:
 *
 *  - `POST /personas/{slug}/memories`             — author a record (`body` + optional `parent`)
 *  - `POST /personas/{slug}/memories/root`        — author the §6.3 root, create-once
 *  - `POST /personas/{slug}/memories/{id}/delete` — delete one node (records reparent-then-delete)
 *
 * Its own controller rather than more weight on [PersonaController]: these are three write
 * endpoints over a table that controller only reads, and the delete/author postures here (no-op
 * flash, never an exception) are this slice's, not the persona form's.
 *
 * **Form params are prefix-scanned out of `allParams`, never bound `@RequestParam(defaultValue="")`**
 * (the S4b blank-replay wipe, 4b D11): a bound param arrives blank on every replayed fixed-field
 * POST and would read as intent. Nothing here reconciles a set, so the blast radius is smaller than
 * the interest form's — but the posture is uniform, and an absent field must stay distinguishable
 * from a blanked one.
 *
 * **An unusable submission is a NO-OP with a logged reason, never an exception**: these writes run
 * on the owner's own profile page, and a 500 for a too-long memory would be the only surface in the
 * app that punishes typing. Validation is [MemoryText.validate] — the SAME function the scribe
 * parse uses (§2.15, one function, not shared constants) — over a body cleaned exactly once at this
 * door, so the validated string IS the stored string (I5).
 */
@Controller
class PersonaMemoryController(
    private val personas: PersonaRepository,
    private val memories: PersonaMemoryRepository,
) {

    private val log = LoggerFactory.getLogger(PersonaMemoryController::class.java)

    /**
     * Author a record as the owner: `source='owner'` by method shape
     * ([PersonaMemoryRepository.insertOwnerRecord] hard-codes it — provenance at birth). The
     * optional `parent` is validated against the member's CURRENT `kind='record'` rows — §2.2's
     * parent-candidate rule at its form-endpoint site: the root is never in [PersonaMemoryRepository.recordsOf],
     * so it cannot be named here even by a hand-crafted POST, and the repository belt backs this up.
     */
    @PostMapping("/personas/{slug}/memories")
    fun author(@PathVariable slug: String, @RequestParam allParams: Map<String, String>): String {
        val persona = personas.findBySlug(slug) ?: return "redirect:/personas"
        val redirect = "redirect:/personas/${persona.slug}"
        val body = MemoryText.clean(allParams[BODY_PARAM].orEmpty())
        MemoryText.validate(body)?.let { reason ->
            log.warn("event=memory.author.rejected persona={} reason={}", persona.id, reason)
            return redirect
        }
        val records = memories.recordsOf(persona.id)
        // The owner's own authoring ceiling (§2.11) lives HERE, on the write surface — not in the
        // DB (owner rows are exempt from the scribe ceiling by design) and not in the pass's
        // config. The extra record is refused, never the existing ones.
        if (records.count { it.source == PersonaMemoryRepository.SOURCE_OWNER } >= MAX_OWNER_MEMORIES) {
            log.warn("event=memory.author.rejected persona={} reason=at-owner-ceiling", persona.id)
            return redirect
        }
        val parentId = allParams[PARENT_PARAM]?.takeIf { it.isNotBlank() }
        if (parentId != null && records.none { it.id == parentId }) {
            log.warn("event=memory.author.rejected persona={} reason=unknown-parent", persona.id)
            return redirect
        }
        memories.insertOwnerRecord(persona.id, body, parentId)
        return redirect
    }

    /**
     * Author the §6.3 root — create once (the V28 partial unique index is the enforcement; this
     * pre-check just turns the second attempt into a readable no-op instead of a driver exception).
     * Delete + re-author to change: no in-place edit, uniform with records, and safe because
     * nothing can ever be parented on the root (§2.2), so re-authoring never cascades a subtree.
     */
    @PostMapping("/personas/{slug}/memories/root")
    fun authorRoot(@PathVariable slug: String, @RequestParam allParams: Map<String, String>): String {
        val persona = personas.findBySlug(slug) ?: return "redirect:/personas"
        val redirect = "redirect:/personas/${persona.slug}"
        val body = MemoryText.clean(allParams[BODY_PARAM].orEmpty())
        MemoryText.validate(body)?.let { reason ->
            log.warn("event=memory.root.rejected persona={} reason={}", persona.id, reason)
            return redirect
        }
        if (memories.rootOf(persona.id) != null) {
            log.warn("event=memory.root.rejected persona={} reason=root-exists", persona.id)
            return redirect
        }
        memories.insertRoot(persona.id, body)
        return redirect
    }

    /**
     * Delete one node. Records go through [PersonaMemoryRepository.deleteRecord] — the
     * reparent-to-grandparent discipline (§2.10), so a mid-chain delete hands the children up
     * rather than orphaning or cascading them; the root has no children by construction, so the
     * same door serves it. The row must belong to THIS member: an id under another member's slug is
     * a stale form or a crafted POST, and either way a no-op beats a cross-member delete.
     */
    @PostMapping("/personas/{slug}/memories/{id}/delete")
    fun delete(@PathVariable slug: String, @PathVariable id: String): String {
        val persona = personas.findBySlug(slug) ?: return "redirect:/personas"
        val row = memories.find(id)
        if (row != null && row.personaId == persona.id) {
            memories.deleteRecord(id)
            log.info("event=memory.deleted persona={} memory={}", persona.id, id)
        }
        return "redirect:/personas/${persona.slug}"
    }

    private companion object {
        const val BODY_PARAM = "body"
        const val PARENT_PARAM = "parent"

        /**
         * The OWNER's authoring ceiling (§2.11's controller half). The same figure as the scribe's
         * `MAX_SCRIBE_MEMORIES`, for the same prompt-budget arithmetic — but a separate constant on
         * the write surface that owns it, the `PersonaController.MAX_INTERESTS` split: this
         * controller has no other reason to depend on the pass's configuration.
         */
        const val MAX_OWNER_MEMORIES = 24
    }
}
