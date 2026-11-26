package ai.alal.app.personal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.room.withTransaction
import ai.alal.app.data.AppDatabase
import ai.alal.app.data.DigestEntity
import ai.alal.app.data.GenerationSettings
import ai.alal.app.data.IngestedItemEntity
import ai.alal.app.data.SummaryLinkEntity
import ai.alal.app.inference.LlamaEngineManager
import kotlinx.coroutines.CancellationException

/** Room-backed [SummaryStore]; the database is the SQLCipher-encrypted one. */
class RoomSummaryStore(private val db: AppDatabase) : SummaryStore {
    private val dao get() = db.personalDao()

    override suspend fun create(entity: DigestEntity, parentIds: List<Long>): Long = db.withTransaction {
        val id = dao.insertDigest(entity)
        dao.insertLinks(parentIds.map { SummaryLinkEntity(childId = id, parentId = it) })
        id
    }

    override suspend fun get(id: Long) = dao.digest(id)
    override suspend fun parents(id: Long) = dao.parentsOf(id)
    override suspend fun updateText(id: Long, text: String, now: Long) = dao.updateDigestText(id, text, now)
    override suspend fun rename(id: Long, title: String?, now: Long) = dao.renameDigest(id, title, now)
    override suspend fun setPinned(id: Long, pinned: Boolean) = dao.setDigestPinned(id, pinned)
    override suspend fun delete(id: Long) = dao.deleteDigest(id)
    override suspend fun itemsForPeriod(start: Long, end: Long): List<IngestedItemEntity> =
        dao.itemsBetween(start, end, 300)
}

/**
 * Runs prompts on the local llama engine only (same path as digests): no web search,
 * no network. Cancelling the calling coroutine stops generation.
 */
class LocalSummaryGenerator(
    private val engine: LlamaEngineManager,
    private val settings: GenerationSettings
) : SummaryGenerator {

    // ~3 chars per token; keep half the window for scaffolding + answer (as digests do).
    override val charBudget: Int =
        ((engine.currentModel.value?.contextSize ?: 4096) * 3 / 2).coerceIn(1_500, 12_000)

    override suspend fun generate(system: String, prompt: String): String {
        val raw = StringBuilder()
        try {
            engine.generateResponse(prompt = prompt, messageHistory = emptyList(), settings = settings, systemPrompt = system)
                .collect { raw.append(it) }
        } catch (e: CancellationException) {
            engine.stopGeneration()
            throw e
        }
        return raw.toString().replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }
}

/** Clipboard copy that hides the text from previews and auto-clears it. */
object SensitiveClipboard {
    const val CLEAR_AFTER_MS = 60_000L
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Alal", text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription_EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        pending?.let(handler::removeCallbacks)
        val appContext = context.applicationContext
        val job = Runnable { clearIfStillOurs(appContext, text) }
        pending = job
        handler.postDelayed(job, CLEAR_AFTER_MS)
    }

    /** Clears only if the clipboard still holds exactly what we put there. */
    private fun clearIfStillOurs(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val current = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        } catch (_: SecurityException) {
            null // another app owns the clipboard now
        }
        if (current == text) {
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        pending = null
    }

    // ClipDescription.EXTRA_IS_SENSITIVE (API 33) as a literal, so older compileSdk still builds.
    private const val ClipDescription_EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
}
