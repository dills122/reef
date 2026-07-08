package com.reef.stockdata

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarketSessionTest {

    private fun etInstant(date: String, time: String) =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), MarketSession.ET_ZONE).toInstant()

    @Test
    fun `regular trading Wednesday during hours is regular session`() {
        val classification = MarketSession.classify(etInstant("2026-07-08", "15:00:00"))
        assertTrue(classification.isRegularSession)
        assertEquals(SessionReason.REGULAR_HOURS, classification.reason)
    }

    @Test
    fun `same trading day after close is outside regular hours`() {
        val classification = MarketSession.classify(etInstant("2026-07-08", "20:00:00"))
        assertFalse(classification.isRegularSession)
        assertEquals(SessionReason.OUTSIDE_REGULAR_HOURS, classification.reason)
    }

    @Test
    fun `before open is outside regular hours`() {
        val classification = MarketSession.classify(etInstant("2026-07-08", "08:00:00"))
        assertFalse(classification.isRegularSession)
        assertEquals(SessionReason.OUTSIDE_REGULAR_HOURS, classification.reason)
    }

    @Test
    fun `Saturday is weekend regardless of time`() {
        val classification = MarketSession.classify(etInstant("2026-07-04", "12:00:00"))
        assertFalse(classification.isRegularSession)
        assertEquals(SessionReason.WEEKEND, classification.reason)
    }

    @Test
    fun `MLK day is a holiday`() {
        val classification = MarketSession.classify(etInstant("2026-01-19", "12:00:00"))
        assertFalse(classification.isRegularSession)
        assertEquals(SessionReason.HOLIDAY, classification.reason)
    }

    @Test
    fun `Good Friday 2026 is a holiday`() {
        assertTrue(MarketSession.isNyseHoliday(LocalDate.parse("2026-04-03")))
    }

    @Test
    fun `fixed holidays for 2026`() {
        listOf(
            "2026-01-01", // New Year's Day
            "2026-02-16", // Washington's Birthday
            "2026-05-25", // Memorial Day
            "2026-06-19", // Juneteenth
            "2026-09-07", // Labor Day
            "2026-11-26", // Thanksgiving
            "2026-12-25", // Christmas
        ).forEach { date ->
            assertTrue(MarketSession.isNyseHoliday(LocalDate.parse(date)), "expected $date to be a holiday")
        }
    }

    @Test
    fun `lastTradingDayAtOrBefore skips weekends and holidays`() {
        // 2026-01-01 is a Thursday holiday; 2025-12-31 is a regular Wednesday.
        val result = MarketSession.lastTradingDayAtOrBefore(LocalDate.parse("2026-01-01"))
        assertEquals(LocalDate.parse("2025-12-31"), result)
    }
}
