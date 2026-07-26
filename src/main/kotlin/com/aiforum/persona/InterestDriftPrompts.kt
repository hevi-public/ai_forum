package com.aiforum.persona

/**
 * Builds the meta-prompt sent to the LLM that judges whether a member has moved on from one of the
 * topics it is drawn to (`plan_docs/ambient-slice-4b.md` D5). Pure (Tier 0): given the member, its
 * character, its interests and the words it has actually written, it returns the exact text handed to
 * the seam — the [StanceJudgePrompts] posture
 * (`src/main/kotlin/com/aiforum/persona/StanceJudgePrompts.kt:70`), so the whole
 * engagements→question translation is unit-tested without an LLM. What comes back is judged by
 * [InterestDrift]; nothing here parses.
 *
 * ## The blinkers ARE the convergence guardrail
 *
 * [instruction] takes the judged member's own name, own character, own interests and own words —
 * **and nothing else**. There is no roster parameter, no other member's interests, and no count of
 * anything, so there is no cross-member channel for the room to converge through and nothing
 * population-shaped for a model to optimise against (D12's second test). `/admin/interests`' room map
 * exists precisely because convergence is made *visible to the owner* while remaining invisible to
 * every model — and the missing parameters are what makes that true rather than promised. A later
 * "give the judge a little room context" change has to widen this signature, which reddens the Tier-0
 * blinkers assertion and the Tier-2 byte-identical-prompt assertion instead of quietly opening the
 * channel.
 *
 * The judgment rides the one shared [com.aiforum.llm.LlmClient] seam, tagged with a synthetic persona
 * the way composition and the stance judgment are, so a spy or the router can tell a drift judgment
 * apart from a reply.
 */
object InterestDriftPrompts {
    /**
     * Synthetic identity the judgment call carries on the shared seam.
     *
     * The NAME is the load-bearing half: the acceptance spy filters purely on `persona.name`, so a
     * collision with [ComposerPrompts.COMPOSER_NAME], [StanceJudgePrompts.JUDGE_NAME] or the
     * dispatcher's `Moderator` would make existing composer, stance and routing assertions start
     * matching drift calls — a slice's worth of tests going quietly wrong rather than red.
     */
    const val JUDGE_ID = "__interest_judge__"
    const val JUDGE_NAME = "InterestJudge"

    /**
     * The stable role for the judging model. Three of its clauses are guardrails rather than style:
     *
     * - **Never a score, never digits.** Stated here so [InterestDrift]'s digit refusal means the model
     *   disobeyed a rule it was given, not that it was ambushed by an undocumented one. The prompt
     *   asks; the parse enforces; V27's CHECK backstops.
     * - **Never contradict who the member is.** The immutable core is named to the judge as fixed
     *   (D3.4), so a verdict that tries to bend it is disobedience rather than an omission on our part.
     * - **Saying nothing moved is always allowed.** Without this a model asked "has it moved?" invents
     *   movement to be useful, and a room whose members all move every week *is* the convergence
     *   failure mode this slice exists to avoid.
     *
     * The text itself contains **no digit** — "at most one", never "1" — pinned at Tier 0: a prompt
     * cannot credibly forbid numbers while modelling one. It also stays clear of the words a reward
     * economy arrives in, except to forbid them, because the owner-controls firewall scans exactly the
     * text that reaches a model.
     */
    val SYSTEM: String = buildString {
        append("You are judging whether one member of a small ambient discussion forum has moved on ")
        append("from one of the topics it is drawn to. You are given who that member is, the interests ")
        append("it keeps regardless, the interests that are open to change, and the words the member ")
        append("has actually written in the forum lately. ")
        append("Weigh what the member kept returning to in its OWN words — not what would be ")
        append("interesting, not what the forum needs, not what anyone else is talking about. ")
        append("A member may set down at most one open interest and take up at most one new one, or ")
        append("nothing at all; answering that nothing moved is ordinary and always allowed. ")
        append("Never propose anything that contradicts who the member is, and never touch an interest ")
        append("listed as kept. ")
        append("Write any new interest as a short phrase of prose in that member's own voice — the ")
        append("words the member itself would use for what it keeps thinking about. ")
        // The no-numbers guardrail, stated at the one place a number could enter what a member is into.
        append("Never mention scores, ratings, levels, tallies or rankings, and NEVER use digits — an ")
        append("interest is prose about where a person's attention went, never a measurement. ")
        append("Output only the lines you were asked for, with no preamble or quotes.")
    }

    /**
     * One thing the member said, as evidence. [room] is the thread title — where the words were said,
     * which is most of what makes a phrase legible as a topic. [body] is the member's OWN comment.
     *
     * What the member was *answering* is deliberately absent, unlike [StanceJudge.Exchange]'s
     * `towardBody`: on the top-level branch that would be `thread.body`, which for an ambient article
     * thread is fetched, untrusted summary text (D4). What the member said is the signal; what it was
     * replying to is not, and it is not worth handing a judgment an untrusted paragraph to be steered
     * by.
     */
    data class Engagement(val room: String, val body: String)

    /**
     * The per-member instruction turn: who the member is (as **fixed**), what it keeps regardless, what
     * is open to change, and the [engagements] it has produced since the pass last looked — then the
     * ask, last, so it is the final thing the judging model reads.
     *
     * Each body is collapsed to one line. A forum comment is markdown and multi-paragraph; dropped in
     * raw it would break the one-bullet-per-engagement shape and let a body starting with "- " read as
     * a further engagement. Collapsing keeps the evidence unambiguous without dropping words —
     * *truncation* is the caller's decision, made once with `Snippet.oneLine` so the same snippet text
     * is both what the model read and what the audit row cites.
     *
     * The empty-[engagements] branch mirrors [StanceProse.block]'s refusal to emit a header over zero
     * bullets. The pass never judges a member with nothing to judge (D8 skips below the engagement
     * floor for zero spend), so this is belt-and-braces — but a dangling header is an invitation to
     * invent the evidence it promised.
     */
    fun instruction(
        member: String,
        character: String,
        pinned: List<String>,
        open: List<String>,
        engagements: List<Engagement>,
    ): String = buildString {
        append("Member: $member\n")
        // D3.4: the core is named as fixed, so a verdict that reaches for it is disobedience.
        append("Who $member is, and this does not change: ${oneLine(character)}\n")
        append("Interests $member keeps regardless: ${phrases(pinned)}\n")
        append("Interests that are open to change: ${phrases(open)}\n")
        if (engagements.isNotEmpty()) {
            append("\nWhat $member has actually been saying, oldest first — the room, then $member's ")
            append("own words:\n")
            engagements.forEach { append("  - in \"${oneLine(it.room)}\": ${oneLine(it.body)}\n") }
        }
        append("\nIf what $member has been saying has moved what $member is drawn to, name ONE open ")
        append("interest to set down and ONE new interest to take up, exactly:\n")
        append("DROP: <the open interest, word for word>\n")
        append("TAKE: <a short phrase, prose>\n")
        append("Otherwise answer exactly: ${InterestDrift.NOTHING_MOVED}")
    }

    /**
     * Interests on one line, or `(none)`. The fallback is really for the pinned list — a member with no
     * pins is the common case and a bare label reads as a truncated prompt — but it covers the open
     * list too rather than assuming: a member with no open interests is skipped before any spend
     * (D8's `no-interests` skip), so if that ever reaches here something upstream is wrong and a
     * legible prompt beats a malformed one.
     */
    private fun phrases(interests: List<String>): String =
        if (interests.isEmpty()) "(none)" else interests.joinToString(", ")

    private fun oneLine(text: String): String = text.replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")
}
