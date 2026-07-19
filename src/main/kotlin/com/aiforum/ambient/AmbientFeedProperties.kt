package com.aiforum.ambient

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `aiforum.ambient` feed-source config (plan_docs/ambient-slice-5.md §2). Read by [FeedArticleSource]
 * (the owner-curated RSS/Atom allowlist, active only when `aiforum.ambient.source=feed`) and surfaced —
 * empty — on `/__diag` so the config_guardrails rail can assert no feeds are configured under test.
 *
 * Only [feeds] and [feedMaxBytes] live here; the sibling `aiforum.ambient.enabled` / `cron` / `source`
 * keys are read elsewhere (the scheduler's `@ConditionalOnProperty`, the source selector's), and
 * relaxed binding harmlessly ignores them on this class.
 *
 * Bound from the NON-profiled [AmbientConfig] `@EnableConfigurationProperties`, so the bean exists (with
 * these defaults) under EVERY profile — including `test`, where [FeedArticleSource] itself can never
 * wire (`@Profile("!test")`). The `/__diag` reader depends on the bean, not the source.
 */
@ConfigurationProperties(prefix = "aiforum.ambient")
data class AmbientFeedProperties(
    /**
     * The owner's feed allowlist — RSS/Atom URLs [FeedArticleSource] pulls from, round-robin. Must be
     * https (non-https entries are dropped at construction with a boot-time warn). Empty by default, so
     * with no config the source yields nothing rather than reaching the open web.
     */
    val feeds: List<String> = emptyList(),
    /**
     * Per-feed response byte cap, enforced BEFORE parsing (the memory-abuse guard, `ImageStore.max-bytes`
     * precedent). 1 MiB default — a feed body over this is treated as a feed error, not parsed.
     */
    val feedMaxBytes: Long = 1_048_576,
)
