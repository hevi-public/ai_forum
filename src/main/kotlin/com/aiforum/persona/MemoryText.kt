package com.aiforum.persona

/**
 * The ONE owner of cleaning and validation for a memory body (`plan_docs/persona-memory.md` §2.15,
 * D15), used by both the scribe parse ([ScribeAnswer]) and the owner's profile form — one *function*,
 * not shared constants, because constants-only agreement is a weaker guarantee no test can assert
 * (the S4b §10.1 lesson).
 *
 * ## The clean-once fixed-point discipline (I5: the value compared IS the value stored)
 *
 * [clean] is applied **exactly once, at the door**; [validate] then *refuses* any candidate that is
 * not a fixed point of [clean] — it never re-cleans. This is the by-construction dodge of the S4b
 * review class (4b §10.3 item 3): validating one form of a string and storing another is how a value
 * slips past a duplicate check and lands as a different row, and re-cleaning at a second site is how
 * the two forms come to exist. Unlike [Interests.clean], this cleaner strips no quotes — trim plus
 * whitespace-run collapse only — so it is **idempotent by construction** (the Tier-0 property test),
 * and a non-fixed-point candidate can only mean a caller skipped the door.
 *
 * ## Code points on BOTH sides
 *
 * Lengths are measured in **code points** ([codePoints]), which is exactly what SQLite's `length()`
 * counts — `String.length` is UTF-16 units, so one emoji measures 2 to Kotlin and 1 to the V28
 * CHECK. Measuring in UTF-16 would admit a 300-"char" body that SQLite sees as under the bound
 * (harmless) but, worse, would let a second measuring site disagree with the DDL. The V28 CHECK
 * (`source = 'owner' OR length(body) BETWEEN 1 AND 300`) is scoped to the pass's rows like V27's, so
 * the owner path never trips a model-aimed constraint mid-write; this validator still refuses an
 * over-long body politely for every caller, keeping the CHECK a backstop rather than an error path.
 *
 * ## What deliberately is NOT here
 *
 * No rating-shape refusal — that binds on the *answer's lines* at the parse ([ScribeAnswer]), the one
 * path whose writer is by definition the model (the [Interests] digit-rule precedent: the rule is
 * about the writer, and this object does not know the writer). And no re-cleaning helper: the absence
 * of any "clean and validate" convenience is the point — callers clean once, then validate what they
 * will store, byte-identical.
 */
object MemoryText {

    /** The V28 scribe-row bound, counted as SQLite counts it. One sentence of experiential prose. */
    const val MAX_CODE_POINTS = 300

    /**
     * Normalise a body for storage and comparison: trim, collapse internal whitespace runs (newlines
     * included) to single spaces. Idempotent — `clean(clean(x)) == clean(x)` for every input — which
     * is what lets [validate] treat "not a fixed point" as "the caller skipped the door" rather than
     * as packaging to silently unwrap.
     */
    fun clean(raw: String): String = raw.trim().replace(WHITESPACE, " ")

    /**
     * Length as SQLite `length()` measures it: Unicode code points, so a surrogate pair (one emoji)
     * counts 1 here and 1 to the V28 CHECK. The two sides agree by construction (I5) **because
     * [validate] refuses NUL first**: SQLite documents `length(X)` as counting characters only up
     * to the first `U+0000` (`length('a'||char(0)||'b')` is 1; this counts 3), so the agreement
     * claim is true of exactly the strings the validator lets through — the close-out audit found
     * the unqualified version of this claim false (persona-memory.md §10.3 item 1; V28's header
     * carries the old wording, immutable because applied).
     */
    fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    /**
     * The canonical case fold for duplicate comparison (§2.5): Kotlin's Unicode-aware [lowercase],
     * never SQLite's ASCII-only NOCASE — the DB collation never participates in a memory comparison.
     * One fold, defined once, so "case-insensitively equals" means the same thing at every caller.
     */
    fun fold(text: String): String = text.lowercase()

    /**
     * Returns the reason [candidate] may not be stored, or `null` when it may — a reason rather than
     * a boolean because both callers log it (the pass's rejection event, the owner form's
     * `memory.author.rejected` warn), and an undifferentiated "invalid" on a log tells the reader
     * nothing about what was wrong. There is no form flash: every owner-form rejection is a silent
     * no-op with the reason logged, uniform with the S4b interest form (§10.3 item 3, §10.4).
     *
     * The NUL refusal is what keeps I5's "code points on both sides" claim true rather than
     * approximate: `U+0000` is the ONE character Kotlin's trim/`isBlank`/`\s+` all pass through
     * while SQLite's `length()` stops counting at it, so a NUL-bearing body would pass every other
     * check here and then part company with what SQLite stores. The two halves are not the same
     * failure, and only one of them is loud: a LEADING NUL measures 0 to the V28 CHECK and trips
     * `length(body) BETWEEN 1 AND 300` mid-write as an uncaught driver exception — the 500 the
     * owner surface promises can never happen. A MID-NUL body does NOT trip it: `length('a' ||
     * char(0) || 'b')` is 1, inside the bound, so it would store SILENTLY with a length nobody can
     * reconcile with the 3 code points validated here — the I5 divergence itself rather than a
     * crash, and the worse of the two precisely because nothing goes red. Hence a refusal on the
     * character, never on its position. Other control characters are deliberately NOT refused: none
     * of them truncates SQLite's count, and over-rejection is its own defect class.
     *
     * Measured on [candidate] AS PASSED, never on a cleaned copy: a candidate that is not already a
     * fixed point of [clean] is refused outright. Re-cleaning here would validate one string and let
     * the caller store another — the exact defect class this object exists to make unrepresentable.
     */
    fun validate(candidate: String): String? = when {
        candidate.isBlank() -> "a memory cannot be blank"
        '\u0000' in candidate -> "a memory cannot contain the NUL character"
        clean(candidate) != candidate ->
            "a memory must arrive already cleaned; it is refused, never re-cleaned"
        codePoints(candidate) > MAX_CODE_POINTS ->
            "a memory must be at most $MAX_CODE_POINTS characters"
        else -> null
    }

    private val WHITESPACE = Regex("\\s+")
}
