package com.aiforum.ambient

/**
 * The ambient comment gate (plan_docs/ambient-slice-2.md §4) — a cheap, deterministic BACKEND heuristic,
 * never an LLM call (§6.4/§10 mandate gating off the model). Two decisions live here as pure Tier-0 logic:
 *
 *  - [relevance]: how well a persona's ability tags match a piece of text (a thread's title+OP body, or an
 *    article's title+summary) — a whole-word, case-insensitive count of tags that appear, with
 *    Unicode-aware boundaries (abilities are owner-typed free text: "café" and "日本語" must work).
 *  - [clears]: whether `talkativeness * relevance` reaches [THRESHOLD]. Default dial 5 × one matching
 *    ability = 5 → passes; zero relevance never passes regardless of talkativeness (relevance-gated, §6.4);
 *    a quiet dial (≤ 4) with a single match stays silent.
 *
 * Picks are deterministic (max score, first-wins on ties over the caller's candidate order) so the
 * acceptance scenarios stay stable — no randomness anywhere in the ambient path.
 */
object AmbientGate {

    /** talkativeness × relevance must reach this to speak (constant, like DepthBudget.DEFAULT_GRANT). */
    const val THRESHOLD = 5

    /**
     * How many of [abilities] appear in [text] as whole words (case-insensitive). The count is the
     * relevance signal both the comment gate and the post-action author pick read; a blank tag never
     * matches.
     *
     * The matcher itself lives in [WholeWords] (extracted for memory recall, plan_docs/persona-memory.md
     * §2.7 — the one permitted edit to this file, a pure delegation pinned by this object's unchanged
     * Tier-0 suite): "whole word" is UNICODE-AWARE, not `\b` — see [WholeWords] for the boundary rules
     * ("go" never matches inside "golang"; a script change IS the boundary in unspaced CJK text).
     */
    fun relevance(abilities: List<String>, text: String): Int =
        abilities.count { tag -> tag.isNotBlank() && WholeWords.contains(text, tag.trim()) }

    /** The gate: `talkativeness * relevance >= THRESHOLD`. Zero relevance can never clear it (§6.4). */
    fun clears(talkativeness: Int, relevance: Int): Boolean =
        talkativeness * relevance >= THRESHOLD

    /**
     * The highest-scoring (`talkativeness * relevance`) candidate that [clears] the threshold, or null when
     * none does — the caller then falls back (§5 step 2). Ties keep the FIRST candidate in [candidates]
     * order, so the caller controls the tie-break by ordering its list (threads in findActive order,
     * personas in rowid order). [maxByOrNull] updates only on a strictly greater score, so first-wins holds.
     */
    fun <T> bestClearing(
        candidates: List<T>,
        talkativenessOf: (T) -> Int,
        relevanceOf: (T) -> Int,
    ): T? =
        candidates
            .filter { clears(talkativenessOf(it), relevanceOf(it)) }
            .maxByOrNull { talkativenessOf(it) * relevanceOf(it) }

    /**
     * The candidate with the highest [relevanceOf] that scores > 0, or null when every candidate scores
     * zero — the post-action author pick (§5 step 3), which then falls back to the S1 round-robin. First-wins
     * on ties, so a rowid-ordered roster picks the earliest-seeded persona among equals.
     */
    fun <T> bestByRelevance(candidates: List<T>, relevanceOf: (T) -> Int): T? =
        candidates
            .filter { relevanceOf(it) > 0 }
            .maxByOrNull { relevanceOf(it) }
}
