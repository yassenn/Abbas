package ai.alal.app.personal

import android.content.Context

/** An app the "Check DMs now" sweep can open. [deepLink] jumps straight to its inbox when it works. */
data class DmApp(val label: String, val pkg: String, val deepLink: String? = null)

object DmApps {
    val CATALOG = listOf(
        DmApp("Instagram", "com.instagram.android", "instagram://direct-inbox"),
        DmApp("WhatsApp", "com.whatsapp"),
        DmApp("WhatsApp Business", "com.whatsapp.w4b"),
        DmApp("Telegram", "org.telegram.messenger"),
        DmApp("Messenger", "com.facebook.orca"),
        DmApp("Signal", "org.thoughtcrime.securesms"),
        DmApp("X", "com.twitter.android", "twitter://messages"),
        DmApp("LinkedIn", "com.linkedin.android", "linkedin://messaging"),
        DmApp("Snapchat", "com.snapchat.android"),
        DmApp("TikTok", "com.zhiliaoapp.musically"),
        DmApp("Discord", "com.discord"),
        DmApp("Gmail", "com.google.android.gm"),
        DmApp("Outlook", "com.microsoft.office.outlook"),
        DmApp("Tuta", "de.tutao.tutanota")
    )

    fun installed(context: Context): List<DmApp> =
        CATALOG.filter { context.packageManager.getLaunchIntentForPackage(it.pkg) != null }
}
