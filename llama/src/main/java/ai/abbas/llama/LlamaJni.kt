package ai.abbas.llama

/**
 * JNI bridge to llama.cpp native library.
 *
 * All methods are called from a single dedicated thread — the caller
 * is responsible for ensuring thread safety.
 */
object LlamaJni {

    /** Initialize llama backend and load CPU backend variants. */
    external fun init(nativeLibDir: String)

    /** Load a GGUF model from the given file path. Returns 0 on success. */
    external fun loadModel(modelPath: String): Int

    /** Create inference context. Returns 0 on success. */
    external fun createContext(nCtx: Int, nThreads: Int, nBatch: Int): Int

    /** Set sampling parameters (temperature, top_p, seed). */
    external fun setSamplerParams(temp: Float, topP: Float, seed: Int)

    /** Clear chat history and KV cache. */
    external fun resetChat()

    /**
     * Submit a conversation history as JSON and prepare for generation.
     *
     * JSON format: {"messages":[{"role":"user","content":"..."},{"role":"assistant","content":"..."},...]}
     * [enableThinking] false suppresses the model's reasoning trace (search mode).
     * Returns 0 on success.
     */
    external fun submitMessages(messagesJson: String, maxTokens: Int, enableThinking: Boolean): Int

    /**
     * Get the next generated token as raw UTF-8 bytes.
     * Returns null when generation is complete or stopped; an empty array
     * means the token produced no visible output (e.g. a control token).
     */
    external fun nextToken(): ByteArray?

    /** Request generation to stop at the next token boundary. */
    external fun stopGeneration()

    /**
     * Reasoning delimiters declared by the loaded model's chat template, as JSON:
     * `{"thinking":bool,"start":"...","ends":["..."]}`.
     */
    external fun getThinkingTags(): String

    /** Unload model and free context resources. */
    external fun unload()

    /** Shutdown llama backend completely. */
    external fun shutdown()

    /** Get system info string (CPU features, backends). */
    external fun systemInfo(): String
}