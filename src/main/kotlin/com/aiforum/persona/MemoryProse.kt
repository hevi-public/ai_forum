package com.aiforum.persona

/**
 * Renders a member's resurfaced memories as the prose block injected into that member's generation
 * prompt (`plan_docs/persona-memory.md` §2.9) — the fourth block, beside [StanceProse] and
 * [InterestProse] and pure for the same reason: the memories→prose translation is unit-tested
 * without an LLM, and the block is injected at the per-reply seam, never baked into the stored
 * `system_prompt` (no recompose, ever — a memory baked in is the stale-roster failure wearing a
 * new hat).
 *
 * ## The signature is the guardrail
 *
 * [block] takes `List<String>` — **bodies only**: no ids, no provenance, no parent structure, no
 * counts. A model that could see which rows are owner-authored, or how records link, would hold a
 * lever on its own memory; there is no parameter to pass any of that through, so no caller can leak
 * it by accident. (No personaName either, unlike [InterestProse] — remembering is first-person
 * already.) There is also no numbering: a numbered list would put digits into a prompt frame that
 * must carry none.
 *
 * ## The ONE truncation site
 *
 * Scribe rows are ≤300 code points by V28's scoped CHECK; an over-long owner row (the CHECK is
 * scoped off the owner on purpose, and hand SQL exists) is truncated HERE, in the rendered block,
 * `Snippet`-style and measured in **code points** ([MemoryText.codePoints], the I5 measure — a
 * UTF-16 `take` would cut an emoji in half). Owned here so no second truncation site can ever
 * disagree with this one; the stored row is never touched.
 */
object MemoryProse {

    /**
     * The memory block for a member, from the already-selected [memories] in the caller's order
     * ([MemoryRecall.select]'s deterministic chain order — prompt text must be byte-stable across
     * runs). Returns `null` on empty so callers append nothing rather than a header over zero
     * lines: zero when irrelevant, and the no-memory prompt stays byte-identical to today's (the
     * S4b parity pin).
     *
     * The closing steer is the house pattern ([InterestProse]'s, [StanceProse]'s): a model handed a
     * list recites the list, and a member opening with "as I remember from a past discussion…"
     * breaks character and leaks the mechanism into the thread. The frame text carries no digit and
     * no `vote` substring (Tier-0-pinned, like the judge prompts' SYSTEM text).
     */
    fun block(memories: List<String>): String? {
        if (memories.isEmpty()) return null
        return buildString {
            append("Things you remember from past discussions here:\n")
            memories.forEach { append("- ").append(truncated(it)).append('\n') }
            append("Let these quietly shape your reply - never recite or list them.")
        }
    }

    /** First [MemoryText.MAX_CODE_POINTS] code points plus an ellipsis, for the rows the scoped
     *  CHECK deliberately does not bound. In-budget bodies pass through byte-identical. */
    private fun truncated(body: String): String {
        if (MemoryText.codePoints(body) <= MemoryText.MAX_CODE_POINTS) return body
        val end = body.offsetByCodePoints(0, MemoryText.MAX_CODE_POINTS)
        return body.substring(0, end).trimEnd() + "…"
    }
}
