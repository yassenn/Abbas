package ai.alal.app.personal

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import ai.alal.app.data.AppDatabase
import ai.alal.app.data.TodoEntity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Something the model proposed doing on the phone. Nothing here runs until the
 * user taps Confirm on the card shown under the reply.
 */
sealed interface PhoneAction {
    /** One-line description for the confirm card. */
    val summary: String
    /** Runtime permissions the action needs before it can run. */
    val permissions: List<String> get() = emptyList()

    data class Alarm(val hour: Int, val minute: Int, val label: String) : PhoneAction {
        override val summary get() = "Set alarm ${"%02d:%02d".format(hour, minute)}" + label.suffix()
    }

    data class Timer(val seconds: Int, val label: String) : PhoneAction {
        override val summary get() = "Start ${formatDuration(seconds)} timer" + label.suffix()
    }

    data class Reminder(val at: Long, val title: String) : PhoneAction {
        override val summary get() = "Remind me “$title” · ${formatWhen(at)}"
        override val permissions get() = CALENDAR
    }

    data class Event(val start: Long, val end: Long, val title: String) : PhoneAction {
        override val summary get() = "Add event “$title” · ${formatWhen(start)}"
        override val permissions get() = CALENDAR
    }

    data class Todo(val text: String, val dueAt: Long?) : PhoneAction {
        override val summary get() = "Add to-do “$text”" + (dueAt?.let { " · due ${formatWhen(it)}" } ?: "")
    }

    /** Sent directly from this phone's SIM after Confirm. [to] is a number or a contact name. */
    data class Sms(val to: String, val body: String) : PhoneAction {
        override val summary get() = "Send SMS to $to: “${body.preview()}”"
        override val permissions get() =
            listOf(Manifest.permission.SEND_SMS) + if (to.isNameLike()) listOf(Manifest.permission.READ_CONTACTS) else emptyList()
    }

    /** Opens a ready-to-send draft in the mail app; Android has no API to send email silently. */
    data class Email(val to: String, val subject: String, val body: String) : PhoneAction {
        override val summary get() = "Draft email to $to: “${subject.ifBlank { body }.preview()}”"
        override val permissions get() =
            if (to.isNameLike() && !to.contains('@')) listOf(Manifest.permission.READ_CONTACTS) else emptyList()
    }

    /** Opens the chat in WhatsApp / Telegram / Signal with the text filled in. */
    data class AppMessage(val app: String, val to: String, val body: String) : PhoneAction {
        override val summary get() = "Draft ${app.replaceFirstChar { it.uppercase() }} message" +
            (if (to.isNotBlank()) " to $to" else "") + ": “${body.preview()}”"
        override val permissions get() =
            if (to.isNameLike()) listOf(Manifest.permission.READ_CONTACTS) else emptyList()
    }

    companion object {
        val CALENDAR = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }
}

private fun String.suffix() = if (isBlank()) "" else " — $this"

private fun String.preview() = if (length > 60) take(57) + "…" else this

/** "Mom" vs "+34 600 000 000". */
internal fun String.isNameLike() = any { it.isLetter() } && !contains('@')

private fun formatWhen(at: Long): String = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(at))

private fun formatDuration(seconds: Int): String = when {
    seconds % 3600 == 0 -> "${seconds / 3600} h"
    seconds >= 60 -> "${seconds / 60} min"
    else -> "${seconds} s"
}

/** Parses `<action>{json}</action>` tags out of a model reply. */
object PhoneActionParser {

    private val tag = Regex("(?s)<action>\\s*(\\{.*?\\})\\s*</action>")
    private val partialTag = Regex("(?s)<action>(?!.*</action>).*$")

    fun parse(text: String): List<PhoneAction> =
        tag.findAll(text).mapNotNull { runCatching { fromJson(JSONObject(it.groupValues[1])) }.getOrNull() }.toList()

    /** Reply text without action tags, including a half-streamed trailing tag. */
    fun strip(text: String): String =
        text.replace(tag, "").replace(partialTag, "").trimEnd()

    private fun fromJson(o: JSONObject): PhoneAction? {
        return when (o.optString("type").lowercase()) {
            "alarm" -> {
                val (h, m) = parseClock(o.optString("time")) ?: return null
                PhoneAction.Alarm(h, m, o.optString("label"))
            }
            "timer" -> {
                val secs = o.optInt("seconds", 0).takeIf { it > 0 }
                    ?: (o.optInt("minutes", 0) * 60).takeIf { it > 0 } ?: return null
                PhoneAction.Timer(secs.coerceAtMost(24 * 3600), o.optString("label"))
            }
            "reminder" -> {
                val at = DigestParser.parseDate(o.optString("datetime")) ?: return null
                PhoneAction.Reminder(at, o.optString("title").ifBlank { "Reminder" })
            }
            "event" -> {
                val start = DigestParser.parseDate(o.optString("start")) ?: return null
                val end = DigestParser.parseDate(o.optString("end"))?.takeIf { it > start } ?: (start + 60 * 60 * 1000)
                PhoneAction.Event(start, end, o.optString("title").ifBlank { "Event" })
            }
            "todo" -> {
                val text = o.optString("text").ifBlank { return null }
                PhoneAction.Todo(text, DigestParser.parseDate(o.optString("due")))
            }
            "sms", "text" -> {
                val to = o.optString("to").ifBlank { return null }
                val body = o.optString("body").ifBlank { return null }
                PhoneAction.Sms(to, body)
            }
            "email" -> {
                val to = o.optString("to").ifBlank { return null }
                PhoneAction.Email(to, o.optString("subject"), o.optString("body"))
            }
            "message" -> {
                val app = o.optString("app").lowercase().ifBlank { "whatsapp" }
                val body = o.optString("body").ifBlank { return null }
                PhoneAction.AppMessage(app, o.optString("to"), body)
            }
            else -> null
        }
    }

    private fun parseClock(value: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2}):(\\d{2})").find(value) ?: return null
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    /**
     * Instructions appended to the system prompt when the user's message looks
     * like it asks for an action.
     */
    fun instructions(caps: WriteCapabilities, now: Date = Date()): String {
        val stamp = SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.US).format(now)
        val lines = buildList {
            if (caps.alarms) {
                add("""<action>{"type":"alarm","time":"HH:MM","label":"..."}</action>""")
                add("""<action>{"type":"timer","minutes":10,"label":"..."}</action>""")
            }
            if (caps.calendar) {
                add("""<action>{"type":"reminder","datetime":"YYYY-MM-DD HH:MM","title":"..."}</action>""")
                add("""<action>{"type":"event","start":"YYYY-MM-DD HH:MM","end":"YYYY-MM-DD HH:MM","title":"..."}</action>""")
            }
            if (caps.todos) add("""<action>{"type":"todo","text":"...","due":"YYYY-MM-DD HH:MM or none"}</action>""")
            if (caps.sms) add("""<action>{"type":"sms","to":"contact name or phone number","body":"..."}</action>""")
            if (caps.email) add("""<action>{"type":"email","to":"contact name or email address","subject":"...","body":"..."}</action>""")
            if (caps.appMessages) add("""<action>{"type":"message","app":"whatsapp|telegram|signal","to":"contact name or phone number","body":"..."}</action>""")
        }
        if (lines.isEmpty()) {
            return "You cannot perform actions on this phone; the user has turned them all off in Write access. Say so if asked."
        }
        return buildString {
            appendLine("You can act on this phone. Current date and time: $stamp.")
            appendLine("Only when the user asks for one, reply with one short sentence and then exactly one line per action, using one of:")
            lines.forEach { appendLine(it) }
            appendLine("Use 24-hour times and resolve words like \"tomorrow\" against the current date.")
            appendLine("Write messages in the user's voice, ready to send. The app shows each action to the user and only runs it after they tap Confirm.")
            append("Anything not listed above is turned off in Write access: tell the user to enable it there instead of emitting it.")
        }
    }

    /** Whether [action] is allowed by the user's Write access settings. */
    fun isAllowed(action: PhoneAction, caps: WriteCapabilities): Boolean = when (action) {
        is PhoneAction.Alarm, is PhoneAction.Timer -> caps.alarms
        is PhoneAction.Reminder, is PhoneAction.Event -> caps.calendar
        is PhoneAction.Todo -> caps.todos
        is PhoneAction.Sms -> caps.sms
        is PhoneAction.Email -> caps.email
        is PhoneAction.AppMessage -> caps.appMessages
    }

    private val actionWords = Regex(
        "\\b(alarm|wake me|timer|remind|reminder|calendar|event|schedule|meeting|appointment|to-?do|add a task|" +
            "send|text|sms|message|reply|email|e-mail|mail|whatsapp|telegram|signal|tell)\\b",
        RegexOption.IGNORE_CASE
    )

    fun looksLikeActionRequest(text: String) = actionWords.containsMatchIn(text)
}

/** Runs a confirmed [PhoneAction]. Returns a short status line or throws. */
object PhoneActionExecutor {

    suspend fun execute(context: Context, action: PhoneAction): String = when (action) {
        is PhoneAction.Alarm -> {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, action.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, action.label.ifBlank { "Alal" })
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "Alarm set"
        }
        is PhoneAction.Timer -> {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, action.label.ifBlank { "Alal" })
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "Timer started"
        }
        is PhoneAction.Reminder -> {
            insertEvent(context, action.title, action.at, action.at + 15 * 60 * 1000, reminderMinutes = 0)
            "Reminder added to your calendar"
        }
        is PhoneAction.Event -> {
            insertEvent(context, action.title, action.start, action.end, reminderMinutes = 15)
            "Event added to your calendar"
        }
        is PhoneAction.Todo -> {
            AppDatabase.getInstance(context).personalDao().insertTodos(
                listOf(TodoEntity(text = action.text, dueAt = action.dueAt, origin = "Chat"))
            )
            "Added to To-dos"
        }
        is PhoneAction.Sms -> {
            val (name, number) = resolvePhone(context, action.to)
            val sms = if (android.os.Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(android.telephony.SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
            }
            val parts = sms.divideMessage(action.body)
            sms.sendMultipartTextMessage(number, null, parts, null, null)
            "SMS sent to $name"
        }
        is PhoneAction.Email -> {
            val address = if (action.to.contains('@')) action.to
            else ContactsDirectory.find(context, action.to).firstOrNull { it.emails.isNotEmpty() }?.emails?.first()
                ?: throw IllegalStateException("No email address for ${action.to}")
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:"))
                    .putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
                    .putExtra(Intent.EXTRA_SUBJECT, action.subject)
                    .putExtra(Intent.EXTRA_TEXT, action.body)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "Email draft opened — tap send"
        }
        is PhoneAction.AppMessage -> {
            openAppMessage(context, action)
            "Draft opened in ${action.app} — tap send"
        }
    }

    /** Contact name or raw number → (display name, dialable number). */
    private fun resolvePhone(context: Context, to: String): Pair<String, String> {
        if (!to.isNameLike()) return Pair(to, to.filter { it.isDigit() || it == '+' })
        val match = ContactsDirectory.find(context, to).firstOrNull { it.phones.isNotEmpty() }
            ?: throw IllegalStateException("No phone number for $to")
        return match.name to match.phones.first()
    }

    private fun openAppMessage(context: Context, action: PhoneAction.AppMessage) {
        val number = action.to.takeIf { it.isNotBlank() }?.let { runCatching { resolvePhone(context, it).second }.getOrNull() }
            ?.filter { it.isDigit() }
        val intent = when (action.app) {
            "whatsapp" -> if (number != null) {
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/$number?text=${android.net.Uri.encode(action.body)}"))
                    .setPackage("com.whatsapp")
            } else {
                shareTo("com.whatsapp", action.body)
            }
            "telegram" -> shareTo("org.telegram.messenger", action.body)
            "signal" -> shareTo("org.thoughtcrime.securesms", action.body)
            else -> Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, action.body), null)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            throw IllegalStateException("${action.app.replaceFirstChar { it.uppercase() }} isn't installed")
        }
    }

    private fun shareTo(pkg: String, text: String) =
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg)

    private fun insertEvent(context: Context, title: String, start: Long, end: Long, reminderMinutes: Int) {
        val resolver = context.contentResolver
        val calendarId = writableCalendarId(context)
            ?: throw IllegalStateException("No writable calendar on this phone")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw IllegalStateException("Calendar refused the event")
        val eventId = uri.lastPathSegment?.toLongOrNull() ?: return
        resolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, reminderMinutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
    }

    /** The primary visible calendar the user can write to, else the first writable one. */
    private fun writableCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
            var first: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (first == null) first = id
                if (c.getInt(1) == 1) return id
            }
            return first
        }
        return null
    }
}
