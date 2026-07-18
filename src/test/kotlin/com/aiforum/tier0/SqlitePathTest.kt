package com.aiforum.tier0

import com.aiforum.config.SqlitePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: the pure `jdbc:sqlite:` URL parsing/expansion behind [DataDirectoryInitializer]. No IO — just
 * that a leading `~` is resolved against the given home dir (so a literal `~` can never reach the
 * filesystem), the query string is stripped for the file path, non-file URLs return null, and ordinary
 * paths pass through untouched.
 */
@Tag("tier0")
class SqlitePathTest {

    private val home = "/home/tester"

    @Test
    fun `expands a leading tilde-slash against the home dir`() {
        val r = SqlitePath.expand("jdbc:sqlite:~/.ai_forum/data/aiforum.db", home)!!
        assertEquals("/home/tester/.ai_forum/data/aiforum.db", r.filePath)
        assertEquals("jdbc:sqlite:/home/tester/.ai_forum/data/aiforum.db", r.url)
    }

    @Test
    fun `expands a bare tilde to the home dir`() {
        assertEquals(home, SqlitePath.expand("jdbc:sqlite:~", home)!!.filePath)
    }

    @Test
    fun `preserves the query string when expanding`() {
        val r = SqlitePath.expand("jdbc:sqlite:~/x/db?journal_mode=WAL&busy_timeout=5000", home)!!
        assertEquals("/home/tester/x/db", r.filePath)
        assertEquals("jdbc:sqlite:/home/tester/x/db?journal_mode=WAL&busy_timeout=5000", r.url)
    }

    @Test
    fun `a trailing slash on the home dir is not doubled`() {
        assertEquals("/home/tester/x", SqlitePath.expand("jdbc:sqlite:~/x", "/home/tester/")!!.filePath)
    }

    @Test
    fun `a relative path passes through unchanged (dev profile unaffected)`() {
        val url = "jdbc:sqlite:data/aiforum-dev.db?journal_mode=WAL"
        val r = SqlitePath.expand(url, home)!!
        assertEquals("data/aiforum-dev.db", r.filePath)
        assertEquals(url, r.url, "no expansion => the URL is returned verbatim")
    }

    @Test
    fun `an absolute path with no tilde passes through unchanged`() {
        val url = "jdbc:sqlite:/var/lib/aiforum.db"
        assertEquals(url, SqlitePath.expand(url, home)!!.url)
    }

    @Test
    fun `a mid-path tilde is left alone — only a leading tilde is a shell-ism`() {
        assertEquals("/var/~weird/db", SqlitePath.expand("jdbc:sqlite:/var/~weird/db", home)!!.filePath)
    }

    @Test
    fun `in-memory databases return null (no file to back)`() {
        assertNull(SqlitePath.expand("jdbc:sqlite::memory:", home))
        assertNull(SqlitePath.expand("jdbc:sqlite:file::memory:?cache=shared", home))
        assertNull(SqlitePath.expand("jdbc:sqlite:", home))
    }

    @Test
    fun `a non-sqlite url returns null`() {
        assertNull(SqlitePath.expand("jdbc:postgresql://localhost:5432/aiforum", home))
    }

    @Test
    fun `the resolved path and url never contain a leading tilde`() {
        // The core guarantee: whatever we hand back can never create a junk `~` directory.
        val r = SqlitePath.expand("jdbc:sqlite:~/.ai_forum/data/aiforum.db?foreign_keys=on", home)!!
        assertFalse(r.filePath.startsWith("~"), "expanded file path must not start with ~")
        assertFalse(r.url.removePrefix("jdbc:sqlite:").startsWith("~"), "expanded url path must not start with ~")
    }
}
