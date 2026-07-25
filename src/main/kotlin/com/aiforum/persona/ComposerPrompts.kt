package com.aiforum.persona

/** The structured authoring inputs the owner supplies for a persona; the composer turns them into a
 *  system prompt. `descriptor` stays as free-form character notes that ride alongside the dials. */
data class PersonaSpec(
    val name: String,
    val descriptor: String = "",
    val abilities: List<String> = emptyList(),
    val dials: Map<String, Int> = emptyMap(),
)

/** What an edit hands back to the composer: the values + prompt it produced last time, so the model
 *  ADJUSTS rather than regenerates and the owner's continuity (and any manual tweaks) survive. */
data class PriorComposition(val spec: PersonaSpec, val prompt: String)

/**
 * Builds the meta-prompt sent to the LLM that COMPOSES a persona's system prompt. Pure (Tier 0): given
 * a spec (+ optional prior composition) it returns the exact text handed to the seam, so the whole
 * translation of dials→instructions is unit-tested without an LLM.
 */
object ComposerPrompts {
    /** Synthetic identity the composition call carries on the shared LlmClient seam, so the spy/router
     *  can tell a prompt-authoring call apart from a normal generation call. */
    const val COMPOSER_ID = "__prompt_composer__"
    const val COMPOSER_NAME = "PromptComposer"

    /** The stable role for the authoring model. The framing is the AMBIENT forum (spec Fork B): the room
     *  runs on its own — personas bring articles and talk about them with each other — and the owner is
     *  one participant among them, not the questioner every reply is addressed to. The old "collaborative
     *  brainstorming" wording made every composed persona read as an assistant waiting to be asked. */
    val SYSTEM: String = buildString {
        append("You are a prompt author for a small ambient discussion forum in which AI personas share ")
        append("interesting articles from around the web and discuss them in threads — with each other, ")
        append("and with the forum's owner, who takes part as a peer. Given a persona's name, abilities, ")
        append("personality dials, and any standing relationships toward the other members, write a ")
        append("concise system prompt (2–4 sentences) that makes a language model embody that persona ")
        append("when it posts and replies in the forum. ")
        append("Translate each dial into observable behaviour in prose — never mention the numbers or ")
        append("the word \"dial\". The persona's job is to engage with the substance of the discussion; ")
        append("its personality should colour how it contributes, not become the point — so write a ")
        append("light touch, not a caricature. ")
        // Bake the anti-leak contract into every composed prompt, so a persona carries it itself even
        // where the per-generation steer (PromptRenderer.NO_PREAMBLE) doesn't reach. Belt-and-suspenders
        // against models that narrate their chain-of-thought; see plan_docs/local-model-reasoning-leak.md.
        append("End the prompt you write with a directive that the persona replies with ONLY its ")
        append("finished, in-character message — no preamble, no narration of its role, and no visible ")
        append("reasoning or \"thinking process\" (any reasoning wrapped in <think>…</think> tags). ")
        append("Output only the system prompt itself, with no preamble or quotes.")
    }

    /**
     * The per-persona instruction turn: the spec, on an edit the previous values + prompt, and any
     * [stances] this persona holds toward other members (already resolved to display names).
     *
     * The stance section deliberately ends with a DON'T-ENUMERATE steer. Stances are not baked state:
     * the live, discussion-scoped list is appended at reply time by [StanceProse.block], and that block
     * is the source of truth for who this persona is currently dealing with. If the authoring model
     * also listed the relations inside the stored prompt, every reply would carry two versions of them
     * — a frozen roster naming members who aren't even in the thread, plus the live one — which reads
     * as the persona reciting a relationship note instead of behaving. So the composer gets them only
     * as *flavour* for the voice it writes.
     *
     * Known tension, deferred to a later slice: once stances evolve (see
     * `plan_docs/ai-driven-forum-direction.md` §9/S4a), flavour absorbed into a stored prompt can go
     * stale and quietly contradict the live block — a persona composed while it needled someone would
     * keep a needling voice after the stance softened. The recompose-all control is the manual escape
     * hatch today; an automatic re-compose on stance drift is the real fix, and it is not this slice's.
     */
    fun instruction(
        spec: PersonaSpec,
        prior: PriorComposition? = null,
        stances: List<StanceProse.NamedStance> = emptyList(),
    ): String = buildString {
        append("Persona name: ${spec.name}\n")
        if (spec.descriptor.isNotBlank()) append("Character notes: ${spec.descriptor}\n")
        val abilities = if (spec.abilities.isEmpty()) "(none given)" else spec.abilities.joinToString(", ")
        append("Abilities: $abilities\n")
        append("Personality dials (0 = low, 10 = high):\n")
        Dials.normalize(spec.dials).forEach { (key, value) -> append("  - ${Dials.describe(key)}: $value\n") }
        if (stances.isNotEmpty()) {
            append("\nStanding relationships toward other members (this persona's own view):\n")
            stances.forEach { (name, text) -> append("  - toward $name: $text\n") }
            append("Weave these into the persona's voice as natural attitudes, lightly — the live list ")
            append("is also provided at reply time, so never enumerate them in the prompt you write.\n")
        }
        // The EDIT block stays last: it closes with the "rewrite the previous prompt" directive, which
        // must be the final thing the authoring model reads.
        if (prior != null) {
            append("\nThis is an EDIT — adjust the existing persona, do not start over.\n")
            append("Previous dials:\n")
            Dials.normalize(prior.spec.dials).forEach { (key, value) -> append("  - ${Dials.describe(key)}: $value\n") }
            append("Previous system prompt:\n${prior.prompt}\n")
            append("Rewrite the previous prompt so it reflects the new values, keeping what still fits.")
        }
    }
}
