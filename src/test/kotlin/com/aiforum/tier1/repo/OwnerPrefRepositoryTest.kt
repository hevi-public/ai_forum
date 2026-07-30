package com.aiforum.tier1.repo

import com.aiforum.repo.OwnerPrefRepository
import com.aiforum.web.FeedView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Tier-1: [OwnerPrefRepository] against the real test SQLite DB (V29 `owner_pref`,
 * plan_docs/ambient-slice-6.md §2.3). Three properties, and only one of them is about round-tripping a
 * value:
 *
 * - **Absence is the default.** No row means [FeedView.DEFAULT], and reading must not create one — a
 *   repository that seeded a row on first read would quietly convert "the owner has never chosen" into
 *   "the owner chose threads", which is the state every pre-S6 front-page scenario depends on being
 *   distinguishable (D4).
 * - **The DDL is the enforcement, proven below the repository.** The raw-SQL tests bypass
 *   [OwnerPrefRepository.setFeedView] entirely, so what refuses `id = 2` and `feed_view = 'chronological'`
 *   can only be V29's CHECKs. Driven through the enum these shapes are unbuildable, which is exactly why
 *   they have to be attempted by hand — otherwise the bottom layer of the three would be untested and
 *   nobody would notice until someone "simplified" the DDL.
 * - **A corrupt preference is not a broken front page.** A value no [FeedView] slug matches reads as the
 *   default instead of throwing, because the alternative is a 500 on the only page the forum has.
 */
@Tag("tier1")
@SpringBootTest
@ActiveProfiles("test")
class OwnerPrefRepositoryTest {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var prefs: OwnerPrefRepository

    @BeforeEach @AfterEach
    fun clean() {
        jdbc.update("DELETE FROM owner_pref")
    }

    private fun rowCount(): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM owner_pref", Int::class.java) ?: 0

    private fun storedView(): String? =
        jdbc.queryForList("SELECT feed_view FROM owner_pref", String::class.java).firstOrNull()

    @Test
    fun `a forum whose owner has never chosen opens on the thread cards, and reading chooses nothing`() {
        // The empty table IS the shipped state: V29 seeds no row (D4).
        assertEquals(0, rowCount())

        assertEquals(FeedView.THREADS, prefs.feedView())

        assertEquals(0, rowCount(), "reading a preference must never write one — absence has to stay absence")
    }

    @Test
    fun `a chosen view is what the next read returns`() {
        prefs.setFeedView(FeedView.ACTIVITY)

        assertEquals(FeedView.ACTIVITY, prefs.feedView())
        assertEquals("activity", storedView(), "the slug is what lands in the column, not the enum's name")
    }

    @Test
    fun `choosing twice leaves one row and the latest choice wins`() {
        // The upsert's whole job: `id` is a literal 1, so every choice after the first hits an existing
        // row. An insert-only writer would throw here; a delete-then-insert one would leave a window
        // where the front page has no preference at all.
        prefs.setFeedView(FeedView.ACTIVITY)
        prefs.setFeedView(FeedView.THREADS)
        prefs.setFeedView(FeedView.ACTIVITY)

        assertEquals(1, rowCount(), "one owner, one row — the id = 1 CHECK makes any other count impossible")
        assertEquals(FeedView.ACTIVITY, prefs.feedView())
    }

    @Test
    fun `the DDL refuses a second preference row, below the repository`() {
        // Raw SQL on purpose: setFeedView cannot even express an id, so the only thing that can refuse
        // this write is V29's CHECK (id = 1). "Two preferences are unrepresentable" (I3) is a database
        // fact or it is nothing.
        prefs.setFeedView(FeedView.ACTIVITY)

        assertThrows(DataAccessException::class.java) {
            jdbc.update(
                "INSERT INTO owner_pref(id, feed_view, updated_at) VALUES (2,'threads','2026-01-01T12:00:00Z')",
            )
        }

        assertEquals(1, rowCount(), "the refused write left nothing behind")
        assertEquals(FeedView.ACTIVITY, prefs.feedView(), "and did not disturb the standing choice")
    }

    @Test
    fun `the DDL refuses a view it does not know, below the enum`() {
        // Same argument one column over: setFeedView takes FeedView, so no caller can spell
        // 'chronological' through it. This UPDATE is what a future String-shaped door, a migration
        // written in a hurry, or a hand edit would do — and the CHECK is what stands there.
        prefs.setFeedView(FeedView.ACTIVITY)

        assertThrows(DataAccessException::class.java) {
            jdbc.update("UPDATE owner_pref SET feed_view = 'chronological' WHERE id = 1")
        }

        assertEquals("activity", storedView(), "the stored row is untouched by the refused update")
    }

    @Test
    fun `a stored view that names nothing reads as the default rather than taking the page down`() {
        prefs.setFeedView(FeedView.ACTIVITY)

        // The CHECK makes this row unreachable through every door this build has — which is precisely
        // why the constraint has to be switched off for one statement to build it. That is the branch's
        // whole subject: a value some older CHECK list admitted, or a hand-edited database. The pragma is
        // restored in a finally because the test datasource pools ONE connection and the setting is
        // per-connection, so leaking it would quietly disarm every CHECK in the rest of the run.
        jdbc.execute("PRAGMA ignore_check_constraints = ON")
        try {
            jdbc.update("UPDATE owner_pref SET feed_view = 'chronological' WHERE id = 1")
        } finally {
            jdbc.execute("PRAGMA ignore_check_constraints = OFF")
        }
        assertEquals("chronological", storedView(), "the fixture really is corrupt — otherwise this proves nothing")

        assertEquals(FeedView.THREADS, prefs.feedView())
    }
}
