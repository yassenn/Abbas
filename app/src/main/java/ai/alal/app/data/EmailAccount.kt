package ai.alal.app.data

/**
 * A mailbox Alal reads over IMAP. Kept in encrypted preferences (Gson), never in
 * the database. [secret] is an app password (PASSWORD) or a Microsoft refresh
 * token (OAUTH_MICROSOFT).
 */
data class EmailAccount(
    val id: String,
    val label: String,
    val email: String,
    val host: String,
    val port: Int = 993,
    val auth: String = AUTH_PASSWORD,
    val secret: String,
    /** Microsoft app (client) ID for OAuth accounts. */
    val clientId: String? = null,
    /** Highest INBOX UID already collected; 0 = never synced. */
    val lastUid: Long = 0,
    val uidValidity: Long = 0,
    val lastError: String? = null
) {
    companion object {
        const val AUTH_PASSWORD = "PASSWORD"
        const val AUTH_OAUTH_MICROSOFT = "OAUTH_MICROSOFT"
        const val GMAIL_HOST = "imap.gmail.com"
        const val OUTLOOK_HOST = "outlook.office365.com"
    }
}
