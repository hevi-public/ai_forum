package com.aiforum.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * `aiforum.llm.jail` config (requirements §12; design `plan_docs/llm-sandbox.md`) — the Docker jail the
 * persona SUBPROCESS runs in. Not the app: the thing we contain is `claude -p`, because that is the
 * process that reads attacker-authored text (PR bodies, issue comments, fetched articles) and holds a
 * tool loop capable of acting on it.
 *
 * The prefix nests under `aiforum.llm` because the jail is a property of the **cli provider's** launcher,
 * not a fleet-wide switch: `provider: openai`/`opencode`/`stub` never spawn a subprocess, so these knobs
 * are inert there (§8 of the design doc says so out loud — an operator must not read "jail: enabled" and
 * conclude an OpenAI backend is contained).
 */
@ConfigurationProperties(prefix = "aiforum.llm.jail")
data class JailProperties(
    /**
     * Master switch, **off by default** — the house rule for every spend/IO knob, and here for a second
     * reason: a broken Docker setup must never be able to take generation down. With this false the
     * spawned argv is byte-identical to the pre-jail one (pinned by a Tier-1 identity test), so enabling
     * is a reversible, one-line flip and disabling is an instant rollback.
     */
    val enabled: Boolean = false,
    /**
     * The jail image tag, built from `docker/claude-jail/Dockerfile` (claude CLI + node + gh + the baked
     * gh-readonly MCP server). Never pulled: it is a local build, so a missing image is a startup WARN
     * carrying the build command, not a silent pull of something from a registry.
     */
    val image: String = "aiforum-claude-jail",
    /**
     * The egress proxy sidecar's image. Squid, because the control we need is CONNECT filtering by
     * destination hostname without terminating TLS. `latest` rather than a digest: the proxy is a local,
     * app-managed container on an internal network — see the design doc §11 for the promotion trigger
     * that would make digest-pinning worth its upkeep.
     */
    val proxyImage: String = "ubuntu/squid:latest",
    /**
     * The **only** hosts a jailed persona can reach, deny-by-default. This is the control that actually
     * protects the credential (§12 decision log): a prompt-injected agent can read whatever credential the
     * container holds regardless of how it is mounted, so what matters is that the credential can only be
     * *spent* against these hosts — never posted to an attacker's collector.
     *
     * Web-fetch / feed domains are deliberately NOT auto-merged in: `web-fetch-allowed-domains` blank
     * means "any host", which deny-by-default cannot express, so a merge would either silently widen the
     * jail to everything or silently narrow WebFetch. The operator adds hosts here on purpose.
     */
    val egressAllowlist: List<String> = listOf("api.anthropic.com", "api.github.com"),
    /** `docker run --memory` (and `--memory-swap`, pinned to the same value so swap adds nothing). */
    val memory: String = "2g",
    /** `docker run --cpus`. */
    val cpus: String = "2",
    /** `docker run --pids-limit` — a fork bomb inside the jail hits this before it hits the host. */
    val pidsLimit: Int = 256,
    /**
     * In-container `timeout` budget, the BACKSTOP under the app's own per-request deadline. The app kills
     * the `docker run` client and then the container by name; this covers the case where neither happens
     * (app crash mid-generation) so no jail can outlive the JVM indefinitely.
     */
    val maxWallClockSeconds: Long = 900L,
    /**
     * Host path of the claude credential file to bind-mount read-only. Blank => `~/.claude/.credentials.json`
     * **if it exists**; on a macOS host whose claude auth lives in the Keychain there is no such file, and
     * the container authenticates from `CLAUDE_CODE_OAUTH_TOKEN` instead (see the design doc §5).
     */
    val credentialFile: String = "",
    /**
     * `docker run --user`. Blank => the image's own `USER node`, which is what you want; set it only to
     * match a host uid for a bind mount's ownership.
     */
    val user: String = "",
    /**
     * The internal Docker network name. Configurable so two app instances on one host can hold two
     * independent jails (and so the opt-in `jailContract` suite can build a throwaway topology beside a
     * running app) — the name is otherwise an implementation detail.
     */
    val network: String = "aiforum-jail-net",
    /**
     * The egress proxy container's name. It is also the **hostname** the jailed container proxies to, so
     * this value appears in the container's `HTTP_PROXY`. Configurable for the same reason as [network].
     */
    val proxyName: String = "aiforum-jail-proxy",
)

/**
 * Enables [JailProperties]. Deliberately NOT `@Profile`-scoped — the [MemoryProperties] pattern: the
 * properties bean must exist under EVERY profile, including `test`, so anything that injects it (today
 * `ProcessLlmClient`) wires identically everywhere and a config rail can read the defaults. Wiring the
 * jail RUNTIME is a separate concern, gated on the class itself (`JailRuntime`).
 */
@Configuration
@EnableConfigurationProperties(JailProperties::class)
class JailConfig
