package com.jeecountdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Expected values were produced by an independent Java (java.time, JDK 21) run and cross-checked with
 * Python zoneinfo for the DST and time-zone cases.
 */
class CountdownEngineTest {
    private val IST = "Asia/Kolkata"
    private val NY = "America/New_York"

    private fun at(zone: String, local: String): ZonedDateTime = LocalDateTime.parse(local).atZone(ZoneId.of(zone))
    private fun cd(zone: String, nowLocal: String, event: String) =
        CountdownEngine.compute(LocalDate.parse(event), at(zone, nowLocal))

    @Test fun eventToday() {
        val c = cd(IST, "2026-11-21T10:30:15", "2026-11-21")
        assertEquals(Phase.TODAY, c.phase)
        assertEquals(0L, c.calendarDays)
        assertEquals(0L, c.untilStart.seconds)
        assertEquals(48585L, c.remainingToday.seconds)
    }

    @Test fun eventTomorrow() {
        val c = cd(IST, "2026-11-21T10:30:15", "2026-11-22")
        assertEquals(Phase.UPCOMING, c.phase)
        assertEquals(1L, c.calendarDays)
        assertEquals(48585L, c.untilStart.seconds)
        assertEquals(13L, c.hoursUntilStart)
    }

    @Test fun jeeSixtyFourDays() {
        val c = cd(IST, "2026-11-19T00:00:00", "2027-01-22")
        assertEquals(64L, c.calendarDays)
        assertEquals(1536L, c.hoursUntilStart)
    }

    @Test fun exactlyAtMidnightOfEventDayIsToday() {
        val c = cd(IST, "2027-01-20T00:00:00", "2027-01-20")
        assertEquals(Phase.TODAY, c.phase)
        assertEquals(0L, c.untilStart.seconds)
        assertEquals(86400L, c.remainingToday.seconds)
    }

    @Test fun oneSecondBeforeMidnightIsStillOneDayAway() {
        val c = cd(IST, "2027-01-19T23:59:59", "2027-01-20")
        assertEquals(Phase.UPCOMING, c.phase)
        assertEquals(1L, c.calendarDays)
        assertEquals(1L, c.untilStart.seconds)
        assertEquals(0L, c.hoursUntilStart)
        assertEquals(1L, c.remainingToday.seconds)
    }

    @Test fun pastEventIsClampedNotNegative() {
        val c = cd(IST, "2027-01-21T09:00:00", "2027-01-20")
        assertEquals(Phase.PAST, c.phase)
        assertEquals(-1L, c.calendarDays)
        assertEquals(0L, c.untilStart.seconds)
        assertEquals(54000L, c.remainingToday.seconds)
    }

    @Test fun leapYear() {
        assertEquals(2L, cd("UTC", "2028-02-28T12:00:00", "2028-03-01").calendarDays)
        assertEquals(1L, cd("UTC", "2028-02-28T12:00:00", "2028-02-29").calendarDays)
        assertEquals(1L, cd("UTC", "2027-02-28T12:00:00", "2027-03-01").calendarDays)
    }

    @Test fun monthAndYearBoundaries() {
        val y = cd("UTC", "2026-12-31T23:00:00", "2027-01-01")
        assertEquals(1L, y.calendarDays)
        assertEquals(3600L, y.untilStart.seconds)
        val m = cd("UTC", "2026-11-30T18:00:00", "2026-12-01")
        assertEquals(1L, m.calendarDays)
        assertEquals(21600L, m.untilStart.seconds)
    }

    @Test fun dstSpringForwardUntilStartIsExactElapsedTime() {
        // Wall clock says 36h, but the clocks skip an hour: exact elapsed time is 35h. (v2's naive math said 36.)
        val c = cd(NY, "2027-03-13T12:00:00", "2027-03-15")
        assertEquals(2L, c.calendarDays)
        assertEquals(126000L, c.untilStart.seconds)
        assertEquals(35L, c.hoursUntilStart)
    }

    @Test fun dstFallBackUntilStartIsExactElapsedTime() {
        val c = cd(NY, "2027-11-06T12:00:00", "2027-11-08")
        assertEquals(2L, c.calendarDays)
        assertEquals(133200L, c.untilStart.seconds)
        assertEquals(37L, c.hoursUntilStart)
    }

    @Test fun timeLeftTodayIs23hOnSpringForwardAnd25hOnFallBack() {
        assertEquals(82800L, cd(NY, "2027-03-14T00:00:00", "2027-03-15").remainingToday.seconds)
        assertEquals(90000L, cd(NY, "2027-11-07T00:00:00", "2027-11-08").remainingToday.seconds)
    }

    @Test fun sameInstantGivesDifferentCalendarDaysInDifferentZones() {
        val instant = Instant.parse("2026-11-20T20:00:00Z")
        val event = LocalDate.parse("2026-11-21")
        val kolkata = CountdownEngine.compute(event, instant.atZone(ZoneId.of(IST)))
        val la = CountdownEngine.compute(event, instant.atZone(ZoneId.of("America/Los_Angeles")))
        val auckland = CountdownEngine.compute(event, instant.atZone(ZoneId.of("Pacific/Auckland")))
        assertEquals(Phase.TODAY, kolkata.phase)
        assertEquals(Phase.UPCOMING, la.phase)
        assertEquals(1L, la.calendarDays)
        assertEquals(43200L, la.untilStart.seconds)
        assertEquals(Phase.TODAY, auckland.phase)
    }

    @Test fun deviceClockChangesJustRecompute() {
        assertEquals(11L, cd("UTC", "2026-11-20T12:00:00", "2026-12-01").calendarDays)
        assertEquals(21L, cd("UTC", "2026-11-10T12:00:00", "2026-12-01").calendarDays) // clock set back
    }

    @Test fun weeksAreFlooredAndNeverNegative() {
        assertEquals(0L, CountdownEngine.weeks(-3))
        assertEquals(0L, CountdownEngine.weeks(0))
        assertEquals(0L, CountdownEngine.weeks(6))
        assertEquals(1L, CountdownEngine.weeks(7))
        assertEquals(9L, CountdownEngine.weeks(64))
    }

    @Test fun threeQuantitiesAreDistinct() {
        // 2 calendar days away, but only 36 exact hours, and 12h left today: they must not be conflated.
        val c = cd("UTC", "2028-02-28T12:00:00", "2028-03-01")
        assertEquals(2L, c.calendarDays)
        assertEquals(36L, c.hoursUntilStart)
        assertEquals(12L * 3600L, c.remainingToday.seconds)
        assertTrue(c.untilStart.seconds != c.calendarDays * 86400L)
    }
}
