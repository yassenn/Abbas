package ai.alal.app.data

/**
 * Model configuration for llama.cpp GGUF models.
 *
 * [ggufFile] is the filename within the baseUrl directory (typically "*.gguf") and is
 * **case-sensitive** — it must match the Hugging Face listing exactly.
 * [baseUrl] should be the HuggingFace resolve/main/ URL (with trailing slash).
 * [estimatedRamBytes] is used for the device capability check (total RAM >= this).
 * [contextSize] is the context window size for this model.
 *
 * All entries below are Q4_K_M (or the repo's nearest 4-bit quant), ungated, and sized to
 * fit a phone. Every URL was verified to resolve (HTTP 200) before shipping.
 */
data class ModelConfig(
    val id: String,
    val name: String,
    val ggufFile: String,
    val baseUrl: String,
    val estimatedRamBytes: Long,
    val contextSize: Int = 4096,
    val isCustom: Boolean = false,
    /** Weights came from a local file the user picked; there is nothing to download. */
    val isImported: Boolean = false,
    /**
     * HIGH | MID | LOW for summaries, documents and instruction following; null for
     * custom models. Nullable because Gson can skip Kotlin defaults on old saved JSON.
     */
    val tier: String? = null,
    /** One-line advice shown in the AI Hub. */
    val note: String? = null
)

/**
 * Validates a user-supplied custom model. Custom models are an untrusted input
 * surface: [ModelConfig.id] and [ModelConfig.ggufFile] are used to build on-disk
 * paths (path-traversal risk) and [ModelConfig.baseUrl] is fetched over the
 * network (SSRF / cleartext / malicious-GGUF risk), so all three are constrained.
 *
 * @return null when the model is safe to accept, otherwise a human-readable reason.
 */
fun validateCustomModel(model: ModelConfig): String? {
    if (!model.id.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
        return "Model ID may only contain letters, digits, '.', '_' and '-'."
    }
    val file = model.ggufFile
    if (file.isBlank() || file.contains('/') || file.contains('\\') ||
        file.contains("..") || !file.endsWith(".gguf", ignoreCase = true)
    ) {
        return "GGUF filename must be a plain '*.gguf' name with no path separators."
    }
    if (!(model.isImported && model.baseUrl.isBlank()) && !isHttpsUrl(model.baseUrl)) {
        return "Base URL must be a valid https:// URL."
    }
    return null
}

/** True when [url] is an absolute https URL with a parseable host. */
private fun isHttpsUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith("https://")) return false
    return try {
        !java.net.URI(trimmed).host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }
}

val availableModels = listOf(
    // ── High performing: best summaries, documents and instruction following ──
    ModelConfig(
        id = "Qwen3.5-4B-Q4_K_M",
        name = "Qwen3.5 4B (Alibaba)",
        ggufFile = "Qwen3.5-4B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/",
        estimatedRamBytes = 3_500_000_000L,
        tier = "HIGH",
        note = "Best overall: top instruction-following and long-document handling in its size. Turn thinking off for fast summaries."
    ),
    ModelConfig(
        id = "gemma-4-E2B-it-Q4_K_M",
        name = "Gemma 4 E2B (Google)",
        ggufFile = "gemma-4-E2B-it-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/",
        estimatedRamBytes = 3_800_000_000L,
        tier = "HIGH",
        note = "Strongest phone-sized model; clean, faithful summaries."
    ),
    ModelConfig(
        id = "NVIDIA-Nemotron3-Nano-4B-Q4_K_M",
        name = "Nemotron 3 Nano 4B (NVIDIA)",
        ggufFile = "NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Nano-4B-GGUF/resolve/main/",
        estimatedRamBytes = 3_400_000_000L,
        tier = "HIGH",
        note = "Excellent at following formats and rules; reliable to-do extraction."
    ),
    ModelConfig(
        id = "Phi-4-mini-instruct-Q4_K_M",
        name = "Phi-4 Mini (Microsoft)",
        ggufFile = "Phi-4-mini-instruct-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/",
        estimatedRamBytes = 3_000_000_000L,
        tier = "HIGH",
        note = "Precise and well-structured; good with documents and web results."
    ),
    ModelConfig(
        id = "Ministral-3-8B-Instruct-2512-Q4_K_M",
        name = "Ministral 3 8B (Mistral)",
        ggufFile = "Ministral-3-8B-Instruct-2512-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/mistralai/Ministral-3-8B-Instruct-2512-GGUF/resolve/main/",
        estimatedRamBytes = 6_500_000_000L,
        tier = "HIGH",
        note = "Highest quality here, but needs a phone with lots of RAM; slow."
    ),
    ModelConfig(
        id = "Olmo-3-7B-Instruct-Q4_K_M",
        name = "OLMo 3 7B (Ai2)",
        ggufFile = "Olmo-3-7B-Instruct-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/Olmo-3-7B-Instruct-GGUF/resolve/main/",
        estimatedRamBytes = 5_600_000_000L,
        tier = "HIGH",
        note = "Strong, fully open; needs lots of RAM and is slow on phones."
    ),

    // ── Mid: good balance of speed and quality ─────────────────────────────────
    ModelConfig(
        id = "Qwen3.5-2B-Q4_K_M",
        name = "Qwen3.5 2B (Alibaba)",
        ggufFile = "Qwen3.5-2B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/",
        estimatedRamBytes = 1_800_000_000L,
        tier = "MID",
        note = "Good balance of speed and quality for daily summaries."
    ),
    ModelConfig(
        id = "granite-4.2-3b-Q4_K_M",
        name = "Granite 4.2 3B (IBM)",
        ggufFile = "granite-4.2-3b-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/ibm-granite/granite-4.2-3b-GGUF/resolve/main/",
        estimatedRamBytes = 2_800_000_000L,
        tier = "MID",
        note = "Business-style, factual summaries; good with documents."
    ),
    ModelConfig(
        id = "Ministral-3-3B-Instruct-2512-Q4_K_M",
        name = "Ministral 3 3B (Mistral)",
        ggufFile = "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512-GGUF/resolve/main/",
        estimatedRamBytes = 2_700_000_000L,
        tier = "MID",
        note = "Solid all-rounder, concise answers."
    ),
    ModelConfig(
        id = "Llama-3.2-3B-Instruct-Q4_K_M",
        name = "Llama 3.2 3B (Meta)",
        ggufFile = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/",
        estimatedRamBytes = 2_500_000_000L,
        tier = "MID",
        note = "Dependable general chat and summaries; weaker on long inputs."
    ),
    ModelConfig(
        id = "gemma-3n-E2B-it-Q4_K_M",
        name = "Gemma 3n E2B (Google, on-device)",
        ggufFile = "gemma-3n-E2B-it-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/gemma-3n-E2B-it-GGUF/resolve/main/",
        estimatedRamBytes = 3_600_000_000L,
        tier = "MID",
        note = "Older Gemma edge model; Gemma 4 E2B is better."
    ),
    ModelConfig(
        id = "SmolLM3-3B-Q4_K_M",
        name = "SmolLM3 3B (HuggingFace)",
        ggufFile = "SmolLM3-3B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/SmolLM3-3B-GGUF/resolve/main/",
        estimatedRamBytes = 2_500_000_000L,
        tier = "MID",
        note = "Decent quality; sometimes drifts from requested formats."
    ),
    ModelConfig(
        id = "GLM-Edge-4B-Chat-Q4_K_M",
        name = "GLM-Edge 4B (Zhipu)",
        ggufFile = "ggml-model-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/zai-org/glm-edge-4b-chat-gguf/resolve/main/",
        estimatedRamBytes = 3_200_000_000L,
        tier = "MID",
        note = "OK chat; summaries can be generic."
    ),
    ModelConfig(
        id = "LFM2.5-1.2B-Instruct-Q4_K_M",
        name = "LFM2.5 1.2B (Liquid AI)",
        ggufFile = "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/resolve/main/",
        estimatedRamBytes = 1_200_000_000L,
        tier = "MID",
        note = "Fastest usable option; fine for brief summaries."
    ),

    // ── Low: tiny or reasoning-only models; fast but weak at summaries ─────────
    ModelConfig(
        id = "Llama-3.2-1B-Instruct-Q4_K_M",
        name = "Llama 3.2 1B (Meta)",
        ggufFile = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/",
        estimatedRamBytes = 1_200_000_000L,
        tier = "LOW",
        note = "Very fast but misses details and invents to-dos."
    ),
    ModelConfig(
        id = "EXAONE-4.0-1.2B-Q4_K_M",
        name = "EXAONE 4.0 1.2B (LG)",
        ggufFile = "EXAONE-4.0-1.2B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/LGAI-EXAONE/EXAONE-4.0-1.2B-GGUF/resolve/main/",
        estimatedRamBytes = 1_200_000_000L,
        tier = "LOW",
        note = "Tiny; struggles with long or mixed-language input."
    ),
    ModelConfig(
        id = "GLM-Edge-1.5B-Chat-Q4_K_M",
        name = "GLM-Edge 1.5B (Zhipu)",
        ggufFile = "ggml-model-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/zai-org/glm-edge-1.5b-chat-gguf/resolve/main/",
        estimatedRamBytes = 1_400_000_000L,
        tier = "LOW",
        note = "Tiny; summaries are shallow."
    ),
    ModelConfig(
        id = "Phi-4-mini-reasoning-Q4_K_M",
        name = "Phi-4 Mini Reasoning (Microsoft)",
        ggufFile = "Phi-4-mini-reasoning-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/lmstudio-community/Phi-4-mini-reasoning-GGUF/resolve/main/",
        estimatedRamBytes = 3_000_000_000L,
        tier = "LOW",
        note = "Math reasoner: rambles and breaks summary formats. Use Phi-4 Mini instead."
    ),
    ModelConfig(
        id = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M",
        name = "DeepSeek R1 1.5B (DeepSeek)",
        ggufFile = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/",
        estimatedRamBytes = 1_600_000_000L,
        tier = "LOW",
        note = "Math reasoner: long thinking, poor at summaries and chat."
    ),
    ModelConfig(
        id = "Zyphra-ZR1-1.5B-Q4_K_M",
        name = "ZR1 1.5B (Zyphra)",
        ggufFile = "Zyphra_ZR1-1.5B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/bartowski/Zyphra_ZR1-1.5B-GGUF/resolve/main/",
        estimatedRamBytes = 1_700_000_000L,
        tier = "LOW",
        note = "Math/code reasoner; not suited to summaries."
    )
)
