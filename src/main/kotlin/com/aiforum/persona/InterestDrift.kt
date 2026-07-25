package com.aiforum.persona

/**
 * The pure half of the interest-drift judgment (`plan_docs/ambient-slice-4b.md` D5): it turns whatever
 * the model wrote back into a [Verdict] the write path can act on, with no LLM in sight — the
 * [StanceJudge] posture (`src/main/kotlin/com/aiforum/persona/StanceJudge.kt:89`) applied to the one
 * mechanism in this app that can change what a member is *into*. [InterestDriftPrompts] asks the
 * question; this object decides whether the answer may move a row.
 *
 * ## The three invariants this parse is the last line of
 *
 * - **A number never enters what a member is into (I2).** The digit refusal below is the same rule
 *   [StanceJudge] enforces for stances, in the one place a number could still arrive: the model's
 *   answer. "Kept coming back to storage engines" is prose and passes; "priority 2 of 5" cannot reach
 *   the table. Unlike S4a this is *backstopped in SQL* (V27's `interest NOT GLOB '*[0-9]*'` CHECK,
 *   scoped to the rows this pass writes), so the parse is the polite half of a rule the database also
 *   holds.
 * - **The member's pinned interests never move (I1/D3.3).** An owner-pinned phrase is skipped before
 *   any spend, but a judgment can still *name* one, and that refusal carries its own reason so
 *   *"it tried to move what you fixed"* is readable on `/admin/interests` instead of hiding inside a
 *   generic malformed message.
 * - **The count never grows (I3).** Drift is one row out, one row in. A `TAKE` the member already holds
 *   is refused for that reason alone: a degenerate swap deletes one phrase and inserts one it already
 *   had, and the member quietly holds fewer interests than the owner authored.
 *
 * A rejection is **not** an error: the run logs the reason, leaves the member's interests alone and
 * leaves the watermark open so the same evidence gets another look (D6). That is why [Verdict.Rejected]
 * carries a short reason written to be read by the owner, rather than an exception nobody sees at
 * four-thirty on a Sunday morning.
 */
object InterestDrift {

    /**
     * The exact word the prompt asks for when nothing moved, shared with
     * [InterestDriftPrompts.instruction] so the sentinel the model is told to write and the one this
     * parse accepts cannot drift apart in separate edits.
     */
    const val NOTHING_MOVED = "NONE"

    /**
     * What a raw judgment amounts to. Three cases because three verdicts map one-to-one onto the three
     * write behaviours in D6's table, and a fourth case would want a fourth behaviour nobody has.
     *
     * [Unchanged] is first-class rather than a degenerate [Drifted]: "nothing moved" is the
     * **overwhelming majority verdict** in this slice by construction (most members most weeks write
     * nothing that moves them), and it must still close the member's window — an answer about the
     * evidence is an answer — while producing no audit row. An audit page filling up with entries
     * recording that nothing happened would destroy the one control the owner has over an
     * auto-applied change.
     */
    sealed interface Verdict {
        /**
         * [dropped] is the phrase **in the spelling the member holds**, not the spelling the model
         * wrote: the write path deletes by primary key, and a stored phrase carrying a double space
         * would survive a delete keyed on the model's tidy version — leaving the member holding both
         * the old phrase and the new one, which is I3 broken by a rounding error. [takenUp] is already
         * cleaned and validated.
         */
        data class Drifted(val dropped: String, val takenUp: String) : Verdict

        /** The judgment may not move a row; [reason] is shown to the owner, so keep it plain. */
        data class Rejected(val reason: String) : Verdict

        /** The member has not moved on — close the window, write nothing. */
        data object Unchanged : Verdict
    }

    /**
     * Judge [raw] against the interests the member holds: [open] are the ones a pass may set down,
     * [pinned] the ones the owner froze (D11). Both lists are the **stored** phrases, untidy or not.
     *
     * The refusals run in D5's order and all of them before the no-op check, because whether the text
     * may move a row at all is settled without reference to whether the member has moved on.
     *
     * Line structure is read *before* the answer is cleaned as a whole, because [Interests.clean]
     * collapses newlines and the two labelled lines *are* the shape being checked. So each line is
     * cleaned on its own, and each value again once its label is off. Two passes, but one pair of
     * quotes per layer of packaging — the line's and the value's — never two pairs off the same text,
     * which is the unwrap [Interests.clean] documents as rewriting the model's words.
     */
    fun parse(raw: String, open: List<String>, pinned: List<String>): Verdict {
        val lines = raw.lines().map(Interests::clean).filter { it.isNotBlank() }
        // 1 — nothing usable at all.
        if (lines.isEmpty()) return Verdict.Rejected("the model answered with nothing usable")
        // 2 — neither the settled answer nor a well-formed pair, extra content lines included.
        val answer = shapeOf(lines)
            ?: return Verdict.Rejected("the answer was not a set-down-and-take-up pair")
        // 3-7, then the swap itself.
        if (answer is Answer.Swap) return judge(answer, open, pinned)
        // 8 — the model was told to say this when nothing moved, and usually will.
        return Verdict.Unchanged
    }

    /**
     * The refusals that only a swap can trip, in D5's numbered order. Each carries its own reason: the
     * owner reading the log has to be able to tell "the model made something up" from "the model went
     * for a phrase you pinned", and one shared malformed message makes those two the same event.
     */
    private fun judge(answer: Answer.Swap, open: List<String>, pinned: List<String>): Verdict {
        // 3 — the length rule lives in Interests, so the parse and the owner's form agree with the DDL.
        //
        // Bound-checked on the string this verdict will actually HAND BACK, not by routing it through
        // `Interests.validate`, which cleans its argument again. `clean` strips one wrapping quote pair
        // and is therefore NOT idempotent — the same reason `holds` refuses to re-clean a candidate — so
        // a double-quoted eighty-character answer would validate at eighty and be stored at eighty-two,
        // tripping V27's `length(trim(interest)) BETWEEN 2 AND 80` inside the write transaction. That
        // rolls the swap back AND leaves the window unstamped, so the run re-buys the same judgment
        // every week: a cheap parse mistake turning into a permanent cost.
        if (answer.take.length !in Interests.MIN_CHARS..Interests.MAX_CHARS) {
            return Verdict.Rejected("the answer was not the length an interest may be")
        }
        // 4 — I2. Char.isDigit() is Unicode-aware, so an Arabic-Indic or fullwidth digit counts too;
        // a rule a model can dodge by changing keyboard layout is not a rule.
        if (answer.take.any { it.isDigit() }) {
            return Verdict.Rejected("the answer carried a number; an interest is prose, never a score")
        }
        // 5 — before the not-held check, so a pinned phrase reports as pinned rather than as absent
        // from the droppable list, which is technically true and tells the owner nothing.
        if (pinned.any { holds(it, answer.drop) }) {
            return Verdict.Rejected("the answer tried to set down an interest the owner pinned")
        }
        // 6 — and the matching STORED phrase is what the verdict carries, not the model's spelling.
        val stored = open.firstOrNull { holds(it, answer.drop) }
            ?: return Verdict.Rejected("the answer set down an interest this member does not hold")
        // 7 — I3: `open ∪ pinned`, because taking up a phrase the owner pinned collapses the count
        // just as surely as re-taking an open one, and additionally un-pins it by relabelling it.
        if (open.any { holds(it, answer.take) } || pinned.any { holds(it, answer.take) }) {
            return Verdict.Rejected("the answer took up an interest this member already holds")
        }
        return Verdict.Drifted(dropped = stored, takenUp = answer.take)
    }

    /**
     * Which of the two answers the model gave, or `null` for anything else.
     *
     * Labels are matched case-insensitively — their case is our packaging, not the member's words, and
     * a whole judgment is too expensive to refuse over `drop:`. **Their ORDER is not** negotiable
     * though a label-keyed lookup could recover it: this is the one parse in the app whose output
     * deletes a row, and a model that reordered the shape it was handed is improvising. A cheap refusal
     * that re-reads the same evidence next run beats a confident guess about which phrase to delete.
     */
    private fun shapeOf(lines: List<String>): Answer? = when {
        lines.size == 1 && lines.single().equals(NOTHING_MOVED, ignoreCase = true) -> Answer.Settled
        lines.size != 2 -> null
        !lines[0].startsWith(DROP_LABEL, ignoreCase = true) -> null
        !lines[1].startsWith(TAKE_LABEL, ignoreCase = true) -> null
        else -> Answer.Swap(
            drop = Interests.clean(lines[0].substring(DROP_LABEL.length)),
            take = Interests.clean(lines[1].substring(TAKE_LABEL.length)),
        )
    }

    /**
     * Does [stored] name the same interest as the already-cleaned [candidate]? Case-insensitive to
     * agree with `persona_interest.interest`'s `COLLATE NOCASE` — otherwise "Storage engines" and
     * "storage engines" become two rows and the count invariant leaks.
     *
     * Only the stored side is cleaned here. The candidate was cleaned exactly once when its label came
     * off, and [Interests.clean] is not idempotent on quotes: a second pass would strip a second pair
     * and rewrite the model's words.
     */
    private fun holds(stored: String, candidate: String): Boolean =
        Interests.clean(stored).equals(candidate, ignoreCase = true)

    /** The answer's shape, before anything is known about whether it may be applied. */
    private sealed interface Answer {
        data object Settled : Answer
        data class Swap(val drop: String, val take: String) : Answer
    }

    private const val DROP_LABEL = "DROP:"
    private const val TAKE_LABEL = "TAKE:"
}
