package ai.alal.app.share

import ai.alal.app.data.ModelConfig
import ai.alal.app.data.SecurityUtils
import ai.alal.app.data.availableModels
import ai.alal.app.data.validateCustomModel
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only view of this install's downloaded model weights, so a renamed
 * successor app (different package, same signing key) can import them instead
 * of downloading gigabytes again. Since Android 11 another app cannot read our
 * Android/data folder directly, so this is the only way across.
 *
 * Access is gated by the signature-level [PERMISSION] declared in the manifest:
 * only apps signed with the same key can query or open anything here.
 *
 *  - `content://<pkg>.modelshare/models` → one row per model folder holding a .gguf
 *  - `content://<pkg>.modelshare/models/<id>/<file>.gguf` → read-only file descriptor
 */
class ModelShareProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun modelRoot(): File? =
        context?.getExternalFilesDir(null)?.let { File(it, "model") }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (uri.pathSegments != listOf(PATH_MODELS)) return null
        val cursor = MatrixCursor(COLUMNS)
        val root = modelRoot() ?: return cursor
        val known = (availableModels + loadCustomModels()).associateBy { it.id }

        root.listFiles()?.filter { it.isDirectory && isSafeModelId(it.name) }?.forEach { dir ->
            val config = known[dir.name]
            // Prefer the file the config names; otherwise the folder's only .gguf.
            val gguf = config?.let { File(dir, it.ggufFile) }?.takeIf { it.isFile }
                ?: dir.listFiles()?.singleOrNull { it.isFile && isSafeGgufName(it.name) }
                ?: return@forEach
            cursor.addRow(
                arrayOf(
                    dir.name,
                    gguf.name,
                    gguf.length(),
                    config?.name ?: dir.name,
                    config?.baseUrl ?: "",
                    config?.estimatedRamBytes ?: 0L,
                    config?.contextSize ?: 4096,
                    if (config?.isCustom == true) 1 else 0
                )
            )
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Model files are read-only")
        val segments = uri.pathSegments
        if (segments.size != 3 || segments[0] != PATH_MODELS ||
            !isSafeModelId(segments[1]) || !isSafeGgufName(segments[2])
        ) throw FileNotFoundException("Bad model uri")

        val root = modelRoot()?.canonicalFile ?: throw FileNotFoundException("No storage")
        val file = File(root, "${segments[1]}/${segments[2]}").canonicalFile
        if (file.parentFile?.parentFile != root || !file.isFile) {
            throw FileNotFoundException("Model file not found")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun loadCustomModels(): List<ModelConfig> = try {
        val json = SecurityUtils.getEncryptedPrefs(context!!).getString("custom_models_json", "[]")
        com.google.gson.Gson().fromJson(json, Array<ModelConfig>::class.java)
            ?.filter { validateCustomModel(it) == null }
            .orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val PERMISSION = "ai.alal.permission.SHARE_MODELS"
        const val AUTHORITY_SUFFIX = ".modelshare"
        const val PATH_MODELS = "models"

        const val COL_ID = "id"
        const val COL_FILE = "gguf_file"
        const val COL_SIZE = "size_bytes"
        const val COL_NAME = "name"
        const val COL_BASE_URL = "base_url"
        const val COL_RAM = "ram_bytes"
        const val COL_CONTEXT = "context_size"
        const val COL_CUSTOM = "is_custom"
        val COLUMNS = arrayOf(COL_ID, COL_FILE, COL_SIZE, COL_NAME, COL_BASE_URL, COL_RAM, COL_CONTEXT, COL_CUSTOM)
    }
}

/** Same rule as custom model ids: these names become on-disk paths. */
internal fun isSafeModelId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9._-]{1,64}")) && id != "." && id != ".."

internal fun isSafeGgufName(name: String): Boolean =
    name.endsWith(".gguf", ignoreCase = true) && !name.contains('/') &&
        !name.contains('\\') && !name.contains("..") && name.length <= 200
