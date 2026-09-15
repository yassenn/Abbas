package ai.abbas.app.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class DownloadProgress(val progress: Float, val status: String)

class ModelDownloader(private val token: String? = null) {
    private val client = OkHttpClient()
    private var currentCall: Call? = null

    /**
     * Download a GGUF model file from HuggingFace.
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
            yield()
            emit(DownloadProgress(0f, "Downloading $ggufFile..."))
            downloadFile(ggufFile, targetDir, baseUrl)
            emit(DownloadProgress(1f, "Download complete!"))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            throw IOException("Failed to download model: ${e.message}", e)
        } finally {
            currentCall?.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadFile(
        filename: String,
        targetDir: File,
        baseUrl: String
    ) = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(baseUrl + filename)

        token?.let {
            if (it.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $it")
            }
        }

        val request = requestBuilder.build()
        val call = client.newCall(request)
        currentCall = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val userFriendlyMessage = when (response.code) {
                        401, 403 -> "Access Denied: This model requires authentication or is private. Please check if you need to accept a license on Hugging Face."
                        404 -> "Model File Not Found: The download link for this model is broken or the repository has moved."
                        500, 502, 503, 504 -> "Server Error: Hugging Face servers are currently having issues. Please try again later."
                        else -> "Download Failed (HTTP ${response.code}): ${response.message}"
                    }
                    throw IOException(userFriendlyMessage)
                }

                val localFile = File(targetDir, filename)
                if (localFile.exists()) localFile.delete()

                val sink = FileOutputStream(localFile)
                response.body?.byteStream()?.use { input ->
                    sink.use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } finally {
            currentCall = null
        }
    }
}