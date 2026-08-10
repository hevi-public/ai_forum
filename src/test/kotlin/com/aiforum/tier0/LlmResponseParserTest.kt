package com.aiforum.tier0

import com.aiforum.dto.ReasoningLeak
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmResponseParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tier-0: the pure mapping from a finished `claude -p` invocation to the failure taxonomy. No
 * subprocess, no Spring — every branch is driven by a canned (exitCode, stdout) pair. The success
 * fixture is the real envelope captured from `claude -p --output-format json`.
 */
@Tag("tier0")
class LlmResponseParserTest {

    private val retryAfter = Duration.ofSeconds(300)

    private fun parse(exitCode: Int, stdout: String) =
        LlmResponseParser.parse(exitCode, stdout, retryAfter)

    @Test
    fun `a clean reply carries no reasoning-leak flag`() {
        val envelope = """{"is_error":false,"subtype":"success","result":"pong","stop_reason":"end_turn"}"""
        assertNull(parse(0, envelope).reasoningLeak)
    }

    @Test
    fun `a think block is stripped from the result and flagged ACTUAL`() {
        val envelope = """{"is_error":false,"subtype":"success","result":"<think>plan</think>Real answer","stop_reason":"end_turn"}"""
        val resp = parse(0, envelope)
        assertEquals("Real answer", resp.text)
        assertEquals(ReasoningLeak.ACTUAL, resp.reasoningLeak)
    }

    @Test
    fun `untagged thinking preamble is kept but flagged POSSIBLE`() {
        val body = "Thinking Process: 1. Analyze the request."
        val envelope = """{"is_error":false,"subtype":"success","result":"$body","stop_reason":"end_turn"}"""
        val resp = parse(0, envelope)
        assertEquals(body, resp.text)
        assertEquals(ReasoningLeak.POSSIBLE, resp.reasoningLeak)
    }

    @Test
    fun `a result that is only a think block is empty output`() {
        assertThrows(LlmException.EmptyOutput::class.java) {
            parse(0, """{"is_error":false,"subtype":"success","result":"<think>only reasoning</think>","stop_reason":"end_turn"}""")
        }
    }

    @Test
    fun `the real success envelope yields its result text and the usage it always carried`() {
        // The fixture is the envelope captured from a real `claude -p --output-format json` run — and it
        // has carried total_cost_usd/duration_ms since the day it was captured. Issue #15 is the slice
        // that stops dropping them on the floor, so this long-standing case now asserts them too.
        val envelope = """
            {"type":"result","subtype":"success","is_error":false,"api_error_status":null,
             "duration_ms":3255,"num_turns":1,"result":"pong","stop_reason":"end_turn",
             "session_id":"abc","total_cost_usd":0.14}
        """.trimIndent()
        val resp = parse(0, envelope)
        assertEquals("pong", resp.text)
        assertEquals(0.14, resp.usage!!.costUsd)
        assertEquals(3255L, resp.usage!!.durationMs)
        assertNull(resp.usage!!.tokens, "this envelope carries no usage block")
        assertTrue(resp.toolCalls.isEmpty(), "the plain-json envelope has no content array to collect from")
    }

    @Test
    fun `a full envelope sums the input and output tokens and joins the model names, sorted`() {
        // cache_creation_input_tokens / cache_read_input_tokens are present and deliberately EXCLUDED:
        // they measure the provider's cache behaviour, not the size of this turn.
        val envelope = """
            {"subtype":"success","is_error":false,"result":"pong","stop_reason":"end_turn",
             "duration_ms":1200,"total_cost_usd":0.02,
             "usage":{"input_tokens":120,"output_tokens":30,"cache_creation_input_tokens":9000,
                      "cache_read_input_tokens":4000},
             "modelUsage":{"claude-sonnet-4":{"x":1},"claude-haiku-4":{"x":1}}}
        """.trimIndent()
        val usage = parse(0, envelope).usage!!
        assertEquals(150L, usage.tokens, "input + output only")
        assertEquals("claude-haiku-4,claude-sonnet-4", usage.model, "sorted, so the string is stable")
        assertEquals(1200L, usage.durationMs)
        assertEquals(0.02, usage.costUsd)
    }

    @Test
    fun `an envelope reporting nothing at all yields a null usage, not an object of nulls`() {
        // The distinction the whole nullable chain exists to keep: `usage != null` must mean "the provider
        // said something", not "the envelope parsed". An openai/opencode-shaped reply reports nothing.
        val envelope = """{"is_error":false,"subtype":"success","result":"pong","stop_reason":"end_turn"}"""
        assertNull(parse(0, envelope).usage)
    }

    @Test
    fun `a partial envelope still reports what it does know`() {
        val envelope = """{"is_error":false,"subtype":"success","result":"pong","stop_reason":"end_turn","total_cost_usd":0.01}"""
        val usage = parse(0, envelope).usage!!
        assertEquals(0.01, usage.costUsd)
        assertNull(usage.tokens)
        assertNull(usage.model)
    }

    @Test
    fun `a rate-limited error envelope carrying a cost still throws — no usage escapes a failure`() {
        // Usage rides out on the SUCCESS branch only: every error branch throws, and the taxonomy
        // exceptions are the failure contract. Bolting accounting onto them would give the two call
        // sites two different ideas of what a failure is.
        assertThrows(LlmException.RateLimited::class.java) {
            parse(1, """{"is_error":true,"subtype":"error","api_error_status":429,"result":"usage limit reached","total_cost_usd":0.03}""")
        }
    }

    @Test
    fun `a clean exit with non-zero code is a process error even with output`() {
        val ex = assertThrows(LlmException.ProcessError::class.java) {
            parse(2, """{"is_error":false,"subtype":"success","result":"hi","stop_reason":"end_turn"}""")
        }
        assertEquals(2, ex.exitCode)
    }

    @Test
    fun `empty stdout with zero exit is empty output`() {
        assertThrows(LlmException.EmptyOutput::class.java) { parse(0, "   ") }
    }

    @Test
    fun `empty stdout with non-zero exit is a process error`() {
        val ex = assertThrows(LlmException.ProcessError::class.java) { parse(137, "") }
        assertEquals(137, ex.exitCode)
    }

    @Test
    fun `a blank result in a success envelope is empty output`() {
        assertThrows(LlmException.EmptyOutput::class.java) {
            parse(0, """{"is_error":false,"subtype":"success","result":"","stop_reason":"end_turn"}""")
        }
    }

    @Test
    fun `a truncated reply (max_tokens) is malformed output`() {
        assertThrows(LlmException.MalformedOutput::class.java) {
            parse(0, """{"is_error":false,"subtype":"success","result":"half a th","stop_reason":"max_tokens"}""")
        }
    }

    @Test
    fun `non-JSON stdout is malformed output carrying the raw text`() {
        val ex = assertThrows(LlmException.MalformedOutput::class.java) { parse(0, "not json at all") }
        assertEquals("not json at all", ex.raw)
    }

    @Test
    fun `a usage-limit error envelope is rate-limited with the configured retry-after`() {
        val ex = assertThrows(LlmException.RateLimited::class.java) {
            parse(1, """{"is_error":true,"subtype":"error","api_error_status":429,"result":"usage limit reached"}""")
        }
        assertEquals(retryAfter, ex.retryAfter)
    }

    @Test
    fun `a successful reply that merely mentions rate limits is not mistaken for one`() {
        // A forum reply about API rate limiting must not false-positive into RATE_LIMITED — rate
        // detection is scoped to error envelopes only.
        val body = "To avoid hitting the rate limit, batch your writes."
        val envelope = """{"is_error":false,"subtype":"success","result":"$body","stop_reason":"end_turn"}"""
        assertEquals(body, parse(0, envelope).text)
    }

    @Test
    fun `a generic error envelope with a non-zero exit is a process error`() {
        val ex = assertThrows(LlmException.ProcessError::class.java) {
            parse(1, """{"is_error":true,"subtype":"error_during_execution","result":"boom"}""")
        }
        assertEquals(1, ex.exitCode)
    }
}
