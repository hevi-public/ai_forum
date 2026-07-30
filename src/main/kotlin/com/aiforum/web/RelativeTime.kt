package com.aiforum.web

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Compact "time ago" labels for the front-page rail (design "9m", "41m", "1h", "2h"). Pure: the
 * caller passes `now` (from the injected Clock) so it stays deterministic and testable. Coarse on
 * purpose — a forum sidebar wants "3h", not "3h 12m".
 */
object RelativeTime {

    /** Where relative stops earning its keep and a date starts. See [ago]. */
    private const val WEEK_SECONDS = 7L * 86_400

    /** `MMM` renders month names through a locale, so both formatters pin one: without it the label
     *  follows whatever locale the JVM happens to start with, which makes the output environment-
     *  dependent and these tests pass or fail by machine. */
    private val DAY_MONTH: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(ZoneOffset.UTC)
    private val DAY_MONTH_YEAR: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)

    /**
     * **Past a week this returns a DATE, not a day count**, and that is the point rather than a nicety.
     * The `d` bucket used to be unbounded, so a forum whose content all landed in one evening rendered
     * the same "7d" on every card and rail row — a 75-minute conversation flattened into one label that
     * says nothing, and one that degrades further with age ("38d", "412d"). A stale forum should read as
     * *dated*, not as a large number. Sub-week stamps stay relative, which is where relative is the more
     * useful reading ("9m" beats "28 Jul").
     *
     * The year is appended only when [then] falls in a different year from [now], so the common case
     * stays short and an old post can never be mistaken for a recent one.
     *
     * **UTC is not a compromise here:** `ClockConfig` provides `Clock.systemUTC()`, so this renders in
     * the same zone every other stamp in the app is written and compared in. If a local-time clock is
     * ever introduced, this formatter needs the same zone or dates will disagree with the `d` counts
     * immediately above them.
     */
    fun ago(then: Instant, now: Instant): String {
        val secs = Duration.between(then, now).seconds.coerceAtLeast(0)
        return when {
            secs < 60 -> "now"
            secs < 3600 -> "${secs / 60}m"
            secs < 86_400 -> "${secs / 3600}h"
            secs < WEEK_SECONDS -> "${secs / 86_400}d"
            else -> {
                val sameYear = then.atZone(ZoneOffset.UTC).year == now.atZone(ZoneOffset.UTC).year
                (if (sameYear) DAY_MONTH else DAY_MONTH_YEAR).format(then)
            }
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
