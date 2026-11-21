package ai.alal.app.personal

import android.content.Context
import java.util.Calendar

/** Learned daily routine from the last two weeks of usage (plus places, when location is on). */
data class Routine(
    val days: Int,
    val avgScreenTime: String?,
    val typicalWake: String?,
    val typicalSleep: String?,
    val topApps: List<String>,
    val byPeriod: Map<String, List<String>>,
    val places: List<String>
) {
    fun describe(): String = buildString {
        appendLine(if (days > 0) "Daily routine (last $days days):" else "Daily routine:")
        typicalWake?.let { appendLine("- First phone use usually around $it") }
        typicalSleep?.let { appendLine("- Last phone use usually around $it") }
        avgScreenTime?.let { appendLine("- Average screen time $it per day") }
        if (topApps.isNotEmpty()) appendLine("- Most used: ${topApps.joinToString(", ")}")
        byPeriod.forEach { (period, apps) -> if (apps.isNotEmpty()) appendLine("- $period: ${apps.joinToString(", ")}") }
        places.forEach { appendLine("- $it") }
    }.trim()
}

object RoutineAnalyzer {

    private const val DAYS = 14

    fun analyze(context: Context, settings: PersonalSettings, places: List<String>): Routine? {
        val usageOn = settings.usageEnabled && settings.usageAccessGranted()
        if (!usageOn) {
            return if (places.isEmpty()) null
            else Routine(0, null, null, null, emptyList(), emptyMap(), places)
        }
        val now = System.currentTimeMillis()
        val from = now - DAYS * 24L * 60 * 60 * 1000
        val sessions = UsageCollector.sessions(context, from, now)
        if (sessions.isEmpty() && places.isEmpty()) return null
        val labels = AppLabels(context)

        // A "day" runs 04:00 → 04:00 so late nights count toward the previous day.
        fun dayKey(t: Long): Int {
            val c = Calendar.getInstance().apply { timeInMillis = t - 4L * 60 * 60 * 1000 }
            return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        }
        fun minuteOfDay(t: Long): Int {
            val c = Calendar.getInstance().apply { timeInMillis = t }
            return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        }

        val byDay = UsageCollector.screenOnTimes(context, from, now).groupBy { dayKey(it) }
        val activeDays = byDay.keys.size.coerceAtLeast(1)
        val wake = median(byDay.values.mapNotNull { it.minOrNull() }.map { minuteOfDay(it) })
        // Shift post-midnight times past 24h so the median of 23:50 and 00:20 isn't noon.
        val sleep = median(byDay.values.mapNotNull { it.maxOrNull() }.map { t ->
            val m = minuteOfDay(t)
            if (m < 4 * 60) m + 24 * 60 else m
        })?.let { it % (24 * 60) }

        val total = sessions.sumOf { it.millis }
        val top = sessions.groupBy { it.pkg }
            .mapValues { (_, s) -> s.sumOf { it.millis } }
            .entries.sortedByDescending { it.value }.take(5)
            .map { "${labels.label(it.key)} (${UsageCollector.minutes(it.value / activeDays)}/day)" }

        val periods = linkedMapOf(
            "Mornings (5–12)" to (5 * 60 until 12 * 60),
            "Afternoons (12–17)" to (12 * 60 until 17 * 60),
            "Evenings (17–22)" to (17 * 60 until 22 * 60),
            "Nights (22–5)" to (22 * 60 until 29 * 60)
        )
        val byPeriod = periods.mapValues { (_, range) ->
            sessions.filter { s ->
                val m = minuteOfDay(s.start)
                (if (m < 5 * 60) m + 24 * 60 else m) in range
            }.groupBy { it.pkg }
                .mapValues { (_, s) -> s.sumOf { it.millis } }
                .entries.sortedByDescending { it.value }.take(3)
                .map { labels.label(it.key) }
        }

        return Routine(
            days = activeDays,
            avgScreenTime = if (total > 0) UsageCollector.minutes(total / activeDays) else null,
            typicalWake = wake?.let { clock(it) },
            typicalSleep = sleep?.let { clock(it) },
            topApps = top,
            byPeriod = byPeriod,
            places = places
        )
    }

    private fun median(values: List<Int>): Int? = if (values.isEmpty()) null else values.sorted()[values.size / 2]
    private fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
}
