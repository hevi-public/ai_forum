package com.aiforum.repo

import com.aiforum.dto.ForumStats
import com.aiforum.dto.PersonaActivity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Read-only forum-wide aggregates for the admin dashboard (§ admin page). All queries are plain
 * COUNT/GROUP BY over the existing schema — no migration, no mutation — mirroring the idioms already
 * used elsewhere (e.g. [VoteRepository.countAll]). One [snapshot] call answers the whole page.
 */
@Repository
class StatsRepository(private val jdbc: JdbcTemplate) {

    fun snapshot(): ForumStats = ForumStats(
        threads = countOf("thread"),
        personas = countOf("persona"),
        commentsByState = groupCount("SELECT state, COUNT(*) FROM comment GROUP BY state"),
        failuresByCategory = groupCount(
            "SELECT failure_category, COUNT(*) FROM comment WHERE failure_category IS NOT NULL GROUP BY failure_category",
        ),
        personaActivity = personaActivity(),
        votes = countOf("vote"),
        regeneratedComments = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT comment_id) FROM comment_revision", Int::class.java,
        ) ?: 0,
        attachments = countOf("attachment"),
        attachmentBytes = jdbc.queryForObject(
            "SELECT COALESCE(SUM(byte_size), 0) FROM attachment", Long::class.java,
        ) ?: 0L,
        captionsByState = groupCount("SELECT caption_state, COUNT(*) FROM attachment GROUP BY caption_state"),
        reasoningLeaks = groupCount(
            "SELECT reasoning_leak, COUNT(*) FROM comment WHERE reasoning_leak IS NOT NULL GROUP BY reasoning_leak",
        ),
        // Ambient loop (plan_docs/ambient-slice-1.md): the run counter, and the thread split on author_id —
        // NULL = owner-authored, NOT NULL = a persona-authored (ambient) thread.
        ambientRuns = countOf("ambient_run"),
        threadsOwner = jdbc.queryForObject("SELECT COUNT(*) FROM thread WHERE author_id IS NULL", Int::class.java) ?: 0,
        threadsPersona = jdbc.queryForObject("SELECT COUNT(*) FROM thread WHERE author_id IS NOT NULL", Int::class.java) ?: 0,
    )

    private fun countOf(table: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java) ?: 0

    /** Run a `SELECT <key>, COUNT(*) …` and collect it into a key→count map (null keys dropped). */
    private fun groupCount(sql: String): Map<String, Int> =
        jdbc.query(sql) { rs, _ -> rs.getString(1) to rs.getInt(2) }
            .filter { it.first != null }
            .toMap()

    /** POSTED comments per author, joined to the persona name where the author is a persona. */
    private fun personaActivity(): List<PersonaActivity> =
        jdbc.query(
            """
            SELECT c.author_id AS author_id, p.name AS name, COUNT(*) AS posted
            FROM comment c
            LEFT JOIN persona p ON p.id = c.author_id
            WHERE c.state = 'POSTED'
            GROUP BY c.author_id
            ORDER BY posted DESC, c.author_id
            """.trimIndent(),
        ) { rs, _ ->
            PersonaActivity(rs.getString("author_id"), rs.getString("name"), rs.getInt("posted"))
        }
}
