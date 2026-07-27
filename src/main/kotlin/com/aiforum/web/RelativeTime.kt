package com.aiforum.web

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Compact "time ago" labels for the front-page rail (design "9m", "41m", "1h", "2h"). Pure: the
 * caller passes `now` (from the injected Clock) so it stays deterministic and testable. Coarse on
 * purpose — a forum sidebar wants "3h", not "3h 12m".
 */
object RelativeTime {
    fun ago(then: Instant, now: Instant): String {
        val secs = Duration.between(then, now).seconds.coerceAtLeast(0)
        return when {
            secs < 60 -> "now"
            secs < 3600 -> "${secs / 60}m"
            secs < 86_400 -> "${secs / 3600}h"
            else -> "${secs / 86_400}d"
        }
    }

    /**
     * [ago] for a stamp still in the text form a DB column hands over: null when it will not parse,
     * so one malformed row costs its own card a timestamp instead of costing the whole feed a 500.
     *
     * Additive on purpose. The four existing unguarded `Instant.parse` sites (RailFeeds ×3,
     * AmbientController) are left throwing: they sit on every thread page, and changing what a
     * corrupt row does there is a behaviour change that belongs in its own PR, not smuggled in
     * behind a new front page (ambient-slice-6 §5).
     */
    fun agoOrNull(raw: String?, now: Instant): String? {
        if (raw == null) return null
        val then = try {
            Instant.parse(raw)
        } catch (_: DateTimeParseException) {
            return null
        }
        return ago(then, now)
    }
}
