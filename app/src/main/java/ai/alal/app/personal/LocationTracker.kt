package ai.alal.app.personal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.alal.app.data.AppDatabase
import ai.alal.app.data.IngestedItemEntity
import ai.alal.app.data.LocationPointEntity
import ai.alal.app.data.PersonalSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.roundToLong

/**
 * Records a location fix every ~15 minutes while the Location source is on.
 * Coordinates never leave the phone: there is deliberately no reverse geocoding
 * (Android's Geocoder sends coordinates to a server), so places are named by
 * behaviour: Home, Work, or "Place 3".
 */
class LocationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        LocationTracker.recordNow(applicationContext)
        return Result.success()
    }
}

object LocationTracker {

    private const val WORK_NAME = "alal_location_points"
    private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    fun schedule(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<LocationWorker>(15, java.util.concurrent.TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun hasPermission(context: Context): Boolean {
        val s = PersonalSettings(context)
        return s.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            s.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    suspend fun recordNow(context: Context) {
        val settings = PersonalSettings(context)
        if (!settings.locationEnabled || !hasPermission(context)) return
        val fix = currentLocation(context) ?: return
        val dao = AppDatabase.getInstance(context).personalDao()
        dao.insertPoint(LocationPointEntity(latitude = fix.latitude, longitude = fix.longitude, accuracy = fix.accuracy, time = fix.time))
        dao.prunePoints(System.currentTimeMillis() - RETENTION_MS)
    }

    @SuppressLint("MissingPermission") // checked in recordNow
    private suspend fun currentLocation(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 10 * 60 * 1000 }
            .minByOrNull { it.accuracy }
        if (recent != null) return recent
        if (Build.VERSION.SDK_INT < 30) {
            return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        }
        val provider = providers.firstOrNull { it == LocationManager.NETWORK_PROVIDER } ?: providers.firstOrNull() ?: return null
        return withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, context.mainExecutor) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            }
        }
    }
}

/** Clusters location fixes into places and visits. */
object PlaceAnalyzer {

    private const val CELL_DEGREES = 1.0 / 700 // ≈160 m north–south
    private const val MIN_VISIT_MS = 10L * 60 * 1000

    private data class Cell(val x: Long, val y: Long)

    private fun cellOf(p: LocationPointEntity): Cell {
        val y = (p.latitude / CELL_DEGREES).roundToLong()
        val x = (p.longitude * cos(Math.toRadians(p.latitude)) / CELL_DEGREES).roundToLong()
        return Cell(x, y)
    }

    private fun hour(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)
    private fun weekday(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_WEEK).let {
        it != Calendar.SATURDAY && it != Calendar.SUNDAY
    }

    /** Names for each cell: Home = most night-time fixes, Work = most weekday 9–17 fixes. */
    private fun names(points: List<LocationPointEntity>): Map<Cell, String> {
        val cells = points.groupBy(::cellOf)
        val home = cells.maxByOrNull { (_, ps) -> ps.count { hour(it.time) in 0..5 } }
            ?.takeIf { (_, ps) -> ps.count { hour(it.time) in 0..5 } >= 3 }?.key
        val work = cells.filterKeys { it != home }
            .maxByOrNull { (_, ps) -> ps.count { weekday(it.time) && hour(it.time) in 9..16 } }
            ?.takeIf { (_, ps) -> ps.count { weekday(it.time) && hour(it.time) in 9..16 } >= 6 }?.key
        var n = 1
        return cells.keys.sortedByDescending { cells[it]!!.size }.associateWith { cell ->
            when (cell) {
                home -> "Home"
                work -> "Work"
                else -> "Place ${n++}"
            }
        }
    }

    private data class Visit(val place: String, val start: Long, val end: Long)

    private fun visits(points: List<LocationPointEntity>): List<Visit> {
        if (points.isEmpty()) return emptyList()
        val names = names(points)
        val out = mutableListOf<Visit>()
        var cell = cellOf(points.first()); var start = points.first().time; var last = start
        for (p in points.drop(1)) {
            val c = cellOf(p)
            if (c != cell) {
                if (last - start >= MIN_VISIT_MS) out += Visit(names[cell] ?: "Somewhere", start, last)
                cell = c; start = p.time
            }
            last = p.time
        }
        if (last - start >= MIN_VISIT_MS) out += Visit(names[cell] ?: "Somewhere", start, last)
        return out
    }

    /** Finished visits from the last two days as ingestable items (deduped by sourceKey). */
    suspend fun collect(context: Context, settings: PersonalSettings): List<IngestedItemEntity> {
        if (!settings.locationEnabled) return emptyList()
        val dao = AppDatabase.getInstance(context).personalDao()
        val now = System.currentTimeMillis()
        val all = dao.pointsSince(now - 30L * 24 * 60 * 60 * 1000)
        val recent = visits(all).filter { it.end > now - 2L * 24 * 60 * 60 * 1000 }
        // The last visit is still ongoing; it is collected once you leave.
        val finished = recent.dropLast(1)
        val fmt = SimpleDateFormat("EEE HH:mm", Locale.US)
        val t = SimpleDateFormat("HH:mm", Locale.US)
        return finished.map {
            IngestedItemEntity(
                source = PersonalSource.LOCATION.name,
                sourceKey = "loc:${it.place}:${it.start}",
                title = it.place,
                body = "At ${it.place} ${fmt.format(Date(it.start))}–${t.format(Date(it.end))}",
                timestamp = it.end
            )
        }
    }

    /** Short lines for the routine card, e.g. "Usually at Work 09:00–17:30 on weekdays". */
    suspend fun routineLines(context: Context): List<String> {
        val dao = AppDatabase.getInstance(context).personalDao()
        val points = dao.pointsSince(System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000)
        val vs = visits(points)
        if (vs.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        val work = vs.filter { it.place == "Work" && weekday(it.start) }
        if (work.size >= 2) {
            val arrive = work.groupBy { day(it.start) }.values.map { minuteOf(it.minOf { v -> v.start }) }.sorted()
            val leave = work.groupBy { day(it.start) }.values.map { minuteOf(it.maxOf { v -> v.end }) }.sorted()
            lines += "Usually at Work ${clock(arrive[arrive.size / 2])}–${clock(leave[leave.size / 2])} on weekdays"
        }
        val home = vs.filter { it.place == "Home" }
        if (home.isNotEmpty()) {
            val back = home.filter { hour(it.start) in 15..23 }.groupBy { day(it.start) }.values
                .map { minuteOf(it.minOf { v -> v.start }) }.sorted()
            if (back.isNotEmpty()) lines += "Usually home by ${clock(back[back.size / 2])}"
        }
        val others = vs.map { it.place }.filter { it.startsWith("Place") }.groupingBy { it }.eachCount()
        if (others.isNotEmpty()) lines += "Other regular spots: ${others.entries.sortedByDescending { it.value }.take(3).joinToString { "${it.key} (${it.value} visits)" }}"
        return lines
    }

    private fun day(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
    private fun minuteOf(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    private fun clock(m: Int) = "%02d:%02d".format(m / 60, m % 60)
}
