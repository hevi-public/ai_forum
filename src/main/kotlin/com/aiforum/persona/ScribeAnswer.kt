package com.aiforum.persona

/**
 * The pure half of the Memory Scribe judgment (`plan_docs/persona-memory.md` §2.5): it turns whatever
 * the model wrote back into a [Verdict] the write path can act on, with no LLM in sight — the
 * [InterestDrift] posture applied to the one mechanism in this app that can write what a member
 * *remembers*. The contract the prompt asks for:
 *
 * ```
 * NOTHING
 * ```
 * or
 * ```
 * REMEMBER: <one sentence of first-person experiential prose, ≤300 chars>
 * EXTENDS: <one letter from the offered list>        (optional line)
 * ```
 *
 * ## What this parse refuses, and what it deliberately does not
 *
 * - **Rating-shaped lines are refused as hygiene (D8).** The rule binds on SHAPE, never on character
 *   class: `importance:`, `salience:`, `score:` labels and the `…/10` form are how a model smuggles a
 *   magnitude into prose, and they are refused — while *"we argued about WAL mode in V27"* passes,
 *   because this forum's own subject matter is digit-saturated and no reader anywhere parses a
 *   numeric value out of a body (the V26/PR#6 lesson: a digit ban plus rejected-never-stamps would
 *   re-buy the same judgment weekly).
 * - **The body is validated by [MemoryText.validate], the same function the owner form uses** (§2.15
 *   — one function, not shared constants), on the string this verdict will hand back: a body that is
 *   not already a fixed point of [MemoryText.clean] is refused, never re-cleaned, so the value the
 *   duplicate check will compare is byte-identical to the value the repository will store (I5).
 * - **The EXTENDS letter is extracted, not judged.** The parse does not know the offered set, so an
 *   out-of-set letter surfaces in the verdict as-is and the *service* decides the degrade
 *   (top-level attachment, `event=memory.parent.unknown` — a broken decoration never costs a paid,
 *   well-formed record, §2.4). A single ASCII letter is normalised to the upper-case identity the
 *   offered list uses; any other token rides through raw so the log can show what the model wrote.
 *
 * A rejection is **not** an error: the run logs the reason, writes nothing, and leaves the member's
 * window unstamped so the same evidence gets another look (§2.5's posture table). The reason is
 * written to be read by the owner, not by a stack trace.
 */
object ScribeAnswer {

    /** The exact word the prompt asks for when the member's week left nothing worth keeping. */
    const val NOTHING = "NOTHING"

    /**
     * What a raw answer amounts to. Three cases, mapping onto §2.5's stamp behaviours: [Remember]
     * and [NothingToRemember] are usable answers (the caller stamps the window — duplicate detection
     * is the caller's job, it needs the stored rows); [Rejected] never stamps.
     */
    sealed interface Verdict {
        /** A paid, well-formed record: [body] is validated, cleaned-form prose, ready to store
         *  byte-identical; [extends] is the raw offered-list selector, or null when top-level. */
        data class Remember(val body: String, val extends: String?) : Verdict

        /** The answer may not write a row; [reason] is shown to the owner, so keep it plain. */
        data class Rejected(val reason: String) : Verdict

        /** The designed steady state: no row, but the window still closes (the V26 cost lesson). */
        data object NothingToRemember : Verdict
    }

    /**
     * Judge [raw] on shape alone. Lines are trimmed individually (a line's edge whitespace is our
     * packaging), but their INSIDES are never re-spaced — [MemoryText.validate]'s fixed-point refusal
     * is what guards the body, and cleaning here would validate one string while logging another.
     */
    fun parse(raw: String): Verdict {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return Verdict.Rejected("the model answered with nothing usable")
        // Rating shapes are refused before shape parsing, so an owner reading the log sees "it wrote
        // a rating" rather than a generic shape complaint — the two are different model behaviours.
        if (lines.any { ratingShaped(it) }) {
            return Verdict.Rejected("the answer carried a rating; a memory is prose, never a score")
        }
        if (lines.size == 1 && lines.single().equals(NOTHING, ignoreCase = true)) {
            return Verdict.NothingToRemember
        }
        if (!lines[0].startsWith(REMEMBER_LABEL, ignoreCase = true)) {
            return Verdict.Rejected("the answer was not $NOTHING or a REMEMBER record")
        }
        if (lines.size > 2 || (lines.size == 2 && !lines[1].startsWith(EXTENDS_LABEL, ignoreCase = true))) {
            return Verdict.Rejected("the answer was not $NOTHING or a REMEMBER record")
        }
        // The delimiter space after the label comes off; everything inside the body stays as the
        // model wrote it, and MemoryText decides whether that is storable as-is (blank, not a fixed
        // point of clean, over 300 code points) — the same function, and therefore the same reasons,
        // the owner's form gets.
        val body = lines[0].substring(REMEMBER_LABEL.length).trim()
        // The body is a line of its own once the label is off: "REMEMBER: score: 9" is a rating
        // wearing the record label, and the line-level check above cannot see it (its line starts
        // with REMEMBER:, not with the rating label).
        if (ratingShaped(body)) {
            return Verdict.Rejected("the answer carried a rating; a memory is prose, never a score")
        }
        MemoryText.validate(body)?.let { return Verdict.Rejected(it) }
        val extends = lines.getOrNull(1)?.substring(EXTENDS_LABEL.length)?.trim()?.let(::normalise)
        return Verdict.Remember(body = body, extends = extends)
    }

    /**
     * A line that smuggles a magnitude: a rating label, or the `…/10` form anywhere in the line.
     * Checked per line, so a rating stapled under a well-formed REMEMBER still refuses the answer —
     * §6 scenario 12's exact fixture.
     */
    private fun ratingShaped(line: String): Boolean =
        RATING_LABELS.any { line.startsWith(it, ignoreCase = true) } || OUT_OF_TEN.containsMatchIn(line)

    /** One ASCII letter is the offered list's identity, folded to its canonical upper case; anything
     *  else is left raw for the service's unknown-selector log line. */
    private fun normalise(token: String): String =
        if (token.length == 1 && token.single() in 'a'..'z') token.uppercase() else token

    private const val REMEMBER_LABEL = "REMEMBER:"
    private const val EXTENDS_LABEL = "EXTENDS:"

    private val RATING_LABELS = listOf("importance:", "salience:", "score:")
    private val OUT_OF_TEN = Regex("""\d\s*/\s*10\b""")
}
