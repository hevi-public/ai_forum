package com.aiforum.persona

/**
 * The shape rules for ONE mutable interest phrase (`plan_docs/ambient-slice-4b.md` D5/D11), in one
 * place because two paths write `persona_interest` rows and both have to agree with V27's `CHECK`
 * constraints: the drift judgment ([InterestDrift.parse]) and the owner's edit form
 * (`PersonaController.edit`).
 *
 * ## Why the validator is shared rather than duplicated per path
 *
 * `V27__persona_interest.sql` enforces `length(trim(interest)) BETWEEN 2 AND 80` in SQL, so a phrase
 * that breaks it does not fail politely — it throws a `DataAccessException` out of the middle of a
 * form save. Interest writes run *before* the prompt logic in `PersonaController.edit`
 * (`src/main/kotlin/com/aiforum/web/PersonaController.kt:167`), which is exactly the failure the three
 * `applyStanceEdits` no-op guards were written against (`:233-239`): the owner would lose their
 * descriptor and dial edits too, for typing one character into a field they were invited to type into.
 * Both writers ask here first, so the DDL's CHECK stays a backstop rather than an error path.
 *
 * ## Why the DIGIT rule is deliberately NOT here
 *
 * V27's digit CHECK is scoped — `source = 'owner' OR interest NOT GLOB '*[0-9]*'` — and the scope is
 * the whole point: the no-numbers guardrail exists to stop a MODEL smuggling a score in, and an owner
 * typing `http/3` or `web3` is naming a real topic, not a measurement. The rule is about the *writer*,
 * and this object does not know the writer. So the digit refusal lives in [InterestDrift.parse], the
 * one path whose writer is by definition the model.
 *
 * **Rejected: a `validate(phrase, source)` overload.** It would put the guardrail behind a parameter
 * any future caller can pass `"owner"` for, which is a rule you can opt out of by writing one word.
 */
object Interests {

    /** Two characters admits "Go" and "AI"; the same floor V27's length CHECK enforces. */
    const val MIN_CHARS = 2

    /** The same ceiling V27's length CHECK enforces, so the parse and the DDL agree by construction. */
    const val MAX_CHARS = 80

    /**
     * Returns a reason the phrase may not be stored, or `null` if it may — a reason rather than a
     * boolean because both callers show it to the owner (the drift log, a skipped form field), and
     * "invalid" on a page tells them nothing about what to type instead.
     *
     * Measured on [clean]'s output, so callers must store `clean(phrase)`: validating one form of the
     * text and inserting another is how a value that passed here still trips the DDL.
     *
     * Blank is kept distinct from too-short even though blank *is* too short. The owner path reads a
     * blank field as a retraction and never asks about one, so the only caller that can reach this
     * branch is the judge with an empty `TAKE:` line — where "blank" is the true diagnosis and "at
     * least two characters" would send the owner looking for a phrase that was never there.
     */
    fun validate(phrase: String): String? {
        val cleaned = clean(phrase)
        // CHARACTERS AS SQLITE COUNTS THEM, which is code points — `String.length` is UTF-16 units, so a
        // single non-BMP character (one emoji) measures 2 here and 1 to `length(trim(interest))`. It
        // would clear this floor and then trip V27's CHECK, and the trip lands mid-write: the owner
        // form's reconciliation commits its retractions before its upserts, so the phrase being edited
        // is already gone when the exception surfaces — and on the drift path the rollback leaves the
        // watermark unstamped, which re-buys that judgment every run. This measurement IS the guarantee
        // that a phrase accepted here can be stored.
        val characters = cleaned.codePointCount(0, cleaned.length)
        return when {
            cleaned.isBlank() -> "an interest cannot be blank"
            // A phrase that is not already a fixed point of [clean] is refused, because the storage door
            // cleans again: `""x""` arrives here, cleans to `"x"`, measures 3, and is written as `x` at
            // 1 — validated as one string and stored as another, which is the shape that lets a phrase
            // slip past the already-held and owner-pinned checks and land on somebody else's row. The
            // judge refuses this too, with its own wording; this is the same rule for the owner's form
            // and for every writer added later.
            clean(cleaned) != cleaned -> "an interest cannot stay wrapped in quotes once unwrapped"
            characters < MIN_CHARS -> "an interest must be at least $MIN_CHARS characters"
            characters > MAX_CHARS -> "an interest must be at most $MAX_CHARS characters"
            else -> null
        }
    }

    /**
     * Normalise a phrase for storage and for comparison: trim, drop ONE matched pair of wrapping
     * quotes, collapse internal whitespace runs. [StanceJudge.parse]'s cleaner
     * (`src/main/kotlin/com/aiforum/persona/StanceJudge.kt:101-113`) lifted out of the judge, because
     * S4a's b6 defect was cleaning only the candidate: stored text is not tidy — hand-written seeds, a
     * textarea that returns whatever the owner typed — so both sides of every comparison run through
     * this, and the value that reaches SQL is the value that was compared.
     *
     * **Not idempotent on quotes**, and callers depend on that: `""agents""` cleans to `"agents"`, not
     * to `agents`. A model that double-quoted its answer meant the inner pair to be part of the phrase,
     * and unwrapping until nothing is left rewrites its words instead of undoing its packaging. So
     * clean each value exactly once (see [InterestDrift]'s comparison helper).
     */
    fun clean(raw: String): String =
        stripWrappingQuotes(raw.trim()).replace(WHITESPACE, " ").trim()

    private fun stripWrappingQuotes(text: String): String {
        if (text.length < 2) return text
        val wrapped = QUOTE_PAIRS.any { (open, close) -> text.first() == open && text.last() == close }
        return if (wrapped) text.substring(1, text.length - 1).trim() else text
    }

    private val QUOTE_PAIRS = listOf('"' to '"', '\'' to '\'', '“' to '”', '‘' to '’')

    private val WHITESPACE = Regex("\\s+")
}
