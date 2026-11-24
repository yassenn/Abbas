package ai.alal.app.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases for the lenient SUMMARY / TODOS parser, beyond the happy path in PersonalParsersTest. */
class DigestParserTest {

    @Test
    fun emptyAndBlankInputYieldEmptyDigest() {
        val empty = DigestParser.parse("")
        assertTrue(empty.summary.isEmpty())
        assertTrue(empty.todos.isEmpty())

        val blank = DigestParser.parse("   \n\t\n  ")
        assertTrue(blank.summary.isEmpty())
        assertTrue(blank.todos.isEmpty())
    }

    @Test
    fun textWithoutHeadingsIsTreatedAsSummary() {
        val d = DigestParser.parse("Just a plain line\n- and a bullet")
        assertEquals(listOf("Just a plain line", "and a bullet"), d.summary)
        assertTrue(d.todos.isEmpty())
    }

    @Test
    fun missingSummarySectionStillParsesTodos() {
        val d = DigestParser.parse("TODOS:\n- Pay rent | due: none")
        assertTrue(d.summary.isEmpty())
        assertEquals(1, d.todos.size)
        assertEquals("Pay rent", d.todos[0].text)
        assertNull(d.todos[0].dueAt)
    }

    @Test
    fun missingTodosSectionLeavesTodosEmpty() {
        val d = DigestParser.parse("SUMMARY:\n- Only news today")
        assertEquals(listOf("Only news today"), d.summary)
        assertTrue(d.todos.isEmpty())
    }

    @Test
    fun headingsAreCaseInsensitiveAndAcceptAliases() {
        val raw = "summary\n• First thing\n  Action Items :  \n1) Buy milk\n2. Book flights"
        val d = DigestParser.parse(raw)
        assertEquals(listOf("First thing"), d.summary)
        assertEquals(listOf("Buy milk", "Book flights"), d.todos.map { it.text })
    }

    @Test
    fun markdownHashHeadingsAreRecognised() {
        val d = DigestParser.parse("## Summary\n- A note\n## Tasks\n- Water plants")
        assertEquals(listOf("A note"), d.summary)
        assertEquals(listOf("Water plants"), d.todos.map { it.text })
    }

    @Test
    fun windowsLineEndingsAndIndentationAreHandled() {
        val d = DigestParser.parse("SUMMARY:\r\n   - Indented item\r\nTODO:\r\n\t- Tabbed task\r\n")
        assertEquals(listOf("Indented item"), d.summary)
        assertEquals(listOf("Tabbed task"), d.todos.map { it.text })
    }

    @Test
    fun multilineThinkBlockIsRemovedIncludingFakeHeadings() {
        val raw = "<think>\nSUMMARY:\n- fake\n</think>\nSUMMARY:\n- real"
        val d = DigestParser.parse(raw)
        assertEquals(listOf("real"), d.summary)
        assertTrue(d.todos.isEmpty())
    }

    @Test
    fun noneEntriesAreSkippedButWordsStartingWithNoneAreKept() {
        val raw = "SUMMARY:\n- None needed\n- NONE\n- Nonetheless, the vet called\nTODOS:\n- none"
        val d = DigestParser.parse(raw)
        assertEquals(listOf("Nonetheless, the vet called"), d.summary)
        assertTrue(d.todos.isEmpty())
    }

    @Test
    fun todoDueDateFormatsAndCasingAreParsed() {
        val raw = """
            TODOS:
            - Submit report | DUE: 2026-11-20
            - Team sync | due: 2026-11-21T09:30
            - Send invoice due: 2026-11-22 08:00
        """.trimIndent()
        val d = DigestParser.parse(raw)
        assertEquals(3, d.todos.size)
        assertEquals("Submit report", d.todos[0].text)
        assertEquals(DigestParser.parseDate("2026-11-20"), d.todos[0].dueAt)
        assertNotNull(d.todos[0].dueAt)
        assertEquals(DigestParser.parseDate("2026-11-21 09:30"), d.todos[1].dueAt)
        assertEquals("Send invoice", d.todos[2].text)
        assertEquals(DigestParser.parseDate("2026-11-22 08:00"), d.todos[2].dueAt)
    }

    @Test
    fun unparseableDueDateKeepsTodoWithNullDue() {
        val d = DigestParser.parse("TODOS:\n- Submit report | due: tomorrow-ish")
        assertEquals(1, d.todos.size)
        assertEquals("Submit report", d.todos[0].text)
        assertNull(d.todos[0].dueAt)
    }

    @Test
    fun tooShortOrEmptyTodoTextIsDropped() {
        val d = DigestParser.parse("TODOS:\n- ab\n- | due: 2026-11-20\n- abc")
        assertEquals(listOf("abc"), d.todos.map { it.text })
    }

    @Test
    fun trailingSeparatorsAreTrimmedFromTodoText() {
        val d = DigestParser.parse("TODOS:\n- Call Bob - due: none\n- Email Sue | ")
        assertEquals(listOf("Call Bob", "Email Sue"), d.todos.map { it.text })
    }

    @Test
    fun outputIsCappedAt30SummaryLinesAnd20Todos() {
        val summary = (1..35).joinToString("\n") { "- Summary item $it" }
        val todos = (1..25).joinToString("\n") { "- Todo item $it" }
        val d = DigestParser.parse("SUMMARY:\n$summary\nTODOS:\n$todos")
        assertEquals(30, d.summary.size)
        assertEquals("Summary item 1", d.summary.first())
        assertEquals(20, d.todos.size)
        assertEquals("Todo item 20", d.todos.last().text)
    }

    @Test
    fun parseDateRejectsBlankNoneInvalidAndOverflowingDates() {
        assertNull(DigestParser.parseDate(""))
        assertNull(DigestParser.parseDate("   "))
        assertNull(DigestParser.parseDate("none"))
        assertNull(DigestParser.parseDate("NONE"))
        assertNull(DigestParser.parseDate("not a date"))
        assertNull(DigestParser.parseDate("2026-02-30"))
        assertNull(DigestParser.parseDate("2026-13-01"))
    }

    @Test
    fun parseDateWithTimeIsLaterThanDateOnlyByThatTime() {
        val dateOnly = DigestParser.parseDate("2026-11-20")!!
        val withTime = DigestParser.parseDate("2026-11-20 10:00")!!
        assertEquals(10 * 60 * 60 * 1000L, withTime - dateOnly)
        assertEquals(withTime, DigestParser.parseDate("2026-11-20T10:00"))
    }
}
