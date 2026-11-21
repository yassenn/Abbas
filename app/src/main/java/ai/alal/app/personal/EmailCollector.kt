package ai.alal.app.personal

import android.content.Context
import ai.alal.app.BuildConfig
import ai.alal.app.data.EmailAccount
import ai.alal.app.data.IngestedItemEntity
import ai.alal.app.data.PersonalSource
import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Properties
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm

/**
 * Reads new INBOX mail over IMAP (read-only: nothing is marked read, moved or
 * deleted). Alal connects only to each account's own mail server.
 */
object EmailCollector {

    private const val MAX_PER_ACCOUNT = 40
    private const val MAX_BODY = 1_500
    private const val FIRST_SYNC_MS = 3L * 24 * 60 * 60 * 1000

    suspend fun collect(context: Context, settings: PersonalSettings): List<IngestedItemEntity> {
        if (!settings.emailEnabled) return emptyList()
        val store = EmailAccounts(context)
        val out = mutableListOf<IngestedItemEntity>()
        for (account in store.all()) {
            try {
                val (items, updated) = sync(account)
                out += items
                store.upsert(updated)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.w("Personal", "Email sync failed for ${account.label}", e)
                store.upsert(account.copy(lastError = e.message ?: e.javaClass.simpleName))
            }
        }
        return out
    }

    /** Connects once to check the credentials. Returns null on success, else the error. */
    suspend fun test(account: EmailAccount): String? = withContext(Dispatchers.IO) {
        try {
            val (password, _) = credentials(account)
            open(account, password).close()
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    private suspend fun credentials(account: EmailAccount): Pair<String, EmailAccount> =
        if (account.auth == EmailAccount.AUTH_OAUTH_MICROSOFT) {
            val tokens = OutlookAuth.refresh(account.clientId ?: error("Missing Microsoft client ID"), account.secret)
            tokens.accessToken to account.copy(secret = tokens.refreshToken)
        } else {
            account.secret to account
        }

    private fun open(account: EmailAccount, password: String): javax.mail.Store {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", account.host)
            put("mail.imaps.port", account.port.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.checkserveridentity", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "30000")
            if (account.auth == EmailAccount.AUTH_OAUTH_MICROSOFT) {
                put("mail.imaps.auth.mechanisms", "XOAUTH2")
            }
        }
        val store = Session.getInstance(props).getStore("imaps")
        store.connect(account.host, account.port, account.email, password)
        return store
    }

    private suspend fun sync(original: EmailAccount): Pair<List<IngestedItemEntity>, EmailAccount> =
        withContext(Dispatchers.IO) {
            val (password, account) = credentials(original)
            val store = open(account, password)
            try {
                val inbox = store.getFolder("INBOX") as IMAPFolder
                inbox.open(Folder.READ_ONLY)
                try {
                    val validity = inbox.uidValidity
                    val resume = validity == account.uidValidity && account.lastUid > 0
                    val found: List<Message> = if (resume) {
                        // "n:*" always returns at least the newest message; filter below.
                        inbox.getMessagesByUID(account.lastUid + 1, UIDFolder.LASTUID).filterNotNull()
                    } else {
                        inbox.search(ReceivedDateTerm(ComparisonTerm.GE, Date(System.currentTimeMillis() - FIRST_SYNC_MS))).toList()
                    }
                    val messages = found.takeLast(MAX_PER_ACCOUNT)

                    var maxUid = account.lastUid
                    val items = messages.mapNotNull { msg ->
                        val uid = inbox.getUID(msg)
                        if (resume && uid <= account.lastUid) return@mapNotNull null
                        maxUid = maxOf(maxUid, uid)
                        toItem(account, validity, uid, msg)
                    }
                    if (!resume && messages.isEmpty()) {
                        maxUid = maxOf(maxUid, (inbox.uidNext - 1).coerceAtLeast(0))
                    }
                    items to account.copy(lastUid = maxUid, uidValidity = validity, lastError = null)
                } finally {
                    inbox.close(false)
                }
            } finally {
                store.close()
            }
        }

    private fun toItem(account: EmailAccount, validity: Long, uid: Long, msg: Message): IngestedItemEntity? {
        val from = (msg.from?.firstOrNull() as? InternetAddress)?.let { it.personal ?: it.address } ?: "Unknown sender"
        val subject = msg.subject?.trim().orEmpty().ifBlank { "(no subject)" }
        val raw = try { textOf(msg, 0) } catch (_: Exception) { "" }
        val body = raw.lines().filterNot { it.trimStart().startsWith(">") }.joinToString(" ")
            .replace(Regex("\\s+"), " ").trim()
        return IngestedItemEntity(
            source = PersonalSource.EMAIL.name,
            sourceKey = "email:${account.id}:$validity:$uid",
            app = account.label,
            sender = from,
            title = subject,
            body = "Subject: $subject. ${body.take(MAX_BODY)}".trim(),
            timestamp = (msg.receivedDate ?: msg.sentDate ?: Date()).time
        )
    }

    /** Plain text of a message, preferring text/plain over HTML. */
    private fun textOf(part: Part, depth: Int): String {
        if (depth > 5) return ""
        return when {
            part.isMimeType("text/plain") -> part.content as? String ?: ""
            part.isMimeType("text/html") -> htmlToText(part.content as? String ?: "")
            part.isMimeType("multipart/*") -> {
                val mp = part.content as Multipart
                val parts = (0 until mp.count).map { mp.getBodyPart(it) }
                parts.firstOrNull { it.isMimeType("text/plain") }?.let { textOf(it, depth + 1) }
                    ?: parts.asSequence().map { textOf(it, depth + 1) }.firstOrNull { it.isNotBlank() }
                    ?: ""
            }
            else -> ""
        }
    }

    private fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
}
