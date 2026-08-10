import gg.jte.ContentType

plugins {
    val kotlinVersion = "2.4.0"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.spring") version kotlinVersion
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("gg.jte.gradle") version "3.2.4"
}

group = "com.aiforum"
version = "0.0.1-SNAPSHOT"

// Align the Kotlin stdlib/reflect managed by the Spring Boot BOM (2.3.21) to the
// plugin version (2.4.0) so there's no plugin/stdlib version skew.
extra["kotlin.version"] = "2.4.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

val jteVersion = "3.2.4"
val cucumberVersion = "7.34.3"
val flywayVersion = "12.4.0"   // matches the Spring Boot 4.1 BOM
val commonmarkVersion = "0.24.0"
val graalVersion = "24.1.2"
val highlightjsWebjarVersion = "11.11.1"

dependencies {
    // --- web + SSR (JTE) — note the Spring Boot 4 starter ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("gg.jte:jte:$jteVersion")
    implementation("gg.jte:jte-spring-boot-starter-4:$jteVersion")
    implementation("gg.jte:jte-kotlin:$jteVersion")

    // htmx via webjar — hermetic + version-pinned like the rest of the build (no CDN at page load).
    // webjars-locator-lite (version managed by the Spring Boot BOM) lets us serve it version-agnostically
    // at /webjars/htmx.org/dist/htmx.min.js, so an htmx bump doesn't churn the <script src> in layout.kte.
    implementation("org.webjars.npm:htmx.org:2.0.6")
    implementation("org.webjars:webjars-locator-lite")

    // --- markdown rendering: commonmark + GFM tables, with server-side syntax highlighting ---
    // commonmark parses reply/post bodies to HTML. escapeHtml(true) at the renderer makes raw HTML in a
    // body inert and sanitizeUrls(true) strips script-scheme link/image destinations (LLM output is
    // untrusted — prompt-injection XSS), so tables come via the GFM extension, not raw <table>.
    // See MarkdownRenderer.
    implementation("org.commonmark:commonmark:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-gfm-tables:$commonmarkVersion")
    // GraalJS (Truffle) runs highlight.js' core highlight() — string in, <span class=hljs-*> HTML out, no
    // DOM. Runs interpreted on this stock JDK 21 toolchain (no GraalVM JDK needed); fine for short snippets.
    implementation("org.graalvm.polyglot:polyglot:$graalVersion")
    implementation("org.graalvm.polyglot:js-community:$graalVersion")
    // highlight.js itself, vendored as a webjar (hermetic, like htmx): the browser IIFE bundle
    // highlight.min.js is read off the classpath and eval'd in GraalJS; the theme CSS is served to the
    // client at /webjars/highlightjs/styles/... via webjars-locator-lite.
    implementation("org.webjars:highlightjs:$highlightjsWebjarVersion")

    // --- persistence: spring-jdbc + SQLite + Flyway (NOT Hibernate) ---
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")
    // Spring Boot 4 modularised autoconfig — the starter brings flyway-core AND the spring-boot-flyway
    // autoconfiguration module (adding flyway-core alone leaves Flyway un-autoconfigured, so it never runs).
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-nc-sqlite:$flywayVersion")   // real SQLite module name

    // --- kotlin ---
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // Spring Boot 4 defaults to Jackson 3 (tools.jackson). Use the Jackson 3 Kotlin module — the old
    // com.fasterxml jackson-module-kotlin is for Jackson 2 and would be dead weight (its absence is why
    // omitted JSON fields didn't pick up Kotlin defaults).
    implementation("tools.jackson.module:jackson-module-kotlin")

    // --- test: tiers 0-2 + Cucumber acceptance over HTTP ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.platform:junit-platform-suite")
    testImplementation("io.cucumber:cucumber-java:$cucumberVersion")
    testImplementation("io.cucumber:cucumber-spring:$cucumberVersion")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:$cucumberVersion")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// --- JTE: generate template sources at build time, compiled together with the DTOs.
// The plugin wires compileKotlin to depend on generateJte and adds the generated
// sources to the source set, so a wrong field/param fails the build (no browser needed).
jte {
    generate()
    contentType.set(ContentType.Html)
}

// =====================================================================================
// Tiered test model (see the bdd-tiered-testing skill).
// Run order is lowest-first; a break low down ripples up, so read the lowest failing tier.
// Discovery mode flips ignoreFailures so a sea of red doesn't abort the build while scaffolding.
// =====================================================================================
val discoveryMode = (project.findProperty("discovery") == "true") ||
    (System.getenv("DISCOVERY_MODE") == "true")

// The default `test` task would run everything unfiltered and double-count against the
// tiered tasks below — disable it and route through verifyAll instead.
tasks.test { enabled = false }

// Manually-registered Test tasks (unlike the default `test`) don't inherit the test source set's
// classes/classpath — wire them explicitly or the task reports NO-SOURCE.
val testSourceSet = sourceSets.test.get()

// Every test task shares build/aiforum-test.db (application-test.yml). A finished task leaves its
// last test's rows in the file, and another task's per-class children-first DELETE lists can then
// FK-block on tables they predate (e.g. acceptance leftovers vs tier1 cleanup). Start each task
// from a fresh file; Flyway re-migrates in milliseconds.
val testDbFiles = listOf("", "-wal", "-shm").map { layout.buildDirectory.file("aiforum-test.db$it") }
fun Test.freshTestDb() = doFirst { testDbFiles.forEach { it.get().asFile.delete() } }

fun registerTier(name: String, tag: String, after: String?) =
    tasks.register<Test>(name) {
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        // jupiter only + tag filter, so tier tasks never run the Cucumber suite.
        useJUnitPlatform { includeEngines("junit-jupiter"); includeTags(tag) }
        ignoreFailures = discoveryMode
        after?.let { shouldRunAfter(it) }
        testLogging { events("passed", "skipped", "failed") }
        freshTestDb()
    }

registerTier("tier0", "tier0", "mcpShortcutTest")
registerTier("tier1", "tier1", "tier0")
registerTier("tier2", "tier2", "tier1")

// node:test's quoted glob args need Node >= 21 (Docker installs 22; .nvmrc pins 22). Fail fast with
// an actionable message instead of node's own cryptic literal-path error. ProcessBuilder in doFirst,
// not providers.exec: runs at execution time and captures nothing the configuration cache rejects.
fun Exec.requireNode(min: Int = 21) = doFirst {
    val v = runCatching {
        ProcessBuilder("node", "--version").start().inputStream.bufferedReader().readText().trim()
    }.getOrElse { throw GradleException("$name needs Node.js >= $min on PATH — none found (see .nvmrc).") }
    if ((v.removePrefix("v").substringBefore('.').toIntOrNull() ?: 0) < min)
        throw GradleException("$name needs Node >= $min (node --test glob args); found $v. Run `nvm use` (.nvmrc pins 22).")
}

// Frontend unit tier (src/test/js): pure *-core.mjs modules under node:test — the JS analogue of tier0
// (pure logic, no DOM/IO). Delegates to `npm test` so there's one source of truth for the runner glob;
// honours discovery mode like the JVM tiers so red breaks the build rather than being a suggestion.
tasks.register<Exec>("jsTest") {
    group = "verification"
    description = "Runs the frontend (node:test) unit tests."
    workingDir = projectDir
    commandLine("npm", "test")
    isIgnoreExitValue = discoveryMode
    requireNode()
}

// MCP server gates. gh-readonly is zero-dep node:test (root package.json's test:mcp script);
// shortcut is TypeScript and needs its dev deps installed first (npm ci, lockfile-keyed).
tasks.register<Exec>("mcpGhTest") {
    group = "verification"
    description = "Runs the gh-readonly MCP server tests (node:test, zero deps)."
    workingDir = projectDir
    commandLine("npm", "run", "test:mcp")
    isIgnoreExitValue = discoveryMode
    requireNode()
    shouldRunAfter("jsTest")
}

val mcpShortcutInstall = tasks.register<Exec>("mcpShortcutInstall") {
    description = "Installs mcp/shortcut dev deps (npm ci); up-to-date while the lockfile is unchanged."
    workingDir = file("mcp/shortcut")
    commandLine("npm", "ci")
    inputs.files("mcp/shortcut/package.json", "mcp/shortcut/package-lock.json")
    outputs.dir("mcp/shortcut/node_modules")
    requireNode()
}

tasks.register<Exec>("mcpShortcutTest") {
    group = "verification"
    description = "Compiles (full typecheck) + runs the shortcut MCP server tests (tsc + node:test)."
    dependsOn(mcpShortcutInstall)
    workingDir = file("mcp/shortcut")
    commandLine("npm", "test")
    isIgnoreExitValue = discoveryMode
    requireNode()
    shouldRunAfter("mcpGhTest")
}

tasks.register<Test>("acceptance") {
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    // The Cucumber RunCucumberTest @Suite runs via the suite engine; selecting only that engine keeps
    // acceptance from re-running the jupiter tier tests. Tag filter (not @wip) lives in
    // src/test/resources/junit-platform.properties.
    useJUnitPlatform { includeEngines("junit-platform-suite") }
    // Gradle's failOnNoDiscoveredTests can't catch a tag-filter regression: cucumber applies
    // cucumber.filter.tags at execution time, so filtered scenarios still count as "discovered"
    // (they report as skipped). Keep the flag for the nothing-discovered-at-all case, and enforce
    // a floor on *executed* scenarios from cucumber's own report.json below.
    failOnNoDiscoveredTests = !discoveryMode
    ignoreFailures = discoveryMode
    shouldRunAfter("tier2")
    testLogging { events("passed", "skipped", "failed") }

    freshTestDb()
    val report = layout.buildDirectory.file("reports/cucumber/report.json")
    doFirst { report.get().asFile.delete() }   // a stale report must never satisfy the floor
    doLast {
        val executed = report.get().asFile.takeIf { it.isFile }?.readText()
            ?.let { Regex("\"type\"\\s*:\\s*\"scenario\"").findAll(it).count() } ?: 0
        println("acceptance: $executed Cucumber scenarios executed")
        // Ratchet, not just a zero-check: ONLY catches "some scenarios silently stopped running" if it
        // tracks the real count. Bump to the actual executed count whenever a scenario is added (last
        // bumped 285 -> 286 (#18's diff scenario) -> 291 (+ #15's five generation_usage scenarios)
        // -> 297 (+ #16's six usage_observability scenarios, incl. the review's population-claim pin) —
        // three parallel branches, so the merged floor is the sum of all three additions, each step
        // verified against its own tree's green run).
        val floor = 297
        if (executed < floor && !discoveryMode)
            throw GradleException(
                "acceptance executed only $executed of a floor of $floor Cucumber scenarios (green would lie) — " +
                "check cucumber.filter.tags in src/test/resources/junit-platform.properties and feature discovery.")
    }
}

// The LLM jail's contract with Docker (plan_docs/llm-sandbox.md §9). Deliberately NOT a verifyAll
// dependency: it needs a Docker daemon, builds an image, and reaches the real internet — a gate that
// requires those goes red for reasons unrelated to the code. Its tag can't leak into the tiers (each
// filters its own) and it never skips on a missing daemon, because you ran it on purpose.
tasks.register<Test>("jailContract") {
    group = "verification"
    description = "Opt-in (NOT in verifyAll): proves a running jail container can't reach a non-allowlisted " +
        "host and can't see the host filesystem. Requires Docker + network."
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform { includeEngines("junit-jupiter"); includeTags("jailContract") }
    testLogging { events("passed", "skipped", "failed") }
    // Never satisfied from cache: the answer is about the machine's Docker, not about the inputs.
    outputs.upToDateWhen { false }
}

tasks.register("verifyAll") {
    group = "verification"
    description = "Runs all gates lowest-first: jsTest, MCP server tests, tiers 0-2, then acceptance."
    dependsOn("jsTest", "mcpGhTest", "mcpShortcutTest", "tier0", "tier1", "tier2", "acceptance")
}

// `bootRun` (plugin-configured) uses the default `dev` profile → throwaway project-local DB. This
// sibling runs the prod profile for long-term work: a persistent DB at ~/.ai_forum/data/aiforum.db (see
// application-prod.yml; ${user.home} resolves there). The profile is passed as an application arg.
tasks.register<org.springframework.boot.gradle.tasks.run.BootRun>("bootRunProd") {
    group = "application"
    description = "Runs the app with the prod profile (persistent DB at ~/.ai_forum/data/aiforum.db)."
    mainClass.set("com.aiforum.AiForumApplicationKt")
    classpath = sourceSets.main.get().runtimeClasspath   // pulls in generateJte → compile, like bootRun
    args("--spring.profiles.active=prod")
}

tasks.named("check") { dependsOn("verifyAll") }
