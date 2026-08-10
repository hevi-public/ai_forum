package com.aiforum.tier1.client

import ch.qos.logback.classic.Level
import com.aiforum.config.JailProperties
import com.aiforum.llm.CommandRunner
import com.aiforum.llm.ExecResult
import com.aiforum.llm.JailLauncher
import com.aiforum.llm.JailRuntime
import com.aiforum.testsupport.LogCapture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Tier-1: [JailRuntime]'s conversation with the host — the boot-time docker sequence, the generated
 * config/env files, and the container kill. The [CommandRunner] port is the seam (a recording fake), and
 * the HOME the runtime writes under is redirected into a @TempDir, so nothing here touches the real
 * `~/.ai_forum`, the real `~/.claude`, or a Docker daemon.
 *
 * What is deliberately NOT re-asserted here: the CONTENT of each argv. That is [JailLauncher]'s pure
 * output, pinned in tier0/JailLauncherTest — this tier proves the runtime issues those argvs, in that
 * order, under the right conditions.
 */
@Tag("tier1")
class JailRuntimeTest {

    /**
     * Records every command and answers from a scripted queue of exit codes (default: success).
     *
     * One scripted answer is seeded rather than left to the default: `network inspect` reports `true`, i.e.
     * an existing network that really is `--internal`. A bare success with empty stdout is the *hostile*
     * case (a network of the right name with no `--internal`), which is fatal — so leaving it to the
     * default would make every case below a test of the failure path.
     */
    private class RecordingRunner(
        private val exits: MutableMap<String, ExecResult> = mutableMapOf(
            "network inspect" to ExecResult(0, "true\n", ""),
        ),
    ) : CommandRunner {
        val calls = mutableListOf<List<String>>()
        val timeouts = mutableListOf<Long>()

        fun answer(match: String, result: ExecResult) = apply { exits[match] = result }

        override fun run(argv: List<String>, timeoutMillis: Long): ExecResult {
            calls += argv
            timeouts += timeoutMillis
            val key = exits.keys.firstOrNull { argv.joinToString(" ").contains(it) }
            return key?.let { exits.getValue(it) } ?: ExecResult(0, "", "")
        }

        /** The recorded calls as "docker network create …" strings, for order assertions. */
        fun lines(): List<String> = calls.map { it.joinToString(" ") }
    }

    /**
     * Fails the first [failures] `docker kill` calls, then answers success — the shape of the cancel race:
     * a kill issued before the daemon has created the container reports "No such container" and stops
     * nothing.
     */
    private class FlakyKillRunner(private val failures: Int) : CommandRunner {
        val calls = mutableListOf<List<String>>()
        val timeouts = mutableListOf<Long>()
        private var kills = 0

        override fun run(argv: List<String>, timeoutMillis: Long): ExecResult {
            calls += argv
            timeouts += timeoutMillis
            val isKill = argv.getOrNull(1) == "kill"
            return if (isKill && ++kills <= failures) {
                ExecResult(1, "", "Error response from daemon: No such container: ${argv.last()}")
            } else {
                ExecResult(0, "", "")
            }
        }
    }

    private val props = JailProperties(enabled = true)

    private fun runtime(
        home: File,
        runner: CommandRunner,
        githubTools: Boolean = false,
        githubMcpConfig: String = "",
        env: Map<String, String> = emptyMap(),
        p: JailProperties = props,
    ) = JailRuntime(p, githubTools, githubMcpConfig, runner, home.absolutePath) { env[it] }

    private fun stateDir(home: File) = File(File(home, ".ai_forum"), "jail")

    // --- the boot sequence -------------------------------------------------------------------------

    @Test
    fun `startup builds the topology in order - network, then proxy, then an image check`(@TempDir home: File) {
        val runner = RecordingRunner().answer("network inspect", ExecResult(1, "", "No such network"))
        runtime(home, runner).start()

        assertEquals(
            listOf(
                JailLauncher.networkInspectArgv(props),
                JailLauncher.networkCreateArgv(props),
                JailLauncher.proxyRemoveArgv(props),
                JailLauncher.proxyRunArgv(props, File(stateDir(home), "squid.conf").absolutePath),
                JailLauncher.proxyConnectArgv(props),
                JailLauncher.imageInspectArgv(props),
            ),
            runner.calls,
        )
    }

    @Test
    fun `an existing internal network is reused rather than recreated`(@TempDir home: File) {
        // `docker network create` on an existing name is an error, not a no-op — so the inspect decides.
        // It has to answer "true": reuse is conditional on the network really being --internal.
        val runner = RecordingRunner().answer("network inspect", ExecResult(0, "true\n", ""))
        runtime(home, runner).start()
        assertTrue(runner.lines().none { it.contains("network create") }, runner.lines().toString())
        assertTrue(runner.lines().any { it.contains("network connect") }, runner.lines().toString())
    }

    @Test
    fun `a pre-existing network of the right name that is NOT internal is fatal, before any proxy work`(@TempDir home: File) {
        // The name is not the guarantee — `--internal` is. A network someone else created without it has a
        // gateway, so every container on it routes off the host directly and the squid sidecar degrades to
        // a politeness a prompt-injected persona may simply ignore. Reusing it silently would forfeit the
        // entire egress-by-topology design, so boot stops and names the remedy: we do NOT auto-recreate,
        // because `docker network rm` fails while containers are attached.
        val runner = RecordingRunner().answer("network inspect", ExecResult(0, "false\n", ""))
        LogCapture.on(JailRuntime::class.java).use { logs ->
            runtime(home, runner).start()   // still never throws — a Docker problem can't take the app down
            val e = logs.withEvent("llm.jail.startup_failed").single()
            assertEquals(Level.ERROR, e.level)
            assertTrue(
                logs.keyValue(e, "reason")!!.contains("docker network rm aiforum-jail-net"),
                "the operator must be told what to remove: ${logs.keyValue(e, "reason")}",
            )
        }
        // Nothing beyond the inspect: a proxy started against a network with a route out would look healthy.
        assertEquals(listOf(JailLauncher.networkInspectArgv(props)), runner.calls, runner.lines().toString())
    }

    @Test
    fun `the proxy is torn down and rebuilt every boot so a changed allowlist takes effect`(@TempDir home: File) {
        val runner = RecordingRunner()
        runtime(home, runner).start()
        val remove = runner.lines().indexOfFirst { it.contains("docker rm -f") }
        val run = runner.lines().indexOfFirst { it.contains("docker run -d") }
        assertTrue(remove in 0..<run, "the old proxy must go before the new one starts: ${runner.lines()}")
    }

    // --- the files it generates ---------------------------------------------------------------------

    @Test
    fun `the squid config on disk is exactly what the pure generator produced`(@TempDir home: File) {
        val p = props.copy(egressAllowlist = listOf("api.anthropic.com", ".github.com"))
        runtime(home, RecordingRunner(), p = p).start()
        assertEquals(
            JailLauncher.squidConf(p.egressAllowlist),
            File(stateDir(home), "squid.conf").readText(),
        )
    }

    @Test
    fun `a claude token in the app's environment is forwarded through an owner-only env file`(@TempDir home: File) {
        val runtime = runtime(home, RecordingRunner(), env = mapOf("CLAUDE_CODE_OAUTH_TOKEN" to "sk-ant-oat-xyz"))
        runtime.start()

        val envFile = File(stateDir(home), "jail.env")
        assertEquals("CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat-xyz\n", envFile.readText())
        assertEquals(envFile.absolutePath, runtime.invocation().envFile)
        // 0600: the file holds a bearer token, and ~/.ai_forum is not a secret store.
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(envFile.toPath()),
        )
    }

    @Test
    fun `a gh token is fetched only when the persona github tools are actually mounted`(@TempDir home: File) {
        val off = RecordingRunner().answer("gh auth token", ExecResult(0, "gho_secret\n", ""))
        runtime(home, off, githubTools = false, githubMcpConfig = "/repo/.mcp.json").start()
        assertTrue(off.lines().none { it.startsWith("gh ") }, "don't hand out a token nothing will use: ${off.lines()}")
        assertNull(runtime(home, off, githubTools = false).invocation().envFile)

        val on = RecordingRunner().answer("gh auth token", ExecResult(0, "gho_secret\n", ""))
        val runtime = runtime(home, on, githubTools = true, githubMcpConfig = "/repo/.mcp.json")
        runtime.start()
        assertEquals("GH_TOKEN=gho_secret\n", File(stateDir(home), "jail.env").readText())
        assertEquals(File(stateDir(home), "jail.env").absolutePath, runtime.invocation().envFile)
    }

    @Test
    fun `the gh switch alone mounts nothing, so on its own it buys no token`(@TempDir home: File) {
        // "Enabled" is only half of it: ProcessLlmClient.githubToolsActive requires the switch AND a
        // --mcp-config, so with the config blank the jail carries no gh tools whatsoever. Fetching a token
        // then would put a live GitHub credential into the one file inside the container a prompt-injected
        // persona can read, to be spent by tools that aren't there.
        val runner = RecordingRunner().answer("gh auth token", ExecResult(0, "gho_secret\n", ""))
        val runtime = runtime(home, runner, githubTools = true, githubMcpConfig = "")
        runtime.start()

        assertTrue(runner.lines().none { it.startsWith("gh ") }, "no tools mounted, no token: ${runner.lines()}")
        assertFalse(File(stateDir(home), "jail.env").exists(), "nothing to forward means no env file at all")
        assertNull(runtime.invocation().envFile)
    }

    @Test
    fun `an unauthenticated gh leaves no env file rather than an empty one`(@TempDir home: File) {
        val runner = RecordingRunner().answer("gh auth token", ExecResult(1, "", "not logged in"))
        val runtime = runtime(home, runner, githubTools = true, githubMcpConfig = "/repo/.mcp.json")
        runtime.start()
        assertFalse(File(stateDir(home), "jail.env").exists(), "an empty --env-file is a docker error, not a no-op")
        assertNull(runtime.invocation().envFile)
    }

    @Test
    fun `a stale env file from a previous boot is deleted when the tokens are gone`(@TempDir home: File) {
        stateDir(home).mkdirs()
        File(stateDir(home), "jail.env").writeText("CLAUDE_CODE_OAUTH_TOKEN=stale\n")
        runtime(home, RecordingRunner(), env = emptyMap()).start()
        assertFalse(File(stateDir(home), "jail.env").exists(), "a revoked token must not outlive its boot")
    }

    // --- what an invocation carries -----------------------------------------------------------------

    @Test
    fun `the credential is mounted only when the file really exists on this host`(@TempDir home: File) {
        val runtime = runtime(home, RecordingRunner())
        // A Keychain-based macOS host has no ~/.claude/.credentials.json at all — and naming a missing
        // path in `docker run -v` would have docker create a host DIRECTORY there.
        assertNull(runtime.invocation().credentialFile)

        val credential = File(File(home, ".claude"), ".credentials.json")
        credential.parentFile.mkdirs()
        credential.writeText("{}")
        assertEquals(credential.absolutePath, runtime.invocation().credentialFile)
    }

    @Test
    fun `an explicitly configured credential path wins over the home-directory default`(@TempDir home: File) {
        val custom = File(home, "elsewhere.json").apply { writeText("{}") }
        val runtime = runtime(home, RecordingRunner(), p = props.copy(credentialFile = custom.absolutePath))
        assertEquals(custom.absolutePath, runtime.invocation().credentialFile)
    }

    // --- the cancel path -----------------------------------------------------------------------------

    @Test
    fun `killing a container names it and is bounded to seconds, not the docker control-plane timeout`(@TempDir home: File) {
        val runner = RecordingRunner()
        runtime(home, runner).killContainer("aiforum-jail-abc")
        // One kill, because it worked; the rm still follows (see the retry case below for why it always
        // does), and against a `--rm` container it is a harmless no-such-container error.
        assertEquals(
            listOf(
                listOf("docker", "kill", "aiforum-jail-abc"),
                listOf("docker", "rm", "-f", "aiforum-jail-abc"),
            ),
            runner.calls,
        )
        // This runs on a generation thread mid-cancel; a minute-long wait there would be the hang the
        // whole runaway-proof await loop exists to prevent.
        assertTrue(runner.timeouts.all { it <= 5_000 }, "kill bounds were ${runner.timeouts}")
    }

    @Test
    fun `a kill the daemon has nothing for yet is retried, and a rm -f always follows`(@TempDir home: File) {
        // The race this exists for: a cancel can fire in the window between `docker run` starting and the
        // daemon having created the container, where `docker kill` fails with "No such container" — and,
        // swallowed, that leaves the container free to materialise and run the persona unattended. So the
        // kill is retried, and a `docker rm -f` ALWAYS follows: it removes a container that appeared after
        // the last retry, and the `Created` husk a killed-too-early run leaves behind. On the normal path
        // `--rm` has already removed it and the rm is a harmless error, swallowed like the rest.
        val runner = FlakyKillRunner(failures = 1)
        runtime(home, runner).killContainer("aiforum-jail-abc")

        assertEquals(
            listOf(
                listOf("docker", "kill", "aiforum-jail-abc"),
                listOf("docker", "kill", "aiforum-jail-abc"),
                listOf("docker", "rm", "-f", "aiforum-jail-abc"),
            ),
            runner.calls,
        )
        // Still the cancel path: every call short-bounded, and the whole retry budget stays in seconds.
        assertTrue(runner.timeouts.all { it <= 5_000 }, "kill bounds were ${runner.timeouts}")
    }

    @Test
    fun `a docker daemon that is not there fails the boot loudly and never the app`(@TempDir home: File) {
        val runner = RecordingRunner()
            .answer("network inspect", ExecResult(1, "", "cannot connect"))
            .answer("network create", ExecResult(1, "", "Cannot connect to the Docker daemon"))
        LogCapture.on(JailRuntime::class.java).use { logs ->
            runtime(home, runner).start()   // must not throw
            val e = logs.withEvent("llm.jail.startup_failed").single()
            assertEquals(Level.ERROR, e.level)
            assertTrue(logs.keyValue(e, "reason")!!.contains("Cannot connect to the Docker daemon"), logs.keyValue(e, "reason")!!)
        }
    }

    // --- logging is IO ---------------------------------------------------------------------------------

    @Test
    fun `a healthy startup reports the allowlist and the credential mode at info`(@TempDir home: File) {
        LogCapture.on(JailRuntime::class.java).use { logs ->
            runtime(home, RecordingRunner(), env = mapOf("CLAUDE_CODE_OAUTH_TOKEN" to "sk-ant-oat-xyz")).start()
            val e = logs.withEvent("llm.jail.ready").single()
            assertEquals(Level.INFO, e.level)
            assertEquals("api.anthropic.com,api.github.com", logs.keyValue(e, "allowlist"))
            assertEquals("token", logs.keyValue(e, "credentialMode"))
            assertEquals("aiforum-claude-jail", logs.keyValue(e, "image"))
            // The token itself must never reach the log — only the MODE does.
            assertTrue(logs.events.none { it.formattedMessage.contains("sk-ant-oat-xyz") }, logs.events.toString())
        }
    }

    @Test
    fun `a missing jail image warns with the command that builds it`(@TempDir home: File) {
        val runner = RecordingRunner().answer("image inspect", ExecResult(1, "", "No such image"))
        LogCapture.on(JailRuntime::class.java).use { logs ->
            runtime(home, runner).start()
            val e = logs.withEvent("llm.jail.image_missing").single()
            assertEquals(Level.WARN, e.level)
            assertEquals(
                "docker build -t aiforum-claude-jail -f docker/claude-jail/Dockerfile .",
                logs.keyValue(e, "build"),
            )
            // Still a warning, not a failure: the operator may be about to build it.
            assertTrue(logs.withEvent("llm.jail.ready").isNotEmpty(), "boot continues past a missing image")
        }
    }
}
