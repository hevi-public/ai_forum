package com.aiforum.persona

/**
 * The persona personality dials — a FIXED schema (not free-form) so every persona is comparable and
 * the "Anyone" router can later reason over the same axes. Each dial is a 0–10 knob the owner sets;
 * the composer turns the numbers into prose at authoring time, because a raw "agreeableness: 8" in a
 * system prompt is something the generation model just ignores.
 *
 * Each axis is chosen to pass the blind test: a reader should be able to guess a high value from a
 * reply alone (length for verbosity, push-back for agreeableness, citations for rigor, tone for warmth).
 */
object Dials {
    const val MIN = 0
    const val MAX = 10
    const val DEFAULT = 5

    /** Canonical order — drives both normalization output order and the form layout. */
    val KEYS = listOf("agreeableness", "verbosity", "rigor", "warmth", "talkativeness")

    private val LABELS = mapOf(
        "agreeableness" to "Agreeableness (contrarian ↔ agreeable)",
        "verbosity" to "Verbosity (terse ↔ long-winded)",
        "rigor" to "Rigor (loose & intuitive ↔ precise & evidence-led)",
        "warmth" to "Warmth (blunt ↔ warm)",
        // S2 (plan_docs/ambient-slice-2.md §3, spec §6.4): P(comment) — 0 = lurker, 10 = every relevant
        // opportunity. Read by AmbientGate to decide WHETHER a persona drops an ambient comment; the
        // read-path defaults a missing key to DEFAULT (dials JSON is not re-normalized on read).
        "talkativeness" to "Talkativeness (lurker ↔ chatty)",
    )

    fun describe(key: String): String = LABELS[key] ?: key

    /**
     * Coerce arbitrary input into the canonical schema: every [KEYS] entry present exactly once, in
     * order, clamped to [MIN]..[MAX]; missing keys fall back to [DEFAULT]; unknown keys are dropped.
     * This is the single gate so storage, the composer, and the router never see an off-schema map.
     */
    fun normalize(raw: Map<String, Int>): Map<String, Int> =
        KEYS.associateWith { (raw[it] ?: DEFAULT).coerceIn(MIN, MAX) }
}
