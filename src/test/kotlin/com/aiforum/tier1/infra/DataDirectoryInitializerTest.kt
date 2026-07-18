package com.aiforum.tier1.infra

import com.aiforum.config.DataDirectoryInitializer
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tier-1: [DataDirectoryInitializer] does real filesystem IO, so it's pinned against a temp dir rather
 * than as a pure function. It must create the parent directory of a relative SQLite file before Flyway
 * opens it (the fresh-checkout `[SQLITE_CANTOPEN]` fix), and stay a no-op for non-file URLs.
 */
@Tag("tier1")
class DataDirectoryInitializerTest {

    private fun runWith(url: String) {
        runReturningEnv(url)
    }

    private fun runReturningEnv(url: String): MockEnvironment {
        val env = MockEnvironment().withProperty("spring.datasource.url", url)
        DataDirectoryInitializer().postProcessEnvironment(env, SpringApplication())
        return env
    }

    @Test
    fun `creates the missing parent directory of a relative sqlite file`(@TempDir tmp: Path) {
        val dataDir = tmp.resolve("data/nested")
        assertFalse(Files.exists(dataDir), "precondition: dir should not exist yet")

        runWith("jdbc:sqlite:$dataDir/aiforum.db?journal_mode=WAL&busy_timeout=5000")

        assertTrue(Files.isDirectory(dataDir), "expected the SQLite parent dir to be created")
    }

    @Test
    fun `is idempotent when the directory already exists`(@TempDir tmp: Path) {
        val dataDir = Files.createDirectories(tmp.resolve("data"))
        assertDoesNotThrow { runWith("jdbc:sqlite:$dataDir/aiforum.db") }
        assertTrue(Files.isDirectory(dataDir))
    }

    @Test
    fun `is a no-op for an in-memory database`() {
        assertDoesNotThrow { runWith("jdbc:sqlite::memory:") }
    }

    @Test
    fun `ignores a non-sqlite datasource url`() {
        assertDoesNotThrow { runWith("jdbc:postgresql://localhost:5432/aiforum") }
    }

    @Test
    fun `expands a leading tilde to the home dir and never creates a junk ~ directory`(@TempDir tmp: Path) {
        val originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tmp.toString())
        try {
            runWith("jdbc:sqlite:~/.ai_forum/data/aiforum.db?journal_mode=WAL")

            assertTrue(Files.isDirectory(tmp.resolve(".ai_forum/data")), "expected the home-expanded parent dir")
            // The headline guarantee: a literal `~/.ai_forum/…` was NOT created relative to the cwd.
            assertFalse(Files.exists(Path.of("~", ".ai_forum")), "a literal ~ directory must never be created")
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }

    @Test
    fun `republishes the resolved url so the driver opens the expanded path, ~-free`(@TempDir tmp: Path) {
        val originalHome = System.getProperty("user.home")
        System.setProperty("user.home", tmp.toString())
        try {
            val env = runReturningEnv("jdbc:sqlite:~/.ai_forum/data/aiforum.db?journal_mode=WAL")

            val resolved = env.getProperty("spring.datasource.url")!!
            assertFalse(resolved.contains("~"), "the republished datasource url must contain no literal ~")
            assertTrue(resolved.contains(tmp.toString()), "the url must point at the home-expanded path")
            assertTrue(resolved.endsWith("?journal_mode=WAL"), "the query string must be preserved")
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }

    @Test
    fun `leaves a tilde-free url untouched (no republish)`(@TempDir tmp: Path) {
        val url = "jdbc:sqlite:$tmp/data/aiforum.db?journal_mode=WAL"
        val env = runReturningEnv(url)
        assertEquals(url, env.getProperty("spring.datasource.url"), "a url with no ~ must not be rewritten")
    }
}
