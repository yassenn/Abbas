package ai.alal.app.personal

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ai.alal.app.data.AppDatabase
import ai.alal.app.data.IngestedItemEntity
import ai.alal.app.data.PersonalSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Imports chat history the user exported themselves:
 *  - WhatsApp "Export chat" (.txt, or .zip with media)
 *  - Telegram Desktop export (result.json)
 *  - Instagram / Facebook "Download your information" (JSON zip, messages/inbox)
 *  - X / Twitter archive (data/direct-messages.js)
 *
 * Messages are grouped per conversation per day. History older than a week is
 * stored as already-summarised so it is searchable from chat without flooding
 * the next digest.
 */
object ChatImporter {

    data class ChatLine(val platform: String, val thread: String, val sender: String, val time: Long, val text: String)
    data class Result(val messages: Int, val conversations: Int, val platforms: Set<String>)

    private const val MAX_ENTRY_BYTES = 30L * 1024 * 1024
    private const val MAX_MESSAGES = 100_000
    private const val ITEM_CHARS = 2_500
    private const val RECENT_MS = 7L * 24 * 60 * 60 * 1000

    suspend fun import(context: Context, uris: List<Uri>): Result = withContext(Dispatchers.IO) {
        val lines = ArrayList<ChatLine>()
        for (uri in uris) {
            val name = displayName(context, uri)
            context.contentResolver.openInputStream(uri)?.use { input ->
                parseStream(name, input, lines)
            }
        }
        if (lines.isEmpty()) throw IllegalArgumentException("No messages found. Share a WhatsApp chat export or a Telegram / Instagram / Facebook / X data download.")
        val items = toItems(lines)
        AppDatabase.getInstance(context).personalDao().insertItems(items)
        Result(lines.size, lines.map { it.platform to it.thread }.toSet().size, lines.map { it.platform }.toSet())
    }

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun parseStream(name: String, input: InputStream, out: MutableList<ChatLine>) {
        val lower = name.lowercase()
        when {
            lower.endsWith(".zip") -> parseZip(input, out)
            lower.endsWith(".json") -> parseJson(name, readCapped(input), out)
            lower.endsWith(".js") -> parseTwitter(readCapped(input), out)
            lower.endsWith(".txt") -> parseWhatsApp(threadFromFileName(name), readCapped(input), out)
            else -> {
                // Unknown name (some share sheets drop it): sniff the content.
                val bytes = input.readNBytesCompat(MAX_ENTRY_BYTES)
                if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                    parseZip(bytes.inputStream(), out)
                } else {
                    val text = bytes.toString(Charsets.UTF_8)
                    if (text.trimStart().startsWith("{")) parseJson(name, text, out) else parseWhatsApp("WhatsApp chat", text, out)
                }
            }
        }
    }

    private fun parseZip(input: InputStream, out: MutableList<ChatLine>) {
        ZipInputStream(input).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > 20_000 || out.size >= MAX_MESSAGES) break
                if (entry.isDirectory) continue
                val path = entry.name.replace('\\', '/')
                val lower = path.lowercase()
                when {
                    lower.endsWith(".txt") && lower.contains("whatsapp") ->
                        parseWhatsApp(threadFromFileName(path.substringAfterLast('/')), readCapped(zip), out)
                    lower.endsWith(".txt") && entries <= 2 ->
                        parseWhatsApp("WhatsApp chat", readCapped(zip), out)
                    lower.contains("messages/inbox/") && lower.substringAfterLast('/').startsWith("message_") && lower.endsWith(".json") ->
                        parseMeta(path, readCapped(zip), out)
                    lower.endsWith("result.json") -> parseJson(path, readCapped(zip), out)
                    lower.endsWith("direct-messages.js") || lower.endsWith("direct-messages-group.js") ->
                        parseTwitter(readCapped(zip), out)
                }
            }
        }
    }

    private fun parseJson(name: String, text: String, out: MutableList<ChatLine>) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        when {
            obj.has("participants") && obj.has("messages") -> parseMeta(name, text, out)
            obj.has("chats") -> {
                val list = obj.getJSONObject("chats").optJSONArray("list") ?: return
                for (i in 0 until list.length()) parseTelegramChat(list.getJSONObject(i), out)
            }
            obj.has("messages") -> parseTelegramChat(obj, out)
        }
    }

    // ── WhatsApp ──────────────────────────────────────────────────

    // "31/12/2023, 21:41 - Ana: hi"  (Android)   "[31/12/23, 9:41:05 PM] Ana: hi"  (iOS)
    private val waLine = Regex(
        "^\\u200E?\\[?(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2,4}),?\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?[\\s\\u202F]?([AaPp]\\.?[\\s\\u202F]?[Mm]\\.?)?\\]?\\s*(?:-\\s)?([^:]{1,60}):\\s(.*)$"
    )

    internal fun parseWhatsApp(thread: String, text: String, out: MutableList<ChatLine>) {
        val raw = text.lines()
        val matches = raw.mapNotNull { waLine.find(it) }
        if (matches.isEmpty()) return
        // Day-first unless the file proves otherwise (a "month" value above 12).
        val monthFirst = matches.any { it.groupValues[2].toInt() > 12 } && matches.none { it.groupValues[1].toInt() > 12 }
        var current: ChatLine? = null
        for (line in raw) {
            val m = waLine.find(line)
            if (m == null) {
                val c = current
                if (c != null && line.isNotBlank()) current = c.copy(text = c.text + " " + line.trim())
                continue
            }
            current?.let { if (out.size < MAX_MESSAGES) out += it }
            val g = m.groupValues
            val a = g[1].toInt(); val b = g[2].toInt()
            val (day, month) = if (monthFirst) b to a else a to b
            var year = g[3].toInt(); if (year < 100) year += 2000
            var hour = g[4].toInt()
            val ampm = g[7].lowercase().filter { it.isLetter() }
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            val cal = Calendar.getInstance().apply {
                set(year, month - 1, day, hour, g[5].toInt(), g[6].ifBlank { "0" }.toInt()); set(Calendar.MILLISECOND, 0)
            }
            val body = g[9].trim()
            current = if (body == "<Media omitted>" || body.isEmpty()) null
            else ChatLine("WhatsApp", thread, g[8].trim(), cal.timeInMillis, body)
        }
        current?.let { if (out.size < MAX_MESSAGES) out += it }
    }

    private fun threadFromFileName(name: String): String =
        name.substringBeforeLast('.')
            .replace(Regex("(?i)^whatsapp chat (with|-)\\s*"), "")
            .ifBlank { "WhatsApp chat" }

    // ── Instagram / Facebook (Meta JSON) ──────────────────────────

    private fun parseMeta(path: String, text: String, out: MutableList<ChatLine>) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val platform = if (path.lowercase().contains("instagram")) "Instagram" else "Facebook"
        val thread = fixMeta(obj.optString("title")).ifBlank {
            val p = obj.optJSONArray("participants")
            (0 until (p?.length() ?: 0)).joinToString { fixMeta(p!!.getJSONObject(it).optString("name")) }
        }.ifBlank { "Conversation" }
        val messages = obj.optJSONArray("messages") ?: return
        for (i in 0 until messages.length()) {
            if (out.size >= MAX_MESSAGES) return
            val m = messages.getJSONObject(i)
            val content = fixMeta(m.optString("content")).trim()
            if (content.isEmpty()) continue
            out += ChatLine(platform, thread, fixMeta(m.optString("sender_name")), m.optLong("timestamp_ms"), content)
        }
    }

    /** Meta exports encode UTF-8 bytes as Latin-1 escapes ("CafÃ©"); undo that. */
    private fun fixMeta(s: String): String =
        if (s.all { it.code < 256 }) runCatching { String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) }.getOrDefault(s) else s

    // ── Telegram Desktop ──────────────────────────────────────────

    private fun parseTelegramChat(chat: JSONObject, out: MutableList<ChatLine>) {
        val thread = chat.optString("name").ifBlank { "Telegram chat" }
        val messages = chat.optJSONArray("messages") ?: return
        for (i in 0 until messages.length()) {
            if (out.size >= MAX_MESSAGES) return
            val m = messages.getJSONObject(i)
            if (m.optString("type", "message") != "message") continue
            val text = telegramText(m.opt("text")).trim()
            if (text.isEmpty()) continue
            val time = m.optString("date_unixtime").toLongOrNull()?.times(1000)
                ?: runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(m.optString("date"))?.time }.getOrNull()
                ?: continue
            out += ChatLine("Telegram", thread, m.optString("from").ifBlank { "Unknown" }, time, text)
        }
    }

    private fun telegramText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).joinToString("") { i ->
            when (val part = value.get(i)) {
                is String -> part
                is JSONObject -> part.optString("text")
                else -> ""
            }
        }
        else -> ""
    }

    // ── X / Twitter archive ───────────────────────────────────────

    private fun parseTwitter(js: String, out: MutableList<ChatLine>) {
        val json = js.substringAfter('=', js).trim()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        for (i in 0 until arr.length()) {
            val conv = arr.getJSONObject(i).optJSONObject("dmConversation") ?: continue
            val thread = "Conversation " + conv.optString("conversationId").takeLast(6)
            val msgs = conv.optJSONArray("messages") ?: continue
            for (j in 0 until msgs.length()) {
                if (out.size >= MAX_MESSAGES) return
                val mc = msgs.getJSONObject(j).optJSONObject("messageCreate") ?: continue
                val text = mc.optString("text").trim()
                if (text.isEmpty()) continue
                val time = runCatching { iso.parse(mc.optString("createdAt"))?.time }.getOrNull() ?: continue
                out += ChatLine("X", thread, "user " + mc.optString("senderId").takeLast(4), time, text)
            }
        }
    }

    // ── Shared ────────────────────────────────────────────────────

    private fun toItems(lines: List<ChatLine>): List<IngestedItemEntity> {
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
        val cutoff = System.currentTimeMillis() - RECENT_MS
        val out = ArrayList<IngestedItemEntity>()
        lines.groupBy { Triple(it.platform, it.thread, dayFmt.format(java.util.Date(it.time))) }
            .forEach { (key, msgs) ->
                val (platform, thread, day) = key
                val sorted = msgs.sortedBy { it.time }
                val chunks = ArrayList<MutableList<ChatLine>>()
                var size = 0
                for (m in sorted) {
                    val len = m.text.length + m.sender.length + 8
                    if (chunks.isEmpty() || size + len > ITEM_CHARS) { chunks += mutableListOf<ChatLine>(); size = 0 }
                    chunks.last().add(m); size += len
                }
                chunks.forEachIndexed { index, chunk ->
                    val last = chunk.last().time
                    out += IngestedItemEntity(
                        source = PersonalSource.IMPORT.name,
                        sourceKey = "import:$platform:$thread:$day:$index:${chunk.first().time}",
                        app = platform,
                        sender = thread,
                        title = thread,
                        body = chunk.joinToString("\n") { "${timeFmt.format(java.util.Date(it.time))} ${it.sender}: ${it.text.take(500)}" }.take(ITEM_CHARS),
                        timestamp = last,
                        summarized = last < cutoff
                    )
                }
            }
        return out
    }

    private fun readCapped(input: InputStream): String = input.readNBytesCompat(MAX_ENTRY_BYTES).toString(Charsets.UTF_8)

    private fun InputStream.readNBytesCompat(max: Long): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            total += n
            if (total > max) throw IllegalArgumentException("File too large to import (over ${max / 1_048_576} MB)")
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }
}
