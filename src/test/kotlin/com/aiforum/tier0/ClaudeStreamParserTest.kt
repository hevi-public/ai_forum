package com.aiforum.tier0

import com.aiforum.agui.AguiEvent
import com.aiforum.llm.ClaudeStreamParser
import com.aiforum.llm.ToolSummaries
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tier-0: the pure NDJSON → [AguiEvent] normalisation for `claude -p --output-format stream-json`. Canned
 * lines in, events out — no subprocess. The terminal `result` line is captured (for [LlmResponseParser]),
 * not emitted as a delta.
 */
@Tag("tier0")
class ClaudeStreamParserTest {

    private fun delta(text: String) =
        """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}}"""

    private val toolStart =
        """{"type":"stream_event","event":{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"WebFetch"}}}"""
    private val toolStop = """{"type":"stream_event","event":{"type":"content_block_stop","index":1}}"""
    private val systemInit = """{"type":"system","subtype":"init","session_id":"s"}"""
    private fun assistant(text: String) = """{"type":"assistant","message":{"content":[{"type":"text","text":"$text"}]}}"""
    private fun result(text: String) =
        """{"type":"result","subtype":"success","is_error":false,"result":"$text","stop_reason":"end_turn"}"""

    @Test
    fun `content_block_delta lines become TextDeltas in order`() {
        val p = ClaudeStreamParser("n")
        assertEquals(emptyList<AguiEvent>(), p.onLine(systemInit))
        assertEquals(listOf(AguiEvent.TextDelta("n", "Index")), p.onLine(delta("Index")))
        assertEquals(listOf(AguiEvent.TextDelta("n", "es help")), p.onLine(delta("es help")))
    }

    @Test
    fun `the result line is captured and emits nothing`() {
        val p = ClaudeStreamParser("n")
        assertEquals(emptyList<AguiEvent>(), p.onLine(result("Indexes help")))
        assertTrue(p.resultJson.contains("\"result\":\"Indexes help\""))
    }

    @Test
    fun `a tool_use block emits ToolCallStart then ToolCallEnd paired by index`() {
        val p = ClaudeStreamParser("n")
        assertEquals(listOf(AguiEvent.ToolCallStart("n", "toolu_1", "WebFetch")), p.onLine(toolStart))
        assertEquals(listOf(AguiEvent.ToolCallEnd("n", "toolu_1")), p.onLine(toolStop))
    }

    @Test
    fun `a whole assistant message becomes one TextDelta when no token deltas were seen`() {
        val p = ClaudeStreamParser("n")
        assertEquals(listOf(AguiEvent.TextDelta("n", "Whole answer")), p.onLine(assistant("Whole answer")))
    }

    @Test
    fun `a trailing assistant message is suppressed once token deltas have streamed`() {
        val p = ClaudeStreamParser("n")
        p.onLine(delta("partial"))
        assertEquals(emptyList<AguiEvent>(), p.onLine(assistant("partial and more")))
    }

    @Test
    fun `a non-JSON line is ignored`() {
        val p = ClaudeStreamParser("n")
        assertEquals(emptyList<AguiEvent>(), p.onLine("not json at all"))
        assertEquals(emptyList<AguiEvent>(), p.onLine("   "))
    }

    // --- issue #15: the tool-call trace collected alongside (and silently beside) the event stream ----
    //
    // Everything above is the regression pin: collecting a trace must not move a single emitted event.

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC)

    private fun parser() = ClaudeStreamParser("n", fixedClock)

    private fun assistantToolUse(id: String, name: String, input: String) =
        """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}]}}"""

    private fun toolResult(id: String, content: String, isError: Boolean = false) =
        """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"$id","content":$content,"is_error":$isError}]}}"""

    @Test
    fun `a complete assistant tool_use collects the call with its input as compact JSON`() {
        val p = parser()
        assertEquals(
            emptyList<AguiEvent>(),
            p.onLine(assistantToolUse("toolu_1", "Read", """{"file_path":"/wal.c"}""")),
            "a tool-only assistant message has no text, so it emits nothing",
        )

        val call = p.toolCalls().single()
        assertEquals("toolu_1", call.id)
        assertEquals("Read", call.name)
        assertEquals("""{"file_path":"/wal.c"}""", call.inputSummary)
        assertEquals(fixedClock.instant(), call.startedAt)
        assertNull(call.outputSummary, "no result has arrived yet")
    }

    @Test
    fun `a tool_result pairs to its call by id, carrying output, error flag and end time`() {
        val p = parser()
        p.onLine(assistantToolUse("toolu_1", "Bash", """{"cmd":"ls"}"""))
        assertEquals(emptyList<AguiEvent>(), p.onLine(toolResult("toolu_1", "\"no such file\"", isError = true)))

        val call = p.toolCalls().single()
        assertEquals("no such file", call.outputSummary)
        assertTrue(call.isError, "a failed tool is still part of the trace, flagged")
        assertEquals(fixedClock.instant(), call.endedAt)
    }

    @Test
    fun `an array-shaped tool_result contributes the text of its text parts`() {
        val p = parser()
        p.onLine(assistantToolUse("toolu_1", "Read", "{}"))
        p.onLine(toolResult("toolu_1", """[{"type":"text","text":"line one"},{"type":"text","text":"line two"}]"""))

        assertEquals("line one\nline two", p.toolCalls().single().outputSummary)
    }

    @Test
    fun `two interleaved tools keep arrival order and pair by id, not by position`() {
        // The results come back in the OPPOSITE order to the calls — which is exactly why the pairing key
        // is the tool_use_id. Arrival order is still what the trace reports, because that is what `seq`
        // persists: the order the model made the calls in.
        val p = parser()
        p.onLine(assistantToolUse("toolu_a", "Read", "{}"))
        p.onLine(assistantToolUse("toolu_b", "WebFetch", "{}"))
        p.onLine(toolResult("toolu_b", "\"fetched\""))
        p.onLine(toolResult("toolu_a", "\"read\""))

        assertEquals(listOf("toolu_a", "toolu_b"), p.toolCalls().map { it.id })
        assertEquals(listOf("read", "fetched"), p.toolCalls().map { it.outputSummary })
    }

    @Test
    fun `a tool_result for an id we never saw open is ignored, never invented`() {
        val p = parser()
        p.onLine(toolResult("toolu_ghost", "\"from a stream we joined late\""))

        assertTrue(p.toolCalls().isEmpty(), "a half-observed stream must not fabricate a nameless call")
    }

    @Test
    fun `a user line emits no events at all`() {
        // Tool results are trace, not liveness: the AG-UI stream already said the tool ended, and a tool's
        // raw output is not something a forum reader should watch scroll past.
        val p = parser()
        p.onLine(assistantToolUse("toolu_1", "Bash", "{}"))
        assertEquals(emptyList<AguiEvent>(), p.onLine(toolResult("toolu_1", "\"output\"")))
    }

    @Test
    fun `a streamed tool start collects the call too, and the later complete message fills its input`() {
        // Partial mode surfaces the same call twice. The FIRST sighting dates it (it is the one that
        // actually happened); the complete message is what carries the input.
        val p = parser()
        assertEquals(listOf(AguiEvent.ToolCallStart("n", "toolu_1", "WebFetch")), p.onLine(toolStart))
        p.onLine(assistantToolUse("toolu_1", "WebFetch", """{"url":"https://x"}"""))

        val call = p.toolCalls().single()
        assertEquals("WebFetch", call.name)
        assertEquals("""{"url":"https://x"}""", call.inputSummary)
        assertEquals(fixedClock.instant(), call.startedAt)
    }

    @Test
    fun `non-partial mode collects the trace from assistant messages alone`() {
        // Without --include-partial-messages there are no stream_event lines at all, so the complete
        // assistant message is the ONLY source — this is why the collector never parses input_json_deltas.
        val p = parser()
        p.onLine(assistantToolUse("toolu_1", "Read", """{"file_path":"/a"}"""))
        p.onLine(toolResult("toolu_1", "\"contents\""))
        p.onLine(assistant("Here is what I found"))

        val call = p.toolCalls().single()
        assertEquals("Read", call.name)
        assertEquals("contents", call.outputSummary)
    }

    @Test
    fun `oversized input and output are clipped at their caps, marker-terminated`() {
        val p = parser()
        p.onLine(assistantToolUse("toolu_1", "Bash", """{"cmd":"${"a".repeat(5_000)}"}"""))
        p.onLine(toolResult("toolu_1", "\"${"b".repeat(9_000)}\""))

        val call = p.toolCalls().single()
        assertEquals(ToolSummaries.INPUT_CAP, call.inputSummary!!.length)
        assertEquals(ToolSummaries.OUTPUT_CAP, call.outputSummary!!.length)
        assertTrue(call.inputSummary!!.endsWith(ToolSummaries.MARKER))
        assertTrue(call.outputSummary!!.endsWith(ToolSummaries.MARKER))
    }

    @Test
    fun `a stream with no tools at all yields an empty trace`() {
        val p = parser()
        p.onLine(delta("Indexes"))
        p.onLine(result("Indexes help"))

        assertTrue(p.toolCalls().isEmpty(), "empty is the correct account of a turn that used no tools")
    }
}
