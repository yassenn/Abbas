package ai.alal.app.personal

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Relative due-date parsing with a fixed "now": Tuesday 2026-11-24 15:30 local. */
class DigestParserRelativeDateTest {

    private fun ms(s: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(s)!!.time

    private val now = ms("2026-11-24 15:30")

    private fun p(s: String) = DigestParser.parseDate(s, now)

    @Test fun today() = assertEquals(ms("2026-11-24 00:00"), p("today"))
    @Test fun tonight() = assertEquals(ms("2026-11-24 20:00"), p("tonight"))
    @Test fun tomorrow() = assertEquals(ms("2026-11-25 00:00"), p("tomorrow"))

    @Test fun caseInsensitiveAndWhitespace() {
        assertEquals(ms("2026-11-25 00:00"), p("  ToMoRRow "))
        assertEquals(ms("2026-11-24 20:00"), p("TONIGHT"))
        assertEquals(ms("2026-11-27 00:00"), p("IN  3   Days"))
    }

    @Test fun inNDays() {
        assertEquals(ms("2026-11-27 00:00"), p("in 3 days"))
        assertEquals(ms("2026-11-25 00:00"), p("in 1 day"))
        assertEquals(ms("2026-12-04 00:00"), p("in 10 days")) // crosses month end
    }

    @Test fun inNWeeks() {
        assertEquals(ms("2026-12-01 00:00"), p("in 1 week"))
        assertEquals(ms("2026-12-08 00:00"), p("in 2 weeks"))
    }

    @Test fun weekdayIsNextOccurrenceStrictlyAfterToday() {
        assertEquals(ms("2026-11-27 00:00"), p("friday"))
        assertEquals(ms("2026-11-27 00:00"), p("next friday"))
        assertEquals(ms("2026-11-25 00:00"), p("Wednesday"))
        assertEquals(ms("2026-11-29 00:00"), p("sunday"))
    }

    @Test fun weekdayMatchingTodayIsNextWeek() {
        assertEquals(ms("2026-12-01 00:00"), p("tuesday"))
        assertEquals(ms("2026-12-01 00:00"), p("next tuesday"))
    }

    @Test fun mondayWrapsToNextWeek() {
        assertEquals(ms("2026-11-30 00:00"), p("monday"))
    }

    @Test fun absoluteFormatsUnchanged() {
        assertEquals(ms("2026-11-20 10:00"), p("2026-11-20 10:00"))
        assertEquals(ms("2026-11-20 10:00"), p("2026-11-20T10:00"))
        assertEquals(ms("2026-11-20 00:00"), p("2026-11-20"))
    }

    @Test fun defaultNowStillWorks() {
        assertEquals(ms("2026-11-20 00:00"), DigestParser.parseDate("2026-11-20"))
    }

    @Test fun negatives() {
        assertNull(p("in -2 days"))
        assertNull(p("someday"))
        assertNull(p("in two days"))
        assertNull(p("in 3 months"))
        assertNull(p("in days"))
        assertNull(p("last friday"))
        assertNull(p("fri"))
        assertNull(p(""))
        assertNull(p("none"))
        assertNull(p("2026-13-45"))
    }

    @Test fun relativeDueInsideTodoLine() {
        val d = DigestParser.parse("TODOS:\n- Call the dentist | due: someday\n- Buy milk | due: tomorrow")
        assertEquals(2, d.todos.size)
        assertNull(d.todos[0].dueAt)
        // parse() uses the real clock; just check it resolved to something.
        assertNotNull(d.todos[1].dueAt)
    }
}
