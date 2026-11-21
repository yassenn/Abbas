package ai.alal.app.inference

import android.content.Context
import android.util.Base64
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.alal.app.BuildConfig
import ai.alal.app.data.ModelConfig
import ai.alal.app.data.SecurityUtils
import ai.alal.app.data.builtInModels
import ai.alal.app.data.retiredBuiltIn
import ai.alal.app.data.validateCustomModel
import ai.alal.app.net.NetworkPrivacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit

/**
 * Over-the-air AI Hub catalogue.
 *
 * The list is published with `tools/catalog/catalog.sh publish` and signed with a
 * private key that never leaves the developer's machine. The app accepts an update
 * only if: the ECDSA P-256 signature verifies against [PUBLIC_KEY], its version is
 * newer than both the built-in list and the last accepted one (no rollbacks), and
 * every entry is a checksummed HTTPS Hugging Face file. Anything else is ignored
 * and the previous list stays in use.
 */
object ModelCatalog {

    /** Version of the list compiled into this APK (YYYYMMDDHH). */
    const val BUILT_IN_VERSION = 2026112100

    private const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEgh5PLApnLzfTTZIyaUlS5UlWGuFDsDV5LOSgjxUmKK/l6/CV4yCB4JCzVm3LEkjpdMi0pPxOCGIE8vmh6AulEQ=="

    /** Repository the signed catalogue is published to (must be publicly readable). */
    private const val URL = "https://raw.githubusercontent.com/yassenn/Abbas/main/catalog/models.json"

    private const val KEY_JSON = "catalog_json"
    private const val KEY_SIG = "catalog_sig"
    private const val KEY_VERSION = "catalog_version"
    private const val KEY_AUTO = "catalog_auto_update"
    private const val KEY_CHECKED = "catalog_checked_at"
    private const val MAX_BYTES = 512 * 1024

    private val TIERS = setOf("HIGH", "MID", "LOW")
    private val STRENGTHS = setOf("CHAT", "REASONING", "CODING", "SUMMARIES", "DOCUMENTS", "LANGUAGES", "FAST")
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    @Volatile var models: List<ModelConfig> = builtInModels
        private set
    @Volatile var version: Int = BUILT_IN_VERSION
        private set

    /** Built-in retired models plus anything an update removed, so downloads stay usable. */
    val retired: List<ModelConfig>
        get() {
            val current = models.map { it.id }.toSet()
            return (retiredBuiltIn + builtInModels.filter { it.id !in current }.map {
                it.copy(tier = "RETIRED", note = "No longer recommended (a better model replaced it). Kept because it is on your phone.")
            }).filter { it.id !in current }.distinctBy { it.id }
        }

    fun init(context: Context) {
        val prefs = SecurityUtils.getEncryptedPrefs(context)
        val json = prefs.getString(KEY_JSON, null) ?: return
        val sig = prefs.getString(KEY_SIG, null) ?: return
        // Re-verified on every start: the cache is trusted no more than the network.
        parseVerified(json.toByteArray(Charsets.UTF_8), sig)?.let { (v, list) ->
            if (v > BUILT_IN_VERSION) { models = list; version = v }
        }
    }

    fun isAutoUpdate(context: Context) = SecurityUtils.getEncryptedPrefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoUpdate(context: Context, on: Boolean) {
        SecurityUtils.getEncryptedPrefs(context).edit().putBoolean(KEY_AUTO, on).apply()
        CatalogWorker.schedule(context, on)
    }

    fun lastChecked(context: Context) = SecurityUtils.getEncryptedPrefs(context).getLong(KEY_CHECKED, 0L)

    /** Fetches, verifies and applies a newer catalogue. Returns a short status line. */
    suspend fun refresh(context: Context): String = withContext(Dispatchers.IO) {
        val prefs = SecurityUtils.getEncryptedPrefs(context)
        try {
            val client = NetworkPrivacy.client { callTimeout(30, TimeUnit.SECONDS) }
            fun get(url: String): ByteArray = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                val body = r.body ?: throw IllegalStateException("empty response")
                if ((body.contentLength()) > MAX_BYTES) throw IllegalStateException("catalogue too large")
                body.bytes().also { if (it.size > MAX_BYTES) throw IllegalStateException("catalogue too large") }
            }
            val bytes = get(URL)
            val sig = get("$URL.sig").toString(Charsets.UTF_8).trim()
            prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
            val (v, list) = parseVerified(bytes, sig) ?: return@withContext "Update rejected: signature or contents invalid"
            if (v <= version) return@withContext "Model list is up to date"
            prefs.edit()
                .putString(KEY_JSON, bytes.toString(Charsets.UTF_8))
                .putString(KEY_SIG, sig)
                .putInt(KEY_VERSION, v)
                .apply()
            models = list; version = v
            "Model list updated (${list.size} models)"
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("Catalog", "Refresh failed", e)
            "Couldn't check for model updates: ${e.message ?: "network error"}"
        }
    }

    /** Signature + content checks. Null when anything is off. */
    internal fun parseVerified(bytes: ByteArray, sigBase64: String): Pair<Int, List<ModelConfig>>? {
        if (!verify(bytes, sigBase64)) return null
        return runCatching {
            val doc = JSONObject(bytes.toString(Charsets.UTF_8))
            val v = doc.getInt("version")
            val arr = doc.getJSONArray("models")
            val list = (0 until arr.length()).mapNotNull { i -> toModel(arr.getJSONObject(i)) }
            if (list.isEmpty() || list.size != arr.length()) null else v to list
        }.getOrNull()
    }

    private fun toModel(o: JSONObject): ModelConfig? {
        val strengths = o.optJSONArray("strengths")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val m = ModelConfig(
            id = o.getString("id"),
            name = o.getString("name").take(60),
            ggufFile = o.getString("ggufFile"),
            baseUrl = o.getString("baseUrl"),
            estimatedRamBytes = o.getLong("estimatedRamBytes"),
            contextSize = o.optInt("contextSize", 4096),
            tier = o.optString("tier").takeIf { it in TIERS },
            note = o.optString("note").take(160).ifBlank { null },
            strengths = strengths.filter { it in STRENGTHS },
            sha256 = o.optString("sha256").lowercase()
        )
        val ok = validateCustomModel(m) == null &&
            m.baseUrl.startsWith("https://huggingface.co/") &&
            m.sha256 != null && SHA256.matches(m.sha256) &&
            m.estimatedRamBytes in 100_000_000L..64_000_000_000L &&
            m.contextSize in 1024..32768
        return m.takeIf { ok }
    }

    private fun verify(bytes: ByteArray, sigBase64: String): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(bytes)
            verify(Base64.decode(sigBase64, Base64.DEFAULT))
        }
    }.getOrDefault(false)
}

/** Weekly background check (honours Network privacy / Tor). */
class CatalogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (ModelCatalog.isAutoUpdate(applicationContext)) ModelCatalog.refresh(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "alal_model_catalog"
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<CatalogWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
