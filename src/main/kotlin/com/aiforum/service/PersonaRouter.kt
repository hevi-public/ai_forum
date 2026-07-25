package com.aiforum.service

import com.aiforum.domain.Comment
import com.aiforum.domain.context.ContextAssembler
import com.aiforum.dto.ScopeMode
import com.aiforum.persona.Dials
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.repo.PersonaRepository.Persona
import com.aiforum.repo.RelationStanceRepository
import com.aiforum.repo.Stance
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The "Anyone" dispatcher. When the owner doesn't pin a persona (the composer's default selection), this
 * asks the model which roster member(s) are best suited to reply to the topic, so the room curates
 * itself instead of the owner having to know who's who.
 *
 * It routes through the SAME single LlmClient seam everything else uses (see the bdd-tiered-testing
 * skill) — no second IO boundary. The model is told to name the participants who should weigh in; we
 * then scan its reply for roster names on word boundaries, so the decision survives the model answering
 * in prose ("I'd let Sol and Paul take this") rather than a strict format. Anything unparseable, an
 * empty pick, or a generation failure falls back to the whole room — "Anyone" must never silently pick
 * no one.
 *
 * Beyond skills and temperament, the brief also carries the qualitative relations pointing at whoever is
 * already talking (see [relationsBlock]) — who bristles at whom is routing signal in a room that argues.
 * Those stances are free text by construction and are never scored or ranked here; they colour the
 * model's judgement, they don't weight it.
 *
 * ## Known failure mode: name-matching honours the model only when it *names* members
 *
 * The routing decision is the model's, but we recover it by string-matching roster names in free text.
 * So the model's judgement is honoured ONLY insofar as it spells a roster member's name. If it answers
 * "the backend folks should take this" or "ask the Kotlin person" without writing "Sol", nothing matches
 * and we fall back to the WHOLE room — silently widening a decision the model may have meant to narrow.
 * In effect a thin slice of "who decides" is really "did the model phrase it in a way we can parse." The
 * system prompt asks for names-only and the tests cover the prose/unparseable paths, but the coupling is
 * real and the fallback hides it (you can't tell a deliberate "everyone" from a parse miss).
 *
 * Ideas to harden this, cheapest first — none implemented yet, ordered by effort/robustness trade-off:
 *  1. **Make fallbacks observable. (RECOMMENDED FIRST STEP.)** Meter each pick's outcome — clean match
 *     vs. parse-miss-widened-to-all vs. generation failure — so the parse-miss RATE is measurable instead
 *     of invisible. We don't yet know how often this bites; this tells us, and gives the data to judge
 *     whether 2–5 are worth their cost. Surfaced on a new Admin → Statistics page (see
 *     plan_docs/persona-routing-observability.md).
 *  2. **Numbered menu.** Present the roster as a numbered list and ask the model to reply with the
 *     number(s); digits are far less ambiguous than names and don't collide with ordinary prose.
 *  3. **Use the structured traits, not just names.** DONE: the roster lines the dispatcher sees now carry
 *     each persona's **abilities** tags (V10) and an adjective summary of their off-centre **dials** (see
 *     [rosterLine]), so the model's pick is topic- AND temperament-aware — a cleaner signal than free-text
 *     descriptor matching. And when a fan-out is capped, [diversify] selects a set that SPANS the
 *     agreeableness axis (a contrarian + an agreeable voice) rather than three alike. This enriches the
 *     model's single existing call rather than adding a second seam. See plan_docs/persona-traits-routing.md.
 *  4. **Reprompt once on a miss** before widening — "Reply with ONLY the exact names" — trading one extra
 *     call for a tighter answer; only then fall back.
 *  5. **Constrained/tool output (the principled fix).** Have the model select from an enum of valid
 *     persona ids via tool-use/structured output, so an invalid pick is impossible by construction. This
 *     needs the LlmClient seam to grow a structured-call shape (today it returns free text only), so it's
 *     the biggest change — but it removes the parse step, and with it this whole failure mode.
 *  6. **Embedding-based routing (different trade-off).** Rank personas by similarity between the question
 *     and their descriptors with no LLM call at all — deterministic and parse-free, but loses the model's
 *     reasoning about the topic.
 */
@Component
class PersonaRouter(
    private val llm: LlmClient,
    // Where each routing outcome is recorded (plan_docs/persona-routing-observability.md). Defaulted to
    // the no-op so the Tier-2 `PersonaRouter(llm)` constructions stay metrics-free; Spring injects the
    // real persisting adapter (RoutingEventRepository). A pure addition — it never changes the pick.
    private val metrics: RoutingMetrics = NoOpRoutingMetrics,
    // The qualitative relation graph (plan_docs/ambient-slice-3.md), read to tell the model how the
    // roster feels about whoever is already talking. Nullable-defaulted rather than a no-op object like
    // [metrics] above: a repository has no meaningful null implementation (an empty-graph stub would be a
    // second thing to keep in sync with the real schema), and every Tier-2 construction here is
    // positional with at most two arguments, so a trailing nullable keeps them compiling untouched.
    private val stances: RelationStanceRepository? = null,
) {

    /**
     * Pick the persona(s) that should reply to [context] from [roster]; never returns empty for a
     * non-empty roster. [routingScope] is recorded alongside the outcome (whole-topic vs this-branch) for
     * later drill-down; it does not affect the pick (the caller already scoped [context]).
     */
    fun pick(
        roster: List<Persona>,
        context: List<Comment>,
        routingScope: ScopeMode = ScopeMode.WHOLE_THREAD,
    ): List<Persona> {
        // A lone persona is the only possible answer — don't spend an LLM call to "choose" it.
        if (roster.size <= 1) {
            metrics.record(RoutingOutcome.SINGLE_PERSONA, roster.size, roster.size, routingScope, null)
            return roster
        }
        // Who is already in the discussion, straight from the context the caller already scoped for us —
        // no extra query, and it follows the "looking at" scope for free. Read the graph ONCE per pick:
        // one query feeds the whole prompt. A null repository yields an empty list, [relationsBlock]
        // returns null, and the prompt is byte-for-byte the pre-relations one.
        val presentAuthorIds = context.map { it.authorId }.toSet()
        val relations = stances?.findAll().orEmpty()
        val reply = runCatching {
            llm.generate(
                LlmRequest(
                    ContextAssembler.assemble(systemPrompt(roster, relations, presentAuthorIds), context),
                    ROUTER,
                    TIMEOUT,
                ),
                CancellationToken(),
            ).text
        }.getOrNull()
        if (reply == null) {
            // The seam errored/timed out — fall back to the whole room, but record it as seam health, not
            // a parse miss, so a flaky model doesn't inflate the parse-miss rate.
            metrics.record(RoutingOutcome.FAILED_GENERATION, roster.size, roster.size, routingScope, null)
            return roster
        }
        // Every name the model matched, ordered by where it appears (most relevant first)...
        val matched = parseChosen(reply, roster, max = roster.size)
        if (matched.isEmpty()) {
            // The model answered but named no one we recognise: the failure mode firing. Keep the raw reply
            // so the stats page can show *why* matching missed.
            metrics.record(RoutingOutcome.WIDENED_NO_MATCH, roster.size, roster.size, routingScope, reply)
            return roster
        }
        // ...then cap the fan-out with a diversity-aware selection so a roomful spans the agreeableness
        // axis (a contrarian AND an agreeable builder) instead of three voices that'd all say the same.
        val chosen = diversify(matched, MAX_PICKS)
        metrics.record(RoutingOutcome.MATCHED, roster.size, chosen.size, routingScope, null)
        return chosen
    }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(60)
        // The dispatcher is not a forum member — it has no descriptor and posts nothing. Blank model =>
        // the default-model fallback (routing is cheap, no need to pin a heavyweight model).
        private val ROUTER = PersonaRef("dispatcher", "Moderator")

        /** A safety cap so a model that over-eagerly lists everyone can't fan out the whole roster at once. */
        const val MAX_PICKS = 3

        /**
         * Extract the chosen personas from the dispatcher's free-text [reply], ordered by where each name
         * first appears (the prompt asks for most-relevant first) and capped at [MAX_PICKS]. Word-boundary
         * matching so "Sol" matches the name but not "solve"/"solution". Pure — Tier-0 testable.
         */
        fun parseChosen(reply: String, roster: List<Persona>, max: Int = MAX_PICKS): List<Persona> =
            roster.mapNotNull { p ->
                Regex("\\b${Regex.escape(p.name)}\\b", RegexOption.IGNORE_CASE).find(reply)?.let { p to it.range.first }
            }.sortedBy { it.second }.take(max).map { it.first }

        /**
         * Narrow [candidates] (already ordered most-relevant-first) to at most [max], chosen to SPAN the
         * agreeableness axis so a roomful reads like a room with friction — a contrarian and an agreeable
         * builder — not a chorus of the same temperament. Pure — Tier-0 testable.
         *
         * The model's top pick is always kept (relevance leads); each further slot greedily takes the
         * candidate whose agreeableness is farthest from the mean of those already chosen. When dials are
         * absent or identical, every distance is 0, ties resolve to the model's order, and this degrades to
         * the old "take the first [max]" — so trait-less rosters are unaffected.
         */
        fun diversify(candidates: List<Persona>, max: Int = MAX_PICKS): List<Persona> {
            if (candidates.size <= max) return candidates
            val chosen = mutableListOf(candidates.first())
            val rest = candidates.drop(1).toMutableList()
            while (chosen.size < max && rest.isNotEmpty()) {
                val mean = chosen.map { agreeableness(it) }.average()
                // maxByOrNull keeps the FIRST maximal element, so ties preserve the model's relevance order.
                val next = rest.maxByOrNull { kotlin.math.abs(agreeableness(it) - mean) }!!
                chosen += next
                rest -= next
            }
            return chosen
        }

        private fun agreeableness(p: Persona): Int = p.dials["agreeableness"] ?: Dials.DEFAULT

        /**
         * One roster line for the dispatcher prompt: name + descriptor, plus the STRUCTURED traits the
         * router used to throw away — abilities (topic match) and the off-centre dials rendered as a few
         * adjectives (temperament). Giving the model these makes its pick topic- and texture-aware, which
         * is the whole point of lifting personality into comparable fields. Pure — Tier-0 testable.
         */
        fun rosterLine(p: Persona): String {
            val clauses = buildList {
                if (p.descriptor.isNotBlank()) add(p.descriptor)
                if (p.abilities.isNotEmpty()) add("skills: ${p.abilities.joinToString(", ")}")
                traitWords(p.dials).takeIf { it.isNotEmpty() }?.let { add("style: ${it.joinToString(", ")}") }
            }
            return "- ${p.name}" + if (clauses.isEmpty()) "" else ": ${clauses.joinToString("; ")}"
        }

        /**
         * Turn the fixed dial schema into a few adjectives, naming ONLY axes set notably off-centre (a
         * value at the default carries no signal, so it's omitted to keep the line terse). Mirrors the dial
         * labels' poles (see [Dials]).
         */
        fun traitWords(dials: Map<String, Int>): List<String> {
            val high = Dials.DEFAULT + 2   // ≥7 reads as the high pole
            val low = Dials.DEFAULT - 2    // ≤3 reads as the low pole
            fun word(key: String, lowWord: String, highWord: String): String? = dials[key]?.let {
                when {
                    it >= high -> highWord
                    it <= low -> lowWord
                    else -> null
                }
            }
            return listOfNotNull(
                word("agreeableness", "contrarian", "agreeable"),
                word("verbosity", "terse", "expansive"),
                word("rigor", "intuitive", "evidence-led"),
                word("warmth", "blunt", "warm"),
            )
        }

        /** The literal header the relations section opens with; the acceptance suite pins this string. */
        private const val RELATIONS_HEADER = "Relations between participants:"

        /**
         * The directed stances worth showing the dispatcher, as `- <From> -> <To>: <text>` lines under
         * [RELATIONS_HEADER], or null when nothing qualifies (so the caller appends nothing rather than a
         * header dangling over zero bullets). Pure — Tier-0 testable.
         *
         * **Why the scoping is this narrow.** The dispatcher's one job is deciding who should weigh in
         * NEXT, so the only relation that can inform it is one pointing AT someone already in the
         * discussion ("Paul needles Sol" matters exactly when Sol has spoken). An edge aimed at a silent
         * persona says nothing about the reply we're about to route. Unfiltered, the seeded graph is 42
         * edges — a wall of prose that would swamp the real routing signal (skills and topic) on every
         * single call, and grows quadratically with the roster. Hence: keep an edge only when BOTH
         * endpoints are on the roster (a stance naming someone who isn't a participant is unactionable)
         * AND its target is in [presentAuthorIds]. Blank text is dropped as an empty edge.
         *
         * Ordering is the repository's (from, to) order, preserved rather than re-derived: the prompt text
         * must be byte-stable across runs, and re-sorting here would be a second ordering rule to keep in
         * step with [RelationStanceRepository.findAll].
         *
         * Note the display-name convention: stance rows and [presentAuthorIds] are persona **ids** (that's
         * what the FKs and `Comment.authorId` carry), while the roster the model reads is written in
         * **names** — so both endpoints are resolved through the roster before rendering, and the lines
         * line up with the `Roster:` block above them.
         */
        fun relationsBlock(
            roster: List<Persona>,
            stances: List<Stance>,
            presentAuthorIds: Set<String>,
        ): String? {
            val names = roster.associate { it.id to it.name }
            val lines = stances.mapNotNull { s ->
                if (s.toPersona !in presentAuthorIds || s.stance.isBlank()) return@mapNotNull null
                val from = names[s.fromPersona] ?: return@mapNotNull null
                val to = names[s.toPersona] ?: return@mapNotNull null
                "- $from -> $to: ${s.stance}"
            }
            if (lines.isEmpty()) return null
            return buildString {
                append(RELATIONS_HEADER).append("\n")
                lines.forEach { append(it).append("\n") }
            }
        }

        /**
         * The dispatcher's brief. Framed for the ambient forum rather than a Q&A helpdesk: nobody "asks a
         * question" here — members post articles they found interesting and argue about them with each
         * other and with the owner — so asking the model who should *answer the question* mis-describes
         * the job and biases it toward whoever looks most like an expert witness.
         *
         * The cap sentence and the output contract are deliberately verbatim: [parseChosen] and
         * [MAX_PICKS] are built around them, and the acceptance suite pins the roster lines.
         */
        private fun systemPrompt(
            roster: List<Persona>,
            stances: List<Stance>,
            presentAuthorIds: Set<String>,
        ): String = buildString {
            append("You are the forum's dispatcher. You do NOT reply yourself. This is an ambient ")
            append("discussion forum where members post articles they find interesting and discuss them ")
            append("with each other and with the owner. Given the discussion below, decide which ")
            append("participant(s) from the roster are best suited to weigh in next — match their skills ")
            append("to the topic, let their relations to those already in the discussion inform the pick, ")
            append("and when more than one should weigh in, prefer a mix of temperaments (a contrarian ")
            append("and an agreeable voice) over three alike. ")
            append("Pick the most relevant — usually one or two, at most three. ")
            append("Respond with ONLY their names, comma-separated, most relevant first. Nothing else.\n\n")
            append("Roster:\n")
            roster.forEach { append(rosterLine(it)).append("\n") }
            relationsBlock(roster, stances, presentAuthorIds)?.let { append("\n").append(it) }
        }
    }
}
