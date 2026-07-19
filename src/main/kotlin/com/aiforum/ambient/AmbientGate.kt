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
     * "Whole word" is UNICODE-AWARE, not `\b` (Java's `\b` treats every non-ASCII letter as a boundary, so
     * a tag like "café" or "日本語" — abilities are owner-typed free text — would silently pin relevance to
     * 0 forever). An occurrence counts when neither edge GLUES onto adjacent text: an adjacent
     * letter/digit/underscore blocks the match ("go" never matches inside "golang", "sql" not inside
     * "SQLite") — UNLESS it belongs to a different Unicode script than the tag's edge character, because a
     * script change IS the word boundary in unspaced CJK text ("日本語" matches "日本語のスレッド", Han →
     * Hiragana, while "日本" does not match inside "日本語", Han → Han). Digits/underscore (script COMMON)
     * bind to everything, preserving the ASCII behaviour ("sqlite" doesn't match inside "sqlite3").
     */
    fun relevance(abilities: List<String>, text: String): Int =
        abilities.count { tag -> tag.isNotBlank() && containsWholeWord(text, tag.trim()) }

    /** True when [tag] occurs in [text] (case-insensitive) with both edges free (see [relevance]). */
    private fun containsWholeWord(text: String, tag: String): Boolean {
        var i = text.indexOf(tag, 0, ignoreCase = true)
        while (i >= 0) {
            if (freeEdge(text, i - 1, tag.first()) && freeEdge(text, i + tag.length, tag.last())) return true
            i = text.indexOf(tag, i + 1, ignoreCase = true)
        }
        return false
    }

    /** The char at [pos] does not glue onto the tag's [edge] char: out of range, not a word char, or a
     *  word char of a DIFFERENT script (the CJK script-change boundary). */
    private fun freeEdge(text: String, pos: Int, edge: Char): Boolean {
        if (pos < 0 || pos >= text.length) return true
        val adjacent = text[pos]
        if (adjacent != '_' && !adjacent.isLetterOrDigit()) return true
        return distinctScripts(adjacent, edge)
    }

    /** Word chars of two different scripts form a natural boundary; COMMON/INHERITED (digits, '_',
     *  combining marks) bind to any script, so they never free an edge. */
    private fun distinctScripts(a: Char, b: Char): Boolean {
        val sa = Character.UnicodeScript.of(a.code)
        val sb = Character.UnicodeScript.of(b.code)
        if (sa == Character.UnicodeScript.COMMON || sa == Character.UnicodeScript.INHERITED) return false
        if (sb == Character.UnicodeScript.COMMON || sb == Character.UnicodeScript.INHERITED) return false
        return sa != sb
    }

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
