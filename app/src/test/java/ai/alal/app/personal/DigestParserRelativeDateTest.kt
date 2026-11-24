package ai.alal.app.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/** Relative due dates from small models, resolved against a fixed "now" (local time zone). */
class DigestParserRelativeDateTest {

    // Tuesday 2026-11-24, 15:30 local.
    private val now = localMillis(2026, Calendar.NOVEMBER, 24, 15, 30)

    private fun parse(value: String) = DigestParser.parseDate(value, now)

    private fun localMillis(y: Int, month: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, month, d, h, min, 0)
        }.timeInMillis

    @Test
    fun todayIsLocalMidnightOfToday() {
        assertEquals(localMillis(2026, Calendar.NOVEMBER, 24, 0, 0), parse("today"))
    }

    @Test
    fun tonightIsTodayAt2000() {
        assertEquals(localMillis(2026, Calendar.NOVEMBER, 24, 20, 0), parse("tonight"))
    }

    @Test
    fun tomorrowIsLocalMidnightOfNextDay() {
        assertEquals(localMillis(2026, Calendar.NOVEMBER, 25, 0, 0), parse("tomorrow"))
    }

    @Test
    fun inNDaysAddsCalendarDaysFromMidnight() {
        assertEquals(localMillis(2026, Calendar.NOVEMBER, 25, 0, 0), parse("in 1 day"))
        assertEquals(localMillis(2026, Calendar.NOVEMBER, 27, 0, 0), parse("in 3 days"))
        assertEquals(localMillis(2026, Calendar.NOVEMBER, 24, 0, 0), parse("in 0 days"))
    }

    @Test
    fun inNWeeksAddsSevenDaysPerWeek() {
        assertEquals(localMillis(2026, Calendar.DECEMBER, 1, 0, 0), parse("in 1 week"))
        assertEquals(localMillis(2026, Calendar.DECEMBER, 8, 0, 0), parse("in 2 weeks"))
    }

    @Test
    fun weekdayNameIsNextOccurrenceStrictlyAfterToday() {
        // Today is Tuesday, so "friday" is the 27th.
        assertEquals(localMillis(2026, Calendar.NOVEMBER, 27, 0, 0), parse("friday"))
        assertEquals(localMillis(2026, Calendar.NOVEMBER, 30, 0, 0), parse("monday"))
        // Same weekday as today means a week from today, not today.
        assertEquals(localMillis(2026, Calendar.DECEMBER, 1, 0, 0), parse("tuesday"))
    }

    @Test
    fun nextWeekdayMatchesPlainWeekday() {
        assertEquals(parse("friday"), parse("next friday"))
        assertEquals(parse("tuesday"), parse("next tuesday"))
    }

    @Test
    fun matchingIsCaseInsensitiveAndTolerantOfSpacing() {
        assertEquals(parse("today"), parse("TODAY"))
        assertEquals(parse("tonight"), parse("Tonight"))
        assertEquals(parse("in 3 days"), parse("In 3 Days"))
        assertEquals(parse("friday"), parse("FRIDAY"))
        assertEquals(parse("next friday"), parse("Next  Friday"))
        assertEquals(parse("in 2 weeks"), parse("  in   2   weeks  "))
    }

    @Test
    fun relativeDatesIgnoreTimeOfDayOfNow() {
        val lateNow = localMillis(2026, Calendar.NOVEMBER, 24, 23, 59)
        assertEquals(localMillis(2026, Calendar.NOVEMBER, 25, 0, 0), DigestParser.parseDate("tomorrow", lateNow))
    }

    @Test
    fun absoluteFormatsStillParseAndIgnoreNow() {
        assertEquals(localMillis(2026, Calendar.NOVEMBER, 30, 0, 0), parse("2026-11-30"))
        assertEquals(localMillis(2026, Calendar.NOVEMBER, 30, 9, 15), parse("2026-11-30 09:15"))
        assertEquals(localMillis(2026, Calendar.NOVEMBER, 30, 9, 15), parse("2026-11-30T09:15"))
    }

    @Test
    fun negativeAndUnsupportedInputReturnsNull() {
        assertNull(parse("in -2 days"))
        assertNull(parse("in two days"))
        assertNull(parse("someday"))
        assertNull(parse("in 3 months"))
        assertNull(parse("yesterday"))
        assertNull(parse("next week"))
        assertNull(parse("funday"))
        assertNull(parse("in friday"))
    }

    @Test
    fun blankAndNoneStillReturnNull() {
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("none"))
    }
}
