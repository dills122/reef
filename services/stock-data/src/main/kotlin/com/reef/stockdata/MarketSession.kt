package com.reef.stockdata

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** Why a given instant is or isn't in the US equities regular session. */
enum class SessionReason {
    REGULAR_HOURS,
    WEEKEND,
    HOLIDAY,
    OUTSIDE_REGULAR_HOURS,
}

data class MarketSessionClassification(
    val isRegularSession: Boolean,
    val reason: SessionReason,
    val marketDate: LocalDate,
    val etTime: ZonedDateTime,
)

/**
 * NYSE-style US equities session classifier: 09:30-16:00 ET on trading days.
 * First slice per docs/STOCK_DATA_SEEDING_PLAN.md "Market Session Rules" -
 * no half-days, no extended-hours realism.
 */
object MarketSession {
    val ET_ZONE: ZoneId = ZoneId.of("America/New_York")
    private val OPEN = LocalTime.of(9, 30)
    private val CLOSE = LocalTime.of(16, 0)

    fun classify(instant: Instant): MarketSessionClassification {
        val et = instant.atZone(ET_ZONE)
        val date = et.toLocalDate()

        if (et.dayOfWeek == DayOfWeek.SATURDAY || et.dayOfWeek == DayOfWeek.SUNDAY) {
            return MarketSessionClassification(false, SessionReason.WEEKEND, date, et)
        }
        if (isNyseHoliday(date)) {
            return MarketSessionClassification(false, SessionReason.HOLIDAY, date, et)
        }
        val time = et.toLocalTime()
        return if (!time.isBefore(OPEN) && time.isBefore(CLOSE)) {
            MarketSessionClassification(true, SessionReason.REGULAR_HOURS, date, et)
        } else {
            MarketSessionClassification(false, SessionReason.OUTSIDE_REGULAR_HOURS, date, et)
        }
    }

    fun isTradingDay(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY && !isNyseHoliday(date)

    /** Most recent trading day at or before [date] (inclusive). */
    fun lastTradingDayAtOrBefore(date: LocalDate): LocalDate {
        var d = date
        while (!isTradingDay(d)) {
            d = d.minusDays(1)
        }
        return d
    }

    fun isNyseHoliday(date: LocalDate): Boolean = nyseHolidays(date.year).contains(date)

    private fun observed(date: LocalDate): LocalDate = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> date.minusDays(1)
        DayOfWeek.SUNDAY -> date.plusDays(1)
        else -> date
    }

    private fun nyseHolidays(year: Int): Set<LocalDate> {
        val holidays = mutableSetOf<LocalDate>()

        holidays += observed(LocalDate.of(year, Month.JANUARY, 1))
        holidays += LocalDate.of(year, Month.JANUARY, 1)
            .with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.MONDAY))
        holidays += LocalDate.of(year, Month.FEBRUARY, 1)
            .with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.MONDAY))
        holidays += goodFriday(year)
        holidays += LocalDate.of(year, Month.MAY, 31)
            .with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY))
        holidays += observed(LocalDate.of(year, Month.JUNE, 19))
        holidays += observed(LocalDate.of(year, Month.JULY, 4))
        holidays += LocalDate.of(year, Month.SEPTEMBER, 1)
            .with(TemporalAdjusters.dayOfWeekInMonth(1, DayOfWeek.MONDAY))
        holidays += LocalDate.of(year, Month.NOVEMBER, 1)
            .with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))
        holidays += observed(LocalDate.of(year, Month.DECEMBER, 25))

        return holidays
    }

    /** Anonymous Gregorian algorithm for Easter Sunday, minus two days. */
    private fun goodFriday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        val easter = LocalDate.of(year, month, day)
        return easter.minusDays(2)
    }
}
