@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.abbas.app.inference

import android.content.Context
import ai.mlc.mlcllm.MLCEngine
import ai.mlc.mlcllm.OpenAIProtocol
import ai.abbas.app.data.ModelConfig
import ai.abbas.app.data.GenerationSettings
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.Result

class CorruptedModelException(message: String) : Exception(message)

class MlcEngineManager(private val context: Context) {

    init {
        // Cap TVM's CPU worker pool BEFORE the native lib loads (it reads
        // TVM_NUM_THREADS when creating its thread pool). Leaving it unset lets
        // TVM spawn one worker per core, saturating the CPU and freezing the UI —
        // especially on mid/low-end phones. Half the cores keeps the big cluster
        // busy while leaving headroom for the UI/render threads.
        try {
            val cap = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
            android.system.Os.setenv("TVM_NUM_THREADS", cap.toString(), true)
        } catch (e: Exception) {
            android.util.Log.w("MLC", "Failed to set TVM_NUM_THREADS", e)
        }
        System.loadLibrary("tvm4j_runtime_packed")
    }

    private var engine: MLCEngine? = null
    private val _currentModel = MutableStateFlow<ModelConfig?>(null)
    val currentModel: StateFlow<ModelConfig?> = _currentModel.asStateFlow()

    private suspend fun downloadModelDirectly(model: ModelConfig, token: String?, onProgress: suspend (Float, String) -> Unit): kotlin.Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val outDir = File(context.getExternalFilesDir(null), "model/${model.id}")
            if (!outDir.exists()) outDir.mkdirs()

            val downloader = ModelDownloader(token)
            downloader.downloadModel(outDir, model.baseUrl).collect { progress ->
                onProgress(progress.progress, progress.status)
            }
            kotlin.Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            kotlin.Result.failure(e)
        }
    }

    fun isDeviceCapable(model: ModelConfig): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalRamBytes = memoryInfo.totalMem
        
        return totalRamBytes >= model.estimatedVramBytes
    }

    fun isModelDownloaded(model: ModelConfig): Boolean {
        val outDir = File(context.getExternalFilesDir(null), "model/${model.id}")
        val isConfigPresent = File(outDir, "mlc-chat-config.json").exists() && 
                             (File(outDir, "ndarray-cache.json").exists() || File(outDir, "tensor-cache.json").exists())
        val shard0 = File(outDir, "params_shard_0.bin")
        return isConfigPresent && shard0.exists() && shard0.length() > 10 * 1024 * 1024
    }

    fun isModelInAssets(model: ModelConfig): Boolean {
        return try {
            val assets = context.assets.list("model") ?: return false
            assets.contains(model.id)
        } catch (e: IOException) {
            false
        }
    }

    private fun verifyModelIntegrity(outDir: File) {
        try {
            // Prefer ndarray-cache.json, fall back to tensor-cache.json
            var manifestFile = File(outDir, "ndarray-cache.json")
            if (!manifestFile.exists()) {
                manifestFile = File(outDir, "tensor-cache.json")
            }
            
            if (!manifestFile.exists()) {
                throw CorruptedModelException("Model manifest (ndarray-cache.json or tensor-cache.json) is missing.")
            }

            val cacheContent = manifestFile.readText()
            val jsonObject = Gson().fromJson(cacheContent, JsonObject::class.java)
            val records = jsonObject.getAsJsonArray("records")
            
            for (i in 0 until records.size()) {
                val record = records.get(i).asJsonObject
                val shardName = record.get("dataPath").asString
                val shardFile = File(outDir, shardName)
                if (!shardFile.exists()) {
                    throw CorruptedModelException("Model weight file $shardName is missing.")
                }
            }
        } catch (e: Exception) {
            if (e is CorruptedModelException) throw e
            throw CorruptedModelException("Failed to parse model metadata: ${e.message}")
        }
    }

    /**
     * Download model weights to disk without loading the engine.
     * Multiple models can be downloaded concurrently.
     */
    suspend fun downloadWeightsOnly(model: ModelConfig, token: String?, onProgress: suspend (Float, String) -> Unit): kotlin.Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val outDir = File(context.getExternalFilesDir(null), "model/${model.id}")
            if (!outDir.exists()) outDir.mkdirs()

            val isConfigPresent = File(outDir, "mlc-chat-config.json").exists()
            val shard0 = File(outDir, "params_shard_0.bin")
            val isWeightsPresent = shard0.exists() && shard0.length() > 10 * 1024 * 1024

            if (!isConfigPresent || !isWeightsPresent) {
                if (isModelInAssets(model)) {
                    copyModelFromAssets(model.id, outDir) { progress ->
                        onProgress(progress, "Extracting model from assets...")
                    }
                } else {
                    val downloadResult = downloadModelDirectly(model, token, onProgress)
                    if (downloadResult.isFailure) {
                        throw downloadResult.exceptionOrNull() ?: IOException("Download failed")
                    }
                }
            }

            onProgress(1f, "Verifying model integrity...")
            verifyModelIntegrity(outDir)
            kotlin.Result.success(Unit)
        } catch (e: CancellationException) {
            kotlin.Result.failure(e)
        } catch (e: CorruptedModelException) {
            kotlin.Result.failure(e)
        } catch (e: Exception) {
            kotlin.Result.failure(e)
        }
    }

    suspend fun initializeEngine(model: ModelConfig, token: String? = null, onProgress: suspend (Float, String) -> Unit): kotlin.Result<Unit> = withContext(Dispatchers.IO) label@ {
        try {
            _currentModel.value = model
            val outDir = File(context.getExternalFilesDir(null), "model/${model.id}")
            if (!outDir.exists()) outDir.mkdirs()
            
            // Initial surface check
            val isConfigPresent = File(outDir, "mlc-chat-config.json").exists()
            val shard0 = File(outDir, "params_shard_0.bin")
            val isWeightsPresent = shard0.exists() && shard0.length() > 10 * 1024 * 1024

            if (!isConfigPresent || !isWeightsPresent) {
                if (isModelInAssets(model)) {
                    copyModelFromAssets(model.id, outDir) { progress ->
                        onProgress(progress, "Extracting model from assets...")
                    }
                } else {
                    val downloadResult = downloadModelDirectly(model, token, onProgress)
                    if (downloadResult.isFailure) {
                        throw downloadResult.exceptionOrNull() ?: IOException("Download failed")
                    }
                }
            }
            
            // Deep verification before native call
            onProgress(1f, "Verifying model integrity...")
            verifyModelIntegrity(outDir)

            onProgress(1f, "Optimizing VRAM for new model...")
            if (engine == null) {
                engine = MLCEngine()
            } else {
                // Explicitly unload current model from VRAM before loading a new one
                engine?.unload()
            }
            
            onProgress(1f, "Loading weights into VRAM...")
            try {
                engine?.reload(outDir.absolutePath, model.modelLib)
            } catch (t: Throwable) {
                // Catch EVERYTHING (including TVM Errors) to prevent JVM crash
                android.util.Log.e("MLC", "Native reload failed", t)
                // If reload failed, clear the engine to force a fresh init next time
                engine = null
                if (t is OutOfMemoryError) {
                    throw CorruptedModelException("Out of memory: this model is too large for this device. Try a smaller model.")
                }
                throw CorruptedModelException("Native engine failed to load weights. This often happens if the download was interrupted or files are incompatible.")
            }
            kotlin.Result.success(Unit)
        } catch (e: CancellationException) {
            android.util.Log.d("MLC", "Initialization cancelled by user")
            kotlin.Result.failure(e)
        } catch (e: CorruptedModelException) {
            kotlin.Result.failure(e)
        } catch (e: Exception) {
            android.util.Log.e("MLC", "Error initializing engine", e)
            val userFriendlyMessage = when (e) {
                is IOException -> "Storage error: ${e.localizedMessage}"
                else -> "Engine Error: ${e.localizedMessage ?: "Unknown initialization failure"}"
            }
            kotlin.Result.failure(Exception(userFriendlyMessage))
        }
    }

    private suspend fun copyModelFromAssets(modelId: String, outDir: File, onProgress: suspend (Float) -> Unit) = withContext(Dispatchers.IO) {
        val assetManager = context.assets
        val assetPath = "model/$modelId"
        val files = assetManager.list(assetPath) ?: return@withContext
        val totalFiles = files.size
        var copiedFiles = 0
        
        for (file in files) {
            assetManager.open("$assetPath/$file").use { input ->
                FileOutputStream(File(outDir, file)).use { output ->
                    input.copyTo(output)
                }
            }
            copiedFiles++
            onProgress(copiedFiles.toFloat() / totalFiles)
        }
    }

    // Dedicated single-thread dispatcher for MLC inference on a CPU-priority thread.
    // Dispatchers.Default has normal thread priority (vs IO's low priority), giving
    // the ML engine proper CPU time during prefill and generation. limitedParallelism(1)
    // prevents the native engine from saturating all CPU cores and starving the UI.
    private val inferenceDispatcher = Dispatchers.Default.limitedParallelism(1)

    fun generateResponse(
        prompt: String,
        messageHistory: List<OpenAIProtocol.ChatCompletionMessage>,
        settings: GenerationSettings = GenerationSettings()
    ): Flow<String> = flow {
        val currentEngine = engine ?: throw IllegalStateException("Engine not initialized. Please select a model first.")
        
        // Cap history to last 20 messages to prevent excessive prefill times on long conversations
        val cappedHistory = if (messageHistory.size > 20) {
            messageHistory.takeLast(20)
        } else {
            messageHistory
        }

        val newHistory = cappedHistory + OpenAIProtocol.ChatCompletionMessage(
            role = OpenAIProtocol.ChatCompletionRole.user,
            content = OpenAIProtocol.ChatCompletionMessageContent(prompt)
        )
        
        val chatRequest = OpenAIProtocol.ChatCompletionRequest(
            messages = newHistory,
            stream = true,
            temperature = settings.temperature,
            top_p = settings.topP,
            frequency_penalty = settings.frequencyPenalty,
            presence_penalty = settings.presencePenalty,
            max_tokens = settings.maxTokens,
            seed = settings.seed
        )
        
        try {
            val responseStream = currentEngine.chat.completions.create(chatRequest)
            for (chunk in responseStream) {
                kotlinx.coroutines.yield()
                val delta = chunk.choices.firstOrNull()?.delta?.content
                if (delta != null) {
                    emit(delta.asText())
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MLC", "Error during generation", e)
            throw Exception("Response Generation Failed: ${e.localizedMessage ?: "Native engine crash"}")
        }
    }.flowOn(inferenceDispatcher)

    fun stopGeneration() {
        engine?.reset()
    }

    fun deleteModelFiles(model: ModelConfig) {
        val outDir = File(context.getExternalFilesDir(null), "model/${model.id}")
        if (outDir.exists()) {
            outDir.deleteRecursively()
        }
    }

    fun clearModelCache() {
        engine?.unload()
        engine = null
        val modelId = _currentModel.value?.id ?: "unknown"
        val outDir = File(context.getExternalFilesDir(null), "model/$modelId")
        if (outDir.exists()) {
            outDir.deleteRecursively()
        }
    }

    fun release() {
        engine?.unload()
        engine = null
    }
}
