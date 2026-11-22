package com.jeecountdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Regression tests for the timing helpers (render ticks aligned to the clock grid; widget midnight alarm).
 * Expected values were checked by an independent Java run (JDK 21) and, for the DST cases, Python zoneinfo.
 */
class CadenceTest {
    private fun at(zone: String, local: String): ZonedDateTime = LocalDateTime.parse(local).atZone(ZoneId.of(zone))
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    // ---- alignedDelay ----
    @Test fun delayLandsJustAfterTheNextBoundary() {
        assertEquals(1020L, Cadence.alignedDelay(1_000_000L, 1_000L))   // exactly on a boundary -> a full period later
        assertEquals(770L, Cadence.alignedDelay(1_000_250L, 1_000L))
        assertEquals(21L, Cadence.alignedDelay(30_000L * 5 + 29_999L, 30_000L))
    }

    @Test fun negativeEpochTimesStillAlign() {
        assertEquals(21L, Cadence.alignedDelay(-1L, 30_000L))
    }

    @Test fun delayIsAlwaysPositiveBoundedAndOnTheGrid() {
        for (period in longArrayOf(1_000L, 30_000L)) {
            var t = -70_000L
            while (t <= 130_000L) {
                val d = Cadence.alignedDelay(t, period)
                assertTrue("t=$t period=$period d=$d", d >= 1L + Cadence.SLACK_MS && d <= period + Cadence.SLACK_MS)
                assertEquals(Cadence.SLACK_MS, Math.floorMod(t + d, period))
                t += 37L
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositivePeriodIsRejected() {
        Cadence.alignedDelay(0L, 0L)
    }

    // ---- nextMidnightMillis (drives the widget's midnight refresh) ----
    @Test fun midnightInIndia() {
        val m = Cadence.nextMidnightMillis(at("Asia/Kolkata", "2026-11-21T10:30:15"))
        assertEquals(ms("2026-11-21T18:30:00Z"), m)
        assertEquals(0L, Math.floorMod(m, 30_000L)) // on the 30 s grid, so the day flips within ~20 ms of midnight
    }

    @Test fun midnightInAHalfHourPlusZone() {
        assertEquals(ms("2026-11-21T18:15:00Z"), Cadence.nextMidnightMillis(at("Asia/Kathmandu", "2026-11-21T10:30:15")))
    }

    @Test fun midnightAcrossDstChangesIsExact() {
        val spring = at("America/New_York", "2027-03-14T00:00:00")
        val springMid = Cadence.nextMidnightMillis(spring)
        assertEquals(ms("2027-03-15T04:00:00Z"), springMid)
        assertEquals(23L * 3_600_000L, springMid - spring.toInstant().toEpochMilli())

        val fall = at("America/New_York", "2027-11-07T00:00:00")
        val fallMid = Cadence.nextMidnightMillis(fall)
        assertEquals(ms("2027-11-08T05:00:00Z"), fallMid)
        assertEquals(25L * 3_600_000L, fallMid - fall.toInstant().toEpochMilli())
    }

    @Test fun midnightThatDoesNotExistBecauseOfADstGap() {
        // Cuba springs forward at 00:00, so 2027-03-14 starts at 01:00 local (05:00 UTC).
        val now = at("America/Havana", "2027-03-13T12:00:00")
        val m = Cadence.nextMidnightMillis(now)
        assertEquals(ms("2027-03-14T05:00:00Z"), m)
        assertEquals(12L * 3_600_000L, m - now.toInstant().toEpochMilli())
    }

    @Test fun midnightAtTheEndOfTheYearIsInTheFuture() {
        val now = at("UTC", "2026-12-31T23:59:59")
        val m = Cadence.nextMidnightMillis(now)
        assertEquals(ms("2027-01-01T00:00:00Z"), m)
        assertTrue(m > now.toInstant().toEpochMilli())
    }
}
