package com.jeecountdown

import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Single source of truth for ALL countdown arithmetic (wallpaper, widget, preview, desk mode, screensaver).
 *
 * Event model (deliberate, documented assumption): an event is an all-day CALENDAR DATE, interpreted in the
 * device's CURRENT time zone ("floating" date). It begins at 00:00 local time on that date. We do not assume
 * a start time-of-day or a fixed exam time zone; both are future features (P1).
 *
 * Three different quantities are kept strictly apart:
 *  - [Countdown.calendarDays]   whole calendar days between today's local date and the event date.
 *  - [Countdown.untilStart]     exact elapsed duration until 00:00 of the event date (DST-correct, never negative).
 *  - [Countdown.remainingToday] exact elapsed duration until the next local midnight (23h/25h on DST days).
 */
enum class Phase { UPCOMING, TODAY, PAST }

class Countdown(
    val phase: Phase,
    val calendarDays: Long,
    val untilStart: Duration,
    val remainingToday: Duration
) {
    /** Whole hours until the event date begins (0 on the event day itself). */
    val hoursUntilStart: Long get() = untilStart.toHours()
}

object CountdownEngine {
    private const val DAYS_PER_WEEK = 7L

    /** [now] carries the zone: pass ZonedDateTime.now() so the CURRENT default zone is always used. */
    fun compute(eventDate: LocalDate, now: ZonedDateTime): Countdown {
        val zone = now.zone
        val today = now.toLocalDate()
        val days = ChronoUnit.DAYS.between(today, eventDate)
        // atStartOfDay(zone) correctly handles zones whose DST gap swallows midnight.
        val untilStart = Duration.between(now, eventDate.atStartOfDay(zone))
        val remainingToday = Duration.between(now, today.plusDays(1).atStartOfDay(zone))
        val phase = when {
            days > 0L -> Phase.UPCOMING
            days == 0L -> Phase.TODAY
            else -> Phase.PAST
        }
        return Countdown(phase, days, if (untilStart.isNegative) Duration.ZERO else untilStart, remainingToday)
    }

    /** Whole weeks (floor); never negative. */
    fun weeks(calendarDays: Long): Long = if (calendarDays <= 0L) 0L else calendarDays / DAYS_PER_WEEK
}

/**
 * Pure timing helpers shared by every surface. Kept free of Android types so they are unit-testable on the JVM.
 *
 * Why this exists: a plain "wait 30 s" loop can show yesterday's number (or a stale minute) for up to 30 s after
 * midnight, and a plain "wait 1 s" loop drifts so the seconds readout skips and repeats. Aligning every wake-up to
 * the wall-clock grid fixes both without extra wake-ups.
 */
object Cadence {
    /** Wake slightly AFTER a boundary so the value we compute is already the new one. */
    const val SLACK_MS = 20L

    /** Delay until the next multiple of [periodMs] on the epoch clock, plus [SLACK_MS]. Range: 1+slack .. period+slack. */
    fun alignedDelay(nowMs: Long, periodMs: Long): Long {
        require(periodMs > 0L) { "periodMs must be positive" }
        val into = Math.floorMod(nowMs, periodMs)
        return periodMs - into + SLACK_MS
    }

    /**
     * Epoch millis of the next local midnight in [now]'s zone. Uses atStartOfDay, so a zone whose DST gap swallows
     * 00:00 (e.g. America/Havana) correctly yields the first valid instant of the next day.
     */
    fun nextMidnightMillis(now: ZonedDateTime): Long =
        now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
}
