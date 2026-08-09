package com.aiforum.tier1.repo

import com.aiforum.acceptance.support.TestData
import com.aiforum.llm.ToolCall
import com.aiforum.llm.ToolSummaries
import com.aiforum.repo.GenerationToolCallRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.Instant

/**
 * Tier-1: [GenerationToolCallRepository] against the real test SQLite DB (see the bdd-tiered-testing
 * skill). Pins the V30 round-trip plus the three properties the header promises and no DDL enforces:
 * the caps hold at the write door, a dateless call still gets a `started_at`, and the CASCADE reaches
 * exactly the comment-linked rows and no further.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class GenerationToolCallRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var data: TestData
    @Autowired lateinit var repo: GenerationToolCallRepository
    @Autowired lateinit var clock: Clock

    @BeforeEach
    fun clean() {
        listOf("generation_tool_call", "vote", "comment_revision", "event_log", "comment", "thread", "persona")
            .forEach { jdbc.update("DELETE FROM $it") }
    }

    private data class Row(
        val runId: String, val commentId: String?, val seq: Int, val toolName: String,
        val input: String?, val output: String?, val isError: Int, val startedAt: String?, val endedAt: String?,
    )

    private fun rows(): List<Row> = jdbc.query(
        "SELECT run_id, comment_id, seq, tool_name, input_summary, output_summary, is_error, started_at, " +
            "ended_at FROM generation_tool_call ORDER BY id",
    ) { rs, _ ->
        Row(
            rs.getString("run_id"), rs.getString("comment_id"), rs.getInt("seq"), rs.getString("tool_name"),
            rs.getString("input_summary"), rs.getString("output_summary"), rs.getInt("is_error"),
            rs.getString("started_at"), rs.getString("ended_at"),
        )
    }

    @Test
    fun `a trace round-trips with its calls numbered from one in the order given`() {
        // run_id IS the settled comment's id (V30's header) — and the comment_id FK is real, so a linked
        // trace needs the reply to exist. That constraint is itself part of the round-trip.
        val thread = data.insertThread("Scaling SQLite")
        val reply = data.insertComment(thread, authorId = "sol", body = "the answer")
        val started = Instant.parse("2026-01-01T12:00:00Z")
        val ended = Instant.parse("2026-01-01T12:00:02Z")
        repo.record(
            reply, reply,
            listOf(
                ToolCall("toolu_a", "Read", "{\"file_path\":\"/wal.c\"}", "static int walCheckpoint", false, started, ended),
                ToolCall("toolu_b", "Bash", "{\"cmd\":\"ls\"}", "boom", isError = true, startedAt = started, endedAt = ended),
            ),
        )

        assertEquals(
            listOf(
                Row(reply, reply, 1, "Read", "{\"file_path\":\"/wal.c\"}", "static int walCheckpoint", 0, started.toString(), ended.toString()),
                Row(reply, reply, 2, "Bash", "{\"cmd\":\"ls\"}", "boom", 1, started.toString(), ended.toString()),
            ),
            rows(),
        )
    }

    @Test
    fun `oversized summaries are re-clipped at the write door`() {
        // The parser already clips; this proves the cap is a property of what is STORED, so a future
        // second writer that skipped the parser cannot land a megabyte in the table.
        repo.record(
            "run-1", null,
            listOf(ToolCall("t", "Bash", "i".repeat(9_000), "o".repeat(9_000))),
        )

        val row = rows().single()
        assertEquals(ToolSummaries.INPUT_CAP, row.input!!.length)
        assertEquals(ToolSummaries.OUTPUT_CAP, row.output!!.length)
        assertTrue(row.input.endsWith(ToolSummaries.MARKER), "a clipped summary says so")
        assertTrue(row.output.endsWith(ToolSummaries.MARKER), "a clipped summary says so")
    }

    @Test
    fun `a call the stream never dated gets started_at from the clock, and no invented end`() {
        // A NULL started_at would drop the row out of every time-window read — an audit row that exists
        // but cannot be found. ended_at gets NO such fallback: absent means the result never came back.
        repo.record("run-1", null, listOf(ToolCall("t", "WebFetch")))

        val row = rows().single()
        assertEquals(clock.instant().toString(), row.startedAt)
        assertNull(row.endedAt, "a call whose result never arrived must not claim an end time")
    }

    @Test
    fun `a NULL comment_id is accepted — a failed run still leaves a trace`() {
        repo.record("run-1", null, listOf(ToolCall("t", "Read")))

        assertNull(rows().single().commentId)
    }

    @Test
    fun `deleting the reply cascades its trace away, and leaves an unlinked trace standing`() {
        val thread = data.insertThread("Scaling SQLite")
        val reply = data.insertComment(thread, authorId = "sol", body = "the answer")
        repo.record(reply, reply, listOf(ToolCall("t1", "Read")))
        repo.record("failed-run", null, listOf(ToolCall("t2", "Bash")))

        jdbc.update("DELETE FROM comment WHERE id = ?", reply)

        val remaining = rows()
        assertEquals(listOf("Bash"), remaining.map { it.toolName }, "the linked trace went with its reply")
        assertFalse(remaining.any { it.commentId != null }, "nothing linked survives the delete")
    }
}
