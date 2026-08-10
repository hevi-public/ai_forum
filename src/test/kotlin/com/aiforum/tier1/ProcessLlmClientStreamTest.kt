package com.aiforum.tier1

import com.aiforum.agui.AguiEvent
import com.aiforum.agui.AguiEventSink
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.ContextComment
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.llm.ProcessLlmClient
import com.aiforum.llm.PromptContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tier-1: the streaming [ProcessLlmClient.generate] overload — that it spawns `--output-format stream-json`,
 * reads NDJSON line by line into [AguiEvent]s via [com.aiforum.llm.ClaudeStreamParser], and still classifies
 * the captured `result` line through [com.aiforum.llm.LlmResponseParser]. The subprocess is a `/bin/sh`
 * script printing canned NDJSON (the same substitution seam as ProcessLlmClientTest); the pure mapping is
 * proven in ClaudeStreamParserTest.
 */
@Tag("tier1")
class ProcessLlmClientStreamTest {

    private class StreamShellClient(private val script: String) :
        ProcessLlmClient(
            command = "claude", defaultModel = "", workingDir = "", rateLimitRetryAfterSeconds = 300,
            pollMillis = 5,
            // Fixed so the tool-call trace's timestamps (issue #15) are assertable rather than "some instant".
            clock = FIXED_CLOCK,
        ) {
        var argv: List<String> = emptyList()
        override fun spawn(argv: List<String>): Process {
            this.argv = argv
            return ProcessBuilder("/bin/sh", "-c", script).start()
        }
    }

    private fun request(timeout: Duration, runId: String = "n") = LlmRequest(
        context = PromptContext(
            "you are sol",
            listOf(ContextComment(id = "c1", authorId = "sol", body = "indexes help here", parentId = null, depth = 0)),
        ),
        persona = PersonaRef("sol", "Sol"),
        timeout = timeout,
        runId = runId,
    )

    private fun record(): Pair<MutableList<AguiEvent>, AguiEventSink> {
        val events = mutableListOf<AguiEvent>()
        return events to AguiEventSink { events.add(it) }
    }

    private fun delta(text: String) =
        """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}}"""
    private fun result(text: String) =
        """{"type":"result","subtype":"success","is_error":false,"result":"$text","stop_reason":"end_turn"}"""

    private fun printScript(lines: List<String>) = "printf '%s\\n' " + lines.joinToString(" ") { "'$it'" }

    @Test
    fun `stream-json deltas reach the sink and the result line is the returned response`() {
        val lines = listOf(
            """{"type":"system","subtype":"init","session_id":"s"}""",
            delta("Index"),
            delta("es help"),
            result("Indexes help"),
        )
        val client = StreamShellClient(printScript(lines))
        val (events, sink) = record()

        val resp = client.generate(request(Duration.ofSeconds(10)), CancellationToken(), sink)

        assertEquals("Indexes help", resp.text)
        assertEquals(
            listOf(
                AguiEvent.RunStarted("n"),
                AguiEvent.TextDelta("n", "Index"),
                AguiEvent.TextDelta("n", "es help"),
                AguiEvent.RunFinished("n"),
            ),
            events,
        )
        assertTrue(client.argv.containsAll(listOf("--output-format", "stream-json", "--verbose", "--include-partial-messages")))
    }

    @Test
    fun `a failed run emits RunStarted then RunError and rethrows the taxonomy exception`() {
        val client = StreamShellClient("exit 1") // no result line, non-zero exit
        val (events, sink) = record()

        val ex = assertThrows(LlmException.ProcessError::class.java) {
            client.generate(request(Duration.ofSeconds(10)), CancellationToken(), sink)
        }
        assertEquals(1, ex.exitCode)
        assertEquals(AguiEvent.RunStarted("n"), events.first())
        assertTrue(events.last() is AguiEvent.RunError)
    }

    // --- issue #15: the streaming path carries usage AND the tool-call trace out ---------------------

    @Test
    fun `a tool-using stream returns the trace and the envelope's usage together`() {
        // The whole slice end to end at the seam: the stream_event tool start (which is what makes the
        // AG-UI ToolCallStart/End appear), the COMPLETE assistant message carrying the input, the user
        // line carrying the result, and a result envelope carrying cost.
        val lines = listOf(
            """{"type":"stream_event","event":{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"Read"}}}""",
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_1","name":"Read","input":{"file_path":"/wal.c"}}]}}""",
            """{"type":"stream_event","event":{"type":"content_block_stop","index":1}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"walCheckpoint","is_error":false}]}}""",
            delta("Indexes help"),
            """{"type":"result","subtype":"success","is_error":false,"result":"Indexes help","stop_reason":"end_turn","duration_ms":900,"total_cost_usd":0.08,"usage":{"input_tokens":10,"output_tokens":5}}""",
        )
        val client = StreamShellClient(printScript(lines))
        val (events, sink) = record()

        val resp = client.generate(request(Duration.ofSeconds(10)), CancellationToken(), sink)

        assertEquals("Indexes help", resp.text)
        assertEquals(0.08, resp.usage!!.costUsd)
        assertEquals(15L, resp.usage!!.tokens)
        val call = resp.toolCalls.single()
        assertEquals("Read", call.name)
        assertEquals("""{"file_path":"/wal.c"}""", call.inputSummary)
        assertEquals("walCheckpoint", call.outputSummary)
        assertEquals(FIXED_CLOCK.instant(), call.startedAt)
        assertEquals(FIXED_CLOCK.instant(), call.endedAt)
        // The event stream is unchanged by any of it — the trace is a second, silent job.
        assertTrue(events.any { it is AguiEvent.ToolCallStart }, "the live tool status still reaches the sink")
        assertTrue(events.any { it is AguiEvent.ToolCallEnd })
    }

    @Test
    fun `a stream with no tools returns an empty trace and no usage when the envelope reports none`() {
        val client = StreamShellClient(printScript(listOf(delta("Hi"), result("Hi"))))
        val (_, sink) = record()

        val resp = client.generate(request(Duration.ofSeconds(10)), CancellationToken(), sink)

        assertTrue(resp.toolCalls.isEmpty())
        assertNull(resp.usage, "a bare envelope reports nothing, and nothing must not become an empty object")
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC)
    }
}
