package ai.alal.app.personal

import android.Manifest
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.Telephony
import ai.alal.app.BuildConfig
import ai.alal.app.data.IngestedItemEntity
import ai.alal.app.data.PersonalSource
import ai.alal.app.repository.DocumentTextExtractor

/**
 * Pull-based collectors for SMS, the call log and watched folders. Notifications
 * are push-based (see [NotificationCollectorService]). Each collector only reads
 * rows newer than its cursor in [PersonalSettings] and silently returns nothing
 * when its permission is missing.
 */
object PhoneCollectors {

    private const val MAX_BODY_CHARS = 2_000
    private const val MAX_FILE_CHARS = 4_000
    private const val MAX_ROWS = 500
    private const val MAX_FILES = 40

    fun collectSms(context: Context, settings: PersonalSettings): List<IngestedItemEntity> {
        if (!settings.smsEnabled || !settings.hasPermission(Manifest.permission.READ_SMS)) return emptyList()
        val since = settings.lastSmsTimestamp.takeIf { it > 0 }
            ?: (System.currentTimeMillis() - PersonalSettings.INITIAL_LOOKBACK_MS)
        val names = ContactNames(context, settings)
        val out = mutableListOf<IngestedItemEntity>()
        var newest = since
        safeQuery(
            context,
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext() && out.size < MAX_ROWS) {
                val id = c.getLong(0)
                val address = c.getString(1).orEmpty()
                val body = c.getString(2).orEmpty().trim()
                val date = c.getLong(3)
                newest = maxOf(newest, date)
                if (body.isEmpty()) continue
                val item = IngestedItemEntity(
                    source = PersonalSource.SMS.name,
                    sourceKey = "sms:$id",
                    sender = names.lookup(address),
                    body = body.take(MAX_BODY_CHARS),
                    timestamp = date
                )
                // One-time codes are never stored, even encrypted.
                if (!PersonalSummarizer.isNoise(item)) out += item
            }
        }
        settings.lastSmsTimestamp = newest
        return out
    }

    fun collectCalls(context: Context, settings: PersonalSettings): List<IngestedItemEntity> {
        if (!settings.callsEnabled || !settings.hasPermission(Manifest.permission.READ_CALL_LOG)) return emptyList()
        val since = settings.lastCallTimestamp.takeIf { it > 0 }
            ?: (System.currentTimeMillis() - PersonalSettings.INITIAL_LOOKBACK_MS)
        val out = mutableListOf<IngestedItemEntity>()
        var newest = since
        safeQuery(
            context,
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE),
            "${CallLog.Calls.DATE} > ?",
            arrayOf(since.toString()),
            "${CallLog.Calls.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext() && out.size < MAX_ROWS) {
                val id = c.getLong(0)
                val number = c.getString(1).orEmpty()
                val name = c.getString(2)?.takeIf { it.isNotBlank() } ?: number.ifBlank { "Unknown number" }
                val type = c.getInt(3)
                val seconds = c.getLong(4)
                val date = c.getLong(5)
                newest = maxOf(newest, date)
                val body = when (type) {
                    CallLog.Calls.MISSED_TYPE -> "Missed call from $name"
                    CallLog.Calls.REJECTED_TYPE -> "Rejected call from $name"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing call to $name (${duration(seconds)})"
                    CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail from $name"
                    else -> "Incoming call from $name (${duration(seconds)})"
                }
                out += IngestedItemEntity(
                    source = PersonalSource.CALL.name,
                    sourceKey = "call:$id",
                    sender = name,
                    body = body,
                    timestamp = date
                )
            }
        }
        settings.lastCallTimestamp = newest
        return out
    }

    /** New or changed PDF / DOCX / text documents in the watched folders (two levels deep). */
    fun collectFiles(context: Context, settings: PersonalSettings): List<IngestedItemEntity> {
        if (!settings.filesEnabled) return emptyList()
        val since = settings.lastFileTimestamp.takeIf { it > 0 }
            ?: (System.currentTimeMillis() - PersonalSettings.INITIAL_LOOKBACK_MS)
        val out = mutableListOf<IngestedItemEntity>()
        var newest = since
        for (tree in settings.watchedFolders) {
            val treeUri = Uri.parse(tree)
            val rootId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { continue }
            val stack = ArrayDeque(listOf(rootId to 0))
            while (stack.isNotEmpty() && out.size < MAX_FILES) {
                val (docId, depth) = stack.removeFirst()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                safeQuery(
                    context, children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ), null, null, null
                )?.use { c ->
                    while (c.moveToNext() && out.size < MAX_FILES) {
                        val id = c.getString(0)
                        val name = c.getString(1).orEmpty()
                        val mime = c.getString(2).orEmpty()
                        val modified = c.getLong(3)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < 1) stack.addLast(id to depth + 1)
                            continue
                        }
                        if (modified <= since || !isReadable(name, mime)) continue
                        newest = maxOf(newest, modified)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        val raw = try {
                            DocumentTextExtractor.extract(context, uri, name, mime)
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) android.util.Log.w("Personal", "Skip unreadable file", e)
                            continue
                        }
                        val text = raw.replace(Regex("\\s+"), " ").trim()
                        if (text.isEmpty()) continue
                        out += IngestedItemEntity(
                            source = PersonalSource.FILE.name,
                            sourceKey = "file:$uri:$modified",
                            title = name,
                            body = text.take(MAX_FILE_CHARS),
                            timestamp = modified
                        )
                    }
                }
            }
        }
        settings.lastFileTimestamp = newest
        return out
    }

    /**
     * New or changed documents anywhere in shared storage (needs All files access).
     * Skips Android/ (other apps' private areas), hidden folders and huge files.
     */
    fun collectAllFiles(context: Context, settings: PersonalSettings): List<IngestedItemEntity> {
        if (!settings.allFilesEnabled || !settings.allFilesGranted()) return emptyList()
        val root = android.os.Environment.getExternalStorageDirectory()
        val since = settings.lastAllFilesTimestamp.takeIf { it > 0 }
            ?: (System.currentTimeMillis() - PersonalSettings.INITIAL_LOOKBACK_MS)
        val candidates = root.walkTopDown()
            .maxDepth(6)
            .onEnter { dir -> dir == root || !(dir.name.startsWith(".") || (dir.parentFile == root && dir.name == "Android")) }
            .filter { it.isFile && it.lastModified() > since && it.length() in 1..(50L * 1024 * 1024) }
            .filter { isReadable(it.name, "") }
            .sortedBy { it.lastModified() }
            .take(MAX_FILES)
            .toList()
        val out = mutableListOf<IngestedItemEntity>()
        var newest = since
        for (file in candidates) {
            newest = maxOf(newest, file.lastModified())
            val raw = try {
                DocumentTextExtractor.extract(context, android.net.Uri.fromFile(file), file.name, null)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.w("Personal", "Skip unreadable file", e)
                continue
            }
            val text = raw.replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) continue
            out += IngestedItemEntity(
                source = PersonalSource.FILE.name,
                sourceKey = "file:${file.absolutePath}:${file.lastModified()}",
                title = file.relativeTo(root).path,
                body = text.take(MAX_FILE_CHARS),
                timestamp = file.lastModified()
            )
        }
        settings.lastAllFilesTimestamp = newest
        return out
    }

    private fun isReadable(name: String, mime: String): Boolean {
        val n = name.lowercase()
        return mime == "application/pdf" || n.endsWith(".pdf") ||
            mime.contains("wordprocessingml") || n.endsWith(".docx") ||
            mime.startsWith("text/") || n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv")
    }

    private fun duration(seconds: Long): String =
        if (seconds < 60) "${seconds}s" else "${seconds / 60} min"

    private fun safeQuery(
        context: Context, uri: Uri, projection: Array<String>,
        selection: String?, args: Array<String>?, sort: String?
    ): Cursor? = try {
        context.contentResolver.query(uri, projection, selection, args, sort)
    } catch (e: Exception) {
        // SecurityException after a permission revoke, or a provider that went away.
        if (BuildConfig.DEBUG) android.util.Log.w("Personal", "Query failed for $uri", e)
        null
    }

    /** Resolves phone numbers to contact names when READ_CONTACTS is granted. */
    private class ContactNames(private val context: Context, settings: PersonalSettings) {
        private val allowed = settings.hasPermission(Manifest.permission.READ_CONTACTS)
        private val cache = HashMap<String, String>()

        fun lookup(number: String): String {
            if (number.isBlank()) return "Unknown"
            if (!allowed) return number
            return cache.getOrPut(number) {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                safeQuery(context, uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: number
            }
        }
    }
}
