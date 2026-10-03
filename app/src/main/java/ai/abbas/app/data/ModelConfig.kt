package ai.abbas.app.data

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
    val isCustom: Boolean = false
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
    if (!isHttpsUrl(model.baseUrl)) {
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
    // ── Alibaba · Qwen (latest 3.5 series) ──────────────────────────────────────
    ModelConfig(
        id = "Qwen3.5-4B-Q4_K_M",
        name = "Qwen3.5 4B (Alibaba)",
        ggufFile = "Qwen3.5-4B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/",
        estimatedRamBytes = 3_500_000_000L
    ),
    ModelConfig(
        id = "Qwen3.5-2B-Q4_K_M",
        name = "Qwen3.5 2B (Alibaba)",
        ggufFile = "Qwen3.5-2B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/",
        estimatedRamBytes = 1_800_000_000L
    ),

    // ── Meta · Llama (latest small Llama) ───────────────────────────────────────
    ModelConfig(
        id = "Llama-3.2-3B-Instruct-Q4_K_M",
        name = "Llama 3.2 3B (Meta)",
        ggufFile = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/",
        estimatedRamBytes = 2_500_000_000L
    ),
    ModelConfig(
        id = "Llama-3.2-1B-Instruct-Q4_K_M",
        name = "Llama 3.2 1B (Meta)",
        ggufFile = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/",
        estimatedRamBytes = 1_200_000_000L
    ),

    // ── Google · Gemma ──────────────────────────────────────────────────────────
    ModelConfig(
        id = "gemma-4-E2B-it-Q4_K_M",
        name = "Gemma 4 E2B (Google)",
        ggufFile = "gemma-4-E2B-it-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/",
        estimatedRamBytes = 3_800_000_000L
    ),
    ModelConfig(
        id = "gemma-3n-E2B-it-Q4_K_M",
        name = "Gemma 3n E2B (Google, on-device)",
        ggufFile = "gemma-3n-E2B-it-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/gemma-3n-E2B-it-GGUF/resolve/main/",
        estimatedRamBytes = 3_600_000_000L
    ),

    // ── Microsoft · Phi ─────────────────────────────────────────────────────────
    ModelConfig(
        id = "Phi-4-mini-instruct-Q4_K_M",
        name = "Phi-4 Mini (Microsoft)",
        ggufFile = "Phi-4-mini-instruct-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/",
        estimatedRamBytes = 3_000_000_000L
    ),
    ModelConfig(
        id = "Phi-4-mini-reasoning-Q4_K_M",
        name = "Phi-4 Mini Reasoning (Microsoft)",
        ggufFile = "Phi-4-mini-reasoning-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/lmstudio-community/Phi-4-mini-reasoning-GGUF/resolve/main/",
        estimatedRamBytes = 3_000_000_000L
    ),

    // ── Mistral AI · Ministral (official GGUF) ──────────────────────────────────
    ModelConfig(
        id = "Ministral-3-3B-Instruct-2512-Q4_K_M",
        name = "Ministral 3 3B (Mistral)",
        ggufFile = "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512-GGUF/resolve/main/",
        estimatedRamBytes = 2_700_000_000L
    ),
    ModelConfig(
        id = "Ministral-3-8B-Instruct-2512-Q4_K_M",
        name = "Ministral 3 8B (Mistral)",
        ggufFile = "Ministral-3-8B-Instruct-2512-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/mistralai/Ministral-3-8B-Instruct-2512-GGUF/resolve/main/",
        estimatedRamBytes = 6_500_000_000L
    ),

    // ── DeepSeek · R1 distill (reasoning) ───────────────────────────────────────
    ModelConfig(
        id = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M",
        name = "DeepSeek R1 1.5B (DeepSeek)",
        ggufFile = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/",
        estimatedRamBytes = 1_600_000_000L
    ),

    // ── Hugging Face · SmolLM ───────────────────────────────────────────────────
    ModelConfig(
        id = "SmolLM3-3B-Q4_K_M",
        name = "SmolLM3 3B (HuggingFace)",
        ggufFile = "SmolLM3-3B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/SmolLM3-3B-GGUF/resolve/main/",
        estimatedRamBytes = 2_500_000_000L
    ),

    // ── Zhipu AI · GLM ──────────────────────────────────────────────────────────
    ModelConfig(
        id = "GLM-Edge-4B-Chat-Q4_K_M",
        name = "GLM-Edge 4B (Zhipu)",
        ggufFile = "ggml-model-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/zai-org/glm-edge-4b-chat-gguf/resolve/main/",
        estimatedRamBytes = 3_200_000_000L
    ),
    ModelConfig(
        id = "GLM-Edge-1.5B-Chat-Q4_K_M",
        name = "GLM-Edge 1.5B (Zhipu)",
        ggufFile = "ggml-model-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/zai-org/glm-edge-1.5b-chat-gguf/resolve/main/",
        estimatedRamBytes = 1_400_000_000L
    ),

    // ── Zyphra · ZR1 (US open-research lab) ─────────────────────────────────────
    ModelConfig(
        id = "Zyphra-ZR1-1.5B-Q4_K_M",
        name = "ZR1 1.5B (Zyphra)",
        ggufFile = "Zyphra_ZR1-1.5B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/bartowski/Zyphra_ZR1-1.5B-GGUF/resolve/main/",
        estimatedRamBytes = 1_700_000_000L
    ),

    // ── IBM · Granite (official GGUF) ───────────────────────────────────────────
    ModelConfig(
        id = "granite-4.2-3b-Q4_K_M",
        name = "Granite 4.2 3B (IBM)",
        ggufFile = "granite-4.2-3b-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/ibm-granite/granite-4.2-3b-GGUF/resolve/main/",
        estimatedRamBytes = 2_800_000_000L
    ),

    // ── NVIDIA · Nemotron (official GGUF) ───────────────────────────────────────
    ModelConfig(
        id = "NVIDIA-Nemotron3-Nano-4B-Q4_K_M",
        name = "Nemotron 3 Nano 4B (NVIDIA)",
        ggufFile = "NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Nano-4B-GGUF/resolve/main/",
        estimatedRamBytes = 3_400_000_000L
    ),

    // ── Liquid AI · LFM (built for on-device) ───────────────────────────────────
    ModelConfig(
        id = "LFM2.5-1.2B-Instruct-Q4_K_M",
        name = "LFM2.5 1.2B (Liquid AI)",
        ggufFile = "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/resolve/main/",
        estimatedRamBytes = 1_200_000_000L
    ),

    // ── LG AI Research · EXAONE (official GGUF) ─────────────────────────────────
    ModelConfig(
        id = "EXAONE-4.0-1.2B-Q4_K_M",
        name = "EXAONE 4.0 1.2B (LG)",
        ggufFile = "EXAONE-4.0-1.2B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/LGAI-EXAONE/EXAONE-4.0-1.2B-GGUF/resolve/main/",
        estimatedRamBytes = 1_200_000_000L
    ),

    // ── Allen Institute for AI · OLMo (fully open) ──────────────────────────────
    ModelConfig(
        id = "Olmo-3-7B-Instruct-Q4_K_M",
        name = "OLMo 3 7B (Ai2)",
        ggufFile = "Olmo-3-7B-Instruct-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/Olmo-3-7B-Instruct-GGUF/resolve/main/",
        estimatedRamBytes = 5_600_000_000L
    )
)
