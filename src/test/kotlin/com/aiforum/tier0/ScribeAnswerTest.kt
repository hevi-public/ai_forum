package com.aiforum.tier0

import com.aiforum.persona.MemoryText
import com.aiforum.persona.ScribeAnswer
import com.aiforum.persona.ScribeAnswer.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: [ScribeAnswer], the pure parse of the Memory Scribe's answer contract
 * (plan_docs/persona-memory.md §2.5). The two postures under guard:
 *
 * - **Rating shapes are refused; digits in prose are not (D8).** The Stays-Cut line is a number
 *   that is model-written AND machine-read as a magnitude — "we argued about WAL mode in V27" is
 *   autobiography and passes; `importance:`/`salience:`/`score:` labels and the `…/10` form are a
 *   score wearing prose and are refused, wherever in the answer they sit.
 * - **The body refusals are [MemoryText]'s own, byte-identical (§2.15).** The parse validates
 *   through the one shared function, so the reason strings asserted here are asserted EQUAL to
 *   `MemoryText.validate`'s output for the same body — the behavioural shadow of "one function, not
 *   shared constants": a parse that grew its own validator would have to counterfeit the other
 *   object's exact wording to stay green.
 *
 * A [Verdict.Rejected] never stamps the member's window (§2.5's table — the service's half, pinned
 * at Tier 2); this suite pins only which answers land in which verdict.
 */
@Tag("tier0")
class ScribeAnswerTest {

    @Test
    fun `NOTHING is the settled answer, case-insensitively`() {
        assertEquals(Verdict.NothingToRemember, ScribeAnswer.parse("NOTHING"))
        assertEquals(Verdict.NothingToRemember, ScribeAnswer.parse("  nothing  \n"))
    }

    @Test
    fun `a well-formed REMEMBER yields the body verbatim with no parent selector`() {
        val verdict = ScribeAnswer.parse("REMEMBER: Learned that preemption arguments never really end")
        assertEquals(
            Verdict.Remember(body = "Learned that preemption arguments never really end", extends = null),
            verdict,
        )
        // The label is our packaging — its case is not the model's words.
        assertEquals(
            Verdict.Remember(body = "Case of the label is packaging", extends = null),
            ScribeAnswer.parse("remember: Case of the label is packaging"),
        )
    }

    @Test
    fun `an EXTENDS line carries the letter, normalised to the offered list's upper case`() {
        val verdict = ScribeAnswer.parse("REMEMBER: Suspects the log format hides more surprises\nEXTENDS: B")
        assertEquals(Verdict.Remember("Suspects the log format hides more surprises", extends = "B"), verdict)
        assertEquals(
            Verdict.Remember("Lower case selects the same parent", extends = "B"),
            ScribeAnswer.parse("REMEMBER: Lower case selects the same parent\nEXTENDS: b"),
        )
    }

    @Test
    fun `an out-of-set or junk selector is visible in the verdict, not judged here`() {
        // The parse does not know the offered set: the SERVICE resolves the selector and decides the
        // degrade (top-level + event=memory.parent.unknown, §2.4) — a broken decoration never costs
        // a paid, well-formed record, so this must NOT come back Rejected.
        assertEquals(
            Verdict.Remember("Suspects the letter protocol has sharp edges", extends = "Q"),
            ScribeAnswer.parse("REMEMBER: Suspects the letter protocol has sharp edges\nEXTENDS: Q"),
        )
        // A non-letter token rides through raw so the unknown-selector log can show what was written.
        assertEquals(
            Verdict.Remember("Junk selectors ride through for the log", extends = "17"),
            ScribeAnswer.parse("REMEMBER: Junk selectors ride through for the log\nEXTENDS: 17"),
        )
    }

    @Test
    fun `rating-shaped lines are refused wherever they sit`() {
        val reason = "the answer carried a rating; a memory is prose, never a score"
        // Scenario 12's exact fixture: a rating line stapled under a well-formed record.
        assertEquals(
            Verdict.Rejected(reason),
            ScribeAnswer.parse("REMEMBER: Keeps a mental list of storage tricks\nimportance: high, 8/10"),
        )
        assertEquals(Verdict.Rejected(reason), ScribeAnswer.parse("salience: high"))
        // A rating label hiding behind the record label is still a rating.
        assertEquals(Verdict.Rejected(reason), ScribeAnswer.parse("REMEMBER: score: 9"))
        // The …/10 form inside otherwise-plausible prose.
        assertEquals(Verdict.Rejected(reason), ScribeAnswer.parse("REMEMBER: An 8/10 kind of insight"))
    }

    @Test
    fun `digits in prose are accepted - the rule is rating shapes, not digits`() {
        // D8 pinned: a digit ban here would re-buy digit-saturated forum judgments weekly (the
        // V26/PR#6 cost shape). Version numbers are this forum's own subject matter.
        assertEquals(
            Verdict.Remember("We argued about WAL mode in V27", extends = null),
            ScribeAnswer.parse("REMEMBER: We argued about WAL mode in V27"),
        )
    }

    @Test
    fun `the body bound sits at exactly 300 code points, measured as SQLite measures`() {
        val exactly300 = "e".repeat(300)
        assertEquals(
            Verdict.Remember(exactly300, extends = null),
            ScribeAnswer.parse("REMEMBER: $exactly300"),
        )
        val over = "e".repeat(301)
        val verdict = ScribeAnswer.parse("REMEMBER: $over")
        // Byte-identical to MemoryText's own reason: the parse routes through the ONE validator, so
        // this assertion reddens if it ever grows a validator of its own (§2.15's structural claim,
        // given a behavioural witness).
        assertEquals(Verdict.Rejected(MemoryText.validate(over)!!), verdict)
    }

    @Test
    fun `an empty REMEMBER body is refused with MemoryText's blank reason`() {
        assertEquals(
            Verdict.Rejected(MemoryText.validate("")!!),
            ScribeAnswer.parse("REMEMBER:"),
        )
    }

    @Test
    fun `a body that is not a fixed point of clean is refused, never re-cleaned`() {
        // I5 at the parse door: silently collapsing the run would hand the write path a string the
        // duplicate check never compared. Refusal costs a re-judgment next run; a wrong row is
        // forever.
        val verdict = ScribeAnswer.parse("REMEMBER: spaced  out  memory")
        assertEquals(Verdict.Rejected(MemoryText.validate("spaced  out  memory")!!), verdict)
        assertInstanceOf(Verdict.Rejected::class.java, verdict)
    }

    @Test
    fun `answers with no usable shape are refused`() {
        assertEquals(Verdict.Rejected("the model answered with nothing usable"), ScribeAnswer.parse(""))
        assertEquals(Verdict.Rejected("the model answered with nothing usable"), ScribeAnswer.parse("  \n \n"))
        val shapeReason = "the answer was not NOTHING or a REMEMBER record"
        assertEquals(Verdict.Rejected(shapeReason), ScribeAnswer.parse("I remember arguing about WAL mode"))
        assertEquals(
            Verdict.Rejected(shapeReason),
            ScribeAnswer.parse("REMEMBER: one thing\nREMEMBER: and another"),
            "one record per run - a second REMEMBER line is not the shape",
        )
        assertEquals(
            Verdict.Rejected(shapeReason),
            ScribeAnswer.parse("REMEMBER: a record\nEXTENDS: A\nand a trailing essay"),
        )
    }

    @Test
    fun `the sentinel constant is the word the prompt asks for`() {
        // Shared constant, same reason InterestDrift.NOTHING_MOVED is shared with its prompts
        // object: the word the model is told to write and the word this parse accepts must not be
        // able to drift apart in separate edits (the prompts object lands with the scribe service).
        assertTrue(ScribeAnswer.NOTHING == "NOTHING")
    }
}
