package com.aiforum.persona

/**
 * Renders a persona's directed stances toward the OTHER PARTICIPANTS OF THE CURRENT DISCUSSION into
 * the prose block appended to that persona's system prompt at generation time. Pure (Tier 0): given
 * the persona's name and its stances toward whoever else is in the thread, it returns the exact text
 * handed to the prompt — the same reasoning as [ComposerPrompts], so the traits→prose translation is
 * unit-tested without an LLM.
 *
 * A stance is deliberately **free text only** — never a number. Persona relationships were cut once
 * already as part of the quantified reward economy (persona votes/reputation/tallies); reviving them
 * as a directed edge with a score would silently re-import exactly that. `NamedStance.text` is prose
 * the owner (or, later, the capped-cadence evolution job — see
 * `plan_docs/ai-driven-forum-direction.md` §9/S4a) writes and rewrites; there is no numeric field to
 * smuggle a tally into.
 */
object StanceProse {
    /** One directed edge, already resolved to a display name — [block] does no lookup of its own. */
    data class NamedStance(val name: String, val text: String)

    /**
     * Builds the "how you relate to the others here" block for [personaName], covering only the
     * [stances] the caller has already scoped to the current discussion's other participants — this
     * object has no notion of "the current discussion" itself, it just renders what it's handed.
     * Order follows the input list order: the caller decides ordering (e.g. by who's actually posted
     * in the thread), this renderer never re-sorts.
     *
     * Returns `null` on an empty list so callers append nothing rather than an empty header — a
     * persona with no scoped relations in this thread should read as if the block were never
     * written, not as a header dangling over zero bullets.
     *
     * The closing line steers the model to let the stance colour tone rather than recite it: a
     * persona that announces "I respect your rigor" mid-reply breaks character and leaks the
     * mechanism to the owner reading the thread — the whole point of injecting relations as prose
     * context is that they show up as behaviour, not as a quoted relationship note.
     */
    fun block(personaName: String, stances: List<NamedStance>): String? {
        if (stances.isEmpty()) return null
        return buildString {
            append("How you, $personaName, relate to the others here:\n")
            stances.forEach { (name, text) -> append("- $name: $text\n") }
            append("Let these attitudes colour your tone toward each of them naturally - never recite or mention them.")
        }
    }
}
