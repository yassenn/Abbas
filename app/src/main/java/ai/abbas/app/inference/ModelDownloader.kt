package ai.abbas.app.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class DownloadProgress(val progress: Float, val status: String)

class ModelDownloader(private val token: String? = null) {

    private companion object {
        const val BUFFER_BYTES = 256 * 1024
        const val PROGRESS_STEP_BYTES = 2L * 1024 * 1024
    }

    private val client = OkHttpClient()
    private var currentCall: Call? = null

    /**
     * Download a GGUF model file from HuggingFace, reporting progress as it streams.
     *
     * @param targetDir Directory to save the model file
     * @param baseUrl HF resolve/main/ URL (with trailing slash)
     * @param ggufFile The GGUF filename to download
     */
    fun downloadModel(targetDir: File, baseUrl: String, ggufFile: String): Flow<DownloadProgress> = flow {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        try {
            emit(DownloadProgress(0f, "Starting download…"))
            downloadFile(ggufFile, targetDir, baseUrl) { p, status ->
                emit(DownloadProgress(p, status))
            }
            emit(DownloadProgress(1f, "Download complete"))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            throw IOException("Failed to download model: ${e.message}", e)
        } finally {
            currentCall?.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Streams the response to disk in chunks, emitting progress at most every
     * [PROGRESS_STEP_BYTES] and once more at the end. Runs on the flow's own
     * dispatcher (via `flowOn`) so `onProgress` -> `emit` keeps the flow invariant.
     */
    private suspend fun downloadFile(
        filename: String,
        targetDir: File,
        baseUrl: String,
        onProgress: suspend (Float, String) -> Unit
    ) {
        val requestBuilder = Request.Builder().url(baseUrl + filename)
        token?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.addHeader("Authorization", "Bearer $it")
        }

        val call = client.newCall(requestBuilder.build())
        currentCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(httpErrorMessage(response.code, response.message))
                }
                val body = response.body ?: throw IOException("Empty response body")
                val total = body.contentLength()

                val localFile = File(targetDir, filename)
                if (localFile.exists()) localFile.delete()

                var read = 0L
                var lastMark = 0L
                val buffer = ByteArray(BUFFER_BYTES)
                body.byteStream().use { input ->
                    FileOutputStream(localFile).use { output ->
                        while (currentCoroutineContext().isActive) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            output.write(buffer, 0, n)
                            read += n
                            if (read - lastMark >= PROGRESS_STEP_BYTES) {
                                lastMark = read
                                onProgress(progressOf(read, total), sizeLabel(read, total))
                            }
                        }
                    }
                }
                // Final tick so the bar lands exactly where the file ended.
                onProgress(progressOf(read, total), sizeLabel(read, total))
            }
        } finally {
            currentCall = null
        }
    }

    private fun httpErrorMessage(code: Int, message: String): String = when (code) {
        401, 403 -> "Access Denied: This model requires authentication or is private. Please check if you need to accept a license on Hugging Face."
        404 -> "Model File Not Found: The download link for this model is broken or the repository has moved."
        500, 502, 503, 504 -> "Server Error: Hugging Face servers are currently having issues. Please try again later."
        else -> "Download Failed (HTTP $code): $message"
    }

    private fun progressOf(read: Long, total: Long): Float =
        if (total > 0) (read.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f

    private fun sizeLabel(read: Long, total: Long): String {
        val mb = read / 1_048_576f
        return if (total > 0) "%.0f / %.0f MB".format(mb, total / 1_048_576f) else "%.0f MB".format(mb)
    }
}
