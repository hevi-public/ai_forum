package com.aiforum.tier1.client

import com.aiforum.config.JailProperties
import com.aiforum.llm.CancellationToken
import com.aiforum.llm.CommandRunner
import com.aiforum.llm.ContextComment
import com.aiforum.llm.ExecResult
import com.aiforum.llm.JailRuntime
import com.aiforum.llm.LlmException
import com.aiforum.llm.LlmRequest
import com.aiforum.llm.PersonaRef
import com.aiforum.llm.ProcessLlmClient
import com.aiforum.llm.PromptContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

/**
 * Tier-1: what [ProcessLlmClient] actually SPAWNS once the jail is switched on, and what it kills when a
 * jailed run is cancelled. Same seam as ProcessLlmClientTest — a subclass overrides `spawn` — so no
 * container ever starts; the jail's own argv content is pinned pure in tier0/JailLauncherTest.
 *
 * The third case is the one that makes the feature safe to ship on by default *off*: with
 * `jail.enabled = false` the spawned argv must be byte-identical to the pre-jail client's. It is a
 * regression pin, not a tautology — remove the `if (!jail.enabled) return spawn(argv)` short-circuit in
 * launchClaude and it reddens.
 */
@Tag("tier1")
class JailedProcessLlmClientTest {

    /** Records what would have been executed, and answers every docker call with success. */
    private class RecordingRunner : CommandRunner {
        val calls = mutableListOf<List<String>>()
        override fun run(argv: List<String>, timeoutMillis: Long): ExecResult {
            calls += argv
            return ExecResult(0, "", "")
        }
    }

    /** Captures the spawned argv; runs a scripted shell instead of docker. */
    private open class CapturingClient(
        jail: JailProperties,
        jailRuntime: JailRuntime?,
        githubMcpConfig: String = "",
        private val script: String = SUCCESS,
    ) : ProcessLlmClient(
        command = "claude",
        defaultModel = "",
        workingDir = "",
        rateLimitRetryAfterSeconds = 300,
        pollMillis = 5,
        githubToolsEnabled = githubMcpConfig.isNotBlank(),
        githubMcpConfig = githubMcpConfig,
        jail = jail,
        jailRuntime = jailRuntime,
    ) {
        var argv: List<String> = emptyList()
        override fun spawn(argv: List<String>): Process {
            this.argv = argv
            return ProcessBuilder("/bin/sh", "-c", script).start()
        }

        companion object {
            const val SUCCESS =
                "printf '%s' '{\"is_error\":false,\"subtype\":\"success\",\"result\":\"ok\",\"stop_reason\":\"end_turn\"}'"
        }
    }

    private fun request() = LlmRequest(
        context = PromptContext(
            "you are sol",
            listOf(ContextComment(id = "c1", authorId = "sol", body = "indexes help here", parentId = null, depth = 0)),
        ),
        persona = PersonaRef("sol", "Sol", "opus"),
        timeout = Duration.ofSeconds(10),
    )

    private fun runtime(home: File, runner: CommandRunner, p: JailProperties) =
        JailRuntime(p, false, "", runner, home.absolutePath) { null }

    private fun value(argv: List<String>, flag: String): String? =
        argv.indexOf(flag).takeIf { it >= 0 }?.let { argv.getOrNull(it + 1) }

    @Test
    fun `with the jail on, the spawned process is docker and the claude command rides inside it`(@TempDir home: File) {
        val jail = JailProperties(enabled = true)
        val client = CapturingClient(jail, runtime(home, RecordingRunner(), jail), githubMcpConfig = "/repo/.mcp.json")
        client.generate(request(), CancellationToken())

        assertEquals(listOf("docker", "run"), client.argv.take(2))
        assertEquals("aiforum-jail-net", value(client.argv, "--network"))
        assertTrue(client.argv.contains("--read-only"), client.argv.toString())
        // The request the caller composed survives intact behind the wrapper — same system prompt, same
        // model, same tool authorisation, with only the mcp-config path relocated into the image.
        val tail = client.argv.drop(client.argv.indexOf("aiforum-claude-jail") + 1)
        assertEquals(listOf("timeout", "--kill-after=10", "900", "claude", "-p"), tail.take(5))
        assertEquals("you are sol\n\n" + ghGuidancePrefix(), (value(tail, "--system-prompt") ?: "").take(guidanceLength()))
        assertEquals("opus", value(tail, "--model"))
        assertEquals("mcp__gh-readonly", value(tail, "--allowedTools"))
        assertTrue(value(tail, "--mcp-config")!!.contains("/opt/aiforum/mcp/gh-readonly/server.mjs"), tail.toString())
    }

    @Test
    fun `cancelling a jailed generation kills the container by name, not just the docker client`(@TempDir home: File) {
        val jail = JailProperties(enabled = true)
        val runner = RecordingRunner()
        val client = CapturingClient(jail, runtime(home, runner, jail), script = "sleep 5")

        val token = CancellationToken()
        Thread { Thread.sleep(50); token.cancel() }.apply { isDaemon = true }.start()
        assertThrows(LlmException.Cancelled::class.java) { client.generate(request(), token) }

        // The container the run STARTED is the container the cancel KILLS — destroying the local
        // `docker run` client would leave the jailed persona running with its whole memory reservation.
        // The trailing `rm -f` is the catch for a cancel that beat the daemon to creating it (JailRuntime
        // .killContainer); here the kill succeeds first time, so there is no retry to see.
        val started = value(client.argv, "--name")
        assertEquals(
            listOf(listOf("docker", "kill", started), listOf("docker", "rm", "-f", started)),
            runner.calls,
        )
    }

    @Test
    fun `with the jail off the spawned argv is byte-identical to a client that has no jail at all`() {
        val jailed = CapturingClient(JailProperties(enabled = false), null, githubMcpConfig = "/repo/.mcp.json")
        val unjailed = object : ProcessLlmClient(
            command = "claude",
            defaultModel = "",
            workingDir = "",
            rateLimitRetryAfterSeconds = 300,
            pollMillis = 5,
            githubToolsEnabled = true,
            githubMcpConfig = "/repo/.mcp.json",
        ) {
            var argv: List<String> = emptyList()
            override fun spawn(argv: List<String>): Process {
                this.argv = argv
                return ProcessBuilder("/bin/sh", "-c", CapturingClient.SUCCESS).start()
            }
        }

        jailed.generate(request(), CancellationToken())
        unjailed.generate(request(), CancellationToken())

        assertEquals(unjailed.argv, jailed.argv)
        assertEquals("claude", jailed.argv.first())
    }

    @Test
    fun `the jail enabled without a runtime refuses to run the persona on the host`() {
        // Fail CLOSED. An operator who switched containment on must never silently get the un-jailed
        // spawn back because a bean didn't wire.
        val client = CapturingClient(JailProperties(enabled = true), null)
        val ex = assertThrows(LlmException.ProcessError::class.java) { client.generate(request(), CancellationToken()) }
        assertEquals(127, ex.exitCode)
        assertEquals(emptyList<String>(), client.argv, "nothing may be spawned at all")
    }

    // The gh guidance is appended by the production client; we only need its opening to prove the system
    // prompt crossed unchanged, so keep the comparison to a stable prefix rather than restating the text.
    private fun ghGuidancePrefix() = "You have read-only GitHub tools available"
    private fun guidanceLength() = ("you are sol\n\n" + ghGuidancePrefix()).length
}
