package com.aiforum.persona

/**
 * Renders what a member is currently into as the prose block appended to that member's system prompt at
 * generation time (`plan_docs/ambient-slice-4b.md` D7). Pure (Tier 0), beside [StanceProse]
 * (`src/main/kotlin/com/aiforum/persona/StanceProse.kt:37-44`) and for the same reason: the
 * interests→prose translation is unit-tested without an LLM, and the block is appended in
 * `GenerationService`'s existing per-reply seam rather than baked into the stored `system_prompt`.
 *
 * **Injection, never baking.** An interest moves more often than a stance and is a *topic* rather than a
 * colour on a voice; a topic frozen into a stored prompt is exactly the "frozen roster naming members
 * who aren't even in the thread" failure `ComposerPrompts.kt:56-61` was written against. Injecting also
 * means a drift never costs a recompose, and the read is live at settle time — an interest written
 * between two replies of one fan-out reaches the second.
 *
 * ## The signature is the guardrail
 *
 * [block] takes `List<String>`, **not** the `Interest` rows with their `source`. A model that could see
 * which of its interests are owner-pinned would know which ones are safe to perform at and which are
 * up for grabs — a lever on its own drift, handed over for free. There is no parameter to pass
 * provenance through, so no caller can leak it by accident; that absence is asserted structurally at
 * Tier 0 rather than left as a comment.
 *
 * There is also **no numbering** — a numbered list would put digits in a prompt, which is the one thing
 * every prompt this slice touches must not carry.
 */
object InterestProse {

    /**
     * Builds the "what you are into" block for [personaName] from the [interests] the caller has
     * already read, in the caller's order (`ORDER BY interest` at the repository, so prompt text is
     * byte-stable across runs — an unrelated insertion must never silently rewrite a prompt).
     *
     * Returns `null` on an empty list so callers append nothing rather than an empty header: a member
     * the owner authored no interests for should read as if this block were never written, not as a
     * header dangling over zero bullets. It is also the common case — drift is opt-in per member.
     *
     * The closing line steers the model to let the interests shape what it notices rather than announce
     * them ([StanceProse]'s `:42` steer, and a model handed a list recites the list). A persona that
     * says "as someone interested in kernel scheduling" breaks character and leaks the mechanism to the
     * owner reading the thread — the point of injecting interests as prose is that they show up as
     * attention, not as a quoted profile.
     */
    fun block(personaName: String, interests: List<String>): String? {
        if (interests.isEmpty()) return null
        return buildString {
            append("What you, $personaName, are into at the moment:\n")
            interests.forEach { append("- $it\n") }
            append("Let these shape what you notice and what you bring up - never recite or list them.")
        }
    }
}
