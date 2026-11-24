package ai.alal.app.personal

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class ParsedTodo(val text: String, val dueAt: Long?)
data class ParsedDigest(val summary: List<String>, val todos: List<ParsedTodo>)

/**
 * Lenient parser for the model's SUMMARY / TODOS reply. Small local models drift
 * from the requested format, so this accepts several bullet styles and headings.
 */
object DigestParser {

    private val thinkBlock = Regex("(?s)<think>.*?</think>")
    private val bullet = Regex("^\\s*(?:[-*•]|\\d+[.)])\\s*")
    private val dueSplit = Regex("\\|?\\s*due\\s*:\\s*", RegexOption.IGNORE_CASE)

    fun parse(raw: String): ParsedDigest {
        val text = raw.replace(thinkBlock, "").replace("**", "")
        val summary = mutableListOf<String>()
        val todos = mutableListOf<ParsedTodo>()
        var section = 0 // 0 = before headings, 1 = summary, 2 = todos
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val heading = trimmed.trimEnd(':').lowercase().replace(Regex("[^a-z]"), "")
            when {
                heading == "summary" -> { section = 1; continue }
                heading in setOf("todos", "todo", "tasks", "actionitems") -> { section = 2; continue }
            }
            val content = trimmed.replace(bullet, "").trim()
            if (content.isEmpty() || content.equals("none", true) || content.startsWith("none ", true)) continue
            when (section) {
                2 -> parseTodo(content)?.let { todos += it }
                else -> summary += content
            }
        }
        return ParsedDigest(summary.take(30), todos.take(20))
    }

    private fun parseTodo(content: String): ParsedTodo? {
        val parts = content.split(dueSplit, limit = 2)
        val task = parts[0].trim().trimEnd('|', '-', ' ')
        if (task.length < 3) return null
        val due = parts.getOrNull(1)?.trim()?.let { parseDate(it) }
        return ParsedTodo(task, due)
    }

    private val inUnits = Regex("^in (\\d{1,4}) (day|days|week|weeks)$")
    private val weekdayRef = Regex("^(?:next )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)$")
    private val weekdays = mapOf(
        "sunday" to Calendar.SUNDAY, "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY,
    )

    /**
     * Parses absolute dates (yyyy-MM-dd with optional time) and relative phrases
     * ("today", "tonight", "tomorrow", "in N days/weeks", "[next] <weekday>").
     * [now] is injectable for tests. Date-only results are local midnight.
     */
    internal fun parseDate(value: String, now: Long = System.currentTimeMillis()): Long? {
        if (value.isBlank() || value.startsWith("none", true)) return null
        for (pattern in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd")) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                val pos = java.text.ParsePosition(0)
                val date = fmt.parse(value, pos)
                if (date != null && pos.index > 0) return date.time
            } catch (_: Exception) { }
        }
        return parseRelative(value, now)
    }

    private fun parseRelative(value: String, now: Long): Long? {
        val s = value.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        when (s) {
            "today" -> return cal.timeInMillis
            "tonight" -> { cal.set(Calendar.HOUR_OF_DAY, 20); return cal.timeInMillis }
            "tomorrow" -> { cal.add(Calendar.DAY_OF_MONTH, 1); return cal.timeInMillis }
        }
        inUnits.matchEntire(s)?.let { m ->
            val n = m.groupValues[1].toInt()
            cal.add(Calendar.DAY_OF_MONTH, if (m.groupValues[2].startsWith("week")) n * 7 else n)
            return cal.timeInMillis
        }
        weekdayRef.matchEntire(s)?.let { m ->
            val target = weekdays.getValue(m.groupValues[1])
            var delta = (target - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
            if (delta == 0) delta = 7
            cal.add(Calendar.DAY_OF_MONTH, delta)
            return cal.timeInMillis
        }
        return null
    }
}
