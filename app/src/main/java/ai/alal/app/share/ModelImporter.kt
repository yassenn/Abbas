package ai.alal.app.share

import ai.alal.app.data.ModelConfig
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** A model found in a previous install of the app, ready to be copied over. */
data class LegacyModel(
    val sourcePackage: String,
    val model: ModelConfig,
    val ggufFile: String,
    val sizeBytes: Long
)

data class LegacyImportState(
    /** Models a previous install has that this install doesn't. */
    val models: List<LegacyModel> = emptyList(),
    /** A previous install exists but is too old to share (needs one update first). */
    val outdatedPackages: List<String> = emptyList()
) {
    val totalBytes: Long get() = models.sumOf { it.sizeBytes }
}

/**
 * Brings model weights into this install from earlier versions of the app that
 * shipped under another package name (see [ModelShareProvider]), or from any
 * .gguf file the user picks.
 */
class ModelImporter(private val context: Context) {

    private val modelRoot: File get() = File(context.getExternalFilesDir(null), "model")

    /** Scan previous installs for weights that aren't already in this one. */
    fun scan(alreadyHere: Set<String>): LegacyImportState {
        val models = mutableListOf<LegacyModel>()
        val outdated = mutableListOf<String>()
        for (pkg in LEGACY_PACKAGES) {
            if (pkg == context.packageName || !isInstalled(pkg)) continue
            val uri = Uri.parse("content://$pkg${ModelShareProvider.AUTHORITY_SUFFIX}/${ModelShareProvider.PATH_MODELS}")
            val cursor = try {
                context.contentResolver.query(uri, null, null, null, null)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (cursor == null) {
                outdated += pkg
                continue
            }
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(c.getColumnIndexOrThrow(ModelShareProvider.COL_ID))
                    val file = c.getString(c.getColumnIndexOrThrow(ModelShareProvider.COL_FILE))
                    if (!isSafeModelId(id) || !isSafeGgufName(file) || id in alreadyHere) continue
                    if (models.any { it.model.id == id }) continue
                    val size = c.getLong(c.getColumnIndexOrThrow(ModelShareProvider.COL_SIZE))
                    models += LegacyModel(
                        sourcePackage = pkg,
                        model = ModelConfig(
                            id = id,
                            name = c.getString(c.getColumnIndexOrThrow(ModelShareProvider.COL_NAME)),
                            ggufFile = file,
                            baseUrl = c.getString(c.getColumnIndexOrThrow(ModelShareProvider.COL_BASE_URL)),
                            estimatedRamBytes = c.getLong(c.getColumnIndexOrThrow(ModelShareProvider.COL_RAM))
                                .takeIf { it > 0 } ?: estimateRam(size),
                            contextSize = c.getInt(c.getColumnIndexOrThrow(ModelShareProvider.COL_CONTEXT)),
                            isCustom = c.getInt(c.getColumnIndexOrThrow(ModelShareProvider.COL_CUSTOM)) == 1
                        ),
                        ggufFile = file,
                        sizeBytes = size
                    )
                }
            }
        }
        return LegacyImportState(models, outdated)
    }

    /** Copy one model's weights out of a previous install. */
    suspend fun importLegacy(legacy: LegacyModel, onProgress: suspend (Float) -> Unit) {
        val uri = Uri.parse(
            "content://${legacy.sourcePackage}${ModelShareProvider.AUTHORITY_SUFFIX}/" +
                "${ModelShareProvider.PATH_MODELS}/${legacy.model.id}/${legacy.ggufFile}"
        )
        copyInto(legacy.model.id, legacy.ggufFile, legacy.sizeBytes, onProgress) {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?.let { ParcelInput(it) } ?: throw IOException("Previous app refused to share ${legacy.model.name}")
        }
    }

    /** Describe a user-picked .gguf as a custom model (not yet copied). */
    fun describePickedFile(uri: Uri): Pair<ModelConfig, Long> {
        var name: String? = null
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        val fileName = sanitizeGgufName(name ?: "imported.gguf")
            ?: throw IOException("Pick a .gguf model file")
        val id = fileName.removeSuffix(".gguf")
        return ModelConfig(
            id = id,
            name = id,
            ggufFile = fileName,
            baseUrl = "",
            estimatedRamBytes = estimateRam(size),
            isCustom = true,
            isImported = true
        ) to size
    }

    suspend fun importPickedFile(uri: Uri, model: ModelConfig, sizeBytes: Long, onProgress: suspend (Float) -> Unit) {
        copyInto(model.id, model.ggufFile, sizeBytes, onProgress) {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?.let { ParcelInput(it) } ?: throw IOException("Couldn't open the picked file")
        }
    }

    /** Stream into model/<id>/<file> via a .part file so a failed copy never looks downloaded. */
    private suspend fun copyInto(
        id: String,
        fileName: String,
        expectedBytes: Long,
        onProgress: suspend (Float) -> Unit,
        open: () -> ParcelInput
    ) = withContext(Dispatchers.IO) {
        val outDir = File(modelRoot, id).apply { mkdirs() }
        val target = File(outDir, fileName)
        val part = File(outDir, "$fileName.part")
        if (expectedBytes > 0) {
            val free = StatFs(outDir.path).availableBytes
            if (free < expectedBytes + SPACE_MARGIN) {
                throw IOException(
                    "Not enough storage: need ${expectedBytes / MB} MB, ${free / MB} MB free"
                )
            }
        }
        try {
            open().use { input ->
                checkGgufMagic(input.stream)
                FileOutputStream(part).use { out ->
                    out.write(GGUF_MAGIC)
                    val buf = ByteArray(1 shl 20)
                    var copied = GGUF_MAGIC.size.toLong()
                    var lastReported = -1
                    while (true) {
                        ensureActive()
                        val n = input.stream.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (expectedBytes > 0) {
                            val pct = (copied * 100 / expectedBytes).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress((copied.toFloat() / expectedBytes).coerceAtMost(1f))
                            }
                        }
                    }
                    out.fd.sync()
                }
            }
            if (!part.renameTo(target)) throw IOException("Couldn't finalize $fileName")
        } catch (t: Throwable) {
            part.delete()
            if (outDir.listFiles().isNullOrEmpty()) outDir.delete()
            throw t
        }
    }

    private fun checkGgufMagic(stream: InputStream) {
        val head = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = stream.read(head, read, 4 - read)
            if (n < 0) break
            read += n
        }
        if (read < 4 || !head.contentEquals(GGUF_MAGIC)) {
            throw IOException("Not a GGUF model file")
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private class ParcelInput(private val pfd: android.os.ParcelFileDescriptor) : AutoCloseable {
        val stream = FileInputStream(pfd.fileDescriptor)
        override fun close() {
            stream.close()
            pfd.close()
        }
    }

    companion object {
        /** Package names earlier releases shipped under. Must match <queries> in the manifest. */
        val LEGACY_PACKAGES = listOf("ai.abbas.app")

        private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"
        private const val MB = 1_048_576L
        private const val SPACE_MARGIN = 200 * MB
    }
}

/** Rough RAM need for a quantised model: the weights plus ~25% for KV cache and buffers. */
internal fun estimateRam(fileBytes: Long): Long =
    if (fileBytes > 0) fileBytes + fileBytes / 4 else 2_000_000_000L

/** Turn a picked file's display name into a safe on-disk .gguf name, or null if it isn't one. */
internal fun sanitizeGgufName(displayName: String): String? {
    val base = displayName.substringAfterLast('/').substringAfterLast('\\')
    if (!base.endsWith(".gguf", ignoreCase = true)) return null
    val stem = base.dropLast(5).replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').take(59)
    if (stem.isEmpty() || stem.contains("..")) return null
    return "$stem.gguf"
}
