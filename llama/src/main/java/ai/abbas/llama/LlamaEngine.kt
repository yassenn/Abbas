package ai.abbas.llama

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * High-level Kotlin wrapper around llama.cpp JNI.
 *
 * Manages the lifecycle (init → loadModel → createContext → chat → unload → shutdown)
 * on a single dedicated thread. Callers should ensure all calls are serialized.
 *
 * Message format uses a simplified JSON structure compatible with the JNI parser:
 *   [{"role":"user","content":"Hello"}, {"role":"assistant","content":"Hi!"}]
 */
class LlamaEngine(private val context: Context) {

    companion object {
        private const val TAG = "LlamaEngine"
        private const val DEFAULT_CONTEXT_SIZE = 4096
        private const val DEFAULT_THREADS = 4
        private const val DEFAULT_BATCH = 512
    }

    private var initialized = false
    private var modelLoaded = false
    private var contextCreated = false

    /**
     * Initialize the llama backend. Must be called once before anything else.
     */
    fun init() {
        if (initialized) return
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        require(nativeLibDir.isNotBlank()) { "Invalid native library path" }

        System.loadLibrary("abbas-llama")
        LlamaJni.init(nativeLibDir)
        initialized = true
        Log.i(TAG, "Initialized. System info:\n${LlamaJni.systemInfo()}")
    }

    /**
     * Load a GGUF model file. Must be called after [init].
     */
    fun loadModel(modelPath: String) {
        check(initialized) { "Engine not initialized" }
        unload() // clean up any previous model

        val file = java.io.File(modelPath)
        require(file.exists()) { "Model file not found: $modelPath" }
        require(file.canRead()) { "Cannot read model file: $modelPath" }

        Log.i(TAG, "Loading model: $modelPath")
        val result = LlamaJni.loadModel(modelPath)
        if (result != 0) {
            throw RuntimeException("Failed to load model (error code $result)")
        }
        modelLoaded = true
        Log.i(TAG, "Model loaded")
    }

    /**
     * Create inference context. Must be called after [loadModel].
     */
    fun createContext(nCtx: Int = DEFAULT_CONTEXT_SIZE,
                      nThreads: Int = DEFAULT_THREADS,
                      nBatch: Int = DEFAULT_BATCH) {
        check(modelLoaded) { "No model loaded" }

        Log.i(TAG, "Creating context: nCtx=$nCtx, threads=$nThreads")
        val result = LlamaJni.createContext(nCtx, nThreads, nBatch)
        if (result != 0) {
            throw RuntimeException("Failed to create context (error code $result)")
        }
        contextCreated = true
        Log.i(TAG, "Context created")
    }

    /**
     * Set sampling parameters for generation.
     */
    fun setSamplerParams(temperature: Float, topP: Float, seed: Int?) {
        check(contextCreated) { "No context created" }
        LlamaJni.setSamplerParams(temperature, topP, seed ?: -1)
    }

    /**
     * Submit conversation history and start generating tokens.
     *
     * @param messages List of role/content pairs representing the conversation.
     * @param maxTokens Maximum number of tokens to generate.
     * @return Flow of generated token strings.
     */
    fun chat(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 512
    ): Flow<String> = flow {
        check(contextCreated) { "No context created" }

        // Build JSON for JNI
        val jsonArray = JSONArray()
        for ((role, content) in messages) {
            jsonArray.put(JSONObject().apply {
                put("role", role)
                put("content", content)
            })
        }
        val jsonStr = JSONObject().apply {
            put("messages", jsonArray)
        }.toString()

        Log.d(TAG, "Submitting ${messages.size} messages, maxTokens=$maxTokens")
        val result = LlamaJni.submitMessages(jsonStr, maxTokens)
        if (result != 0) {
            throw RuntimeException("Failed to submit messages (error code $result)")
        }

        // Generate tokens
        while (true) {
            val token = LlamaJni.nextToken() ?: break
            emit(token)
        }
        Log.d(TAG, "Generation complete")
    }

    /**
     * Request generation to stop. The flow will emit one more token then close.
     */
    fun stopGeneration() {
        LlamaJni.stopGeneration()
    }

    /**
     * Clear chat history and KV cache for a fresh conversation.
     */
    fun resetChat() {
        LlamaJni.resetChat()
    }

    /**
     * Unload the current model and free context.
     */
    fun unload() {
        if (contextCreated || modelLoaded) {
            LlamaJni.unload()
            contextCreated = false
            modelLoaded = false
        }
    }

    /**
     * Full shutdown — releases all resources.
     */
    fun shutdown() {
        unload()
        if (initialized) {
            LlamaJni.shutdown()
            initialized = false
        }
    }
}