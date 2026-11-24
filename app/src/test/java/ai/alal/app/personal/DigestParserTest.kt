package ai.alal.app.personal

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases for DigestParser: section handling, bullets, due dates and messy whitespace. */
class DigestParserTest {

    private fun millis(pattern: String, value: String): Long =
        SimpleDateFormat(pattern, Locale.US).parse(value)!!.time

    @Test
    fun digestHappyPathMixesBulletStylesAndDateFormats() {
        val raw = """
            SUMMARY:
            1. Mom asked if you're coming Sunday
            • Bank statement is ready
            TODOS:
            - Reply to Mom | due: 2026-11-21T09:30
            - Pay rent | due: 2026-12-01
        """.trimIndent()
        val d = DigestParser.parse(raw)
        assertEquals(listOf("Mom asked if you're coming Sunday", "Bank statement is ready"), d.summary)
        assertEquals(2, d.todos.size)
        assertEquals("Reply to Mom", d.todos[0].text)
        assertEquals(millis("yyyy-MM-dd HH:mm", "2026-11-21 09:30"), d.todos[0].dueAt)
        assertEquals("Pay rent", d.todos[1].text)
        assertEquals(millis("yyyy-MM-dd", "2026-12-01"), d.todos[1].dueAt)
    }

    @Test
    fun emptyAndWhitespaceOnlyInputYieldNothing() {
        for (raw in listOf("", "   \n\t\n  ", "<think>only thinking</think>")) {
            val d = DigestParser.parse(raw)
            assertTrue("summary should be empty for ${raw.length}-char input", d.summary.isEmpty())
            assertTrue("todos should be empty for ${raw.length}-char input", d.todos.isEmpty())
        }
    }

    @Test
    fun linesBeforeAnyHeadingAllCountAsSummary() {
        // No SUMMARY/TODOS heading at all: even a todo-looking line lands in the summary.
        val d = DigestParser.parse("Mom asked about Sunday\n- Bank OTP arrived\n2. Dentist moved | due: 2026-11-20")
        assertEquals(
            listOf("Mom asked about Sunday", "Bank OTP arrived", "Dentist moved | due: 2026-11-20"),
            d.summary
        )
        assertTrue(d.todos.isEmpty())
    }

    @Test
    fun missingSectionsAreEmptyNotErrors() {
        val onlySummary = DigestParser.parse("SUMMARY:\n- Only summary here")
        assertEquals(listOf("Only summary here"), onlySummary.summary)
        assertTrue(onlySummary.todos.isEmpty())

        val onlyTodos = DigestParser.parse("TODOS:\n- Buy milk | due: none")
        assertTrue(onlyTodos.summary.isEmpty())
        assertEquals(listOf(ParsedTodo("Buy milk", null)), onlyTodos.todos)
    }

    @Test
    fun malformedDueDatesKeepTaskButDropDate() {
        val d = DigestParser.parse(
            "TODOS:\n" +
                "- Call Ana | due: 2026-13-45\n" +
                "- Send invoice | due: next tuesday\n" +
                "- Water plants | due:"
        )
        assertEquals(listOf("Call Ana", "Send invoice", "Water plants"), d.todos.map { it.text })
        assertTrue(d.todos.all { it.dueAt == null })
    }

    @Test
    fun tooShortTodoTitlesAreDropped() {
        val d = DigestParser.parse("TODOS:\n- go | due: 2026-11-20\n- ab\n- Gym")
        assertEquals(listOf(ParsedTodo("Gym", null)), d.todos)
    }

    @Test
    fun unusualWhitespaceCrlfAndHeadingCasingAreTolerated() {
        val raw = "  sUmMaRy :  \r\n*   Item with   extra   spaces   \r\n\tTo-dos:\t\r\n" +
            "  - Call   Mom  |  DUE:   2026-11-20 10:00  \r\n"
        val d = DigestParser.parse(raw)
        assertEquals(listOf("Item with   extra   spaces"), d.summary)
        assertEquals(1, d.todos.size)
        assertEquals("Call   Mom", d.todos[0].text)
        assertEquals(millis("yyyy-MM-dd HH:mm", "2026-11-20 10:00"), d.todos[0].dueAt)
    }

    @Test
    fun noneItemsAreSkippedButWordsStartingWithNoneAreKept() {
        val d = DigestParser.parse("SUMMARY:\n- NONE\n- Nonetheless the bank called\nTODOS:\n- None")
        assertEquals(listOf("Nonetheless the bank called"), d.summary)
        assertTrue(d.todos.isEmpty())
    }

    @Test
    fun todoAndSummaryListsAreCapped() {
        val todos = DigestParser.parse("TODOS:\n" + (1..25).joinToString("\n") { "- Task number $it" })
        assertEquals(20, todos.todos.size)
        assertEquals("Task number 1", todos.todos.first().text)

        val summary = DigestParser.parse("SUMMARY:\n" + (1..35).joinToString("\n") { "- Line $it" })
        assertEquals(30, summary.summary.size)
    }

    @Test
    fun headingAliasesSwitchToTodoSection() {
        val actionItems = DigestParser.parse("Action Items:\n- Renew passport | due: 2026-12-01")
        assertEquals(1, actionItems.todos.size)
        assertTrue(actionItems.summary.isEmpty())

        val tasks = DigestParser.parse("Tasks:\n- Book dentist")
        assertEquals(listOf(ParsedTodo("Book dentist", null)), tasks.todos)

        val singular = DigestParser.parse("TODO:\n- Water the ferns")
        assertEquals(listOf(ParsedTodo("Water the ferns", null)), singular.todos)
    }

    @Test
    fun multiLineThinkBlockIsRemovedBeforeParsing() {
        val d = DigestParser.parse("<think>\nlet me see\n- not a bullet\n</think>\nSUMMARY:\n- Kept line")
        assertEquals(listOf("Kept line"), d.summary)
        assertTrue(d.todos.isEmpty())
    }
}
