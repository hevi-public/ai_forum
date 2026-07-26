package com.aiforum.persona

/**
 * The room map: which topics the room has converged on, and which ones only one member holds
 * (`plan_docs/ambient-slice-4b.md` D12). Pure (Tier 0), computed on the `/admin/interests` **read**
 * path and rendered as text.
 *
 * ## How this is a readout and not the reward economy coming back
 *
 * The standing guardrail (`V24__persona_stance.sql:6-9`, restated in V25 and V27) forbids a magnitude
 * attached to a member, persisted where it can be compared, or reaching a model. This passes on three
 * counts, each of which is a property of the code rather than a promise:
 *
 * 1. **Its subject is a phrase and the members who hold it — never a member.** *"boring technology
 *    choices — held by Sol, Paul and Mira"* attaches nothing to Sol. Hence [SharedTopic.holderNames]:
 *    names, not a tally. **Rejected: rendering "three of seven"**, or any per-member count — a number is
 *    the shape an owner starts thresholding on, and a threshold an owner acts on is the population
 *    metric this slice keeps away from models.
 * 2. **No model can see it.** [InterestDriftPrompts.instruction] is built from one member's own
 *    material and has no parameter this could enter through.
 * 3. **It fires nothing.** A detector that fires is the scratched perturbation thermostat
 *    (`ai-forum-requirements.md:245`). This is text on a page an owner chooses to open.
 *
 * [rosterSize] is an **input**, not a field: the divisor is a property of the room at the moment of the
 * read, and it is deliberately absent from every returned type so that nothing here can be stored,
 * compared across weeks, or keyed to a member. That absence is asserted structurally at Tier 0.
 *
 * **Honest limitation:** this sees *lexical* convergence only. A room could converge in voice while
 * holding disjoint phrases and the map would read all-clear. There is no automatic backstop, by choice.
 */
object TopicSpread {

    /** A phrase more than half the room holds, and who holds it — by name, in a stable order. */
    data class SharedTopic(val phrase: String, val holderNames: List<String>)

    /** A phrase exactly one member holds: the counterweight reading, and what has not converged. */
    data class SoleTopic(val phrase: String, val holderName: String)

    /** The whole readout, including the one line of prose the page leads with. */
    data class Spread(val shared: List<SharedTopic>, val sole: List<SoleTopic>, val sentence: String)

    /**
     * Build the map from `PersonaInterestRepository.sharedInterests()`'s phrase → holder names, against
     * the roster the room actually has.
     *
     * **Shared is strictly more than half.** A phrase held by exactly half is *not* shared, and that is
     * the interesting boundary rather than an off-by-one: half the room on one side of a topic is a room
     * having an argument, which is the healthy state this readout exists to distinguish from a room that
     * has agreed. Calling a two-two split "most of the room" would put a convergence warning in front of
     * the owner every time two members happened to overlap.
     *
     * Ordering is imposed here, on both phrases and holder names, rather than trusted from the map: a
     * `Map`'s iteration order is not part of its contract, and an admin page whose rows shuffle between
     * two reloads reads as breakage. [holdersByPhrase] entries with no holders are dropped, and holders
     * are de-duplicated — the threshold is about *members*, so a name appearing twice must not push a
     * phrase over it.
     *
     * A roster of nobody (and a room where nobody holds anything) yields empty lists and a sentence
     * saying so, rather than dividing by a roster that isn't there.
     */
    fun of(holdersByPhrase: Map<String, List<String>>, rosterSize: Int): Spread {
        val held = holdersByPhrase
            .map { (phrase, holders) -> phrase to holders.distinct().sortedBy { it.lowercase() } }
            .filter { (_, holders) -> holders.isNotEmpty() }
            .sortedBy { (phrase, _) -> phrase.lowercase() }
        if (rosterSize <= 0 || held.isEmpty()) {
            return Spread(emptyList(), emptyList(), NOTHING_SETTLED)
        }
        // `rosterSize > 1` is not pedantry: in a one-member room a single phrase satisfies BOTH
        // `holders.size * 2 > rosterSize` and `holders.size == 1`, so the same phrase would render as
        // shared and as sole at once — "most of the room is into X" and "one member holds a topic
        // nobody else does", about the same phrase and the same person. A fresh install has exactly
        // that roster, so the first thing an owner ever sees on this page would be the nonsense one.
        // Convergence is a claim about a POPULATION, and one member is not one.
        val shared = if (rosterSize > 1) {
            held.filter { (_, holders) -> holders.size * 2 > rosterSize }
                .map { (phrase, holders) -> SharedTopic(phrase, holders) }
        } else {
            emptyList()
        }
        val sole = held
            .filter { (_, holders) -> holders.size == 1 }
            .map { (phrase, holders) -> SoleTopic(phrase, holders.single()) }
        return Spread(shared, sole, sentence(shared, sole))
    }

    /**
     * One line of plain English, both halves always stated — the reassuring reading ("no topic has taken
     * the room over") is the whole value of the map on the usual week, and a page that says nothing when
     * nothing is wrong trains the owner to stop reading it.
     *
     * Counts are **spelled out**. The subject of a count here is the population, which D12 sanctions in
     * these words — *"Three members hold a topic nobody else does"* — but a digit on this page is one
     * copy-paste away from a digit in a prompt, and every other guardrail in this slice is written as
     * "no digit reaches a model". Spelling them keeps the page and the prompts under one rule.
     */
    private fun sentence(shared: List<SharedTopic>, sole: List<SoleTopic>): String {
        val converged =
            if (shared.isEmpty()) "No topic has taken the room over."
            else "Most of the room is now into ${and(shared.map { it.phrase })}."
        // Distinct HOLDERS, not sole topics: one member holding two phrases nobody else holds is one
        // member going its own way, and saying "two members" of one member misreads the room.
        val loners = sole.mapTo(mutableSetOf()) { it.holderName }.size
        val apart = when (loners) {
            0 -> "Nobody is holding a topic on their own."
            1 -> "One member holds a topic nobody else does."
            else -> "${countWord(loners)} members hold a topic nobody else does."
        }
        return "$converged $apart"
    }

    /** Plain-English list joining, so the sentence reads as a sentence for one phrase or for several. */
    private fun and(items: List<String>): String =
        if (items.size == 1) items.single()
        else items.dropLast(1).joinToString(", ") + " and " + items.last()

    /**
     * A count as a word, capitalised for sentence position. Beyond the list "Many" is honest: past a
     * dozen members going their own way, the exact figure is not what the owner is reading for.
     */
    private fun countWord(n: Int): String =
        COUNT_WORDS.getOrElse(n) { "Many" }

    private val COUNT_WORDS = listOf(
        "No", "One", "Two", "Three", "Four", "Five", "Six",
        "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve",
    )

    private const val NOTHING_SETTLED = "Nothing has settled yet — no member holds an interest."
}
