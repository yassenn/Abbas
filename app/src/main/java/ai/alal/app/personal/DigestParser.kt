package ai.alal.app.personal

import java.text.SimpleDateFormat
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
        val due = parts.getOrNull(1)?.trim()?.let(::parseDate)
        return ParsedTodo(task, due)
    }

    internal fun parseDate(value: String): Long? {
        if (value.isBlank() || value.startsWith("none", true)) return null
        for (pattern in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd")) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                val pos = java.text.ParsePosition(0)
                val date = fmt.parse(value, pos)
                if (date != null && pos.index > 0) return date.time
            } catch (_: Exception) { }
        }
        return null
    }
}
