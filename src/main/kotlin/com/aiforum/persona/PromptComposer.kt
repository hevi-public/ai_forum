package com.aiforum.persona

import com.aiforum.llm.CancellationToken
import com.aiforum.llm.ContextComment
import com.aiforum.llm.LlmClient
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.llm.PromptContext
import org.springframework.stereotype.Service
import java.time.Duration

/** Turns a persona's structured authoring inputs into its system prompt. */
interface PromptComposer {
    /** Compose a fresh system prompt for [spec]; on an edit pass the [prior] composition so the model
     *  adjusts the existing prompt instead of regenerating it from scratch. [stances] are this persona's
     *  standing relations toward other members, resolved to names by the caller — they colour the voice
     *  the composer writes, while the discussion-scoped copy injected at reply time stays authoritative
     *  (see [ComposerPrompts.instruction]). Defaulted to empty so callers that have no relations to hand
     *  — and the pure Tier-0 call sites — stay unchanged. */
    fun compose(
        spec: PersonaSpec,
        prior: PriorComposition? = null,
        stances: List<StanceProse.NamedStance> = emptyList(),
    ): String
}

/**
 * The production composer: it rides the SAME single [LlmClient] seam as generation (see the
 * bdd-tiered-testing skill — one mock point), tagging the call with the synthetic [ComposerPrompts]
 * persona so the spy/router can tell a prompt-authoring call apart from a normal reply. The dial→prose
 * translation lives entirely in [ComposerPrompts] (Tier 0); this class only does the IO round-trip.
 */
@Service
class LlmPromptComposer(private val llm: LlmClient) : PromptComposer {

    override fun compose(
        spec: PersonaSpec,
        prior: PriorComposition?,
        stances: List<StanceProse.NamedStance>,
    ): String {
        val request = LlmRequest(
            context = PromptContext(
                personaSystemPrompt = ComposerPrompts.SYSTEM,
                comments = listOf(
                    ContextComment(
                        id = "spec",
                        authorId = "owner",
                        body = ComposerPrompts.instruction(spec, prior, stances),
                        parentId = null,
                        depth = 0,
                    ),
                ),
            ),
            persona = PersonaRef(ComposerPrompts.COMPOSER_ID, ComposerPrompts.COMPOSER_NAME),
            timeout = COMPOSE_TIMEOUT,
        )
        return llm.generate(request, CancellationToken()).text.trim()
    }

    private companion object {
        // Authoring is synchronous in the create/edit request, so keep it bounded but generous.
        val COMPOSE_TIMEOUT: Duration = Duration.ofSeconds(120)
    }
}
