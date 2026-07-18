package com.aiforum.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Config drifts silently, so we assert it. Under the `test` profile the app must use the test DB and
 * must not produce backups — these guardrails are also exercised from the outside by the
 * config_guardrails.feature rail scenarios (see the bdd-tiered-testing / sqlite-spring-jdbc skills).
 */
@Component
class ProfileGuard(
    env: Environment,
    @Value("\${spring.datasource.url:}") private val datasourceUrl: String,
    @Value("\${aiforum.backups.enabled:true}") private val backupsEnabled: Boolean,
    @Value("\${aiforum.images.dir:}") private val imagesDir: String,
) {
    init {
        if (env.activeProfiles.contains("test")) {
            require("test" in datasourceUrl) {
                "test profile must point at the test database, but datasource is: $datasourceUrl"
            }
            require(!backupsEnabled) { "backups must be disabled under the test profile" }
            // Image blobs must land under build/, never the real ~/.ai_forum store — same guard as the DB.
            require("build" in imagesDir) {
                "test profile must store images under build/, but images.dir is: $imagesDir"
            }
        }
    }
}
