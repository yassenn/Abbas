package ai.alal.app.personal

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract
import ai.alal.app.BuildConfig
import ai.alal.app.data.IngestedItemEntity
import ai.alal.app.data.PersonalSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Upcoming calendar events (next 7 days); each event instance is collected once. */
object CalendarCollector {

    private const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    fun collect(context: Context, settings: PersonalSettings): List<IngestedItemEntity> {
        if (!settings.calendarEnabled || !settings.hasPermission(Manifest.permission.READ_CALENDAR)) return emptyList()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + WINDOW_MS)
        }.build()
        val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.US)
        val out = mutableListOf<IngestedItemEntity>()
        try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.ALL_DAY
                ),
                null, null, "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 100) {
                    val id = c.getLong(0)
                    val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(no title)"
                    val begin = c.getLong(2)
                    val end = c.getLong(3)
                    val place = c.getString(4)?.takeIf { it.isNotBlank() }
                    val allDay = c.getInt(5) == 1
                    val whenText = if (allDay) fmt.format(Date(begin)).substringBeforeLast(' ') + " (all day)"
                    else "${fmt.format(Date(begin))}–${SimpleDateFormat("HH:mm", Locale.US).format(Date(end))}"
                    out += IngestedItemEntity(
                        source = PersonalSource.CALENDAR.name,
                        sourceKey = "cal:$id:$begin",
                        title = title,
                        body = "Upcoming: $title, $whenText" + (place?.let { " at $it" } ?: ""),
                        timestamp = begin
                    )
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("Personal", "Calendar read failed", e)
        }
        return out
    }
}

data class ContactMatch(val name: String, val phones: List<String>, val emails: List<String>)

/** Looks people up by name, for chat answers and for resolving message recipients. */
object ContactsDirectory {

    fun canRead(context: Context) = PersonalSettings(context).hasPermission(Manifest.permission.READ_CONTACTS)

    /** Contacts whose display name contains [query] (case-insensitive), best matches first. */
    fun find(context: Context, query: String, limit: Int = 5): List<ContactMatch> {
        val q = query.trim()
        if (q.length < 2 || !canRead(context)) return emptyList()
        val ids = LinkedHashMap<Long, String>()
        try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
                arrayOf("%$q%"),
                "${ContactsContract.Contacts.TIMES_CONTACTED} DESC"
            )?.use { c ->
                while (c.moveToNext() && ids.size < limit) ids[c.getLong(0)] = c.getString(1).orEmpty()
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("Personal", "Contacts read failed", e)
            return emptyList()
        }
        // Exact name matches first ("Mom" before "Mom's dentist").
        return ids.map { (id, name) -> ContactMatch(name, phones(context, id), emails(context, id)) }
            .sortedBy { if (it.name.equals(q, ignoreCase = true)) 0 else 1 }
    }

    private fun phones(context: Context, id: Long): List<String> = values(
        context, ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.CONTACT_ID, id
    )

    private fun emails(context: Context, id: Long): List<String> = values(
        context, ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.CommonDataKinds.Email.CONTACT_ID, id
    )

    private fun values(context: Context, uri: android.net.Uri, column: String, idColumn: String, id: Long): List<String> =
        try {
            context.contentResolver.query(uri, arrayOf(column), "$idColumn = ?", arrayOf(id.toString()), null)
                ?.use { c -> buildList { while (c.moveToNext()) c.getString(0)?.let { add(it) } } }
                .orEmpty().distinct()
        } catch (_: Exception) {
            emptyList()
        }
}
