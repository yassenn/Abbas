package ai.alal.app.personal

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import ai.alal.app.data.IngestedItemEntity
import ai.alal.app.data.PersonalSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One continuous stretch of an app in the foreground. */
internal data class AppSession(val pkg: String, val start: Long, val end: Long) {
    val millis get() = end - start
}

/**
 * Reads the system's app-usage log (the same data Digital Wellbeing shows) via
 * UsageStatsManager. Needs the special "Usage access" grant in system settings.
 */
object UsageCollector {

    private const val MIN_WINDOW_MS = 60L * 60 * 1000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** One "App usage" item for the time since the last collection (at most once an hour). */
    fun collect(context: Context, settings: PersonalSettings): List<IngestedItemEntity> {
        if (!settings.usageEnabled || !settings.usageAccessGranted()) return emptyList()
        val now = System.currentTimeMillis()
        val from = settings.lastUsageTimestamp.takeIf { it > 0 } ?: (now - DAY_MS)
        if (now - from < MIN_WINDOW_MS) return emptyList()

        val sessions = sessions(context, from, now)
        settings.lastUsageTimestamp = now
        if (sessions.isEmpty()) return emptyList()

        val labels = AppLabels(context)
        val byApp = sessions.groupBy { it.pkg }
            .mapValues { (_, s) -> s.sumOf { it.millis } to s.size }
            .entries.sortedByDescending { it.value.first }
        val total = sessions.sumOf { it.millis }
        if (total < 60_000) return emptyList()

        val fmt = SimpleDateFormat("EEE HH:mm", Locale.US)
        val body = buildString {
            append("Screen time ${minutes(total)} between ${fmt.format(Date(from))} and ${fmt.format(Date(now))}. ")
            append(byApp.take(8).joinToString(", ") { entry ->
                "${labels.label(entry.key)} ${minutes(entry.value.first)} (${entry.value.second}×)"
            })
        }
        return listOf(
            IngestedItemEntity(
                source = PersonalSource.USAGE.name,
                sourceKey = "usage:$from:$now",
                body = body,
                timestamp = now
            )
        )
    }

    /** Foreground sessions between [from] and [to], skipping the launcher, system UI and Alal. */
    internal fun sessions(context: Context, from: Long, to: Long): List<AppSession> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = try { usm.queryEvents(from, to) } catch (_: Exception) { return emptyList() }
        val ignored = ignoredPackages(context)
        val out = ArrayList<AppSession>()
        var current: String? = null
        var start = 0L
        val e = UsageEvents.Event()
        fun close(at: Long) {
            val pkg = current ?: return
            if (at > start && pkg !in ignored) out += AppSession(pkg, start, at)
            current = null
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (current != e.packageName) {
                        close(e.timeStamp)
                        current = e.packageName
                        start = e.timeStamp
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> if (current == e.packageName) close(e.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> close(e.timeStamp)
            }
        }
        close(to)
        return out
    }

    /** Moments the user picked up the phone, used for wake / sleep estimates. */
    internal fun screenOnTimes(context: Context, from: Long, to: Long): List<Long> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = try { usm.queryEvents(from, to) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.SCREEN_INTERACTIVE ||
                e.eventType == UsageEvents.Event.KEYGUARD_HIDDEN ||
                e.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) out += e.timeStamp
        }
        return out
    }

    private fun ignoredPackages(context: Context): Set<String> {
        val home = context.packageManager
            .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return setOfNotNull(context.packageName, "com.android.systemui", home)
    }

    internal fun minutes(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }
}

/** Cached package → app name lookups. */
internal class AppLabels(private val context: Context) {
    private val cache = HashMap<String, String>()
    fun label(pkg: String): String = cache.getOrPut(pkg) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}
