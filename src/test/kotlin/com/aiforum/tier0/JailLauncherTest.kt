package com.aiforum.tier0

import com.aiforum.config.JailProperties
import com.aiforum.llm.JailInvocation
import com.aiforum.llm.JailLauncher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure docker argv and squid-config construction — no subprocess, no Docker daemon, no
 * Spring. Everything the jail *is* as a decision (which flags contain the container, which hosts it may
 * reach, what crosses the boundary and how) is a string built here from canned config, so the whole
 * containment posture is unit-testable without a container ever running. What's left for the opt-in
 * `jailContract` suite is only whether Docker honours these flags.
 *
 * The mirror of [com.aiforum.llm.LlmResponseParser] on the other side of the seam: that one classifies a
 * finished invocation, this one composes the one that starts.
 */
@Tag("tier0")
class JailLauncherTest {

    private val props = JailProperties()

    /** A realistic non-streaming `claude -p` argv, as ProcessLlmClient.buildArgs produces it. */
    private val claudeArgv = listOf(
        "claude", "-p",
        "--output-format", "json",
        "--system-prompt", "you are sol",
        "--model", "opus",
        "--allowedTools", "WebFetch",
    )

    /** The same, with the gh-readonly MCP server mounted (the github-tools-enabled shape). */
    private val ghArgv = listOf(
        "claude", "-p",
        "--output-format", "json",
        "--system-prompt", "you are sol",
        "--mcp-config", "/repo/.mcp.json",
        "--strict-mcp-config",
        "--allowedTools", "mcp__gh-readonly",
    )

    private val invocationId = "1234abcd"
    private val credential = "/Users/hevi/.claude/.credentials.json"
    private val envFile = "/Users/hevi/.ai_forum/jail/jail.env"

    /** Both side-channels present: a credential file to mount and a token env-file to load. */
    private val fullInv = JailInvocation(credentialFile = credential, envFile = envFile)

    /** Neither: no credential file on this host, no tokens in the app's environment. */
    private val bareInv = JailInvocation(credentialFile = null, envFile = null)

    private fun wrap(
        argv: List<String> = claudeArgv,
        p: JailProperties = props,
        inv: JailInvocation = fullInv,
        server: String = "gh-readonly",
    ) = JailLauncher.wrap(argv, p, invocationId, inv, server)

    /** The value following [flag], or null when the flag is absent. */
    private fun value(argv: List<String>, flag: String): String? =
        argv.indexOf(flag).takeIf { it >= 0 }?.let { argv.getOrNull(it + 1) }

    /** Every value following an occurrence of [flag] (for repeated flags like -e / --tmpfs). */
    private fun values(argv: List<String>, flag: String): List<String> =
        argv.withIndex().filter { it.value == flag }.mapNotNull { argv.getOrNull(it.index + 1) }

    /** Everything after the image name — the in-container command line. */
    private fun containerCommand(argv: List<String>, p: JailProperties = props): List<String> =
        argv.drop(argv.indexOf(p.image) + 1)

    // --- the container's identity and lifecycle -------------------------------------------------

    @Test
    fun `the wrapped argv runs a one-shot docker container under the deterministic invocation name`() {
        val argv = wrap()
        assertEquals(listOf("docker", "run"), argv.take(2))
        assertTrue(argv.contains("--rm"), "the container must not survive the invocation: $argv")
        assertTrue(argv.contains("-i"), "stdin carries the prompt: $argv")
        assertTrue(argv.contains("--init"), "pid 1 must reap, or a wedged child leaks: $argv")
        assertEquals("aiforum-jail-$invocationId", value(argv, "--name"))
        assertEquals("aiforum-jail-$invocationId", JailLauncher.containerName(invocationId))
        // A TTY would merge stderr into stdout and mangle the NDJSON stream the parser reads.
        assertFalse(argv.contains("-t"), "never allocate a TTY: $argv")
    }

    @Test
    fun `the container joins the internal jail network and its proxy env points at the sidecar`() {
        val argv = wrap()
        assertEquals("aiforum-jail-net", value(argv, "--network"))
        val env = values(argv, "-e")
        val proxy = "http://aiforum-jail-proxy:3128"
        assertTrue(env.contains("HTTP_PROXY=$proxy"), env.toString())
        assertTrue(env.contains("HTTPS_PROXY=$proxy"), env.toString())
        assertTrue(env.contains("http_proxy=$proxy"), env.toString())
        assertTrue(env.contains("https_proxy=$proxy"), env.toString())
        assertTrue(env.contains("NO_PROXY=localhost,127.0.0.1"), env.toString())
    }

    @Test
    fun `the rootfs is read-only, every capability is dropped, and privileges cannot be regained`() {
        val argv = wrap()
        assertTrue(argv.contains("--read-only"), argv.toString())
        assertEquals("ALL", value(argv, "--cap-drop"))
        assertEquals("no-new-privileges", value(argv, "--security-opt"))
        assertFalse(argv.contains("--privileged"), argv.toString())
    }

    @Test
    fun `home, work and tmp are tmpfs, and the workdir points at the scratch mount`() {
        val argv = wrap()
        val tmpfs = values(argv, "--tmpfs")
        assertEquals(
            listOf(
                "/home/node:rw,size=268435456,mode=1777",
                "/work:rw,size=67108864,mode=1777",
                "/tmp:rw,size=67108864,mode=1777",
            ),
            tmpfs,
        )
        assertEquals("/work", value(argv, "--workdir"))
    }

    @Test
    fun `the configured memory, cpu and pid caps are carried onto the run`() {
        val argv = wrap(p = props.copy(memory = "512m", cpus = "1.5", pidsLimit = 64))
        assertEquals("512m", value(argv, "--memory"))
        // Pinned to --memory so swap can't quietly restore the headroom the cap removed.
        assertEquals("512m", value(argv, "--memory-swap"))
        assertEquals("1.5", value(argv, "--cpus"))
        assertEquals("64", value(argv, "--pids-limit"))
    }

    @Test
    fun `the wall-clock budget prefixes the in-container command with a kill-after timeout`() {
        val argv = wrap(p = props.copy(maxWallClockSeconds = 42))
        assertEquals(
            listOf("timeout", "--kill-after=10", "42", "claude"),
            containerCommand(argv, props.copy(maxWallClockSeconds = 42)).take(4),
        )
    }

    // --- what crosses the boundary ---------------------------------------------------------------

    @Test
    fun `the credential file is the only bind mount and it is read-only`() {
        val argv = wrap()
        assertEquals(listOf("$credential:/home/node/.claude/.credentials.json:ro"), values(argv, "-v"))
        assertFalse(argv.contains("--mount"), "one mount syntax only, so the audit above is complete: $argv")
    }

    @Test
    fun `a missing credential file yields no bind mount at all`() {
        // Emitting -v for a path that doesn't exist would make docker create a host DIRECTORY there,
        // which then shadows the real credential the day the user logs in.
        val argv = wrap(inv = JailInvocation(credentialFile = null, envFile = envFile))
        assertEquals(emptyList<String>(), values(argv, "-v"))
        assertFalse(argv.contains("-v"), argv.toString())
        // …and the rest of the run is untouched, so this is "no mount", not "no argv".
        assertEquals("aiforum-jail-$invocationId", value(argv, "--name"))
        assertEquals(envFile, value(argv, "--env-file"))
    }

    @Test
    fun `secrets cross via an env-file only, never as an argv value`() {
        val argv = wrap()
        assertEquals(envFile, value(argv, "--env-file"))
        // argv is world-readable via `ps`; token VALUES must live in the 0600 file, never here.
        assertTrue(
            argv.none { it.contains("TOKEN=") },
            "no token may appear on the command line: $argv",
        )
    }

    @Test
    fun `no env-file flag appears when there is no token side-channel to load`() {
        val argv = wrap(inv = bareInv)
        assertNull(value(argv, "--env-file"))
        assertFalse(argv.contains("--env-file"), argv.toString())
        // Structural, so the absence is a decision rather than an empty argv: the invocation still runs.
        assertEquals(listOf("docker", "run"), argv.take(2))
        assertEquals(listOf("claude") + claudeArgv.drop(1), containerCommand(argv).drop(3))
    }

    @Test
    fun `the claude tail survives verbatim after the image name`() {
        val argv = wrap()
        // Everything the caller decided — system prompt, output format, model, allowedTools — is passed
        // through byte-identically; the jail relocates the process, it does not re-negotiate the request.
        assertEquals(listOf("claude") + claudeArgv.drop(1), containerCommand(argv).drop(3))
    }

    @Test
    fun `an --mcp-config value is rewritten to the baked container path under the configured server name`() {
        val argv = wrap(argv = ghArgv)
        assertEquals(
            """{"mcpServers":{"gh-readonly":{"command":"node","args":["/opt/aiforum/mcp/gh-readonly/server.mjs"]}}}""",
            value(argv, "--mcp-config"),
        )
        assertTrue(argv.contains("--strict-mcp-config"), "hermetic config loading survives: $argv")
        assertTrue(argv.none { it.contains("/repo/.mcp.json") }, "the HOST path must not reach the jail: $argv")

        val renamed = wrap(argv = ghArgv, server = "gh-ro")
        assertEquals(
            """{"mcpServers":{"gh-ro":{"command":"node","args":["/opt/aiforum/mcp/gh-readonly/server.mjs"]}}}""",
            value(renamed, "--mcp-config"),
        )
    }

    @Test
    fun `without github tools no --mcp-config appears and none is invented`() {
        val argv = wrap(argv = claudeArgv)
        assertNull(value(argv, "--mcp-config"))
        assertTrue(argv.none { it.contains("mcpServers") }, argv.toString())
        // The tail is otherwise whole — the rewrite is keyed on the flag being THERE, not conjured.
        assertEquals(listOf("claude") + claudeArgv.drop(1), containerCommand(argv).drop(3))
    }

    @Test
    fun `the argv never references the docker socket or a host path beyond the credential`() {
        val argv = wrap()
        assertTrue(argv.none { it.contains("docker.sock") }, "a socket mount would be a host root: $argv")
        assertTrue(argv.none { it == "--pid" || it == "--ipc" || it == "--network=host" }, argv.toString())
        // The credential and the env-file are the two host paths, both named explicitly; nothing else.
        val hostPaths = argv.filter { it.contains("/Users/") }
        assertEquals(listOf("$credential:/home/node/.claude/.credentials.json:ro", envFile), hostPaths)
    }

    // --- the egress policy ------------------------------------------------------------------------

    @Test
    fun `the squid config lists one dstdomain line per allowlisted host and denies everything else`() {
        val conf = JailLauncher.squidConf(listOf("api.anthropic.com", "api.github.com")).lines()
        assertTrue(conf.contains("http_port 3128"), conf.toString())
        assertEquals(
            listOf("acl allowed_dst dstdomain api.anthropic.com", "acl allowed_dst dstdomain api.github.com"),
            conf.filter { it.startsWith("acl allowed_dst") },
        )
        assertTrue(conf.contains("http_access allow allowed_dst"), conf.toString())
        assertEquals("http_access deny all", conf.last { it.startsWith("http_access") })
        // Measured, not assumed: squid drops to the unprivileged `proxy` user and reopens this path, and
        // the container's root-owned stdout pipe kills it FATAL at boot. The audit log has to go to a
        // directory that user owns, so `stdio:/dev/stdout` here would be a proxy that never starts.
        assertEquals("access_log stdio:/var/log/squid/access.log", conf.single { it.startsWith("access_log") })
    }

    @Test
    fun `an empty allowlist produces a config with no allow rule at all`() {
        val conf = JailLauncher.squidConf(emptyList()).lines()
        assertTrue(conf.none { it.startsWith("http_access allow") }, "deny-by-default means deny: $conf")
        assertTrue(conf.none { it.startsWith("acl allowed_dst") }, "an unused acl squid would reject: $conf")
        assertTrue(conf.contains("http_access deny all"), conf.toString())
    }

    @Test
    fun `internal address ranges are denied ahead of the allowlist`() {
        val conf = JailLauncher.squidConf(listOf("api.github.com")).lines()
        val internal = conf.indexOfFirst { it == "http_access deny to_internal" }
        val allow = conf.indexOfFirst { it == "http_access allow allowed_dst" }
        assertTrue(internal >= 0, "the SSRF guard must exist: $conf")
        assertTrue(internal < allow, "squid takes the first matching rule; the deny must come first: $conf")
        val acl = conf.single { it.startsWith("acl to_internal") }
        listOf("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16")
            .forEach { assertTrue(acl.contains(it), "missing $it in: $acl") }
    }

    @Test
    fun `a hostile allowlist entry is refused rather than spliced into the config`() {
        // The allowlist comes from yml an operator edits — but a host that carried a newline could append
        // `http_access allow all`, turning the one real control off from inside its own config file.
        listOf(
            "evil.com\nhttp_access allow all",
            "evil.com http_access allow all",
            "evil.com\"",
            "evil.com;rm -rf /",
            "",
        ).forEach { hostile ->
            assertThrows(IllegalArgumentException::class.java, { JailLauncher.squidConf(listOf(hostile)) }, "accepted: <$hostile>")
        }
        // A leading dot is the legitimate subdomain wildcard and must still pass.
        assertTrue(JailLauncher.squidConf(listOf(".github.com")).contains("acl allowed_dst dstdomain .github.com"))
    }

    // --- the topology the app builds at boot -------------------------------------------------------

    @Test
    fun `the network create argv makes the named network internal`() {
        assertEquals(listOf("docker", "network", "create", "--internal", "aiforum-jail-net"), JailLauncher.networkCreateArgv(props))
        assertEquals(listOf("docker", "network", "inspect", "aiforum-jail-net"), JailLauncher.networkInspectArgv(props))
    }

    @Test
    fun `the proxy run argv mounts the generated config read-only on the egress-capable side`() {
        val argv = JailLauncher.proxyRunArgv(props, "/Users/hevi/.ai_forum/jail/squid.conf")
        assertEquals(listOf("docker", "run"), argv.take(2))
        assertTrue(argv.contains("-d"), argv.toString())
        assertEquals("aiforum-jail-proxy", value(argv, "--name"))
        assertEquals("/Users/hevi/.ai_forum/jail/squid.conf:/etc/squid/squid.conf:ro", value(argv, "-v"))
        assertEquals("ubuntu/squid:latest", argv.last())
        // The proxy starts on the DEFAULT bridge (which has a route out) and is attached to the internal
        // network afterwards — starting it inside would leave it as cut off as its clients.
        assertTrue(argv.none { it == "aiforum-jail-net" }, "the proxy must not START on the internal net: $argv")
        assertEquals(listOf("docker", "network", "connect", "aiforum-jail-net", "aiforum-jail-proxy"), JailLauncher.proxyConnectArgv(props))
        assertEquals(listOf("docker", "rm", "-f", "aiforum-jail-proxy"), JailLauncher.proxyRemoveArgv(props))
        assertEquals(listOf("docker", "image", "inspect", "aiforum-claude-jail"), JailLauncher.imageInspectArgv(props))
    }

    @Test
    fun `the kill argv names the same container the wrapped argv started`() {
        // Killing the `docker run` CLIENT does not stop the container — the cancel path has to name it.
        val argv = wrap()
        assertEquals(
            listOf("docker", "kill", value(argv, "--name")),
            JailLauncher.killContainerArgv(JailLauncher.containerName(invocationId)),
        )
    }
}
