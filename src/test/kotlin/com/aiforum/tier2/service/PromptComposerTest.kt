package com.aiforum.tier2.service

import com.aiforum.llm.CancellationToken
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.LlmResponse
import com.aiforum.persona.ComposerPrompts
import com.aiforum.persona.LlmPromptComposer
import com.aiforum.persona.PersonaSpec
import com.aiforum.persona.PriorComposition
import com.aiforum.persona.StanceProse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-2: the LLM prompt composer running real composition logic over a faked LlmClient (the single IO
 * seam). Pins that authoring rides the seam tagged as a composer call, hands the model the abilities,
 * dials and standing stances, and on an edit replays the previous prompt so the model adjusts rather
 * than regenerates.
 */
@Tag("tier2")
class PromptComposerTest {

    /** A seam that records every request and returns canned text. */
    private class CannedLlm(private val text: String) : LlmClient {
        val received = mutableListOf<LlmRequest>()
        override fun generate(request: LlmRequest, cancellation: CancellationToken): LlmResponse {
            received += request
            return LlmResponse(text)
        }
    }

    private fun LlmRequest.allText(): String =
        context.personaSystemPrompt + " " + context.comments.joinToString(" ") { it.body }

    @Test
    fun `compose returns the model's text, trimmed`() {
        val llm = CannedLlm("  You are Lune, a terse poet.  ")
        val prompt = LlmPromptComposer(llm).compose(PersonaSpec(name = "Lune"))
        assertEquals("You are Lune, a terse poet.", prompt)
    }

    @Test
    fun `the composition call is tagged as the composer, not a generation reply`() {
        val llm = CannedLlm("ok")
        LlmPromptComposer(llm).compose(PersonaSpec(name = "Lune"))
        val req = llm.received.single()
        assertEquals(ComposerPrompts.COMPOSER_ID, req.persona.id)
        assertEquals(ComposerPrompts.COMPOSER_NAME, req.persona.name)
    }

    @Test
    fun `compose hands the model the abilities and the dials`() {
        val llm = CannedLlm("ok")
        LlmPromptComposer(llm).compose(
            PersonaSpec(name = "Lune", abilities = listOf("kotlin", "systems"), dials = mapOf("verbosity" to 1)),
        )
        val text = llm.received.single().allText()
        assertTrue(text.contains("kotlin"), "abilities reach the model")
        assertTrue(text.contains("agreeableness", ignoreCase = true), "every dial reaches the model")
    }

    @Test
    fun `compose hands the model the persona's stances toward the other members`() {
        // The composed prompt is who the persona IS, so a standing relation has to reach the authoring
        // model too — not only the discussion-scoped block injected at reply time.
        val llm = CannedLlm("ok")
        LlmPromptComposer(llm).compose(
            PersonaSpec(name = "Vex"),
            stances = listOf(StanceProse.NamedStance("Sol", "needles him about hype")),
        )
        val text = llm.received.single().allText()
        assertTrue(text.contains("needles him about hype"), "the stance prose reaches the seam")
        assertTrue(text.contains("Sol"), "so does the member it points at")
    }

    @Test
    fun `recompose replays the previous prompt so the model adjusts it`() {
        val llm = CannedLlm("NEW prompt")
        val prior = PriorComposition(
            spec = PersonaSpec(name = "Vex", dials = mapOf("agreeableness" to 1)),
            prompt = "OLD: a blunt contrarian.",
        )
        val result = LlmPromptComposer(llm).compose(PersonaSpec(name = "Vex", dials = mapOf("agreeableness" to 8)), prior)

        assertEquals("NEW prompt", result)
        assertTrue(llm.received.single().allText().contains("OLD: a blunt contrarian."), "the previous prompt is replayed")
    }
}
