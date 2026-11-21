package ai.alal.app.personal

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import ai.alal.app.BuildConfig
import ai.alal.app.data.IngestedItemEntity
import ai.alal.app.data.PersonalSource
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Daily health summaries from Health Connect (steps, sleep, heart rate, workouts),
 * for whatever your fitness apps and watch write there. One item per finished day.
 */
object HealthCollector {

    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )

    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun grantedPermissions(context: Context): Set<String> =
        if (!isAvailable(context)) emptySet()
        else try {
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        } catch (_: Exception) {
            emptySet()
        }

    suspend fun collect(context: Context, settings: PersonalSettings): List<IngestedItemEntity> {
        if (!settings.healthEnabled || !isAvailable(context)) return emptyList()
        val client = HealthConnectClient.getOrCreate(context)
        val granted = try { client.permissionController.getGrantedPermissions() } catch (_: Exception) { return emptyList() }
        if (granted.isEmpty()) return emptyList()

        val zone = ZoneId.systemDefault()
        val yesterday = LocalDate.now(zone).minusDays(1)
        val first = settings.lastHealthDay.takeIf { it > 0 }?.let { LocalDate.ofEpochDay(it + 1) }
            ?: yesterday.minusDays(2)
        if (first.isAfter(yesterday)) return emptyList()

        val out = mutableListOf<IngestedItemEntity>()
        var day = maxOf(first, yesterday.minusDays(6))
        while (!day.isAfter(yesterday)) {
            try {
                summarizeDay(client, granted, day, zone)?.let { out += it }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.w("Personal", "Health read failed for $day", e)
            }
            day = day.plusDays(1)
        }
        settings.lastHealthDay = yesterday.toEpochDay()
        return out
    }

    private suspend fun summarizeDay(
        client: HealthConnectClient,
        granted: Set<String>,
        day: LocalDate,
        zone: ZoneId
    ): IngestedItemEntity? {
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        val range = TimeRangeFilter.between(start, end)
        val parts = mutableListOf<String>()

        val metrics = buildSet {
            if (HealthPermission.getReadPermission(StepsRecord::class) in granted) add(StepsRecord.COUNT_TOTAL)
            if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted) {
                add(HeartRateRecord.BPM_AVG); add(HeartRateRecord.BPM_MIN); add(HeartRateRecord.BPM_MAX)
            }
        }
        if (metrics.isNotEmpty()) {
            val agg = client.aggregate(AggregateRequest(metrics, range))
            agg[StepsRecord.COUNT_TOTAL]?.let { parts += "%,d steps".format(it) }
            agg[HeartRateRecord.BPM_AVG]?.let { avg ->
                parts += "heart rate avg $avg bpm (${agg[HeartRateRecord.BPM_MIN]}–${agg[HeartRateRecord.BPM_MAX]})"
            }
        }

        if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
            // Sleep that ended this day (i.e. last night).
            val sleepRange = TimeRangeFilter.between(start.minus(Duration.ofHours(12)), end.minus(Duration.ofHours(12)))
            val sessions = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, sleepRange)).records
            if (sessions.isNotEmpty()) {
                val t = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
                val total = sessions.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                val s = sessions.minByOrNull { it.startTime }!!
                val e = sessions.maxByOrNull { it.endTime }!!
                parts += "slept ${total / 60}h ${total % 60}m (${t.format(s.startTime)}–${t.format(e.endTime)})"
            }
        }

        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) {
            val workouts = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records
            if (workouts.isNotEmpty()) {
                parts += workouts.joinToString(", ", prefix = "workouts: ") {
                    "${it.title ?: "workout"} ${Duration.between(it.startTime, it.endTime).toMinutes()} min"
                }
            }
        }

        if (parts.isEmpty()) return null
        val label = day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US))
        return IngestedItemEntity(
            source = PersonalSource.HEALTH.name,
            sourceKey = "health:${day.toEpochDay()}",
            body = "Health on $label: ${parts.joinToString("; ")}",
            timestamp = end.toEpochMilli() - 1
        )
    }
}
