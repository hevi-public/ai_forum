package com.aiforum.llm

import com.aiforum.config.JailProperties

/**
 * The PURE half of the persona-subprocess jail (requirements §12; design `plan_docs/llm-sandbox.md`):
 * every docker argv and the squid config, built as strings from config alone. No `File`, no
 * `System.getenv`, no `ProcessBuilder`, no clock — which is what lets Tier-0 pin the entire containment
 * posture without a Docker daemon in the room. [JailRuntime] owns the IO that runs these.
 *
 * The mechanism, in one paragraph, because the argv only makes sense against it: egress is **topology,
 * not cooperation**. The jail container joins a `--internal` Docker network, which has no gateway and
 * therefore no route off the host at all — a process that ignores the proxy environment gets nothing,
 * and a direct-IP dial is dead by construction rather than by policy. The single path out is a squid
 * sidecar that is dual-homed (started on the default bridge, then *connected* to the internal network),
 * which filters CONNECT by destination hostname without terminating TLS. So the allowlist below is the
 * real perimeter, and [squidConf] is where it is written.
 *
 * @see JailInvocation for the two host-side side-channels (credential file, env file) a run may carry.
 */
object JailLauncher {

    /** The proxy's listening port, inside the jail network only — never published to the host. */
    const val PROXY_PORT = 3128

    /** Where the jail image bakes the gh-readonly MCP server (see `docker/claude-jail/Dockerfile`). */
    const val MCP_SERVER_PATH = "/opt/aiforum/mcp/gh-readonly/server.mjs"

    /** The container's HOME — a tmpfs, so anything claude caches dies with the invocation. */
    const val CONTAINER_HOME = "/home/node"

    /** Where a mounted credential file lands; the path claude looks for under [CONTAINER_HOME]. */
    const val CONTAINER_CREDENTIAL = "$CONTAINER_HOME/.claude/.credentials.json"

    /** The scratch workdir — a tmpfs, so the persona has somewhere to write and nowhere that persists. */
    const val CONTAINER_WORKDIR = "/work"

    /**
     * Where squid writes the access log — every allow and every DENY, which is the audit trail for "did a
     * persona try to reach somewhere it shouldn't". Inside the proxy container, in the log directory the
     * unprivileged squid user owns; see [squidConf] for why it cannot be the container's stdout.
     */
    const val ACCESS_LOG = "/var/log/squid/access.log"

    /** Grace between `timeout`'s TERM and its KILL, for the in-container wall-clock backstop. */
    private const val TIMEOUT_KILL_AFTER_SECONDS = 10

    /** 256 MiB home / 64 MiB work / 64 MiB tmp — enough for claude's cache and a persona's scratch. */
    private const val HOME_TMPFS_BYTES = 268_435_456L
    private const val SCRATCH_TMPFS_BYTES = 67_108_864L

    /**
     * A hostname (optionally a `.example.com` subdomain wildcard) and nothing else. squid.conf is
     * line-oriented and unquoted, so an entry carrying a newline or a space could append its own
     * directive — `http_access allow all` being the obvious one — and switch off the only control the
     * jail has, from inside the file that defines it. Refuse rather than sanitise: a mangled host is an
     * operator typo worth surfacing, not something to silently reinterpret.
     */
    private val HOST_PATTERN = Regex("^\\.?[A-Za-z0-9][A-Za-z0-9.-]*$")

    /** The container name for an invocation — deterministic, so the cancel path can name what to kill. */
    fun containerName(invocationId: String): String = "aiforum-jail-$invocationId"

    /**
     * Wrap a host `claude -p` argv into the `docker run` that executes it inside the jail.
     *
     * `docker run -i` pipes our stdin to the container and streams its stdout/stderr back, exiting with
     * the inner command's code — so every bit of the existing writeStdin/drain/parse/timeout plumbing in
     * [ProcessLlmClient] keeps working with the wrapped argv substituted for the bare one. Note the
     * absence of `-t`: a TTY would merge stderr into stdout and mangle the NDJSON stream.
     *
     * [claudeArgv]`[0]` (the host command name) is dropped — inside the image the binary is always
     * `claude`. Everything after it is passed through byte-identically, with one exception: a
     * `--mcp-config` VALUE is rewritten to point at the MCP server baked into the image, since the host
     * path it names does not exist in there.
     */
    fun wrap(
        claudeArgv: List<String>,
        props: JailProperties,
        invocationId: String,
        inv: JailInvocation,
        mcpServerName: String,
    ): List<String> = buildList {
        val proxy = "http://${props.proxyName}:$PROXY_PORT"
        add("docker"); add("run")
        add("--rm")                                    // no container survives its invocation
        add("-i")                                      // the prompt arrives on stdin
        add("--init")                                  // pid 1 reaps, so a wedged child can't linger
        add("--name"); add(containerName(invocationId))
        add("--network"); add(props.network)           // internal: no gateway, therefore no route out
        add("--read-only")                             // the image's own filesystem is immutable
        add("--cap-drop"); add("ALL")
        add("--security-opt"); add("no-new-privileges")
        add("--pids-limit"); add(props.pidsLimit.toString())
        add("--memory"); add(props.memory)
        add("--memory-swap"); add(props.memory)        // == --memory, so swap adds no headroom back
        add("--cpus"); add(props.cpus)
        add("--tmpfs"); add("$CONTAINER_HOME:rw,size=$HOME_TMPFS_BYTES,mode=1777")
        add("--tmpfs"); add("$CONTAINER_WORKDIR:rw,size=$SCRATCH_TMPFS_BYTES,mode=1777")
        add("--tmpfs"); add("/tmp:rw,size=$SCRATCH_TMPFS_BYTES,mode=1777")
        add("--workdir"); add(CONTAINER_WORKDIR)
        if (props.user.isNotBlank()) {
            add("--user"); add(props.user)
        }
        // The ONLY bind mount, and only when the file is really there: naming a non-existent path would
        // have docker create a host DIRECTORY at it, which then shadows the real credential forever.
        inv.credentialFile?.let {
            add("-v"); add("$it:$CONTAINER_CREDENTIAL:ro")
        }
        // Tokens travel in a 0600 file, never as `-e NAME=value` — argv is world-readable via `ps`.
        inv.envFile?.let {
            add("--env-file"); add(it)
        }
        // Both cases, because different runtimes read different ones.
        add("-e"); add("HTTP_PROXY=$proxy")
        add("-e"); add("HTTPS_PROXY=$proxy")
        add("-e"); add("http_proxy=$proxy")
        add("-e"); add("https_proxy=$proxy")
        add("-e"); add("NO_PROXY=localhost,127.0.0.1")
        add("-e"); add("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1")
        add("-e"); add("DISABLE_AUTOUPDATER=1")        // a read-only rootfs can't be updated anyway
        add(props.image)
        // Wall-clock backstop UNDER the app's own deadline: covers the case where the app dies mid-run
        // and never gets to kill the container by name.
        add("timeout"); add("--kill-after=$TIMEOUT_KILL_AFTER_SECONDS"); add(props.maxWallClockSeconds.toString())
        add("claude")
        addAll(rewriteMcpConfig(claudeArgv.drop(1), mcpServerName))
    }

    /**
     * Point `--mcp-config` at the server baked into the image. The host value (a path to the repo's
     * `.mcp.json`, or inline JSON naming a host path) is meaningless inside the jail, so we substitute
     * inline JSON for the same server under the same name — `--strict-mcp-config` and the
     * `mcp__<name>` permission rule the caller already added therefore still line up. Absent flag =>
     * absent tools: nothing is invented.
     */
    private fun rewriteMcpConfig(tail: List<String>, mcpServerName: String): List<String> {
        val flag = tail.indexOf("--mcp-config")
        if (flag < 0 || flag + 1 >= tail.size) return tail
        val inline = """{"mcpServers":{"$mcpServerName":{"command":"node","args":["$MCP_SERVER_PATH"]}}}"""
        return tail.toMutableList().also { it[flag + 1] = inline }
    }

    /**
     * The squid config that IS the egress policy: deny by default, allow the listed destination domains,
     * and refuse the private ranges outright so a jailed persona can't be talked into probing the host's
     * LAN or a cloud metadata endpoint. squid takes the FIRST matching `http_access` rule, so the order
     * of these lines is the policy — the internal-range deny has to precede the allowlist.
     *
     * CONNECT is filtered by `dstdomain`, which reads the hostname out of the tunnel request; TLS is
     * never terminated, so nothing here can see or alter a request body.
     *
     * An empty [allowlist] emits no `allow` line **and** no `allowed_dst` acl (squid rejects a config
     * that references an undefined acl) — total isolation, which is a legitimate configuration.
     */
    fun squidConf(allowlist: List<String>): String {
        allowlist.forEach {
            require(HOST_PATTERN.matches(it)) { "illegal jail egress allowlist entry: <$it> (hostname or .suffix only)" }
        }
        return buildList {
            add("# GENERATED by JailLauncher.squidConf — edits are overwritten on every app boot.")
            add("http_port $PROXY_PORT")
            allowlist.forEach { add("acl allowed_dst dstdomain $it") }
            add("acl Safe_ports port 80 443")
            add("acl SSL_ports port 443")
            add("acl CONNECT method CONNECT")
            add("acl to_internal dst 127.0.0.0/8 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 169.254.0.0/16")
            add("http_access deny to_internal")
            add("http_access deny !Safe_ports")
            add("http_access deny CONNECT !SSL_ports")
            if (allowlist.isNotEmpty()) add("http_access allow allowed_dst")
            add("http_access deny all")
            // NOT stdio:/dev/stdout, however much nicer `docker logs` would be: squid drops to the
            // unprivileged `proxy` user and then REOPENS this path, and the container's stdout pipe is
            // root-owned — squid dies FATAL at startup. Measured on ubuntu/squid 6.13, 2026-08-09. Read
            // it with `docker exec <proxy> tail -f /var/log/squid/access.log` instead.
            add("access_log stdio:$ACCESS_LOG")
        }.joinToString("\n", postfix = "\n")
    }

    /**
     * Asks TWO questions in one call: does a network of this name exist (exit code), and is it `--internal`
     * (`{{.Internal}}` on stdout, `true`/`false`). Existence alone is not the answer worth having — a
     * network of the right NAME created without `--internal` has a gateway, so every container on it routes
     * off the host directly and the squid sidecar degrades from the perimeter to a politeness. [JailRuntime]
     * treats `false` as fatal for exactly that reason.
     */
    fun networkInspectArgv(props: JailProperties): List<String> =
        listOf("docker", "network", "inspect", "-f", "{{.Internal}}", props.network)

    /**
     * `--internal` is the load-bearing flag of the whole design: docker attaches no gateway to an
     * internal network, so containers on it have no route off the host — enforcement by topology rather
     * than by the jailed process choosing to honour a proxy variable.
     */
    fun networkCreateArgv(props: JailProperties): List<String> =
        listOf("docker", "network", "create", "--internal", props.network)

    fun proxyRemoveArgv(props: JailProperties): List<String> =
        listOf("docker", "rm", "-f", props.proxyName)

    /**
     * Start the proxy on the DEFAULT bridge — i.e. the side that still has egress. It is attached to the
     * internal network afterwards ([proxyConnectArgv]); starting it inside would leave it exactly as cut
     * off as the containers it exists to serve. The config is mounted read-only, so a compromised squid
     * cannot rewrite its own policy.
     */
    fun proxyRunArgv(props: JailProperties, confHostPath: String): List<String> = listOf(
        "docker", "run", "-d",
        "--name", props.proxyName,
        "--restart", "unless-stopped",
        "-v", "$confHostPath:/etc/squid/squid.conf:ro",
        props.proxyImage,
    )

    fun proxyConnectArgv(props: JailProperties): List<String> =
        listOf("docker", "network", "connect", props.network, props.proxyName)

    /** `inspect`, never `pull`: the jail image is a local build, so absence is an operator error. */
    fun imageInspectArgv(props: JailProperties): List<String> =
        listOf("docker", "image", "inspect", props.image)

    /**
     * Killing the `docker run` client does NOT stop the container it started — the daemon owns it. So
     * the cancel/timeout path has to name the container, which is why [containerName] is deterministic.
     */
    fun killContainerArgv(containerName: String): List<String> =
        listOf("docker", "kill", containerName)

    /**
     * The follow-up to [killContainerArgv], and it names the same container [wrap] started. A kill can miss
     * in two ways the cancel path has to survive: it can arrive before the daemon has created the container
     * (nothing to kill, and the container then materialises unattended), and it can leave a `Created` husk
     * that never ran. `rm -f` answers both — it removes a container in any state, including one that showed
     * up between the last kill attempt and this call. On the normal path `--rm` has already removed it, so
     * this is a no-such-container error and is swallowed.
     */
    fun rmContainerArgv(containerName: String): List<String> =
        listOf("docker", "rm", "-f", containerName)
}

/**
 * The two host-side side-channels a jailed invocation may carry, resolved once at boot by [JailRuntime]
 * and re-checked per invocation. Both nullable because both are genuinely optional: a macOS host whose
 * claude auth lives in the Keychain has no credential FILE (the container authenticates from
 * `CLAUDE_CODE_OAUTH_TOKEN` in [envFile] instead), and a host with neither has no side-channel at all.
 */
data class JailInvocation(val credentialFile: String? = null, val envFile: String? = null)
