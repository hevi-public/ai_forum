package com.aiforum.acceptance.steps

import com.aiforum.acceptance.support.HttpClient
import com.aiforum.acceptance.support.ScenarioWorld
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Config-guardrail rails (§14): assert from the outside that the test profile is wired safely. Reads
 * the test-only /__diag endpoint.
 */
class ConfigRailSteps(
    private val world: ScenarioWorld,
    private val http: HttpClient,
) {
    @When("the test diagnostics are read")
    fun readDiagnostics() {
        val resp = http.get("/__diag")
        world.lastStatus = resp.statusCode.value()
        world.lastBody = resp.body
    }

    @Then("the active datasource points at the test database")
    fun datasourceIsTest() =
        assertTrue((world.lastBody ?: "").contains("aiforum-test.db"), "datasource not the test DB: ${world.lastBody}")

    @Then("backups are disabled")
    fun backupsDisabled() =
        assertTrue((world.lastBody ?: "").contains("\"backupsEnabled\":false"), "backups not disabled: ${world.lastBody}")

    @Then("the active profile is {string}")
    fun activeProfile(profile: String) =
        assertTrue((world.lastBody ?: "").contains("\"$profile\""), "profile $profile not active: ${world.lastBody}")

    @Then("persona web fetch is disabled")
    fun webFetchDisabled() =
        assertTrue((world.lastBody ?: "").contains("\"webFetchEnabled\":false"), "web-fetch not disabled: ${world.lastBody}")

    @Then("persona GitHub tools are disabled")
    fun githubToolsDisabled() =
        assertTrue((world.lastBody ?: "").contains("\"githubToolsEnabled\":false"), "gh tools not disabled: ${world.lastBody}")

    @Then("ambient ticking is disabled")
    fun ambientTickingDisabled() =
        assertTrue((world.lastBody ?: "").contains("\"ambientEnabled\":false"), "ambient ticking not disabled: ${world.lastBody}")

    @Then("the article source is the scriptable fake")
    fun articleSourceIsScriptableFake() =
        assertTrue(
            (world.lastBody ?: "").contains("\"articleSource\":\"ScriptableArticleSource\""),
            "article source not the scriptable fake: ${world.lastBody}",
        )

    // S5 (plan_docs/ambient-slice-5.md §2 "Source selection", §4 config_guardrails.feature additions):
    // the new `aiforum.ambient.source` switch must default to `stub` (matchIfMissing = true), and
    // `AmbientFeedProperties` must be wired from a non-profiled @Configuration so the bean exists — empty
    // — under test. Neither field exists on DiagnosticsController's /__diag payload yet, so both fail
    // honestly on a missing key, not a wiring/404 error.
    @Then("the ambient source selection defaults to the stub")
    fun ambientSourceDefaultsToStub() =
        assertTrue(
            (world.lastBody ?: "").contains("\"ambientSource\":\"stub\""),
            "ambient source selection not defaulted to stub: ${world.lastBody}",
        )

    @Then("no feeds are configured under test")
    fun noFeedsConfiguredUnderTest() =
        assertTrue(
            (world.lastBody ?: "").contains("\"ambientFeedCount\":0"),
            "ambient feed count not 0 under test: ${world.lastBody}",
        )
}
