package com.aiforum.ambient

/**
 * The Unicode-script-aware whole-word matcher, extracted verbatim from [AmbientGate]'s private
 * `containsWholeWord` (plan_docs/persona-memory.md §2.7, build step 4) so memory recall can ask the
 * same question the ambient gate asks — "does this word occur in this text as a word?" — without
 * routing through a count-returning gate API. `AmbientGate.relevance(listOf(word), text) > 0` would
 * have worked, but a count-shaped value in recall code is an invitation to rank, and recall is
 * binary by design; extraction keeps it binary by signature.
 *
 * The extraction is a pure refactor: [AmbientGate.relevance] delegates here and its own Tier-0
 * suite is the pin that the behaviour moved without changing. The semantics, unchanged:
 *
 * "Whole word" is UNICODE-AWARE, not `\b` (Java's `\b` treats every non-ASCII letter as a boundary,
 * so a word like "café" or "日本語" — abilities and memory bodies are free text — would silently
 * never match). An occurrence counts when neither edge GLUES onto adjacent text: an adjacent
 * letter/digit/underscore blocks the match ("go" never matches inside "golang", "sql" not inside
 * "SQLite") — UNLESS it belongs to a different Unicode script than the word's edge character,
 * because a script change IS the word boundary in unspaced CJK text ("日本語" matches
 * "日本語のスレッド", Han → Hiragana, while "日本" does not match inside "日本語", Han → Han).
 * Digits/underscore (script COMMON) bind to everything, preserving the ASCII behaviour ("sqlite"
 * doesn't match inside "sqlite3").
 */
object WholeWords {

    /** True when [word] occurs in [text] (case-insensitive) with both edges free. */
    fun contains(text: String, word: String): Boolean {
        var i = text.indexOf(word, 0, ignoreCase = true)
        while (i >= 0) {
            if (freeEdge(text, i - 1, word.first()) && freeEdge(text, i + word.length, word.last())) return true
            i = text.indexOf(word, i + 1, ignoreCase = true)
        }
        return false
    }

    /** The char at [pos] does not glue onto the word's [edge] char: out of range, not a word char, or a
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
}
