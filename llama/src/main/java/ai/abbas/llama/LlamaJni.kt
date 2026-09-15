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
     * Returns 0 on success.
     */
    external fun submitMessages(messagesJson: String, maxTokens: Int): Int

    /**
     * Get the next generated token as a UTF-8 string.
     * Returns null when generation is complete or stopped.
     */
    external fun nextToken(): String?

    /** Request generation to stop at the next token boundary. */
    external fun stopGeneration()

    /** Unload model and free context resources. */
    external fun unload()

    /** Shutdown llama backend completely. */
    external fun shutdown()

    /** Get system info string (CPU features, backends). */
    external fun systemInfo(): String
}