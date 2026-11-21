package ai.alal.app.personal

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import ai.alal.app.data.SecurityUtils

/**
 * Which phone sources Alal may read, plus per-source collection cursors.
 * Stored in the app's encrypted preferences.
 */
class PersonalSettings(private val context: Context) {

    private val prefs: SharedPreferences by lazy { SecurityUtils.getEncryptedPrefs(context) }

    var smsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMS, false)
        set(v) = prefs.edit().putBoolean(KEY_SMS, v).apply()

    var callsEnabled: Boolean
        get() = prefs.getBoolean(KEY_CALLS, false)
        set(v) = prefs.edit().putBoolean(KEY_CALLS, v).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, false)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, v).apply()

    var filesEnabled: Boolean
        get() = prefs.getBoolean(KEY_FILES, false)
        set(v) = prefs.edit().putBoolean(KEY_FILES, v).apply()

    /** Whole shared storage via "All files access" (the folder picker can't grant the root). */
    var allFilesEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALL_FILES, false)
        set(v) = prefs.edit().putBoolean(KEY_ALL_FILES, v).apply()

    var lastAllFilesTimestamp: Long
        get() = prefs.getLong(KEY_ALL_FILES_CURSOR, 0L)
        set(v) = prefs.edit().putLong(KEY_ALL_FILES_CURSOR, v).apply()

    fun allFilesGranted(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else hasPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)

    /** The user's own rules for digests (tone, focus, what to ignore). Added to the summary system prompt. */
    var summaryInstructions: String
        get() = prefs.getString(KEY_SUMMARY_PROMPT, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_SUMMARY_PROMPT, v.take(2_000)).apply()

    var summaryOptions: ai.alal.app.data.SummaryOptions
        get() = runCatching {
            com.google.gson.Gson().fromJson(prefs.getString(KEY_SUMMARY_OPTIONS, null), ai.alal.app.data.SummaryOptions::class.java)
        }.getOrNull() ?: ai.alal.app.data.SummaryOptions()
        set(v) = prefs.edit().putString(KEY_SUMMARY_OPTIONS, com.google.gson.Gson().toJson(v)).apply()

    var emailEnabled: Boolean
        get() = prefs.getBoolean(KEY_EMAIL, false)
        set(v) = prefs.edit().putBoolean(KEY_EMAIL, v).apply()

    /** Package names the "Check DMs now" sweep opens. */
    var dmApps: Set<String>
        get() = prefs.getStringSet(KEY_DM_APPS, emptySet()).orEmpty().toSet()
        set(v) = prefs.edit().putStringSet(KEY_DM_APPS, v.toSet()).apply()

    var usageEnabled: Boolean
        get() = prefs.getBoolean(KEY_USAGE, false)
        set(v) = prefs.edit().putBoolean(KEY_USAGE, v).apply()

    var calendarEnabled: Boolean
        get() = prefs.getBoolean(KEY_CALENDAR, false)
        set(v) = prefs.edit().putBoolean(KEY_CALENDAR, v).apply()

    /** Contacts are used for names and for resolving message recipients, not summarised. */
    var contactsEnabled: Boolean
        get() = prefs.getBoolean(KEY_CONTACTS, false)
        set(v) = prefs.edit().putBoolean(KEY_CONTACTS, v).apply()

    var healthEnabled: Boolean
        get() = prefs.getBoolean(KEY_HEALTH, false)
        set(v) = prefs.edit().putBoolean(KEY_HEALTH, v).apply()

    var locationEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCATION, false)
        set(v) = prefs.edit().putBoolean(KEY_LOCATION, v).apply()

    var screenEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREEN, false)
        set(v) = prefs.edit().putBoolean(KEY_SCREEN, v).apply()

    // ── Write access (what Alal may propose doing; every action still needs Confirm) ──

    var writeAlarms: Boolean
        get() = prefs.getBoolean(KEY_W_ALARMS, true)
        set(v) = prefs.edit().putBoolean(KEY_W_ALARMS, v).apply()

    var writeCalendar: Boolean
        get() = prefs.getBoolean(KEY_W_CALENDAR, true)
        set(v) = prefs.edit().putBoolean(KEY_W_CALENDAR, v).apply()

    var writeTodos: Boolean
        get() = prefs.getBoolean(KEY_W_TODOS, true)
        set(v) = prefs.edit().putBoolean(KEY_W_TODOS, v).apply()

    var writeSms: Boolean
        get() = prefs.getBoolean(KEY_W_SMS, false)
        set(v) = prefs.edit().putBoolean(KEY_W_SMS, v).apply()

    var writeEmail: Boolean
        get() = prefs.getBoolean(KEY_W_EMAIL, false)
        set(v) = prefs.edit().putBoolean(KEY_W_EMAIL, v).apply()

    var writeAppMessages: Boolean
        get() = prefs.getBoolean(KEY_W_APPS, false)
        set(v) = prefs.edit().putBoolean(KEY_W_APPS, v).apply()

    fun writeCapabilities() = WriteCapabilities(
        alarms = writeAlarms, calendar = writeCalendar, todos = writeTodos,
        sms = writeSms, email = writeEmail, appMessages = writeAppMessages
    )

    var lastUsageTimestamp: Long
        get() = prefs.getLong(KEY_USAGE_CURSOR, 0L)
        set(v) = prefs.edit().putLong(KEY_USAGE_CURSOR, v).apply()

    /** Last fully collected health day, as epoch day. */
    var lastHealthDay: Long
        get() = prefs.getLong(KEY_HEALTH_CURSOR, 0L)
        set(v) = prefs.edit().putLong(KEY_HEALTH_CURSOR, v).apply()

    /** Background (scheduled) summaries on; when off, summaries only run on demand. */
    var backgroundEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND, false)
        set(v) = prefs.edit().putBoolean(KEY_BACKGROUND, v).apply()

    /** Folders picked through the system folder picker (persisted tree URIs). */
    var watchedFolders: Set<String>
        get() = prefs.getStringSet(KEY_FOLDERS, emptySet()).orEmpty().toSet()
        set(v) = prefs.edit().putStringSet(KEY_FOLDERS, v.toSet()).apply()

    var lastSmsTimestamp: Long
        get() = prefs.getLong(KEY_SMS_CURSOR, 0L)
        set(v) = prefs.edit().putLong(KEY_SMS_CURSOR, v).apply()

    var lastCallTimestamp: Long
        get() = prefs.getLong(KEY_CALL_CURSOR, 0L)
        set(v) = prefs.edit().putLong(KEY_CALL_CURSOR, v).apply()

    var lastFileTimestamp: Long
        get() = prefs.getLong(KEY_FILE_CURSOR, 0L)
        set(v) = prefs.edit().putLong(KEY_FILE_CURSOR, v).apply()

    var lastRunAt: Long
        get() = prefs.getLong(KEY_LAST_RUN, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_RUN, v).apply()

    val anySourceEnabled: Boolean
        get() = smsEnabled || callsEnabled || notificationsEnabled || (filesEnabled && watchedFolders.isNotEmpty()) || allFilesEnabled || emailEnabled ||
            usageEnabled || calendarEnabled || healthEnabled || locationEnabled || screenEnabled

    fun addFolder(uri: Uri) { watchedFolders = watchedFolders + uri.toString() }
    fun removeFolder(uri: String) { watchedFolders = watchedFolders - uri }

    fun resetCursors() {
        prefs.edit()
            .remove(KEY_SMS_CURSOR).remove(KEY_CALL_CURSOR).remove(KEY_FILE_CURSOR)
            .remove(KEY_USAGE_CURSOR).remove(KEY_HEALTH_CURSOR).remove(KEY_ALL_FILES_CURSOR)
            .apply()
    }

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** True when the user has granted Alal notification access in system settings. */
    fun notificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        val me = ComponentName(context, NotificationCollectorService::class.java)
        return enabled.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it == me }
    }

    /** True when "Usage access" is granted for Alal in system settings. */
    fun usageAccessGranted(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        } else {
            ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /** True when Alal's screen reader is switched on in Accessibility settings. */
    fun screenReaderEnabled(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(context, ScreenReaderService::class.java)
        return enabled.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it == me }
    }

    companion object {
        private const val KEY_SUMMARY_PROMPT = "personal_summary_prompt"
        private const val KEY_SUMMARY_OPTIONS = "personal_summary_options"
        private const val KEY_EMAIL = "personal_email"
        private const val KEY_DM_APPS = "personal_dm_apps"
        private const val KEY_ALL_FILES = "personal_all_files"
        private const val KEY_ALL_FILES_CURSOR = "personal_all_files_cursor"
        private const val KEY_USAGE = "personal_usage"
        private const val KEY_CALENDAR = "personal_calendar"
        private const val KEY_CONTACTS = "personal_contacts"
        private const val KEY_HEALTH = "personal_health"
        private const val KEY_LOCATION = "personal_location"
        private const val KEY_SCREEN = "personal_screen"
        private const val KEY_W_ALARMS = "write_alarms"
        private const val KEY_W_CALENDAR = "write_calendar"
        private const val KEY_W_TODOS = "write_todos"
        private const val KEY_W_SMS = "write_sms"
        private const val KEY_W_EMAIL = "write_email"
        private const val KEY_W_APPS = "write_app_messages"
        private const val KEY_USAGE_CURSOR = "personal_usage_cursor"
        private const val KEY_HEALTH_CURSOR = "personal_health_cursor"
        private const val KEY_SMS = "personal_sms"
        private const val KEY_CALLS = "personal_calls"
        private const val KEY_NOTIFICATIONS = "personal_notifications"
        private const val KEY_FILES = "personal_files"
        private const val KEY_BACKGROUND = "personal_background"
        private const val KEY_FOLDERS = "personal_folders"
        private const val KEY_SMS_CURSOR = "personal_sms_cursor"
        private const val KEY_CALL_CURSOR = "personal_call_cursor"
        private const val KEY_FILE_CURSOR = "personal_file_cursor"
        private const val KEY_LAST_RUN = "personal_last_run"

        /** First collection looks back this far. */
        const val INITIAL_LOOKBACK_MS = 3L * 24 * 60 * 60 * 1000
    }
}

/** Which kinds of action Alal may propose. Each still needs a tap on Confirm. */
data class WriteCapabilities(
    val alarms: Boolean = true,
    val calendar: Boolean = true,
    val todos: Boolean = true,
    val sms: Boolean = false,
    val email: Boolean = false,
    val appMessages: Boolean = false
)
