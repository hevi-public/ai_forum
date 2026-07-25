package com.aiforum.persona

/**
 * The pure half of the stance-evolution judgment (`plan_docs/ambient-slice-4a.md` D6): it turns
 * whatever the model wrote back into a [Verdict] the write path can act on, with no LLM in sight —
 * the same Tier-0 posture as [ComposerPrompts] and [StanceProse]. [StanceJudgePrompts] asks the
 * question; this object decides whether the answer is fit to become a stance.
 *
 * ## The digit rule is the point of this object
 *
 * The relation model is prose by hard guardrail. Persona relationships were cut once already as part
 * of a quantified reward economy (per-persona scores, reputation, tallies), and reviving them as a
 * directed edge carrying a number would silently re-import exactly that. Every other defence of that
 * rule is a convention someone has to remember — no numeric column on `persona_stance`, none on
 * `stance_change`, nothing that ranks or aggregates.
 *
 * This one is a test. The single place a number could still enter the graph is the judge's answer:
 * the stance text is the only field in the relation model a language model writes. So an answer
 * containing a digit is REFUSED here and the old stance stands untouched. "Pushed back twice this
 * week" is a relationship and passes; "trust 4/5" or "+2 respect" is a score wearing a new name, and
 * it cannot reach the table because this function will not hand it on. The judge's SYSTEM prompt
 * states the rule too, so a rejection means the model disobeyed — not that it was ambushed by a rule
 * nobody told it.
 *
 * A rejection is **not** an error: the run logs the reason, leaves the edge alone and moves to the
 * next pair. That is why [Verdict.Rejected] carries a short reason written to be read by the owner on
 * `/admin/stances`, rather than an exception nobody sees at four in the morning.
 */
object StanceJudge {

    /**
     * One persona→persona exchange, as evidence for the judgment. [body] is what the stance's HOLDER
     * wrote; [towardBody] is the text they were answering — the parent comment, or the thread's
     * opening post when the comment landed top-level on the other member's thread (D2's load-bearing
     * branch: the ambient loop's most common interaction has no parent row at all).
     */
    data class Exchange(val body: String, val towardBody: String)

    /**
     * What a raw judgment amounts to.
     *
     * [Unchanged] is a first-class outcome rather than a degenerate [Changed]: a stance that came back
     * the same must produce no upsert and no audit row, or the owner's history page fills with entries
     * recording that nothing happened — and the one control they have over an auto-applied change is
     * being able to read that page.
     */
    sealed interface Verdict {
        /** The judgment is usable and differs from the current stance; [text] is already cleaned. */
        data class Changed(val text: String) : Verdict

        /** The judgment cannot become a stance; [reason] is shown to the owner, so keep it plain. */
        data class Rejected(val reason: String) : Verdict

        /** The judgment restates the current stance — no write, no audit row. */
        data object Unchanged : Verdict
    }

    /**
     * A stance is one sentence of prose that gets injected into every prompt its holder sends (see
     * [StanceProse.block]), so the ceiling is about prompt budget as much as taste: an answer running
     * past it is a model that started explaining its reasoning instead of stating an attitude, and
     * pasting that into every future generation is worse than leaving the old stance in place.
     */
    const val MAX_STANCE_CHARS = 300

    /**
     * Judge [raw] against the stance it would replace ([current]).
     *
     * Cleaning first, so a model that obeyed the instruction badly still gets a fair hearing: trim,
     * drop ONE pair of wrapping quotes (models quote their answers even when told not to — see the
     * "no preamble or quotes" line in [StanceJudgePrompts.SYSTEM]), and collapse internal whitespace
     * runs so a wrapped answer doesn't carry newlines into a prompt block that renders one line per
     * relation.
     *
     * Then the three refusals — blank, over-long, digit-bearing — before the no-op check, because a
     * rejection is about whether the text may become a stance at all and that question is settled
     * without reference to what the stance says today.
     *
     * BOTH sides of the no-op check go through [clean], not just the candidate. The stored stance is
     * not guaranteed to be tidy: seeds are hand-written prose, the persona form's textarea returns
     * whatever the owner typed (a double space, a wrapped line), and an older stance may still be
     * carrying the quotes a judgment wrapped it in. Cleaning one side only makes a model that echoed
     * the standing view back verbatim — the behaviour [StanceJudgePrompts.SYSTEM] explicitly asks for
     * when nothing moved — read as [Verdict.Changed]: an audit row on `/admin/stances` recording that
     * the stance became itself, an upsert restamping the row's provenance as evolved, and an LLM
     * recompose of the holder's stored prompt, all for text nobody altered. Comparing like with like
     * costs one extra normalisation per judgment.
     */
    fun parse(raw: String, current: String): Verdict {
        val cleaned = clean(raw)
        return when {
            cleaned.isBlank() -> Verdict.Rejected("the model answered with nothing usable")
            cleaned.length > MAX_STANCE_CHARS -> Verdict.Rejected("the answer was longer than a stance may be")
            cleaned.any { it.isDigit() } ->
                Verdict.Rejected("the answer carried a number; a relation is prose, never a score")
            cleaned.equals(clean(current), ignoreCase = true) -> Verdict.Unchanged
            else -> Verdict.Changed(cleaned)
        }
    }

    private fun clean(raw: String): String =
        stripWrappingQuotes(raw.trim()).replace(WHITESPACE, " ").trim()

    /**
     * Drop a SINGLE matched pair of wrapping quotes — straight or curly, double or single. One pair
     * only: a model that answered `""needles him""` meant the inner quotes to be part of the sentence,
     * and unwrapping until nothing is left would rewrite its words rather than undo its packaging.
     */
    private fun stripWrappingQuotes(text: String): String {
        if (text.length < 2) return text
        val wrapped = QUOTE_PAIRS.any { (open, close) -> text.first() == open && text.last() == close }
        return if (wrapped) text.substring(1, text.length - 1).trim() else text
    }

    private val QUOTE_PAIRS = listOf('"' to '"', '\'' to '\'', '“' to '”', '‘' to '’')

    private val WHITESPACE = Regex("\\s+")
}
