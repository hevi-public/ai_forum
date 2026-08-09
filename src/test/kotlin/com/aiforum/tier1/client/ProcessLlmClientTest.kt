package com.aiforum.tier1.client

import com.aiforum.llm.CancellationToken
import com.aiforum.llm.ContextComment
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.llm.ProcessLlmClient
import com.aiforum.llm.PromptContext
import com.aiforum.testsupport.LogCapture
import ch.qos.logback.classic.Level
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tier-1: the genuinely un-fakeable plumbing of [ProcessLlmClient] — stdin delivery, exit-code mapping,
 * the timeout deadline, and cooperative cancellation. We substitute the spawn with a controlled
 * `/bin/sh` subprocess (poll interval 5ms for snappy tests) so this runs hermetically, without the
 * real `claude` binary or any quota. The classification of well-formed output is proven separately in
 * LlmResponseParserTest.
 */
@Tag("tier1")
class ProcessLlmClientTest {

    /** A ProcessLlmClient whose subprocess is a fixed shell script instead of `claude`. */
    private class ShellClient(private val script: String) :
        ProcessLlmClient(command = "claude", defaultModel = "", workingDir = "", rateLimitRetryAfterSeconds = 300, pollMillis = 5) {
        override fun spawn(argv: List<String>): Process =
            ProcessBuilder("/bin/sh", "-c", script).start()
    }

    /**
     * Captures the argv that would be handed to `claude` (so we can assert model selection) while still
     * returning a well-formed success so generate() completes. The configured `defaultModel` is the
     * fallback when the persona doesn't pin one.
     */
    private class CapturingClient(
        defaultModel: String,
        webFetchEnabled: Boolean = false,
        webFetchAllowedDomains: String = "",
        githubToolsEnabled: Boolean = false,
        githubMcpConfig: String = "",
        githubMcpServerName: String = "gh-readonly",
    ) :
        ProcessLlmClient(
            command = "claude",
            defaultModel = defaultModel,
            workingDir = "",
            rateLimitRetryAfterSeconds = 300,
            pollMillis = 5,
            webFetchEnabled = webFetchEnabled,
            webFetchAllowedDomains = webFetchAllowedDomains,
            githubToolsEnabled = githubToolsEnabled,
            githubMcpConfig = githubMcpConfig,
            githubMcpServerName = githubMcpServerName,
        ) {
        var argv: List<String> = emptyList()
        override fun spawn(argv: List<String>): Process {
            this.argv = argv
            return ProcessBuilder("/bin/sh", "-c", "printf '%s' '{\"is_error\":false,\"subtype\":\"success\",\"result\":\"ok\",\"stop_reason\":\"end_turn\"}'").start()
        }
    }

    private fun request(timeout: Duration, personaModel: String = "") = LlmRequest(
        context = PromptContext(
            "you are sol",
            listOf(ContextComment(id = "c1", authorId = "sol", body = "indexes help here", parentId = null, depth = 0)),
        ),
        persona = PersonaRef("sol", "Sol", personaModel),
        timeout = timeout,
    )

    /** The flag value that follows `--model` in argv, or null when the flag is absent. */
    private fun modelArg(argv: List<String>): String? =
        argv.indexOf("--model").takeIf { it >= 0 }?.let { argv.getOrNull(it + 1) }

    /** The flag value that follows `--allowedTools` in argv, or null when the flag is absent. */
    private fun allowedToolsArg(argv: List<String>): String? =
        argv.indexOf("--allowedTools").takeIf { it >= 0 }?.let { argv.getOrNull(it + 1) }

    /** The flag value that follows `--mcp-config` in argv, or null when the flag is absent. */
    private fun mcpConfigArg(argv: List<String>): String? =
        argv.indexOf("--mcp-config").takeIf { it >= 0 }?.let { argv.getOrNull(it + 1) }

    /** The flag value that follows `--system-prompt` in argv, or null when the flag is absent. */
    private fun systemPromptArg(argv: List<String>): String? =
        argv.indexOf("--system-prompt").takeIf { it >= 0 }?.let { argv.getOrNull(it + 1) }

    @Test
    fun `the non-streaming path carries usage out but never a tool trace — the pinned asymmetry`() {
        // Issue #15's one deliberate difference between the two generate paths. Cost comes from the same
        // LlmResponseParser both paths use, so it is identical here. Tool calls cannot be: the plain-json
        // envelope has no content array to collect them from, so an EMPTY list is the structurally correct
        // answer rather than a gap. If a future change makes this list non-empty, that is a real design
        // change, and this is the test that should say so.
        val envelope = """{"is_error":false,"subtype":"success","result":"ok","stop_reason":"end_turn","duration_ms":1500,"total_cost_usd":0.09}"""
        val client = ShellClient("printf '%s' '$envelope'")

        val resp = client.generate(request(Duration.ofSeconds(10)), CancellationToken())

        assertEquals(0.09, resp.usage!!.costUsd)
        assertEquals(1500L, resp.usage!!.durationMs)
        assertTrue(resp.toolCalls.isEmpty(), "the plain-json envelope structurally carries no tool calls")
    }

    @Test
    fun `a persona's pinned model is passed as --model and wins over the configured default`() {
        val client = CapturingClient(defaultModel = "sonnet")
        client.generate(request(Duration.ofSeconds(10), personaModel = "opus"), CancellationToken())
        assertEquals("opus", modelArg(client.argv))
    }

    @Test
    fun `a persona with no pinned model falls back to the configured default-model`() {
        val client = CapturingClient(defaultModel = "sonnet")
        client.generate(request(Duration.ofSeconds(10), personaModel = ""), CancellationToken())
        assertEquals("sonnet", modelArg(client.argv))
    }

    @Test
    fun `with neither a persona model nor a default, no --model flag is sent and the CLI picks its own`() {
        val client = CapturingClient(defaultModel = "")
        client.generate(request(Duration.ofSeconds(10), personaModel = ""), CancellationToken())
        assertEquals(null, modelArg(client.argv))
    }

    @Test
    fun `with web-fetch disabled no --allowedTools flag is sent so headless mode keeps WebFetch denied`() {
        val client = CapturingClient(defaultModel = "", webFetchEnabled = false)
        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        assertEquals(null, allowedToolsArg(client.argv))
    }

    @Test
    fun `web-fetch enabled with no domain allowlist pre-authorises bare WebFetch for any host`() {
        val client = CapturingClient(defaultModel = "", webFetchEnabled = true)
        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        assertEquals("WebFetch", allowedToolsArg(client.argv))
    }

    @Test
    fun `web-fetch enabled with a domain allowlist scopes WebFetch to one rule per host`() {
        val client = CapturingClient(
            defaultModel = "",
            webFetchEnabled = true,
            webFetchAllowedDomains = "news.ycombinator.com, github.com",
        )
        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        assertEquals("WebFetch(domain:news.ycombinator.com),WebFetch(domain:github.com)", allowedToolsArg(client.argv))
    }

    @Test
    fun `with github tools disabled no --mcp-config flag is sent and no mcp rule is authorised`() {
        val client = CapturingClient(defaultModel = "", githubToolsEnabled = false, githubMcpConfig = "/x/.mcp.json")
        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        assertEquals(null, mcpConfigArg(client.argv))
        assertEquals(null, allowedToolsArg(client.argv))
    }

    @Test
    fun `github tools enabled but with a blank config stays inert - no path is ever guessed`() {
        val client = CapturingClient(defaultModel = "", githubToolsEnabled = true, githubMcpConfig = "")
        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        assertEquals(null, mcpConfigArg(client.argv))
        assertEquals(null, allowedToolsArg(client.argv))
    }

    @Test
    fun `github tools enabled with a config mounts the mcp server strictly and authorises its read tools`() {
        val client = CapturingClient(defaultModel = "", githubToolsEnabled = true, githubMcpConfig = "/repo/.mcp.json")
        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        assertEquals("/repo/.mcp.json", mcpConfigArg(client.argv))
        assertTrue(client.argv.contains("--strict-mcp-config"), "config must be loaded strictly (no ambient MCP)")
        assertEquals("mcp__gh-readonly", allowedToolsArg(client.argv))
    }

    @Test
    fun `the authorised mcp rule follows the configured server name`() {
        val client = CapturingClient(
            defaultModel = "",
            githubToolsEnabled = true,
            githubMcpConfig = "/repo/.mcp.json",
            githubMcpServerName = "gh-ro",
        )
        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        assertEquals("mcp__gh-ro", allowedToolsArg(client.argv))
    }

    @Test
    fun `web-fetch and github tools compose - both rules are authorised together`() {
        val client = CapturingClient(
            defaultModel = "",
            webFetchEnabled = true,
            githubToolsEnabled = true,
            githubMcpConfig = "/repo/.mcp.json",
        )
        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        assertEquals("WebFetch,mcp__gh-readonly", allowedToolsArg(client.argv))
    }

    @Test
    fun `with github tools active the system prompt gains the pull-the-PR guidance`() {
        val client = CapturingClient(defaultModel = "", githubToolsEnabled = true, githubMcpConfig = "/repo/.mcp.json")
        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        val sys = systemPromptArg(client.argv)!!
        assertTrue(sys.startsWith("you are sol"), "keeps the persona's own system prompt first:\n$sys")
        assertTrue(sys.contains("read-only GitHub tools"), "tells the persona the tools exist:\n$sys")
        assertTrue(sys.contains("pull the complete change"), "directs it to pull the full PR:\n$sys")
        assertTrue(sys.contains("untrusted text"), "carries the prompt-injection guard:\n$sys")
    }

    @Test
    fun `with github tools off the system prompt is untouched - no guidance about absent tools`() {
        val client = CapturingClient(defaultModel = "", githubToolsEnabled = false)
        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        assertEquals("you are sol", systemPromptArg(client.argv))
    }

    @Test
    fun `github tools enabled but blank config leaves the system prompt untouched`() {
        // The guidance tracks whether the tools are actually MOUNTED, not just the flag — a blank config is inert.
        val client = CapturingClient(defaultModel = "", githubToolsEnabled = true, githubMcpConfig = "")
        client.generate(request(Duration.ofSeconds(10)), CancellationToken())
        assertEquals("you are sol", systemPromptArg(client.argv))
    }

    @Test
    fun `startup logs the github-tools event at INFO carrying the resolved config when mounted`() {
        LogCapture.on(ProcessLlmClient::class.java).use { logs ->
            CapturingClient(defaultModel = "", githubToolsEnabled = true, githubMcpConfig = "/repo/.mcp.json").logStartupTools()
            val e = logs.withEvent("llm.github.tools").single()
            assertEquals(Level.INFO, e.level)
            assertEquals("/repo/.mcp.json", logs.keyValue(e, "config"))
        }
    }

    @Test
    fun `startup stays silent about github tools when they are off`() {
        LogCapture.on(ProcessLlmClient::class.java).use { logs ->
            CapturingClient(defaultModel = "", githubToolsEnabled = false).logStartupTools()
            assertTrue(logs.events.isEmpty(), "a disabled gh-tools integration must stay silent at startup; got: ${logs.events}")
        }
    }

    @Test
    fun `the rendered prompt is delivered on stdin and the parsed result comes back`() {
        // The script proves stdin arrived: it echoes a different result when stdin is non-empty.
        val script = """
            in=${'$'}(cat)
            if [ -n "${'$'}in" ]; then
              printf '%s' '{"is_error":false,"subtype":"success","result":"got-prompt","stop_reason":"end_turn"}'
            else
              printf '%s' '{"is_error":false,"subtype":"success","result":"no-prompt","stop_reason":"end_turn"}'
            fi
        """.trimIndent()
        val resp = ShellClient(script).generate(request(Duration.ofSeconds(10)), CancellationToken())
        assertEquals("got-prompt", resp.text)
    }

    @Test
    fun `a non-zero exit maps to ProcessError carrying the code`() {
        val ex = assertThrows(LlmException.ProcessError::class.java) {
            ShellClient("echo boom >&2; exit 7").generate(request(Duration.ofSeconds(10)), CancellationToken())
        }
        assertEquals(7, ex.exitCode)
    }

    @Test
    fun `a subprocess that outruns the timeout is killed and surfaces Timeout`() {
        assertThrows(LlmException.Timeout::class.java) {
            ShellClient("sleep 5").generate(request(Duration.ofMillis(150)), CancellationToken())
        }
    }

    @Test
    fun `tripping the cancellation token kills the subprocess and surfaces Cancelled`() {
        val token = CancellationToken()
        Thread { Thread.sleep(50); token.cancel() }.apply { isDaemon = true }.start()
        assertThrows(LlmException.Cancelled::class.java) {
            ShellClient("sleep 5").generate(request(Duration.ofSeconds(30)), token)
        }
    }

    // --- logging is IO: pin the generation seam's events (see the bdd-tiered-testing skill) ---

    @Test
    fun `a spawn logs the llm-spawn event at debug with the resolved model`() {
        LogCapture.on(ProcessLlmClient::class.java).use { logs ->
            CapturingClient(defaultModel = "sonnet").generate(request(Duration.ofSeconds(10), personaModel = "opus"), CancellationToken())
            val e = logs.withEvent("llm.spawn").single()
            assertEquals(Level.DEBUG, e.level)
            assertEquals("Sol", logs.keyValue(e, "persona"))
            assertEquals("opus", logs.keyValue(e, "model"))
        }
    }

    @Test
    fun `with no model pinned the spawn event records the cli default`() {
        LogCapture.on(ProcessLlmClient::class.java).use { logs ->
            CapturingClient(defaultModel = "").generate(request(Duration.ofSeconds(10)), CancellationToken())
            assertEquals("(cli default)", logs.keyValue(logs.withEvent("llm.spawn").single(), "model"))
        }
    }

    @Test
    fun `a timeout logs the llm-timeout event at warn carrying the budget`() {
        LogCapture.on(ProcessLlmClient::class.java).use { logs ->
            assertThrows(LlmException.Timeout::class.java) {
                ShellClient("sleep 5").generate(request(Duration.ofMillis(150)), CancellationToken())
            }
            val e = logs.withEvent("llm.timeout").single()
            assertEquals(Level.WARN, e.level)
            assertEquals("Sol", logs.keyValue(e, "persona"))
            assertEquals("150", logs.keyValue(e, "timeoutMs"))
        }
    }

    @Test
    fun `a cancellation logs the llm-cancelled event at info`() {
        LogCapture.on(ProcessLlmClient::class.java).use { logs ->
            val token = CancellationToken()
            Thread { Thread.sleep(50); token.cancel() }.apply { isDaemon = true }.start()
            assertThrows(LlmException.Cancelled::class.java) {
                ShellClient("sleep 5").generate(request(Duration.ofSeconds(30)), token)
            }
            val e = logs.withEvent("llm.cancelled").single()
            assertEquals(Level.INFO, e.level)
            assertEquals("Sol", logs.keyValue(e, "persona"))
        }
    }
}
