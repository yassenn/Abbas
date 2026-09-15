@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.abbas.app.inference

import android.content.Context
import ai.abbas.llama.LlamaEngine
import ai.abbas.app.data.ModelConfig
import ai.abbas.app.data.GenerationSettings
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

class CorruptedModelException(message: String) : Exception(message)

/**
 * Manages llama.cpp model lifecycle: download, verify, load, generate.
 *
 * Replaces the old MLC-based MlcEngineManager. Uses the LlamaEngine
 * (JNI wrapper around llama.cpp) for inference.
 */
class LlamaEngineManager(private val context: Context) {

    // ── llama.cpp engine (singleton per manager instance) ───────────

    private var llamaEngine: LlamaEngine? = null
    private var engineReady = false

    private val _currentModel = MutableStateFlow<ModelConfig?>(null)
    val currentModel: StateFlow<ModelConfig?> = _currentModel.asStateFlow()

    // Dedicated single-thread dispatcher for llama.cpp inference.
    // CPU-priority thread prevents native code from saturating all cores.
    private val inferenceDispatcher = Dispatchers.Default.limitedParallelism(1)

    // ── Device capability ──────────────────────────────────────────

    fun isDeviceCapable(model: ModelConfig): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem >= model.estimatedRamBytes
    }

    // ── Model file management ──────────────────────────────────────

    fun isModelDownloaded(model: ModelConfig): Boolean {
        val ggufFile = File(context.getExternalFilesDir(null), "model/${model.id}/${model.ggufFile}")
        return ggufFile.exists() && ggufFile.length() > 100 * 1024 * 1024 // at least 100MB
    }

    fun isModelInAssets(model: ModelConfig): Boolean {
        return try {
            val assets = context.assets.list("model") ?: return false
            assets.contains(model.id)
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Download model weights to disk without loading the engine.
     * Multiple models can be downloaded concurrently.
     */
    suspend fun downloadWeightsOnly(
        model: ModelConfig,
        token: String?,
        onProgress: suspend (Float, String) -> Unit
    ): kotlin.Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val outDir = File(context.getExternalFilesDir(null), "model/${model.id}")
            if (!outDir.exists()) outDir.mkdirs()

            val ggufFile = File(outDir, model.ggufFile)

            if (!ggufFile.exists() || ggufFile.length() < 100 * 1024 * 1024) {
                if (isModelInAssets(model)) {
                    copyModelFromAssets(model.id, model.ggufFile, outDir) { progress ->
                        onProgress(progress, "Extracting model from assets...")
                    }
                } else {
                    val downloadResult = downloadModelDirectly(model, token, onProgress)
                    if (downloadResult.isFailure) {
                        throw downloadResult.exceptionOrNull() ?: IOException("Download failed")
                    }
                }
            }

            onProgress(1f, "Verifying model...")
            verifyGgufIntegrity(ggufFile)
            kotlin.Result.success(Unit)
        } catch (e: CancellationException) {
            kotlin.Result.failure(e)
        } catch (e: CorruptedModelException) {
            kotlin.Result.failure(e)
        } catch (e: Exception) {
            kotlin.Result.failure(e)
        }
    }

    private suspend fun downloadModelDirectly(
        model: ModelConfig,
        token: String?,
        onProgress: suspend (Float, String) -> Unit
    ): kotlin.Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val outDir = File(context.getExternalFilesDir(null), "model/${model.id}")
            if (!outDir.exists()) outDir.mkdirs()

            val downloader = ModelDownloader(token)
            downloader.downloadModel(outDir, model.baseUrl, model.ggufFile).collect { progress ->
                onProgress(progress.progress, progress.status)
            }
            kotlin.Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            kotlin.Result.failure(e)
        }
    }

    private fun verifyGgufIntegrity(ggufFile: File) {
        if (!ggufFile.exists()) {
            throw CorruptedModelException("Model file ${ggufFile.name} is missing.")
        }
        if (ggufFile.length() < 10 * 1024 * 1024) { // less than 10MB is suspicious
            throw CorruptedModelException("Model file ${ggufFile.name} appears truncated (${ggufFile.length()} bytes).")
        }
        // GGUF files start with magic bytes "GGUF" (0x47 0x47 0x55 0x46)
        try {
            ggufFile.inputStream().use { stream ->
                val magic = ByteArray(4)
                if (stream.read(magic) == 4) {
                    val magicStr = String(magic, Charsets.UTF_8)
                    if (magicStr != "GGUF") {
                        throw CorruptedModelException("File ${ggufFile.name} is not a valid GGUF model (bad magic: $magicStr).")
                    }
                }
            }
        } catch (e: CorruptedModelException) {
            throw e
        } catch (e: Exception) {
            throw CorruptedModelException("Failed to verify model integrity: ${e.message}")
        }
    }

    // ── Engine initialization ──────────────────────────────────────

    suspend fun initializeEngine(
        model: ModelConfig,
        token: String? = null,
        onProgress: suspend (Float, String) -> Unit
    ): kotlin.Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _currentModel.value = model
            val outDir = File(context.getExternalFilesDir(null), "model/${model.id}")
            if (!outDir.exists()) outDir.mkdirs()

            val ggufFile = File(outDir, model.ggufFile)

            // Download if needed
            if (!ggufFile.exists() || ggufFile.length() < 100 * 1024 * 1024) {
                if (isModelInAssets(model)) {
                    copyModelFromAssets(model.id, model.ggufFile, outDir) { progress ->
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
            verifyGgufIntegrity(ggufFile)

            // Initialize engine + load model
            onProgress(1f, "Initializing llama.cpp engine...")
            if (llamaEngine == null) {
                llamaEngine = LlamaEngine(context)
                llamaEngine?.init()
            } else {
                // Unload previous model
                llamaEngine?.unload()
            }

            onProgress(1f, "Loading model into memory...")
            try {
                llamaEngine?.loadModel(ggufFile.absolutePath)
                llamaEngine?.createContext(
                    nCtx = model.contextSize,
                    nThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2),
                    nBatch = 512
                )
                engineReady = true
            } catch (t: Throwable) {
                android.util.Log.e("Llama", "Native load failed", t)
                llamaEngine = null
                engineReady = false
                if (t is OutOfMemoryError) {
                    throw CorruptedModelException("Out of memory: this model is too large for this device. Try a smaller model.")
                }
                throw CorruptedModelException("Native engine failed to load model. This often happens if the file is incompatible or corrupted.")
            }

            kotlin.Result.success(Unit)
        } catch (e: CancellationException) {
            android.util.Log.d("Llama", "Initialization cancelled by user")
            kotlin.Result.failure(e)
        } catch (e: CorruptedModelException) {
            kotlin.Result.failure(e)
        } catch (e: Exception) {
            android.util.Log.e("Llama", "Error initializing engine", e)
            kotlin.Result.failure(Exception("Engine Error: ${e.localizedMessage ?: "Unknown initialization failure"}"))
        }
    }

    private suspend fun copyModelFromAssets(
        modelId: String,
        ggufFile: String,
        outDir: File,
        onProgress: suspend (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
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

    // ── Response generation ────────────────────────────────────────

    /**
     * A simple message representation for llama.cpp chat history.
     * Replaces the MLC-specific OpenAIProtocol.ChatCompletionMessage.
     */
    data class ChatMessage(
        val role: String,   // "user", "assistant", "system"
        val content: String
    )

    /**
     * Generate a response using llama.cpp.
     *
     * @param prompt The user's prompt (with context prepended if any)
     * @param messageHistory Previous messages in the conversation
     * @param settings Generation parameters (temperature, top_p, etc.)
     * @return Flow of token strings
     */
    fun generateResponse(
        prompt: String,
        messageHistory: List<ChatMessage>,
        settings: GenerationSettings = GenerationSettings()
    ): Flow<String> = flow {
        val engine = llamaEngine
            ?: throw IllegalStateException("Engine not initialized. Please select a model first.")

        // Cap history to last 20 messages to prevent excessive prefill
        val cappedHistory = if (messageHistory.size > 20) {
            messageHistory.takeLast(20)
        } else {
            messageHistory
        }

        // Build the message list: history + new user prompt
        val messages = cappedHistory.map { it.role to it.content }.toMutableList()
        messages.add("user" to prompt)

        // Configure sampler
        engine.setSamplerParams(
            temperature = settings.temperature,
            topP = settings.topP,
            seed = settings.seed
        )

        android.util.Log.d("Llama", "Generating: ${messages.size} messages, temp=${settings.temperature}")

        // Generate tokens
        engine.chat(messages, maxTokens = settings.maxTokens).collect { token ->
            emit(token)
        }

        android.util.Log.d("Llama", "Generation complete")
    }.flowOn(inferenceDispatcher)

    fun stopGeneration() {
        llamaEngine?.stopGeneration()
    }

    // ── Cleanup ────────────────────────────────────────────────────

    fun deleteModelFiles(model: ModelConfig) {
        val outDir = File(context.getExternalFilesDir(null), "model/${model.id}")
        if (outDir.exists()) {
            outDir.deleteRecursively()
        }
    }

    fun clearModelCache() {
        llamaEngine?.unload()
        llamaEngine = null
        engineReady = false
        val modelId = _currentModel.value?.id ?: "unknown"
        val outDir = File(context.getExternalFilesDir(null), "model/$modelId")
        if (outDir.exists()) {
            outDir.deleteRecursively()
        }
    }

    fun release() {
        llamaEngine?.shutdown()
        llamaEngine = null
        engineReady = false
    }
}