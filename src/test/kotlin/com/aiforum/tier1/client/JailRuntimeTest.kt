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

    /** Records every command and answers from a scripted queue of exit codes (default: success). */
    private class RecordingRunner(private val exits: MutableMap<String, ExecResult> = mutableMapOf()) : CommandRunner {
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

    private val props = JailProperties(enabled = true)

    private fun runtime(
        home: File,
        runner: RecordingRunner,
        githubTools: Boolean = false,
        env: Map<String, String> = emptyMap(),
        p: JailProperties = props,
    ) = JailRuntime(p, githubTools, runner, home.absolutePath) { env[it] }

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
    fun `an existing network is reused rather than recreated`(@TempDir home: File) {
        // `docker network create` on an existing name is an error, not a no-op — so the inspect decides.
        val runner = RecordingRunner()   // inspect succeeds
        runtime(home, runner).start()
        assertTrue(runner.lines().none { it.contains("network create") }, runner.lines().toString())
        assertTrue(runner.lines().any { it.contains("network connect") }, runner.lines().toString())
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
        runtime(home, off, githubTools = false).start()
        assertTrue(off.lines().none { it.startsWith("gh ") }, "don't hand out a token nothing will use: ${off.lines()}")
        assertNull(runtime(home, off, githubTools = false).invocation().envFile)

        val on = RecordingRunner().answer("gh auth token", ExecResult(0, "gho_secret\n", ""))
        val runtime = runtime(home, on, githubTools = true)
        runtime.start()
        assertEquals("GH_TOKEN=gho_secret\n", File(stateDir(home), "jail.env").readText())
        assertEquals(File(stateDir(home), "jail.env").absolutePath, runtime.invocation().envFile)
    }

    @Test
    fun `an unauthenticated gh leaves no env file rather than an empty one`(@TempDir home: File) {
        val runner = RecordingRunner().answer("gh auth token", ExecResult(1, "", "not logged in"))
        val runtime = runtime(home, runner, githubTools = true)
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
        assertEquals(listOf(listOf("docker", "kill", "aiforum-jail-abc")), runner.calls)
        // This runs on a generation thread mid-cancel; a minute-long wait there would be the hang the
        // whole runaway-proof await loop exists to prevent.
        assertTrue(runner.timeouts.single() <= 5_000, "kill bound was ${runner.timeouts.single()}ms")
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
