package com.aiforum.persona

/**
 * Builds the meta-prompt sent to the LLM that JUDGES how one member now regards another
 * (`plan_docs/ambient-slice-4a.md` D5). Pure (Tier 0): given the pair, the stance standing between
 * them today and the exchanges that have since passed, it returns the exact text handed to the seam —
 * so the whole exchanges→question translation is unit-tested without an LLM, the same reasoning as
 * [ComposerPrompts]. What comes back is judged by [StanceJudge]; nothing here parses.
 *
 * The judgment rides the one shared [com.aiforum.llm.LlmClient] seam, tagged with a synthetic persona
 * the way composition is, so a spy or the router can tell a tone judgment apart from a reply.
 */
object StanceJudgePrompts {
    /**
     * Synthetic identity the judgment call carries on the shared seam.
     *
     * The NAME is the load-bearing half: the acceptance spy filters purely on `persona.name`, so a
     * collision with [ComposerPrompts.COMPOSER_NAME] or the dispatcher's `Moderator` would make the
     * existing composer and routing assertions start matching judge calls — a whole slice's worth of
     * tests would go quietly wrong rather than red.
     */
    const val JUDGE_ID = "__stance_judge__"
    const val JUDGE_NAME = "StanceJudge"

    /**
     * The stable role for the judging model. Two of its clauses are guardrails rather than style:
     *
     * - **Never a score.** The relation model is prose by construction (see [StanceProse]); asking for
     *   a rating here is the one prompt in the codebase that could talk a model into writing one.
     * - **Never digits.** Stated so that [StanceJudge]'s refusal means the model disobeyed a rule it
     *   was given, not that it was ambushed by an undocumented one. The prompt asks; the parse
     *   enforces.
     *
     * The wording also stays free of the words a score would come dressed in ("approval", "rating",
     * "level"), except to forbid them — evolved prose is injected straight into a persona's system
     * prompt, and the owner-controls firewall scans exactly that text for reward-economy signal.
     */
    val SYSTEM: String = buildString {
        append("You are judging how one member of a small ambient discussion forum now regards ")
        append("another member. You are given the two members, the standing view the first currently ")
        append("holds of the second, and the exchanges that have passed between them since. ")
        append("Weigh the TONE of what they wrote to each other — warmth, friction, curiosity, ")
        append("impatience, respect — not who happened to be right. ")
        append("Answer with ONE short sentence of prose in that member's own voice, describing the ")
        append("attitude they now hold toward the other: what they have come to expect of them, how ")
        append("they read them. If the exchanges do not move the attitude, say the standing view ")
        append("again unchanged. ")
        // The no-numbers guardrail, stated where the only model-authored field in the relation model is
        // written. StanceJudge refuses a digit-bearing answer outright; this is the half that asks.
        append("Never mention scores, ratings, levels, tallies or approval, and NEVER use digits — a ")
        append("standing view is prose about a person, never a measurement. ")
        append("Output only the sentence itself, with no preamble or quotes.")
    }

    /**
     * The per-pair instruction turn: who is judging whom, the stance standing between them today, and
     * the [exchanges] that have happened since — each rendered as what [toward] wrote followed by what
     * [holder] wrote back, which is the shape the judgment is actually about.
     *
     * Each body is collapsed to one line. A forum comment is markdown and multi-paragraph; dropped in
     * raw it would break the indented two-line shape and let a body that starts with "- " read as a
     * further exchange. Collapsing keeps the evidence unambiguous without truncating it — the caller
     * decides how much text is worth spending, this renderer never drops words.
     *
     * The empty-[exchanges] branch mirrors [StanceProse.block]'s refusal to emit a header over zero
     * bullets. The evolution pass never judges a pair with nothing to judge (D3 qualifies pairs on
     * having produced exchanges in the window), so this is belt-and-braces — but a dangling header
     * would be an invitation for the model to invent the evidence it was promised.
     */
    fun instruction(
        holder: String,
        toward: String,
        currentStance: String,
        exchanges: List<StanceJudge.Exchange>,
    ): String = buildString {
        append("Member: $holder\n")
        append("The other member: $toward\n")
        append("$holder's current standing view of $toward: $currentStance\n")
        if (exchanges.isNotEmpty()) {
            append("\nWhat has passed between them since, oldest first — what $toward wrote, then what ")
            append("$holder wrote back:\n")
            exchanges.forEach { exchange ->
                append("  - $toward: ${oneLine(exchange.towardBody)}\n")
                append("    $holder: ${oneLine(exchange.body)}\n")
            }
        }
        // The ask stays last, so it is the final thing the judging model reads.
        append("\nWrite how $holder now regards $toward — one sentence, prose only.")
    }

    private fun oneLine(body: String): String = body.replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")
}
