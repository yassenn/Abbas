package ai.alal.app.personal

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Background digest. Runs every few hours, only while charging and with a
 * healthy battery, because running a local model is heavy.
 */
class PersonalDigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = PersonalSettings(applicationContext)
        if (!settings.backgroundEnabled || !settings.anySourceEnabled) return Result.success()
        // A failure (e.g. no model downloaded) just waits for the next period;
        // retrying with backoff would keep waking the phone for nothing.
        PersonalSummarizer.run(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "alal_personal_digest"

        /** (Re)schedules with the interval and charging rule from the user's summary settings. */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val options = PersonalSettings(context).summaryOptions
            val request = PeriodicWorkRequestBuilder<PersonalDigestWorker>(
                options.scheduleHours.coerceIn(1, 24).toLong(), TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(options.requireCharging)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            // UPDATE so a changed interval or charging rule takes effect without losing the slot.
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
