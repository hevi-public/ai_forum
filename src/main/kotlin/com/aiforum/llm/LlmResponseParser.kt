package com.aiforum.llm

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.DeserializationFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Duration

/**
 * Pure Tier-0 classification of a finished `claude -p --output-format json` invocation into either a
 * successful [LlmResponse] or the right [LlmException] (see the bdd-tiered-testing skill). This holds
 * NO IO — the subprocess plumbing lives in [ProcessLlmClient]; here we only reason about the captured
 * (exitCode, stdout) pair, so every branch of the failure taxonomy is unit-testable against canned
 * envelopes.
 *
 * [LlmUsage] is derived HERE rather than in [ClaudeStreamParser] (issue #15) because this object serves
 * BOTH generate paths: the streaming client re-parses its captured terminal `result` line through this
 * same function, so putting cost here gets it to the streaming path for free and keeps the two paths
 * identical in text, leak AND usage. Tool calls cannot follow it — they live in the stream's content
 * blocks, which the plain-json envelope does not carry — so those are collected by the stream parser.
 */
object LlmResponseParser {
    // Lenient on unknown fields (the real envelope carries ~20); the Kotlin module applies defaults to
    // the ones we omit, so a sparse error envelope deserialises cleanly.
    private val mapper = jacksonMapperBuilder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    /** Substrings (lower-cased) that mark an error envelope as a usage/rate limit rather than a generic fault. */
    private val RATE_LIMIT_SIGNALS = listOf(
        "rate limit", "rate_limit", "ratelimit", "usage limit", "overloaded", "429", "too many requests",
    )

    /**
     * The subset of the CLI's `result` JSON we classify on, e.g.
     * `{"subtype":"success","is_error":false,"api_error_status":null,"result":"pong","stop_reason":"end_turn"}`.
     * `apiErrorStatus` is `Any?` because the CLI puts a null / number / object there depending on the
     * fault — we only ever stringify it to scan for a rate-limit signal.
     */
    private data class ClaudeEnvelope(
        @param:JsonProperty("is_error") val isError: Boolean = false,
        val subtype: String? = null,
        val result: String? = null,
        @param:JsonProperty("stop_reason") val stopReason: String? = null,
        @param:JsonProperty("api_error_status") val apiErrorStatus: Any? = null,
        // Issue #15: the accounting fields the envelope has always carried and we always dropped. All
        // nullable — an older CLI, or a provider aping this shape, may omit any of them, and "absent"
        // has to stay distinguishable from "zero" all the way out to the run row.
        @param:JsonProperty("total_cost_usd") val totalCostUsd: Double? = null,
        @param:JsonProperty("duration_ms") val durationMs: Long? = null,
        val usage: EnvelopeUsage? = null,
        // Keys only — the per-model breakdown's VALUES are token/cost objects we do not need twice
        // (`usage` and `total_cost_usd` already carry the totals). Any? values keep this tolerant of
        // whatever shape the CLI puts under each key.
        @param:JsonProperty("modelUsage") val modelUsage: Map<String, Any?>? = null,
    )

    /** The envelope's token block. cache_creation_/cache_read_ are deliberately NOT read — see [LlmUsage]. */
    private data class EnvelopeUsage(
        @param:JsonProperty("input_tokens") val inputTokens: Long? = null,
        @param:JsonProperty("output_tokens") val outputTokens: Long? = null,
    )

    /**
     * The envelope's accounting as an [LlmUsage], or null when the provider reported NOTHING we can use.
     * The all-null check is the point: an object of four nulls is indistinguishable from silence to every
     * consumer, and returning one would make `usage != null` mean "the envelope parsed" rather than "the
     * provider said something", which is the distinction the whole nullable chain exists to preserve.
     */
    private fun usageOf(env: ClaudeEnvelope): LlmUsage? {
        val tokens = listOfNotNull(env.usage?.inputTokens, env.usage?.outputTokens)
            .takeIf { it.isNotEmpty() }?.sum()
        // Sorted then joined so the string is stable across runs: a map's iteration order is not a
        // contract, and an unstable model string would churn every row it is written to.
        val model = env.modelUsage?.keys?.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")
        val usage = LlmUsage(env.totalCostUsd, tokens, env.durationMs, model)
        return usage.takeIf { it.costUsd != null || it.tokens != null || it.durationMs != null || it.model != null }
    }

    fun parse(exitCode: Int, stdout: String, rateLimitRetryAfter: Duration): LlmResponse {
        val raw = stdout.trim()
        if (raw.isEmpty()) {
            // Nothing on stdout at all: a non-zero exit is a hard process failure, otherwise the model
            // simply produced nothing — both are FAILED_RETRY but the taxonomy distinguishes them.
            if (exitCode != 0) throw LlmException.ProcessError(exitCode)
            throw LlmException.EmptyOutput()
        }

        val env = try {
            mapper.readValue(raw, ClaudeEnvelope::class.java)
        } catch (_: Exception) {
            // stdout present but not the JSON envelope we asked for — truncated or malformed.
            throw LlmException.MalformedOutput(raw)
        }

        val subtype = env.subtype.orEmpty()
        val result = env.result.orEmpty()
        val errored = env.isError || (subtype.isNotEmpty() && subtype != "success")

        if (errored) {
            // Rate-limit detection is scoped to error envelopes only, so a successful reply that merely
            // *mentions* "rate limit" in its body is never mistaken for one. The signal can surface in the
            // structured api_error_status or in the error text the CLI puts in `result`.
            val signal = (subtype + " " + env.apiErrorStatus?.toString().orEmpty() + " " + result).lowercase()
            if (RATE_LIMIT_SIGNALS.any { it in signal }) throw LlmException.RateLimited(rateLimitRetryAfter)
            if (exitCode != 0) throw LlmException.ProcessError(exitCode)
            // An error envelope with a clean exit and no rate signal: nothing usable came back.
            throw LlmException.MalformedOutput(raw)
        }

        // Success envelope. A non-zero exit alongside it is contradictory — trust the exit code.
        if (exitCode != 0) throw LlmException.ProcessError(exitCode)
        if (result.isBlank()) throw LlmException.EmptyOutput()
        // Hit the output ceiling mid-reply: we have text but it's truncated, so the owner should retry.
        if (env.stopReason == "max_tokens") throw LlmException.MalformedOutput(result)
        // Strip any leaked chain-of-thought and flag what we strip/suspect (never discard — see
        // ReplySanitizer). A body that was ONLY a <think> block is now blank: that's empty output.
        val sanitized = ReplySanitizer.sanitize(result)
        if (sanitized.text.isBlank()) throw LlmException.EmptyOutput()
        // Usage rides out on the SUCCESS branch only. Every error branch above throws, so a failed turn
        // carries no usage anywhere — deliberate: the taxonomy exceptions are the failure contract, and
        // bolting accounting onto them would give two call sites two different ideas of what a failure is.
        // (The spend on a failed turn is real but unattributable to a settled node; the run row stays
        // unpriced, which is honest — see the recordRunCost comment in AmbientTickService.)
        return LlmResponse(sanitized.text, sanitized.leak, usageOf(env))
    }
}
