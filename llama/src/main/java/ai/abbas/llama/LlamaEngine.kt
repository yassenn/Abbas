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

    /**
     * Reasoning delimiters declared by the loaded model's chat template.
     *
     * Thinking models open the trace inside the generation prompt and close it with
     * a marker token, so the streamed output carries only the closing marker —
     * everything before it is the reasoning trace.
     *
     * @param supported whether the template frames a reasoning section at all
     * @param start the opening marker (may be absent from the stream if pre-opened)
     * @param ends every marker that closes the reasoning section
     */
    data class ThinkingTags(
        val supported: Boolean,
        val start: String,
        val ends: List<String>
    ) {
        companion object {
            val NONE = ThinkingTags(supported = false, start = "", ends = emptyList())
        }
    }

    companion object {
        private const val TAG = "LlamaEngine"
        private const val DEFAULT_CONTEXT_SIZE = 4096
        private const val DEFAULT_THREADS = 4
        private const val DEFAULT_BATCH = 512

        /**
         * Length of the longest prefix of [buf] that ends on a complete UTF-8
         * character boundary. An incomplete trailing sequence (multi-byte
         * character not yet fully received) or an invalid lead byte stops the
         * scan so those bytes are carried over to the next call.
         */
        private fun longestValidUtf8Prefix(buf: ByteArray): Int {
            var i = 0
            while (i < buf.size) {
                val b = buf[i].toInt() and 0xFF
                val len = when {
                    b < 0x80 -> 1
                    b in 0xC2..0xDF -> 2
                    b in 0xE0..0xEF -> 3
                    b in 0xF0..0xF4 -> 4
                    else -> return i   // invalid lead byte
                }
                if (i + len > buf.size) return i // incomplete tail
                for (j in 1 until len) {
                    val c = buf[i + j].toInt() and 0xFF
                    if (c < 0x80 || c > 0xBF) return i
                }
                i += len
            }
            return i
        }
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
        maxTokens: Int = 512,
        enableThinking: Boolean = true
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

        Log.d(TAG, "Submitting ${messages.size} messages, maxTokens=$maxTokens, thinking=$enableThinking")
        val result = LlamaJni.submitMessages(jsonStr, maxTokens, enableThinking)
        if (result != 0) {
            throw RuntimeException("Failed to submit messages (error code $result)")
        }

        // Generate tokens. Native returns raw UTF-8 bytes; buffer them so a
        // multi-byte character split across two tokens is emitted as one unit
        // instead of two invalid fragments (which decoded to mojibake).
        val pending = java.io.ByteArrayOutputStream()
        while (true) {
            val tokenBytes = LlamaJni.nextToken() ?: break
            if (tokenBytes.isNotEmpty()) pending.write(tokenBytes, 0, tokenBytes.size)

            val buffered = pending.toByteArray()
            val validLen = longestValidUtf8Prefix(buffered)
            if (validLen > 0) {
                emit(String(buffered, 0, validLen, Charsets.UTF_8))
                pending.reset()
                if (validLen < buffered.size) {
                    pending.write(buffered, validLen, buffered.size - validLen)
                }
            }
        }
        // Flush any trailing bytes that never formed a complete character.
        if (pending.size() > 0) {
            emit(String(pending.toByteArray(), Charsets.UTF_8))
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
     * Reasoning delimiters for the loaded model. Call after [createContext]; returns
     * [ThinkingTags.NONE] when no model/template is loaded.
     */
    fun thinkingTags(): ThinkingTags {
        if (!contextCreated) return ThinkingTags.NONE
        return try {
            val o = JSONObject(LlamaJni.getThinkingTags())
            val ends = o.optJSONArray("ends")?.let { a -> List(a.length()) { a.getString(it) } }
                ?: emptyList()
            ThinkingTags(
                supported = o.optBoolean("thinking", false),
                start = o.optString("start", ""),
                ends = ends.filter { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not read thinking tags: ${e.message}")
            ThinkingTags.NONE
        }
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