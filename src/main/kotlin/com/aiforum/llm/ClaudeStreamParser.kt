package com.aiforum.llm

import com.aiforum.agui.AguiEvent
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Clock
import java.time.Instant

/**
 * Pure Tier-0 normalisation of `claude -p --output-format stream-json` NDJSON into our [AguiEvent]
 * vocabulary — the claude side of the provider-agnostic streaming layer (sibling of [LlmResponseParser],
 * which still classifies the FINAL result). Holds NO IO: [ProcessLlmClient] reads stdout line by line and
 * feeds each raw line to [onLine], emitting whatever comes back, then hands the captured [resultJson] to
 * [LlmResponseParser] for the authoritative response — so the persisted reply is byte-identical to the
 * non-streaming path; the deltas are purely for liveness.
 *
 * Stateful per run (one instance per generate call): it tracks open tool-use blocks by content-block index
 * to pair start/stop, and suppresses a trailing full `assistant` message once token deltas have been seen
 * (claude emits both with `--include-partial-messages`) so text isn't shown twice.
 *
 * **Tool-call collection (issue #15) is a SECOND, SILENT job.** [toolCalls] accumulates the audit trail
 * [ProcessLlmClient] persists at settle, and collecting it changes the emitted event stream not at all —
 * the events remain exactly what they were, which is what lets the six pre-existing Tier-0 cases stand as
 * the regression pin. The `user` lines it now reads (where `tool_result`s live) emit nothing whatsoever.
 *
 * Assumed shapes (Claude Code stream-json):
 *  - partial text: `{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}}`
 *  - tool start:   `{"type":"stream_event","event":{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_x","name":"WebFetch"}}}`
 *  - tool stop:    `{"type":"stream_event","event":{"type":"content_block_stop","index":1}}`
 *  - whole message (no partial): `{"type":"assistant","message":{"content":[{"type":"text","text":"…"}]}}`
 *  - tool use (complete): `{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_x","name":"Read","input":{…}}]}}`
 *  - tool result:  `{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_x","content":"…","is_error":false}]}}`
 *  - terminal:     `{"type":"result","subtype":"success","is_error":false,"result":"…","stop_reason":"end_turn"}`
 */
class ClaudeStreamParser(private val runId: String, private val clock: Clock = Clock.systemUTC()) {

    /** The raw terminal `result` line, captured for [LlmResponseParser]; "" until one is seen. */
    var resultJson: String = ""
        private set

    private val toolByIndex = HashMap<Int, String>()
    private var sawTextDelta = false

    /**
     * Tool calls in ARRIVAL order, keyed by the CLI's tool-use id. A LinkedHashMap because the id is the
     * only thing that pairs a `tool_use` with the `tool_result` that answers it (they arrive in separate
     * NDJSON lines, and interleaved calls answer out of order), while the audit trail has to preserve the
     * order the model made them in — that ordering is what `seq` persists.
     */
    private val tools = LinkedHashMap<String, MutableToolCall>()

    /** The trace collected so far, in arrival order. Read by [ProcessLlmClient] once the stream ends. */
    fun toolCalls(): List<ToolCall> = tools.values.map { it.toToolCall() }

    /** Events to emit for one NDJSON line (empty when the line carries no streamable signal). */
    fun onLine(raw: String): List<AguiEvent> {
        val line = raw.trim()
        if (line.isEmpty()) return emptyList()
        val parsed = try {
            mapper.readValue(line, StreamLine::class.java)
        } catch (_: Exception) {
            return emptyList() // a non-JSON line (banner, blank) is just ignored
        }

        if (parsed.type == "result") {
            resultJson = line
            return emptyList()
        }

        // A full assistant message arrives in non-partial mode (or as a trailing summary in partial mode).
        // Emit its text only when we haven't already streamed token deltas, so text is never doubled.
        if (parsed.type == "assistant") {
            // Collect tool inputs from the COMPLETE message rather than accumulating input_json_delta
            // fragments: completeness beats reassembly. The deltas are a partial JSON string that is only
            // valid once the block closes, and a stream that dies mid-tool would leave us re-parsing
            // half an object; the complete message carries the whole input, already structured. So the
            // deltas are never parsed for this purpose — this branch is the single source.
            parsed.message?.content.orEmpty()
                .filter { it.type == "tool_use" }
                .forEach { part ->
                    part.id?.let { id ->
                        val call = open(id, part.name)
                        call.inputSummary = ToolSummaries.clip(part.input?.toString(), ToolSummaries.INPUT_CAP)
                    }
                }
            if (sawTextDelta) return emptyList()
            val text = parsed.message?.content.orEmpty()
                .filter { it.type == "text" }.mapNotNull { it.text }.joinToString("")
            return if (text.isEmpty()) emptyList() else listOf(AguiEvent.TextDelta(runId, text))
        }

        // `user` lines carry the tool RESULTS the CLI feeds back to the model. They are pure trace: this
        // branch emits nothing, ever — the AG-UI stream already said the tool ended (ToolCallEnd), and a
        // tool's raw output is not something the reader of a forum reply should see scroll past.
        if (parsed.type == "user") {
            parsed.message?.content.orEmpty()
                .filter { it.type == "tool_result" }
                .forEach { part ->
                    // An id we never saw open is ignored rather than invented: a half-observed stream
                    // (we joined late, the CLI changed shape) must not fabricate a call with no name.
                    val call = part.toolUseId?.let { tools[it] } ?: return@forEach
                    call.outputSummary = ToolSummaries.clip(extractText(part.content), ToolSummaries.OUTPUT_CAP)
                    call.isError = part.isError ?: false
                    call.endedAt = clock.instant()
                }
            return emptyList()
        }

        val ev = if (parsed.type == "stream_event") parsed.event else null
        return when (ev?.type) {
            "content_block_delta" -> {
                val text = ev.delta?.text
                if (text.isNullOrEmpty()) emptyList()
                else { sawTextDelta = true; listOf(AguiEvent.TextDelta(runId, text)) }
            }
            "content_block_start" -> {
                val block = ev.contentBlock
                if (block?.type != "tool_use") emptyList()
                else {
                    val id = block.id ?: "tool-${ev.index ?: 0}"
                    ev.index?.let { toolByIndex[it] = id }
                    open(id, block.name)
                    listOf(AguiEvent.ToolCallStart(runId, id, block.name ?: "tool"))
                }
            }
            "content_block_stop" -> {
                val id = ev.index?.let { toolByIndex.remove(it) }
                if (id == null) emptyList() else listOf(AguiEvent.ToolCallEnd(runId, id))
            }
            else -> emptyList()
        }
    }

    /**
     * Upsert the call [id] arrived under. FIRST SIGHTING WINS for `startedAt`: the same call surfaces
     * twice in partial mode (the `content_block_start` event, then the complete assistant message), and
     * the earlier of the two is the one that actually happened. A later sighting only fills in a name we
     * did not have.
     */
    private fun open(id: String, name: String?): MutableToolCall =
        tools.getOrPut(id) { MutableToolCall(id, name ?: "tool", startedAt = clock.instant()) }
            .also { if (name != null) it.name = name }

    /**
     * The text of a `tool_result`'s `content`, which the CLI writes in whichever of three shapes suits
     * the tool: a bare string, an array of content parts (take the `text` of each text part), or some
     * other node. Anything unrecognised is stringified rather than dropped — an odd-shaped result is
     * still evidence of what the tool answered, and an operator reading a trace would rather see JSON
     * than a blank.
     */
    private fun extractText(content: JsonNode?): String? = when {
        content == null || content.isNull -> null
        content.isString -> content.stringValue()
        content.isArray -> content.mapNotNull { part ->
            part.get("text")?.takeIf { it.isString }?.stringValue()
        }.joinToString("\n")
        else -> content.toString()
    }

    private companion object {
        private val mapper = jacksonMapperBuilder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
    }

    /** The accumulating half of a [ToolCall]: a call is assembled across two or three NDJSON lines. */
    private class MutableToolCall(
        val id: String,
        var name: String,
        var inputSummary: String? = null,
        var outputSummary: String? = null,
        var isError: Boolean = false,
        var startedAt: Instant? = null,
        var endedAt: Instant? = null,
    ) {
        fun toToolCall() = ToolCall(id, name, inputSummary, outputSummary, isError, startedAt, endedAt)
    }

    private data class StreamLine(
        val type: String? = null,
        val event: AnthropicEvent? = null,
        val message: AssistantMessage? = null,
    )

    private data class AnthropicEvent(
        val type: String? = null,
        val index: Int? = null,
        val delta: AnthropicDelta? = null,
        @param:JsonProperty("content_block") val contentBlock: ContentBlock? = null,
    )

    private data class AnthropicDelta(val type: String? = null, val text: String? = null)
    private data class ContentBlock(val type: String? = null, val id: String? = null, val name: String? = null)

    /** Reused for `user` lines too: the two message shapes differ only in which part types appear. */
    private data class AssistantMessage(val content: List<ContentPart> = emptyList())

    /**
     * One content part of a message. Widened for #15: a `tool_use` part carries id/name/input, a
     * `tool_result` part carries tool_use_id/content/is_error. [input] and [content] stay raw [JsonNode]s
     * because their shapes are per-tool and unknowable — we only ever stringify them into a summary.
     */
    private data class ContentPart(
        val type: String? = null,
        val text: String? = null,
        val id: String? = null,
        val name: String? = null,
        val input: JsonNode? = null,
        @param:JsonProperty("tool_use_id") val toolUseId: String? = null,
        val content: JsonNode? = null,
        @param:JsonProperty("is_error") val isError: Boolean? = null,
    )
}
