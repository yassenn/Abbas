package ai.alal.app.personal

import android.app.Notification
import android.content.pm.PackageManager
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ai.alal.app.BuildConfig
import ai.alal.app.data.AppDatabase
import ai.alal.app.data.IngestedItemEntity
import ai.alal.app.data.PersonalSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Records incoming notifications (WhatsApp, Telegram, Signal, mail, …) so they
 * can be summarised. This is the only way Android lets one app see another
 * app's messages, so coverage starts when the user grants notification access.
 *
 * Nothing leaves the device: rows go straight into the encrypted database.
 */
class NotificationCollectorService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings by lazy { PersonalSettings(applicationContext) }
    private val appLabels = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (!settings.notificationsEnabled) return
            val item = toItem(sbn) ?: return
            scope.launch {
                try {
                    AppDatabase.getInstance(applicationContext).personalDao().insertItems(listOf(item))
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) android.util.Log.w("Personal", "Notification insert failed", e)
                }
            }
        } catch (e: Exception) {
            // Never let a malformed notification crash the listener.
            if (BuildConfig.DEBUG) android.util.Log.w("Personal", "Notification skipped", e)
        }
    }

    private fun toItem(sbn: StatusBarNotification): IngestedItemEntity? {
        if (sbn.packageName == packageName) return null
        val n = sbn.notification ?: return null
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.category in IGNORED_CATEGORIES) return null
        // SMS are read directly from the inbox when that source is on; avoid doubles.
        if (settings.smsEnabled && sbn.packageName == Telephony.Sms.getDefaultSmsPackage(this)) return null

        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?.mapNotNull { (it as? android.os.Bundle)?.getCharSequence("text")?.toString() }
            ?.takeLast(5)
            .orEmpty()
        val text = when {
            messages.isNotEmpty() -> messages.joinToString("\n")
            else -> (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        }?.trim().orEmpty()
        if (text.isEmpty()) return null

        return IngestedItemEntity(
            source = PersonalSource.NOTIFICATION.name,
            sourceKey = "notif:${sbn.key}:${sbn.postTime}:${text.hashCode()}",
            app = appLabel(sbn.packageName),
            sender = title,
            title = title,
            body = text.take(2_000),
            timestamp = sbn.postTime
        )
    }

    private fun appLabel(pkg: String): String = appLabels.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val IGNORED_CATEGORIES = setOf(
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_STATUS
        )
    }
}
