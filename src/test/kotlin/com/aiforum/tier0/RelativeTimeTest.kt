package com.aiforum.tier0

import com.aiforum.web.RelativeTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tier-0: the pure "time ago" label behind the front-page Active-threads box. No IO — `now` is passed
 * in, so the coarse buckets (now / Xm / Xh / Xd) are deterministic.
 *
 * `agoOrNull` is the feed card's null-tolerant door onto the same buckets. Its null branch is pinned
 * here and nowhere else: no fixture in the suite seeds a corrupt stamp (ambient-slice-6 §11), so these
 * are the only tests standing between the degrade and a 500 on the front page.
 */
@Tag("tier0")
class RelativeTimeTest {

    private val now = Instant.parse("2026-06-21T12:00:00Z")
    private fun agoOf(d: java.time.Duration) = RelativeTime.ago(now.minus(d), now)

    @Test
    fun `under a minute reads as now`() {
        assertEquals("now", agoOf(java.time.Duration.ofSeconds(0)))
        assertEquals("now", agoOf(java.time.Duration.ofSeconds(59)))
    }

    @Test
    fun `minutes between one minute and an hour`() {
        assertEquals("1m", agoOf(java.time.Duration.ofMinutes(1)))
        assertEquals("41m", agoOf(java.time.Duration.ofMinutes(41)))
        assertEquals("59m", agoOf(java.time.Duration.ofSeconds(3599)))
    }

    @Test
    fun `hours between one hour and a day`() {
        assertEquals("1h", agoOf(java.time.Duration.ofHours(1)))
        assertEquals("23h", agoOf(java.time.Duration.ofHours(23)))
    }

    @Test
    fun `days past a day`() {
        assertEquals("1d", agoOf(java.time.Duration.ofDays(1)))
        assertEquals("9d", agoOf(java.time.Duration.ofDays(9)))
    }

    @Test
    fun `a future instant clamps to now rather than going negative`() {
        assertEquals("now", RelativeTime.ago(now.plusSeconds(120), now))
    }

    @Test
    fun `agoOrNull returns exactly what ago returns for a stamp that parses`() {
        val stamp = "2026-06-21T11:00:00Z"
        assertEquals(RelativeTime.ago(Instant.parse(stamp), now), RelativeTime.agoOrNull(stamp, now))
        assertEquals("1h", RelativeTime.agoOrNull(stamp, now))
    }

    @Test
    fun `agoOrNull returns null for a stamp that will not parse`() {
        assertNull(RelativeTime.agoOrNull("not-a-timestamp", now))
        assertNull(RelativeTime.agoOrNull("2026-06-21 12:00:00", now), "a space instead of T is the shape a hand-edited row takes")
        assertNull(RelativeTime.agoOrNull("", now))
    }

    @Test
    fun `agoOrNull returns null for an absent stamp`() {
        assertNull(RelativeTime.agoOrNull(null, now))
    }
}
