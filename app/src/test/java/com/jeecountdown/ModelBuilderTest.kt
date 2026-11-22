package com.jeecountdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ModelBuilderTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(local: String, zone: ZoneId = ist): ZonedDateTime = LocalDateTime.parse(local).atZone(zone)
    private fun ex(name: String, date: String, created: String = "2026-11-01") =
        Exam(name, LocalDate.parse(date).toEpochDay(), LocalDate.parse(created).toEpochDay())

    private val jee = ex("JEE MAIN", "2027-01-22", "2026-11-19")

    @Test fun noEventShowsCallToAction() {
        val m = ModelBuilder.build(emptyList(), Cfg(), at("2026-11-21T10:30:15"))
        assertFalse(m.hasEvent)
        assertEquals(-1L, m.bigNumber)
        assertEquals("ADD A COUNTDOWN", m.label)
    }

    @Test fun customLabelIsUsedEvenWithoutEvent() {
        val m = ModelBuilder.build(emptyList(), Cfg(label = "  my goal "), at("2026-11-21T10:30:15"))
        assertEquals("MY GOAL", m.label)
    }

    @Test fun daysUnit() {
        val m = ModelBuilder.build(listOf(jee), Cfg(), at("2026-11-19T00:00:00"))
        assertTrue(m.hasEvent)
        assertEquals(64L, m.bigNumber)
        assertEquals("DAYS TO JEE MAIN", m.label)
        assertEquals("22 JAN 2027", m.dateText)
        assertEquals(Urgency.NONE, m.urgency)
    }

    @Test fun weeksAndHoursUnits() {
        val now = at("2026-11-19T00:00:00")
        val w = ModelBuilder.build(listOf(jee), Cfg(unit = 1), now)
        assertEquals(9L, w.bigNumber)
        assertEquals("WEEKS TO JEE MAIN", w.label)
        val h = ModelBuilder.build(listOf(jee), Cfg(unit = 2), now)
        assertEquals(1536L, h.bigNumber)
        assertEquals("HOURS TO JEE MAIN", h.label)
    }

    @Test fun eventTodayStillShowsAndSaysToday() {
        val m = ModelBuilder.build(listOf(jee), Cfg(), at("2027-01-22T08:00:00"))
        assertTrue(m.hasEvent)
        assertEquals(Phase.TODAY, m.phase)
        assertEquals(0L, m.bigNumber)
        assertEquals("JEE MAIN IS TODAY", m.label)
    }

    @Test fun urgencyThresholds() {
        val now = at("2026-11-21T10:30:00")
        val today = LocalDate.of(2026, 11, 21)
        val cases = listOf(31L to Urgency.NONE, 30L to Urgency.AMBER, 11L to Urgency.AMBER, 10L to Urgency.RED, 0L to Urgency.RED)
        for ((d, expected) in cases) {
            val e = ex("X", today.plusDays(d).toString())
            assertEquals("days=$d", expected, ModelBuilder.build(listOf(e), Cfg(), now).urgency)
        }
    }

    @Test fun pastEventsAreSkippedAndNextUpcomingIsChosen() {
        val now = at("2026-11-21T10:30:00")
        val past = ex("OLD", "2026-11-01")
        val later = ex("LATER", "2027-05-23")
        val sooner = ex("SOONER", "2026-12-25")
        val m = ModelBuilder.build(listOf(later, past, sooner), Cfg(), now)
        assertEquals("SOONER", m.name)
        assertFalse(ModelBuilder.build(listOf(past), Cfg(), now).hasEvent)
    }

    @Test fun timeLeftTodayText() {
        val m = ModelBuilder.build(listOf(jee), Cfg(), at("2026-11-21T10:30:15"))
        assertEquals("13H 29M 45S LEFT TODAY", m.timeLeftText)
        assertEquals("13H 29M LEFT TODAY", m.timeLeftNoSeconds)
    }

    @Test fun timeLeftTodayOnDstDays() {
        val ny = ZoneId.of("America/New_York")
        val farEvent = ex("YEAR END", "2027-12-31", "2027-01-01") // still upcoming on both DST days
        assertEquals("23H 00M 00S LEFT TODAY",
            ModelBuilder.build(listOf(farEvent), Cfg(), at("2027-03-14T00:00:00", ny)).timeLeftText)
        assertEquals("25H 00M 00S LEFT TODAY",
            ModelBuilder.build(listOf(farEvent), Cfg(), at("2027-11-07T00:00:00", ny)).timeLeftText)
    }

    @Test fun progressRingHalfway() {
        // created 2026-11-19, event 2027-01-22 (64 days), now = day 32
        val m = ModelBuilder.build(listOf(jee), Cfg(), at("2026-12-21T09:00:00"))
        assertEquals(0.5f, m.progress, 1e-6f)
    }

    @Test fun allSurfacesShareOneModelForTheSameInstant() {
        val now = at("2026-11-21T10:30:15")
        val a = ModelBuilder.build(listOf(jee), Cfg(), now)
        val b = ModelBuilder.build(listOf(jee), Cfg(), now)
        assertEquals(a.bigNumber, b.bigNumber)
        assertEquals(a.label, b.label)
        assertEquals(a.timeLeftText, b.timeLeftText)
        assertEquals(a.progress, b.progress, 0f)
    }
}
