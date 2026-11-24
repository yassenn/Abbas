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
    private val runs = Regex("\\s+")
    private val inDuration = Regex("in (\\d+) (days?|weeks?)")
    private val weekdays = mapOf(
        "sunday" to Calendar.SUNDAY, "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY, "friday" to Calendar.FRIDAY,
        "saturday" to Calendar.SATURDAY,
    )

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

    /**
     * Absolute dates (yyyy-MM-dd, optional time) first, then relative forms resolved against [now]
     * (epoch millis, local time zone): today, tonight (today 20:00), tomorrow, in N days/weeks,
     * and weekday names (next occurrence strictly after today). Date-only results are local midnight.
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
        return parseRelativeDate(value, now)
    }

    private fun parseRelativeDate(value: String, now: Long): Long? {
        val s = value.trim().replace(runs, " ").lowercase(Locale.ROOT)
        val cal = Calendar.getInstance().apply { timeInMillis = now; atMidnight() }
        var evening = false
        when {
            s == "today" -> Unit
            s == "tonight" -> evening = true
            s == "tomorrow" -> cal.add(Calendar.DAY_OF_MONTH, 1)
            s.startsWith("in ") -> {
                val m = inDuration.matchEntire(s) ?: return null
                val n = m.groupValues[1].toIntOrNull() ?: return null
                if (m.groupValues[2].startsWith("week")) cal.add(Calendar.WEEK_OF_YEAR, n)
                else cal.add(Calendar.DAY_OF_MONTH, n)
            }
            else -> {
                val target = weekdays[s.removePrefix("next ")] ?: return null
                var delta = (target - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (delta == 0) delta = 7 // strictly after today
                cal.add(Calendar.DAY_OF_MONTH, delta)
            }
        }
        if (evening) cal.set(Calendar.HOUR_OF_DAY, 20)
        return cal.timeInMillis
    }

    private fun Calendar.atMidnight() {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
}
