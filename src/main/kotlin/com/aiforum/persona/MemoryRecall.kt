package com.aiforum.persona

import com.aiforum.ambient.WholeWords
import com.aiforum.repo.PersonaMemory
import com.aiforum.repo.PersonaMemoryRepository

/**
 * Deterministic memory retrieval (`plan_docs/persona-memory.md` §2.7): which of a member's records
 * resurface in front of the conversation the member is about to read. Pure Tier-0 logic in the
 * [com.aiforum.ambient.AmbientGate] lineage — a cheap backend heuristic, never an LLM call, and
 * **unrankable by construction**:
 *
 * - **Binary, not scored.** A record surfaces iff ≥1 of its own words occurs in the context text as
 *   a whole word ([WholeWords] — substring matching is what SQL `LIKE` would get wrong: *cat* must
 *   not hit *concatenate*). The overlap count exists only as the short-circuit inside `any {}` —
 *   never kept, compared across records, persisted, or rendered. Two matches rank no higher than
 *   one; there is nothing here a model could game into ranking (I4).
 * - **The tie-break is a clock, not a magnitude.** Over [MAX_MATCHED] matches keep the newest by
 *   `created_at` (id tiebreak) — a backend-side ordering over injected-Clock stamps.
 * - **One associative hop, records only.** Each surfaced record pulls its [PersonaMemory.parentId]
 *   antecedent into the result — recall is a *chain*, §6.3's threading payoff — but parents resolve
 *   ONLY among the `kind='record'` rows this function loaded (§2.2's parent-candidate rule, the
 *   construction half): even a root-parented row smuggled in by hand SQL can never drag the root
 *   into a prompt. One hop, no walk — a memory three links from a match stays dormant (non-goal).
 * - **Hard cap** [MAX_TOTAL], so prompt cost is bounded by the recall cap, not the store size.
 *
 * The word floor ([MIN_WORD_CODE_POINTS], code points — the I5 measure) is a crude stopword rule,
 * stated as crude and tuneable: "the"/"once"/"eat" never key a resurfacing, at the price of also
 * ignoring short real words. Only its determinism is pinned; its recall quality is a §7 unpinnable.
 */
object MemoryRecall {

    /** At most this many MATCHED records (the newest); their parents ride on top, up to [MAX_TOTAL]. */
    const val MAX_MATCHED = 3

    /** The whole injection budget: ≤3 matched + parents, ≤5 records total (≈1.5KB at the 300 bound). */
    const val MAX_TOTAL = 5

    /** A record word shorter than this (in code points) never keys a match — the crude stopword floor. */
    const val MIN_WORD_CODE_POINTS = 5

    /**
     * Select the records of ONE member that resurface against [contextText] (the scoped context the
     * member is about to read: comment bodies plus the thread title, concatenated by the caller —
     * BRANCH_ONLY composes for free, because a narrower scope is a narrower match text).
     *
     * [memories] is that member's rows; anything that is not `kind='record'` is dropped here as the
     * defensive half of §2.2's rule (the caller should already pass records only). Result order is
     * deterministic and pinned: matched records newest-first, each immediately followed by the
     * antecedent it drags in (dedup by id), truncated at [MAX_TOTAL]. No match ⇒ empty ⇒ the caller
     * renders no block and the prompt is byte-identical to a memoryless member's (§2.9).
     */
    fun select(memories: List<PersonaMemory>, contextText: String): List<PersonaMemory> {
        val records = memories.filter { it.kind == PersonaMemoryRepository.KIND_RECORD }
        val matched = records
            .filter { record -> wordsOf(record.body).any { WholeWords.contains(contextText, it) } }
            .sortedWith(compareByDescending<PersonaMemory> { it.createdAt }.thenBy { it.id })
            .take(MAX_MATCHED)
        val recordById = records.associateBy { it.id }
        val selected = mutableListOf<PersonaMemory>()
        matched.forEach { record ->
            if (selected.none { it.id == record.id }) selected += record
            // The hop: resolved ONLY among the loaded records — the root is unreachable here even
            // when a corrupt row names it, because it was never in recordById to begin with.
            val parent = record.parentId?.let { recordById[it] }
            if (parent != null && selected.none { it.id == parent.id }) selected += parent
        }
        return selected.take(MAX_TOTAL)
    }

    /**
     * The record's own retrieval vocabulary (§2.7 — no tags column; the prose that provably exists
     * is the vocabulary): its distinct lower-cased words of at least [MIN_WORD_CODE_POINTS] code
     * points. Split on anything that is not a letter, digit or underscore — the same word-character
     * family [WholeWords] treats as gluing — so the extracted token is one the matcher can find.
     */
    private fun wordsOf(body: String): List<String> =
        body.lowercase()
            .split(NON_WORD)
            .filter { it.isNotEmpty() && MemoryText.codePoints(it) >= MIN_WORD_CODE_POINTS }
            .distinct()

    private val NON_WORD = Regex("[^\\p{L}\\p{N}_]+")
}
