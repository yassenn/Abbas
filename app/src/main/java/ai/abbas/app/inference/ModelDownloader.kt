package ai.abbas.app.inference

import com.google.gson.Gson
import com.google.gson.JsonObject
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

    fun downloadModel(targetDir: File, baseUrl: String): Flow<DownloadProgress> = flow {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        try {
            yield()
            emit(DownloadProgress(0f, "Downloading mlc-chat-config.json..."))
            downloadFile("mlc-chat-config.json", targetDir, baseUrl)

            yield()
            emit(DownloadProgress(0.01f, "Downloading tokenizer files..."))
            try { downloadFile("tokenizer.json", targetDir, baseUrl) } catch(e: Exception) {}
            try { downloadFile("tokenizer.model", targetDir, baseUrl) } catch(e: Exception) {}
            try { downloadFile("tokenizer_config.json", targetDir, baseUrl) } catch(e: Exception) {}

            yield()
            emit(DownloadProgress(0.02f, "Downloading parameter manifest..."))
            var cacheFile = File(targetDir, "ndarray-cache.json")
            try {
                downloadFile("ndarray-cache.json", targetDir, baseUrl)
            } catch (e: Exception) {
                // Try alternative manifest name used by some models
                try {
                    downloadFile("tensor-cache.json", targetDir, baseUrl)
                    cacheFile = File(targetDir, "tensor-cache.json")
                } catch (e2: Exception) {
                    throw IOException("Could not find parameter manifest (tried ndarray-cache.json and tensor-cache.json)")
                }
            }

            // Parse manifest to find shards
            val cacheContent = cacheFile.readText()
            val jsonObject = Gson().fromJson(cacheContent, JsonObject::class.java)
            val records = jsonObject.getAsJsonArray("records")
            
            val shards = mutableListOf<String>()
            for (i in 0 until records.size()) {
                val record = records.get(i).asJsonObject
                shards.add(record.get("dataPath").asString)
            }

            val totalShards = shards.size
            var downloadedShards = 0

            for (shard in shards) {
                yield()
                if (!currentCoroutineContext().isActive) break
                
                val progress = 0.02f + (0.98f * (downloadedShards.toFloat() / totalShards))
                emit(DownloadProgress(progress, "Downloading $shard ($downloadedShards/$totalShards)..."))
                
                downloadFile(shard, targetDir, baseUrl, skipIfSizeMatches = true)
                downloadedShards++
            }
            
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

    private suspend fun downloadFile(filename: String, targetDir: File, baseUrl: String, skipIfSizeMatches: Boolean = false) = withContext(Dispatchers.IO) {
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

                val remoteSize = response.body?.contentLength() ?: -1L
                val localFile = File(targetDir, filename)
                
                if (skipIfSizeMatches && localFile.exists() && remoteSize != -1L && localFile.length() == remoteSize) {
                    return@withContext
                }

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