package com.aiforum.llm

import com.aiforum.config.JailProperties
import com.aiforum.observability.event
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

/** The result of one host command — the whole surface [CommandRunner] exposes. */
data class ExecResult(val exit: Int, val stdout: String, val stderr: String)

/**
 * The IO port for "run a host command and tell me what happened" — deliberately tiny, because it is the
 * seam the jail's Tier-1 test fakes. Everything the runtime *decides* is argv built by [JailLauncher]
 * (pure, Tier-0) and everything it *does* goes through here, so the recording fake sees the whole
 * conversation with Docker.
 */
fun interface CommandRunner {
    fun run(argv: List<String>, timeoutMillis: Long): ExecResult
}

/** The production [CommandRunner]: a bounded [ProcessBuilder] that can neither hang nor leak a child. */
object RealCommandRunner : CommandRunner {
    override fun run(argv: List<String>, timeoutMillis: Long): ExecResult {
        val process = ProcessBuilder(argv).start()
        process.outputStream.close()   // nothing to say on stdin; let the child see EOF immediately
        val out = process.inputStream.bufferedReader()
        val err = process.errorStream.bufferedReader()
        // Read on daemon threads so a chatty child can't deadlock us on a full pipe while we wait.
        val outTask = readAsync(out)
        val errTask = readAsync(err)
        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
        }
        return ExecResult(
            exit = if (finished) process.exitValue() else TIMED_OUT,
            stdout = outTask.get(),
            stderr = errTask.get(),
        )
    }

    /** Distinct from any real docker exit code, so a caller can tell "took too long" from "said no". */
    const val TIMED_OUT = -1

    private fun readAsync(reader: java.io.BufferedReader): java.util.concurrent.FutureTask<String> =
        java.util.concurrent.FutureTask { reader.use { it.readText() } }
            .also { Thread(it).apply { isDaemon = true }.start() }
}

/**
 * The IMPURE half of the jail (design `plan_docs/llm-sandbox.md` §2): it owns the host state the pure
 * [JailLauncher] only describes — the internal network, the squid sidecar, the generated config and env
 * files, and the credential resolution — plus the container kill the cancel path needs.
 *
 * Only wires when the jail is switched ON, and never under `test`: the acceptance suite must never so
 * much as look for a Docker daemon. Consequently **nothing here is on the default path**, and nothing
 * here is allowed to be fatal — a broken Docker setup logs `llm.jail.startup_failed` and the app boots.
 * (Generation then fails loudly per-invocation, which is the correct trade: with the jail requested,
 * failing closed beats silently running the persona on the host.)
 *
 * Runs at [ApplicationReadyEvent] rather than in a constructor so a slow `docker network create` can
 * never stall context refresh.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "aiforum.llm.jail", name = ["enabled"], havingValue = "true")
class JailRuntime(
    private val props: JailProperties,
    private val githubToolsEnabled: Boolean,
    /**
     * The same `aiforum.llm.github-mcp-config` [ProcessLlmClient] reads. Both halves are needed here for the
     * same reason they are needed there: blank config means no `--mcp-config` on the argv, which means no gh
     * tools in the container — see [githubToolsActive].
     */
    private val githubMcpConfig: String = "",
    private val runner: CommandRunner = RealCommandRunner,
    /** Seam: the tests point this at a temp dir so they never touch the real `~/.ai_forum` or `~/.claude`. */
    private val homeDir: String = System.getProperty("user.home"),
    /** Seam: the app's own environment, read for the tokens the jail forwards. */
    private val env: (String) -> String? = { System.getenv(it) },
) {

    /**
     * The constructor Spring uses. Written out explicitly rather than relying on Kotlin default
     * arguments during autowiring: [CommandRunner] and the two seams above have no beans, and this bean
     * only ever materialises on an operator's machine with the jail switched on — the one place a
     * resolution surprise would be discovered late.
     */
    @Autowired
    constructor(
        props: JailProperties,
        @Value("\${aiforum.llm.github-tools-enabled:false}") githubToolsEnabled: Boolean,
        @Value("\${aiforum.llm.github-mcp-config:}") githubMcpConfig: String,
    ) : this(props, githubToolsEnabled, githubMcpConfig, RealCommandRunner)

    /**
     * Whether a jailed run will really carry gh tools — the same conjunction [ProcessLlmClient] applies
     * when it decides to emit `--mcp-config`. Kept in step deliberately: the switch on its own mounts
     * nothing, and a token forwarded for tools that don't exist is a live credential sitting in the
     * container for no purpose.
     */
    private fun githubToolsActive(): Boolean = githubToolsEnabled && githubMcpConfig.isNotBlank()

    private val log = LoggerFactory.getLogger(JailRuntime::class.java)

    private companion object {
        const val EV_READY = "llm.jail.ready"
        const val EV_STARTUP_FAILED = "llm.jail.startup_failed"
        const val EV_IMAGE_MISSING = "llm.jail.image_missing"

        /** Docker control-plane calls: generous, they run once at boot. */
        const val DOCKER_TIMEOUT_MILLIS = 60_000L
        /** The cancel path runs on a generation thread — `docker kill` is fast or it is not happening. */
        const val KILL_TIMEOUT_MILLIS = 2_000L
        /** Enough attempts to outlast container creation losing a race with cancel; few enough to stay a blink. */
        const val KILL_ATTEMPTS = 3
        /** Total retry sleep is bounded at ~1s — this is a generation thread mid-cancel, not a background job. */
        const val KILL_RETRY_MILLIS = 500L
        /** `gh auth token` shells out to the host CLI; short, and failure is a non-event. */
        const val GH_TIMEOUT_MILLIS = 10_000L

        val TOKEN_VARS = listOf("CLAUDE_CODE_OAUTH_TOKEN")
    }

    /** `~/.ai_forum/jail` — generated, app-owned, rewritten every boot. */
    private val stateDir = File(File(homeDir, ".ai_forum"), "jail")
    private val squidConfFile = File(stateDir, "squid.conf")
    private val envFile = File(stateDir, "jail.env")

    /**
     * Build the topology the jail runs in, then report. Never throws: see the class KDoc — a Docker
     * problem must not be able to take the app down at boot.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        runCatching { bootstrap() }.onFailure { e ->
            log.atError().setMessage("LLM jail startup failed — jailed generations will fail until Docker is healthy: {}")
                .addArgument(e.toString())
                .event(EV_STARTUP_FAILED).addKeyValue("reason", e.toString())
                .log()
        }
    }

    private fun bootstrap() {
        stateDir.mkdirs()
        val inv = writeSideChannels()
        squidConfFile.writeText(JailLauncher.squidConf(props.egressAllowlist))

        // The network first: the proxy is attached to it, and `docker network create` is idempotent only
        // in the sense that a second call errors — so inspect, then create if genuinely absent.
        //
        // The inspect reports `{{.Internal}}`, not merely existence, because the NAME is not the guarantee:
        // a pre-existing network of this name that was created WITHOUT `--internal` has a gateway, and
        // reusing it would hand every jailed container a direct route off the host — the proxy would still
        // start, still filter, and mean nothing, because nothing would have to go through it. That is the
        // whole egress-by-topology design forfeited silently, so it is fatal rather than a warning. We do
        // not auto-recreate: `docker network rm` fails while containers are attached, so the operator has to
        // decide what to do with them.
        val existing = docker(JailLauncher.networkInspectArgv(props))
        if (existing.exit == 0) {
            require(existing.stdout.trim() == "true") {
                "docker network ${props.network} exists but is NOT --internal, so containers on it would " +
                    "have a route off the host and the egress proxy would be optional. Remove it with " +
                    "`docker network rm ${props.network}` (detach anything using it first) and restart."
            }
        } else {
            val created = docker(JailLauncher.networkCreateArgv(props))
            require(created.exit == 0) { "docker network create failed: ${created.stderr.trim()}" }
        }
        // The proxy is recreated every boot, because the allowlist may have changed in yml and its
        // config is baked in at start. Removal failing just means it wasn't there.
        docker(JailLauncher.proxyRemoveArgv(props))
        val started = docker(JailLauncher.proxyRunArgv(props, squidConfFile.absolutePath))
        require(started.exit == 0) { "docker run (egress proxy) failed: ${started.stderr.trim()}" }
        val connected = docker(JailLauncher.proxyConnectArgv(props))
        require(connected.exit == 0) { "docker network connect (egress proxy) failed: ${connected.stderr.trim()}" }

        if (docker(JailLauncher.imageInspectArgv(props)).exit != 0) {
            log.atWarn().setMessage("LLM jail image {} is missing — build it with: {}")
                .addArgument(props.image).addArgument(buildCommand())
                .event(EV_IMAGE_MISSING).addKeyValue("image", props.image).addKeyValue("build", buildCommand())
                .log()
        }

        log.atInfo().setMessage("LLM jail ready: image {}, egress {}, credential mode {}")
            .addArgument(props.image).addArgument(props.egressAllowlist.joinToString(",")).addArgument(credentialMode(inv))
            .event(EV_READY)
            .addKeyValue("image", props.image)
            .addKeyValue("allowlist", props.egressAllowlist.joinToString(","))
            .addKeyValue("credentialMode", credentialMode(inv))
            .log()
    }

    private fun buildCommand() =
        "docker build -t ${props.image} -f docker/claude-jail/Dockerfile ."

    /** What an operator needs to see in one word when a jailed run comes back unauthenticated. */
    private fun credentialMode(inv: JailInvocation): String = when {
        inv.credentialFile != null && inv.envFile != null -> "file+token"
        inv.credentialFile != null -> "file"
        inv.envFile != null -> "token"
        else -> "none"
    }

    /**
     * Write `jail.env` with whatever auth the host can hand over, at owner-only permissions.
     *
     * An env FILE rather than `-e NAME=value` because argv is world-readable through `ps`. The tokens
     * themselves are read from the app's own environment (claude) and from `gh auth token` (GitHub, and
     * only when the persona gh tools are ACTUALLY mounted — [githubToolsActive], both halves, not just the
     * switch: we don't hand out a token nothing will use). Nothing to write => no file and no `--env-file`.
     */
    private fun writeSideChannels(): JailInvocation {
        val vars = buildList {
            TOKEN_VARS.forEach { name -> env(name)?.takeIf { it.isNotBlank() }?.let { add("$name=$it") } }
            if (githubToolsActive()) {
                val token = runner.run(listOf("gh", "auth", "token"), GH_TIMEOUT_MILLIS)
                if (token.exit == 0 && token.stdout.isNotBlank()) add("GH_TOKEN=${token.stdout.trim()}")
            }
        }
        if (vars.isEmpty()) {
            envFile.delete()   // a stale file from a previous boot must not outlive the token it held
        } else {
            // Perms BEFORE content: writeText-then-chmod leaves the tokens world-readable for the umask
            // window on a first boot. Creating empty, restricting, then writing means the window only
            // ever exposes an empty file.
            envFile.writeText("")
            runCatching {
                Files.setPosixFilePermissions(
                    envFile.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
            envFile.writeText(vars.joinToString("\n", postfix = "\n"))
        }
        return invocation()
    }

    /**
     * What this invocation may carry across the boundary. Re-resolved per call (two `stat`s) rather than
     * cached at boot, so logging in — or out — takes effect on the next generation instead of the next
     * app restart.
     */
    fun invocation(): JailInvocation = JailInvocation(
        credentialFile = credentialPath().takeIf { File(it).isFile },
        envFile = envFile.absolutePath.takeIf { envFile.isFile },
    )

    private fun credentialPath(): String =
        props.credentialFile.ifBlank { File(File(homeDir, ".claude"), ".credentials.json").absolutePath }

    /**
     * Stop a jailed container by name. Best-effort and short-bounded on purpose: this runs on the cancel
     * and timeout paths, where the caller is already destroying the `docker run` client — which the
     * daemon does not treat as a reason to stop the container, hence this call existing at all.
     *
     * Two ways a single `docker kill` silently does nothing, both of which leave a persona running or a
     * husk behind, and neither of which the swallowed exit code would have told us about:
     *
     * - **Cancel wins the race with container creation.** `docker run` has been spawned but the daemon has
     *   not created the container yet, so the kill reports "No such container" and stops nothing — and the
     *   container then materialises and runs the persona unattended with its whole memory reservation. The
     *   short retry covers that window.
     * - **A container killed before it ran stays as a `Created` husk.** `--rm` only cleans up after a
     *   container that actually started, so the trailing [JailLauncher.rmContainerArgv] is what removes it.
     *
     * The `rm -f` therefore runs ALWAYS, not just after a failed kill: it is also the last catch for a
     * container that appeared after the final retry. On the normal path `--rm` got there first and the rm
     * is a no-such-container error, swallowed with everything else — nothing here may throw into a cancel.
     *
     * The happy path is one fast call plus one fast error. The retry budget only accrues against a daemon
     * that is refusing to answer, where the alternative to waiting is leaving the persona running.
     */
    fun killContainer(name: String) {
        for (attempt in 0 until KILL_ATTEMPTS) {
            val killed = runCatching { runner.run(JailLauncher.killContainerArgv(name), KILL_TIMEOUT_MILLIS) }
            if (killed.getOrNull()?.exit == 0 || attempt == KILL_ATTEMPTS - 1) break
            try {
                Thread.sleep(KILL_RETRY_MILLIS)
            } catch (_: InterruptedException) {
                // Never swallowed: the caller may be shutting the app down, and the flag is how it knows.
                // Stop retrying, but still fall through to the rm — the container is the thing at stake.
                Thread.currentThread().interrupt()
                break
            }
        }
        runCatching { runner.run(JailLauncher.rmContainerArgv(name), KILL_TIMEOUT_MILLIS) }
    }

    private fun docker(argv: List<String>): ExecResult = runner.run(argv, DOCKER_TIMEOUT_MILLIS)
}
