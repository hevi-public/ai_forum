package com.aiforum.tier0

import com.aiforum.persona.ComposerPrompts
import com.aiforum.persona.InterestDrift
import com.aiforum.persona.InterestDrift.Verdict
import com.aiforum.persona.InterestDriftPrompts
import com.aiforum.persona.InterestDriftPrompts.Engagement
import com.aiforum.persona.Interests
import com.aiforum.persona.StanceJudgePrompts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure interest-drift judgment (see the bdd-tiered-testing skill). No Spring, no LLM — just
 * [InterestDriftPrompts] building the question, [InterestDrift] deciding whether the answer may move a
 * row, and [Interests] holding the length rule both of those and the owner's edit form share.
 *
 * All three halves live in one class the way [StanceJudgeTest] holds both halves of the stance
 * judgment: the refusal table is only meaningful against the prompt that produced the answer, and the
 * length rule is only meaningful if it is demonstrably the *same* rule on both paths.
 *
 * The cases worth defending hardest are the digit refusal (the executable form of the no-numbers
 * guardrail — `plan_docs/ambient-slice-4b.md` I2) and the two distinct core refusals: a judgment that
 * reaches for a pinned interest and one that names a phrase the member never held are different
 * failures, and the owner has to be able to tell them apart on the log.
 */
@Tag("tier0")
class InterestDriftTest {

    private val open = listOf("typography", "kernel scheduling")
    private val pinned = listOf("boring technology choices")

    // --- InterestDrift.parse: the refusal table, in order ----------------------------------------

    @Test
    fun `parse accepts a well-formed set-down-and-take-up pair`() {
        assertEquals(
            Verdict.Drifted(dropped = "typography", takenUp = "release engineering"),
            InterestDrift.parse("DROP: typography\nTAKE: release engineering", open, pinned),
        )
    }

    @Test
    fun `parse returns the dropped phrase as the member holds it, so the delete matches the row`() {
        // The stored phrase is untidy (a hand-written seed, a textarea); the model answers tidily. The
        // verdict has to carry the STORED spelling or the delete misses and the member keeps both.
        val stored = listOf("boring  technology   choices")
        assertEquals(
            Verdict.Drifted(dropped = "boring  technology   choices", takenUp = "release engineering"),
            InterestDrift.parse("DROP: boring technology choices\nTAKE: release engineering", stored, emptyList()),
        )
    }

    @Test
    fun `parse reads NONE as unchanged - the settled answer the prompt asks for`() {
        assertEquals(Verdict.Unchanged, InterestDrift.parse("NONE", open, pinned))
    }

    @Test
    fun `parse rejects a blank answer`() {
        assertRejected(InterestDrift.parse("   \n\t  ", open, pinned))
    }

    @Test
    fun `parse rejects a pair with no labels at all`() {
        assertRejected(InterestDrift.parse("typography\nrelease engineering", open, pinned))
    }

    @Test
    fun `parse rejects the labels in the wrong order rather than guessing which phrase to delete`() {
        assertRejected(InterestDrift.parse("TAKE: release engineering\nDROP: typography", open, pinned))
    }

    @Test
    fun `parse rejects a third content line, since an explaining model is an improvising one`() {
        assertRejected(
            InterestDrift.parse(
                "DROP: typography\nTAKE: release engineering\nBecause the scheduler keeps coming up",
                open,
                pinned,
            ),
        )
    }

    @Test
    fun `parse rejects a take under the minimum length`() {
        assertRejected(InterestDrift.parse("DROP: typography\nTAKE: G", open, pinned))
    }

    @Test
    fun `parse rejects a take over the maximum length`() {
        val tooLong = "a".repeat(Interests.MAX_CHARS + 1)
        assertRejected(InterestDrift.parse("DROP: typography\nTAKE: $tooLong", open, pinned))
    }

    @Test
    fun `parse rejects a digit anywhere in the take, not just at the front`() {
        assertRejected(
            InterestDrift.parse("DROP: typography\nTAKE: kernel scheduling, priority 2 of 5", open, pinned),
        )
    }

    @Test
    fun `parse accepts the same claim written as prose - the rule is digits, not counting`() {
        assertEquals(
            Verdict.Drifted(dropped = "typography", takenUp = "the second-order cost of preemption"),
            InterestDrift.parse("DROP: typography\nTAKE: the second-order cost of preemption", open, pinned),
        )
    }

    @Test
    fun `parse refuses to set down an interest the owner pinned`() {
        val verdict = InterestDrift.parse("DROP: boring technology choices\nTAKE: release engineering", open, pinned)
        assertTrue(verdict is Verdict.Rejected, "expected a rejection, got $verdict")
        assertTrue(
            (verdict as Verdict.Rejected).reason.contains("pinned"),
            "the owner must be able to read that the pass tried to move what they fixed: ${verdict.reason}",
        )
    }

    @Test
    fun `parse refuses to set down an interest the member does not hold`() {
        assertRejected(InterestDrift.parse("DROP: typesetting\nTAKE: release engineering", open, pinned))
    }

    @Test
    fun `the pinned refusal and the not-held refusal read differently, or the log cannot say which happened`() {
        val pinnedReason = reasonOf(
            InterestDrift.parse("DROP: boring technology choices\nTAKE: release engineering", open, pinned),
        )
        val notHeldReason = reasonOf(
            InterestDrift.parse("DROP: typesetting\nTAKE: release engineering", open, pinned),
        )
        assertNotEquals(
            pinnedReason, notHeldReason,
            "\"it tried to move what you fixed\" must be distinguishable from \"it made a phrase up\"",
        )
    }

    @Test
    fun `parse refuses a take the member already holds, or a degenerate swap eats an interest`() {
        assertRejected(InterestDrift.parse("DROP: typography\nTAKE: kernel scheduling", open, pinned))
    }

    @Test
    fun `parse refuses a take the owner pinned, which would un-pin it as well as collapse the count`() {
        assertRejected(InterestDrift.parse("DROP: typography\nTAKE: boring technology choices", open, pinned))
    }

    @Test
    fun `parse matches an already-held interest without regard to case`() {
        assertRejected(InterestDrift.parse("DROP: typography\nTAKE: Kernel Scheduling", open, pinned))
    }

    @Test
    fun `parse cleans the STORED side too when deciding what the member already holds`() {
        // S4a's b6 defect, in this slice's shape: cleaning only the model's answer lets a phrase the
        // member already holds through whenever the stored copy carries a double space — and the member
        // ends up holding the same interest twice under two spellings, one interest short of what the
        // owner authored.
        val untidy = listOf("typography", "release  engineering")
        assertRejected(InterestDrift.parse("DROP: typography\nTAKE: release engineering", untidy, emptyList()))
    }

    @Test
    fun `parse strips one pair of wrapping quotes from a take, which is what a model actually emits`() {
        assertEquals(
            Verdict.Drifted(dropped = "typography", takenUp = "release engineering"),
            InterestDrift.parse("DROP: typography\nTAKE: \"release engineering\"", open, pinned),
        )
        assertEquals(
            Verdict.Drifted(dropped = "typography", takenUp = "release engineering"),
            InterestDrift.parse("DROP: typography\nTAKE: “release engineering”", open, pinned),
        )
    }

    /**
     * SQLite's `length()` counts CHARACTERS; Kotlin's `String.length` counts UTF-16 units. One non-BMP
     * character measures 2 to Kotlin and 1 to V27's `length(trim(interest)) BETWEEN 2 AND 80`, so a
     * lone emoji would clear the floor here and then trip the CHECK — and the trip lands mid-write: the
     * owner form commits its retractions before its upserts, and the drift path rolls the swap back with
     * the window unstamped, re-buying that judgment every run. The floor has to be measured the way the
     * database measures it.
     */
    @Test
    fun `validate counts characters the way SQLite does, so one emoji is too short rather than just long enough`() {
        assertNotNull(
            Interests.validate("\uD83D\uDE80"),
            "a single non-BMP character is ONE character to the CHECK, so it must be refused here too",
        )
        // Two real characters, four UTF-16 units — accepted, because the DDL will accept it.
        assertNull(Interests.validate("\uD83D\uDE80\uD83D\uDE80"))
    }

    @Test
    fun `validate refuses a phrase the storage door would unwrap a second time`() {
        // The owner's form validates and then hands the value to a door that cleans again, so a phrase
        // that is not already a fixed point of clean is validated as one string and stored as another.
        assertNotNull(Interests.validate("\"\"x\"\""), "must be refused: it would be stored as a single character")
        assertNull(Interests.validate("kernel scheduling"))
    }

    @Test
    fun `parse refuses a phrase that is still wrapped after one unwrap, because the store would unwrap it again`() {
        // This USED to assert the doubly-wrapped phrase came back as a Drifted takenUp with its inner
        // quotes intact. That looked like careful quote handling and was the un-pin hole: the repository
        // cleans again at the write door, so the phrase compared here (quoted) and the phrase stored
        // (unquoted) were different values, and the unquoted one could land on an owner-pinned row and
        // relabel it. What the parse hands back must be what SQL stores.
        val verdict = InterestDrift.parse("DROP: typography\nTAKE: \"\"release engineering\"\"", open, pinned)
        assertTrue(verdict is Verdict.Rejected, "expected a refusal, got: $verdict")
        assertTrue(
            (verdict as Verdict.Rejected).reason.contains("wrapped"),
            "the owner reads this reason on the log, so it has to name the actual problem: ${verdict.reason}",
        )
    }

    @Test
    fun `every rejection carries a reason, because the owner reads it on the drift log`() {
        val raws = listOf(
            "",
            "typography",
            "DROP: typography\nTAKE: G",
            "DROP: typography\nTAKE: kernel scheduling, priority 2 of 5",
            "DROP: boring technology choices\nTAKE: release engineering",
            "DROP: typesetting\nTAKE: release engineering",
            "DROP: typography\nTAKE: kernel scheduling",
        )
        raws.forEach { raw ->
            val verdict = InterestDrift.parse(raw, open, pinned)
            assertTrue(verdict is Verdict.Rejected, "expected a rejection for \"$raw\", got $verdict")
            assertTrue(
                (verdict as Verdict.Rejected).reason.isNotBlank(),
                "a rejection with no reason is unreadable on the log",
            )
        }
    }

    // --- Interests: the length rule both write paths share ---------------------------------------

    @Test
    fun `validate passes a plain phrase`() {
        assertNull(Interests.validate("release engineering"))
    }

    @Test
    fun `validate refuses a blank phrase with its own reason, not a length complaint`() {
        val reason = Interests.validate("   \n ")
        assertTrue(reason != null && reason.contains("blank"), "got: $reason")
    }

    @Test
    fun `validate agrees with V27's length CHECK at both bounds`() {
        assertNull(Interests.validate("a".repeat(Interests.MIN_CHARS)), "two characters admits Go and AI")
        assertNull(Interests.validate("a".repeat(Interests.MAX_CHARS)))
        assertTrue(Interests.validate("a".repeat(Interests.MIN_CHARS - 1)) != null)
        assertTrue(Interests.validate("a".repeat(Interests.MAX_CHARS + 1)) != null)
    }

    @Test
    fun `validate measures the phrase as it will be stored, not as it was typed`() {
        // clean() collapses the whitespace, so a phrase that only exceeds the ceiling because it was
        // wrapped across lines is stored — and therefore validated — at its collapsed length. Raw it is
        // 83 characters and V27's CHECK would throw; cleaned it is exactly the 80 the CHECK allows.
        val wrapped = "a".repeat(Interests.MAX_CHARS - 2) + "\n\n\n b"
        assertNull(Interests.validate(wrapped))
    }

    @Test
    fun `validate does not refuse a digit, because the owner's path shares it`() {
        // V27's digit CHECK is scoped to non-owner rows on purpose: an owner typing "http/3" is naming a
        // real topic. The digit rule belongs to the model's path, and lives in parse.
        assertNull(Interests.validate("http/3"))
        assertRejected(InterestDrift.parse("DROP: typography\nTAKE: http/3", open, pinned))
    }

    // --- InterestDriftPrompts: the question, and the blinkers ------------------------------------

    @Test
    fun `the judge's synthetic name collides with no other seam identity`() {
        // The acceptance spy filters purely on persona NAME: a collision would make the composer,
        // stance-judge and dispatcher assertions start matching drift calls instead of going red.
        assertNotEquals(ComposerPrompts.COMPOSER_NAME, InterestDriftPrompts.JUDGE_NAME)
        assertNotEquals(ComposerPrompts.COMPOSER_ID, InterestDriftPrompts.JUDGE_ID)
        assertNotEquals(StanceJudgePrompts.JUDGE_NAME, InterestDriftPrompts.JUDGE_NAME)
        assertNotEquals(StanceJudgePrompts.JUDGE_ID, InterestDriftPrompts.JUDGE_ID)
        assertNotEquals("Moderator", InterestDriftPrompts.JUDGE_NAME, "that is PersonaRouter's dispatcher")
    }

    @Test
    fun `SYSTEM asks for a phrase of prose in the member's own voice`() {
        assertTrue(InterestDriftPrompts.SYSTEM.contains("short phrase of prose"))
        assertTrue(InterestDriftPrompts.SYSTEM.contains("own voice"))
    }

    @Test
    fun `SYSTEM forbids the shapes a score arrives in`() {
        assertTrue(
            InterestDriftPrompts.SYSTEM.contains("Never mention scores, ratings, levels, tallies or rankings"),
            "the prompt asks and the parse enforces; a refusal must mean the model disobeyed",
        )
    }

    @Test
    fun `SYSTEM states the digit rule the parse enforces`() {
        assertTrue(InterestDriftPrompts.SYSTEM.contains("NEVER use digits"))
    }

    @Test
    fun `SYSTEM itself contains no digit - it cannot forbid numbers while modelling one`() {
        assertTrue(InterestDriftPrompts.SYSTEM.none { it.isDigit() })
    }

    @Test
    fun `SYSTEM allows the answer that nothing moved, so a model does not invent movement to be useful`() {
        assertTrue(InterestDriftPrompts.SYSTEM.contains("nothing moved is ordinary and always allowed"))
    }

    @Test
    fun `nothing the judge sends carries a reward-economy signal past the firewall`() {
        val everything = (InterestDriftPrompts.SYSTEM + instruction()).lowercase()
        assertFalse(everything.contains("vote"), "the judge's prompt would leak an owner-control signal")
        assertFalse(everything.contains("+1"), "the judge's prompt would leak an owner-control signal")
    }

    @Test
    fun `instruction pins the exact rendering for a two-engagement example so the shape cannot drift silently`() {
        assertEquals(
            "Member: Sol\n" +
                "Who Sol is, and this does not change: dry, sceptical, allergic to hype\n" +
                "Interests Sol keeps regardless: (none)\n" +
                "Interests that are open to change: typography, kernel scheduling\n" +
                "\n" +
                "What Sol has actually been saying, oldest first — the room, then Sol's own words:\n" +
                "  - in \"Rust in the kernel\": The scheduler is the interesting part\n" +
                "  - in \"Boring tech wins\": Preemption cost decides this\n" +
                "\n" +
                "If what Sol has been saying has moved what Sol is drawn to, name ONE open interest to " +
                "set down and ONE new interest to take up, exactly:\n" +
                "DROP: <the open interest, word for word>\n" +
                "TAKE: <a short phrase, prose>\n" +
                "Otherwise answer exactly: NONE",
            instruction(),
        )
    }

    @Test
    fun `instruction names the member's character as fixed, so reaching for it is disobedience`() {
        assertTrue(instruction().contains("Who Sol is, and this does not change: dry, sceptical"))
    }

    @Test
    fun `instruction lists the owner's pins as kept, separately from what is open to change`() {
        val rendered = instruction(pinned = listOf("boring technology choices"))
        assertTrue(rendered.contains("Interests Sol keeps regardless: boring technology choices\n"))
        assertTrue(rendered.contains("Interests that are open to change: typography, kernel scheduling\n"))
    }

    @Test
    fun `instruction cannot be handed another member's material - the signature is the enforcement`() {
        // STRUCTURAL, and it has to be. The obvious version of this test renders the prompt and asserts
        // some other member's phrase is absent — but no production change can make a string appear that
        // was never passed in, so that test passes against every possible implementation, including one
        // that grew a roster parameter. What actually keeps the convergence channel shut is that there
        // is nowhere to put the room: every parameter is about the ONE member being judged. So the
        // parameter list is what gets pinned, and a later "give the judge a little room context" reddens
        // here rather than shipping quietly.
        // GENERIC types: an erased ["String","String","List","List","List"] is satisfied by
        // `List<Interest>` (provenance) or a roster-carrying engagement type, so it would survive the
        // very change it claims to catch.
        val parameters = InterestDriftPrompts::class.java.methods
            .single { it.name == "instruction" }
            .genericParameterTypes.map { it.toString() }

        assertEquals(
            listOf(
                "class java.lang.String",
                "class java.lang.String",
                "java.util.List<java.lang.String>",
                "java.util.List<java.lang.String>",
                "java.util.List<com.aiforum.persona.InterestDriftPrompts\$Engagement>",
            ),
            parameters,
            "instruction takes the member, its character, its pinned phrases, its open phrases and its " +
                "own engagements - and nothing else. A sixth parameter is the cross-member channel D12 denies.",
        )
    }

    @Test
    fun `instruction one-lines a body so a multi-paragraph comment cannot pose as another engagement`() {
        val rendered = instruction(
            engagements = listOf(Engagement(room = "Rust in\tthe kernel", body = "First point.\n\n- second point")),
        )
        assertTrue(rendered.contains("  - in \"Rust in the kernel\": First point. - second point\n"))
    }

    @Test
    fun `instruction omits the engagement header when there is nothing to cite`() {
        val rendered = instruction(engagements = emptyList())
        assertFalse(
            rendered.contains("has actually been saying"),
            "a header over zero engagements invites the model to invent the evidence it was promised",
        )
        assertTrue(rendered.endsWith("Otherwise answer exactly: NONE"), "the ask stays last")
    }

    @Test
    fun `the settled answer the instruction asks for is the one the parse accepts`() {
        // The two halves are edited in different files; a sentinel that agreed only by coincidence would
        // make every judgment a refusal and every window stay open forever.
        val asked = instruction().substringAfterLast("Otherwise answer exactly: ")
        assertEquals(Verdict.Unchanged, InterestDrift.parse(asked, open, pinned))
    }

    private fun instruction(
        pinned: List<String> = emptyList(),
        engagements: List<Engagement> = ENGAGEMENTS,
    ): String = InterestDriftPrompts.instruction(
        member = "Sol",
        character = "dry, sceptical, allergic to hype",
        pinned = pinned,
        open = listOf("typography", "kernel scheduling"),
        engagements = engagements,
    )

    private fun assertRejected(verdict: Verdict) {
        assertTrue(verdict is Verdict.Rejected, "expected a rejection, got $verdict")
    }

    private fun reasonOf(verdict: Verdict): String {
        assertTrue(verdict is Verdict.Rejected, "expected a rejection, got $verdict")
        return (verdict as Verdict.Rejected).reason
    }

    private companion object {
        val ENGAGEMENTS = listOf(
            Engagement(room = "Rust in the kernel", body = "The scheduler is the interesting part"),
            Engagement(room = "Boring tech wins", body = "Preemption cost decides this"),
        )
    }
}
