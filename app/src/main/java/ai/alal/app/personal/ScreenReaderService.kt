package ai.alal.app.personal

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import ai.alal.app.BuildConfig
import ai.alal.app.data.AppDatabase
import ai.alal.app.data.IngestedItemEntity
import ai.alal.app.data.PersonalSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Optional screen reader: captures the text visible in other apps (e.g. a chat
 * you scroll through) so it can be summarised. Password fields, keyboards, the
 * launcher, system UI and Alal itself are never read. Only runs while the user
 * has it switched on in Accessibility settings AND the source is on in Alal.
 */
class ScreenReaderService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings by lazy { PersonalSettings(applicationContext) }
    private val lastCapture = HashMap<String, Long>()
    private val lastHash = HashMap<String, Int>()
    private val labels by lazy { AppLabels(applicationContext) }
    private val ignored by lazy { ignoredPackages() }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            if (!settings.screenEnabled) return
            val pkg = event.packageName?.toString() ?: return
            if (pkg in ignored) return
            val now = System.currentTimeMillis()
            if (now - (lastCapture[pkg] ?: 0L) < DEBOUNCE_MS) return
            lastCapture[pkg] = now

            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() != pkg) return
            val text = buildString { collect(root, this, 0) }.trim()
            if (text.length < MIN_CHARS) return
            val hash = text.hashCode()
            if (lastHash[pkg] == hash) return
            lastHash[pkg] = hash

            val item = IngestedItemEntity(
                source = PersonalSource.SCREEN.name,
                sourceKey = "screen:$pkg:$hash",
                app = labels.label(pkg),
                body = text.take(MAX_CHARS),
                timestamp = now
            )
            scope.launch {
                try {
                    AppDatabase.getInstance(applicationContext).personalDao().insertItems(listOf(item))
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) android.util.Log.w("Personal", "Screen insert failed", e)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("Personal", "Screen event skipped", e)
        }
    }

    /** Depth-first visible text, skipping password fields and hidden nodes. */
    private fun collect(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        if (depth > 40 || out.length > MAX_CHARS) return
        if (node.isPassword || !node.isVisibleToUser) return
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        val piece = if (t.isNotEmpty()) t else if (node.childCount == 0) d else ""
        if (piece.isNotEmpty() && piece.length > 1) out.append(piece).append('\n')
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, depth + 1)
        }
    }

    private fun ignoredPackages(): Set<String> {
        val home = packageManager
            .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        val keyboards = (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .enabledInputMethodList.map { it.packageName }
        return setOfNotNull(packageName, "com.android.systemui", "com.android.settings", home) + keyboards + NEVER_READ
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /**
     * Opens each app's inbox, reads what's on screen, then returns to Alal.
     * Launching from an accessibility service is allowed even while Alal is in
     * the background, which is what makes the hop between apps possible.
     */
    suspend fun sweep(apps: List<DmApp>, onProgress: (String) -> Unit): Int {
        val dao = AppDatabase.getInstance(applicationContext).personalDao()
        var captured = 0
        for (app in apps) {
            onProgress("Reading ${app.label}…")
            if (!launch(app)) continue
            var text = ""
            // Apps take a moment to draw their inbox; retry a couple of times.
            for (attempt in 0 until 3) {
                delay(if (attempt == 0) 3_500 else 1_500)
                val root = rootInActiveWindow
                if (root != null && root.packageName?.toString() == app.pkg) {
                    text = buildString { collect(root, this, 0) }.trim()
                    if (text.length >= MIN_CHARS) break
                }
            }
            if (text.length < MIN_CHARS) continue
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                dao.insertItems(listOf(IngestedItemEntity(
                    source = PersonalSource.SCREEN.name,
                    sourceKey = "dm:${app.pkg}:${text.hashCode()}",
                    app = app.label,
                    title = "${app.label} inbox",
                    body = text.take(MAX_CHARS),
                    timestamp = now
                )))
            }
            captured++
        }
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        return captured
    }

    private fun launch(app: DmApp): Boolean {
        val deep = app.deepLink?.let {
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it)).setPackage(app.pkg)
                .takeIf { i -> i.resolveActivity(packageManager) != null }
        }
        val intent = deep ?: packageManager.getLaunchIntentForPackage(app.pkg) ?: return false
        return try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("Personal", "Couldn't open ${app.label}", e)
            false
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** The running service, if the user has switched it on in Accessibility settings. */
        @Volatile
        var instance: ScreenReaderService? = null
            private set

        /** Password managers and authenticators: never read, whatever the user enables. */
        private val NEVER_READ = setOf(
            "com.x8bit.bitwarden", "com.onepassword.android", "com.lastpass.lpandroid", "com.dashlane",
            "com.kunzisoft.keepass.free", "com.kunzisoft.keepass.libre", "keepass2android.keepass2android",
            "proton.android.pass", "com.google.android.apps.authenticator2", "com.authy.authy",
            "com.azure.authenticator", "com.beemdevelopment.aegis", "org.fedorahosted.freeotp",
            "com.google.android.gms"
        )

        private const val DEBOUNCE_MS = 4_000L
        private const val MIN_CHARS = 40
        private const val MAX_CHARS = 3_000
    }
}
