package com.aiforum.tier0

import com.aiforum.persona.ComposerPrompts
import com.aiforum.persona.StanceJudge
import com.aiforum.persona.StanceJudge.Exchange
import com.aiforum.persona.StanceJudge.Verdict
import com.aiforum.persona.StanceJudgePrompts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure tone judgment (see the bdd-tiered-testing skill). No Spring, no LLM — just
 * [StanceJudgePrompts] building the question and [StanceJudge] deciding whether the answer may become
 * a stance, the same posture as [StanceProseTest].
 *
 * The digit cases are the ones worth defending: they are the executable form of the relation model's
 * no-numbers guardrail (`plan_docs/ambient-slice-4a.md` D6). The judge's answer is the only field in
 * the relation graph a model writes, so if a score can get past these tests it can get into the
 * table.
 */
@Tag("tier0")
class StanceJudgeTest {

    private val current = "kindred pessimist, quietly enjoys catching him out"

    // --- StanceJudge.parse: what may become a stance -------------------------------------------

    @Test
    fun `parse accepts a plain sentence as a changed stance`() {
        assertEquals(
            Verdict.Changed("has started treating his posts as claims to be checked"),
            StanceJudge.parse("has started treating his posts as claims to be checked", current),
        )
    }

    @Test
    fun `parse rejects a judgment carrying a rating so a score can never reach the table`() {
        assertRejected(StanceJudge.parse("trust 4/5, down from where it was", current))
    }

    @Test
    fun `parse rejects a signed tally, the reward economy's other favourite shape`() {
        assertRejected(StanceJudge.parse("+2 respect since the benchmark thread", current))
    }

    @Test
    fun `parse rejects a digit anywhere in the sentence, not just at the front`() {
        assertRejected(StanceJudge.parse("has pushed back 3 times this week and is not sorry", current))
    }

    @Test
    fun `parse accepts the same claim written as prose - the rule is digits, not counting`() {
        assertEquals(
            Verdict.Changed("has pushed back twice this week and is not sorry"),
            StanceJudge.parse("has pushed back twice this week and is not sorry", current),
        )
    }

    @Test
    fun `parse rejects a blank answer`() {
        assertRejected(StanceJudge.parse("   \n\t  ", current))
    }

    @Test
    fun `parse rejects an answer that is nothing but quotes`() {
        assertRejected(StanceJudge.parse("\"\"", current))
    }

    @Test
    fun `parse accepts an answer exactly at the length ceiling`() {
        val atLimit = "a".repeat(StanceJudge.MAX_STANCE_CHARS)
        assertEquals(Verdict.Changed(atLimit), StanceJudge.parse(atLimit, current))
    }

    @Test
    fun `parse rejects an answer one character past the ceiling`() {
        assertRejected(StanceJudge.parse("a".repeat(StanceJudge.MAX_STANCE_CHARS + 1), current))
    }

    @Test
    fun `every rejection carries a reason, because the owner reads it on the audit page`() {
        listOf("", "trust 4/5", "a".repeat(StanceJudge.MAX_STANCE_CHARS + 1)).forEach { raw ->
            val verdict = StanceJudge.parse(raw, current)
            assertTrue(verdict is Verdict.Rejected, "expected a rejection for \"$raw\"")
            assertTrue((verdict as Verdict.Rejected).reason.isNotBlank(), "a rejection with no reason is unreadable")
        }
    }

    @Test
    fun `parse reports an unchanged stance without regard to case or surrounding whitespace`() {
        val shouted = "  Kindred Pessimist, Quietly Enjoys Catching Him Out  "
        assertEquals(Verdict.Unchanged, StanceJudge.parse(shouted, current))
    }

    @Test
    fun `parse reports unchanged even when the model quoted the stance back`() {
        assertEquals(Verdict.Unchanged, StanceJudge.parse("\"$current\"", current))
    }

    // The STORED side is the untidy one in the three below, which is the case that actually happens: a
    // seed or an owner's textarea can hold a double space, a wrapped line or leftover quotes, while the
    // model — told to restate the standing view when nothing moved — answers in clean prose. Comparing a
    // cleaned answer against a raw stored stance calls that a change, and a change is not free: an audit
    // row saying the stance became itself, provenance restamped to evolved, and a recompose of the
    // holder's prompt, all for text nobody altered.

    @Test
    fun `parse reports unchanged when the stored stance carries a double space`() {
        val stored = "kindred pessimist,  quietly enjoys catching him out"
        assertEquals(Verdict.Unchanged, StanceJudge.parse(current, stored))
    }

    @Test
    fun `parse reports unchanged when the stored stance was wrapped across lines`() {
        val stored = "kindred pessimist,\n  quietly enjoys catching him out"
        assertEquals(Verdict.Unchanged, StanceJudge.parse(current, stored))
    }

    @Test
    fun `parse reports unchanged when the stored stance is the one wearing quotes`() {
        assertEquals(Verdict.Unchanged, StanceJudge.parse(current, "\"$current\""))
    }

    @Test
    fun `parse strips one pair of straight double quotes`() {
        assertEquals(Verdict.Changed("needles him gently"), StanceJudge.parse("\"needles him gently\"", current))
    }

    @Test
    fun `parse strips one pair of straight single quotes`() {
        assertEquals(Verdict.Changed("needles him gently"), StanceJudge.parse("'needles him gently'", current))
    }

    @Test
    fun `parse strips one pair of curly quotes, which is what a model actually emits`() {
        assertEquals(Verdict.Changed("needles him gently"), StanceJudge.parse("“needles him gently”", current))
        assertEquals(Verdict.Changed("needles him gently"), StanceJudge.parse("‘needles him gently’", current))
    }

    @Test
    fun `parse strips only the outer pair, so quoted words inside the sentence survive`() {
        assertEquals(
            Verdict.Changed("\"needles him gently\""),
            StanceJudge.parse("\"\"needles him gently\"\"", current),
        )
    }

    @Test
    fun `parse collapses a wrapped answer to one line, since a stance renders as one`() {
        assertEquals(
            Verdict.Changed("reads him now with an eyebrow already raised"),
            StanceJudge.parse("reads him now\n   with an eyebrow\talready raised", current),
        )
    }

    // --- StanceJudgePrompts: the question --------------------------------------------------------

    @Test
    fun `the judge's synthetic name collides with no other seam identity`() {
        // The acceptance spy filters purely on persona NAME: a collision would make the composer and
        // dispatcher assertions start matching judge calls instead of going red.
        assertNotEquals(ComposerPrompts.COMPOSER_NAME, StanceJudgePrompts.JUDGE_NAME)
        assertNotEquals(ComposerPrompts.COMPOSER_ID, StanceJudgePrompts.JUDGE_ID)
        assertNotEquals("Moderator", StanceJudgePrompts.JUDGE_NAME, "that is PersonaRouter's dispatcher")
    }

    @Test
    fun `SYSTEM asks for one sentence of prose in the member's own voice`() {
        assertTrue(StanceJudgePrompts.SYSTEM.contains("ONE short sentence of prose"))
        assertTrue(StanceJudgePrompts.SYSTEM.contains("own voice"))
    }

    @Test
    fun `SYSTEM forbids the shapes a score arrives in`() {
        assertTrue(
            StanceJudgePrompts.SYSTEM.contains("Never mention scores, ratings, levels, tallies or approval"),
            "the one prompt in the codebase that could talk a model into writing a rating must forbid it",
        )
    }

    @Test
    fun `SYSTEM states the digit rule, so a rejection means the model disobeyed`() {
        assertTrue(StanceJudgePrompts.SYSTEM.contains("NEVER use digits"))
    }

    @Test
    fun `SYSTEM asks for the bare sentence, which is why parse only has to strip quotes`() {
        assertTrue(StanceJudgePrompts.SYSTEM.contains("no preamble or quotes"))
    }

    @Test
    fun `SYSTEM itself contains no digit - it cannot demand prose while modelling a number`() {
        assertTrue(StanceJudgePrompts.SYSTEM.none { it.isDigit() })
    }

    @Test
    fun `nothing the judge sends carries a reward-economy signal past the firewall`() {
        // Evolved prose lands in a persona's system prompt, which the owner-controls firewall scans.
        val instruction = StanceJudgePrompts.instruction("Paul", "Sol", current, EXCHANGES)
        val everything = (StanceJudgePrompts.SYSTEM + instruction).lowercase()
        assertFalse(everything.contains("vote"), "the judge's prompt would leak an owner-control signal")
        assertFalse(everything.contains("+1"), "the judge's prompt would leak an owner-control signal")
    }

    @Test
    fun `instruction pins the exact rendering for a two-exchange example so the shape cannot drift silently`() {
        assertEquals(
            "Member: Paul\n" +
                "The other member: Sol\n" +
                "Paul's current standing view of Sol: kindred pessimist, quietly enjoys catching him out\n" +
                "\n" +
                "What has passed between them since, oldest first — what Sol wrote, then what Paul wrote back:\n" +
                "  - Sol: Rust in the kernel is finally ready for the merge window\n" +
                "    Paul: This benchmark measures the wrong thing entirely\n" +
                "  - Sol: It will hold under load, I have seen worse survive\n" +
                "    Paul: You said that about the last one\n" +
                "\n" +
                "Write how Paul now regards Sol — one sentence, prose only.",
            StanceJudgePrompts.instruction("Paul", "Sol", current, EXCHANGES),
        )
    }

    @Test
    fun `instruction collapses a multi-paragraph comment so a body cannot pose as another exchange`() {
        val rendered = StanceJudgePrompts.instruction(
            "Paul",
            "Sol",
            current,
            listOf(Exchange(body = "First point.\n\n- second point", towardBody = "It will\nhold")),
        )
        assertTrue(rendered.contains("  - Sol: It will hold\n"))
        assertTrue(rendered.contains("    Paul: First point. - second point\n"))
    }

    @Test
    fun `instruction omits the evidence header when there is nothing to cite`() {
        val rendered = StanceJudgePrompts.instruction("Paul", "Sol", current, emptyList())
        assertFalse(
            rendered.contains("What has passed between them"),
            "a header over zero exchanges invites the model to invent the evidence it was promised",
        )
        assertTrue(rendered.endsWith("Write how Paul now regards Sol — one sentence, prose only."))
    }

    private fun assertRejected(verdict: Verdict) {
        assertTrue(verdict is Verdict.Rejected, "expected a rejection, got $verdict")
    }

    private companion object {
        val EXCHANGES = listOf(
            Exchange(
                body = "This benchmark measures the wrong thing entirely",
                towardBody = "Rust in the kernel is finally ready for the merge window",
            ),
            Exchange(
                body = "You said that about the last one",
                towardBody = "It will hold under load, I have seen worse survive",
            ),
        )
    }
}
