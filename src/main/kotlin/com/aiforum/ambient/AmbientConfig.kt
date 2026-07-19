package com.aiforum.ambient

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Enables [AmbientFeedProperties]. Deliberately NOT `@Profile`-scoped (plan_docs/ambient-slice-5.md §4):
 * the properties bean must exist under EVERY profile — including `test`, where the real
 * [FeedArticleSource] can never wire — so the test-only DiagnosticsController can inject it and the
 * config_guardrails rail can assert `ambientFeedCount=0`. Wiring the source (`FeedArticleSource` vs
 * `StubArticleSource`) is a separate concern, gated on `aiforum.ambient.source` at those beans; this
 * just makes the allowlist readable everywhere.
 */
@Configuration
@EnableConfigurationProperties(AmbientFeedProperties::class)
class AmbientConfig
