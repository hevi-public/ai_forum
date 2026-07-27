package com.aiforum.tier0

import com.aiforum.service.MemoryScribeService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tier-0: the scribe queue's window-age comparator ([MemoryScribeService.BY_WINDOW_AGE]) — pure
 * ordering over (judgedAt, personaId) pairs, no Spring, no service. Pinned here INCLUDING the
 * null-vs-stamped case, which is the S4b §10.4 gap (the drift comparator's `nullsFirst` shipped
 * with no test asserting a null actually beats a stamp) closed in this slice rather than repeated.
 *
 * Why the order is load-bearing: `take(cap)` takes the first N, so whatever sorts first holds the
 * budget. Oldest window first — never-judged ahead of everything — is what makes a biting cap
 * ROTATE: a judged member is stamped, drops to the back, and the next run reaches further down.
 * Name order would hand an alphabetically early member a permanent claim.
 */
@Tag("tier0")
class MemoryWindowOrderTest {

    private val comparator = MemoryScribeService.BY_WINDOW_AGE

    private fun at(stamp: String): Instant = Instant.parse(stamp)

    @Test
    fun `a never-judged member sorts ahead of every stamped one - null beats a stamp, both directions`() {
        // The S4b §10.4 case, asserted in BOTH argument orders: a comparator that treated null as
        // "greatest" would pass a sorted-list smoke test on some fixtures and starve every
        // never-judged member under a biting cap.
        val never = null to "zed"
        val judged = at("2026-01-01T09:00:00Z") to "ann"
        assertTrue(comparator.compare(never, judged) < 0, "never-judged goes first, whatever its id")
        assertTrue(comparator.compare(judged, never) > 0, "and the comparison is antisymmetric")
    }

    @Test
    fun `the older window sorts first`() {
        val older = at("2026-01-01T09:00:00Z") to "zed"
        val newer = at("2026-01-01T12:00:00Z") to "ann"
        assertTrue(comparator.compare(older, newer) < 0, "window age decides, not the id")
    }

    @Test
    fun `equal windows fall back to the persona id, so a capped run is reproducible`() {
        val stamp = at("2026-01-01T09:00:00Z")
        assertTrue(comparator.compare(stamp to "ann", stamp to "zed") < 0)
        assertTrue(comparator.compare(stamp to "zed", stamp to "ann") > 0)
        assertEquals(0, comparator.compare(stamp to "ann", stamp to "ann"))
    }

    @Test
    fun `two never-judged members tie-break on id too`() {
        assertTrue(comparator.compare(null to "ann", null to "zed") < 0)
        assertEquals(0, comparator.compare(null to "ann", null to "ann"))
    }

    @Test
    fun `a full sort puts never-judged first, then oldest window, then id`() {
        val queue = listOf(
            at("2026-01-01T12:00:00Z") to "ann",
            null to "zed",
            at("2026-01-01T09:00:00Z") to "bea",
            at("2026-01-01T09:00:00Z") to "abe",
            null to "moe",
        ).sortedWith(comparator)

        assertEquals(
            listOf("moe", "zed", "abe", "bea", "ann"),
            queue.map { it.second },
            "null windows lead (id-ordered), then stamps oldest-first with the id tiebreak",
        )
    }
}
