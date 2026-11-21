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
    val note: String? = null,
    /** What the model is best at: CHAT, REASONING, CODING, SUMMARIES, DOCUMENTS, LANGUAGES, FAST. */
    val strengths: List<String>? = null,
    /** Expected SHA-256 of [ggufFile]; a mismatching download is rejected before loading. */
    val sha256: String? = null
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

/** The current AI Hub catalogue: the newest signed over-the-air list, else [builtInModels]. */
val availableModels: List<ModelConfig> get() = ai.alal.app.inference.ModelCatalog.models

/** Retired models (built-in ones plus any an update removed); offered only when downloaded. */
val retiredModels: List<ModelConfig> get() = ai.alal.app.inference.ModelCatalog.retired

/** Catalogue compiled into this APK. Also the source for `tools/catalog/catalog.sh publish`. */
val builtInModels = listOf(
    // ── High: best answers and summaries ───────────────────────────────────────
    ModelConfig(
        id = "Qwen3.5-9B-Q4_K_M",
        name = "Qwen3.5 9B (Alibaba)",
        ggufFile = "Qwen3.5-9B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/resolve/main/",
        estimatedRamBytes = 9_500_000_000L,
        contextSize = 8192,
        tier = "HIGH",
        strengths = listOf("CHAT", "REASONING", "CODING", "SUMMARIES", "DOCUMENTS", "LANGUAGES"),
        note = "Smartest model here. Flagship phones with 12 GB+ RAM only.",
        sha256 = "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8"
    ),
    ModelConfig(
        id = "Qwen3.5-4B-Q4_K_M",
        name = "Qwen3.5 4B (Alibaba)",
        ggufFile = "Qwen3.5-4B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/",
        estimatedRamBytes = 3_500_000_000L,
        contextSize = 8192,
        tier = "HIGH",
        strengths = listOf("CHAT", "REASONING", "CODING", "SUMMARIES", "DOCUMENTS", "LANGUAGES"),
        note = "Best all-rounder for most phones: answers, reasoning, code and long documents.",
        sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4"
    ),
    ModelConfig(
        id = "gemma-4-E4B-it-Q4_K_M",
        name = "Gemma 4 E4B (Google)",
        ggufFile = "gemma-4-E4B-it-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/",
        estimatedRamBytes = 6_500_000_000L,
        contextSize = 8192,
        tier = "HIGH",
        strengths = listOf("CHAT", "SUMMARIES", "DOCUMENTS", "LANGUAGES"),
        note = "Very natural answers and faithful summaries. Needs 8 GB+ RAM.",
        sha256 = "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87"
    ),
    ModelConfig(
        id = "gemma-4-E2B-it-Q4_K_M",
        name = "Gemma 4 E2B (Google)",
        ggufFile = "gemma-4-E2B-it-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/",
        estimatedRamBytes = 3_800_000_000L,
        contextSize = 8192,
        tier = "HIGH",
        strengths = listOf("CHAT", "SUMMARIES", "LANGUAGES"),
        note = "Strong phone-sized model; clean, faithful summaries.",
        sha256 = "740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8"
    ),
    ModelConfig(
        id = "MiniCPM5-2B-Q4_K_M",
        name = "MiniCPM5 2B (OpenBMB)",
        ggufFile = "MiniCPM5-2B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/openbmb/MiniCPM5-2B-GGUF/resolve/main/",
        estimatedRamBytes = 2_300_000_000L,
        contextSize = 8192,
        tier = "HIGH",
        strengths = listOf("SUMMARIES", "DOCUMENTS", "REASONING", "CODING", "FAST"),
        note = "Top model under 4B; excellent summaries for its small size.",
        sha256 = "ec2d5801640099e97d8d7e8003ad4d81f336e757811f03a26173dddf386602fd"
    ),
    ModelConfig(
        id = "NVIDIA-Nemotron3-Nano-4B-Q4_K_M",
        name = "Nemotron 3 Nano 4B (NVIDIA)",
        ggufFile = "NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Nano-4B-GGUF/resolve/main/",
        estimatedRamBytes = 3_400_000_000L,
        contextSize = 8192,
        tier = "HIGH",
        strengths = listOf("SUMMARIES", "CHAT", "REASONING"),
        note = "Follows formats and rules precisely; reliable to-do extraction.",
        sha256 = "be5d9a656a51922f24f1f09a759cebb694e1f5d9728bf0ef9f8c972c5a0b5ef2"
    ),
    ModelConfig(
        id = "Phi-4-mini-instruct-Q4_K_M",
        name = "Phi-4 Mini (Microsoft)",
        ggufFile = "Phi-4-mini-instruct-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/",
        estimatedRamBytes = 3_000_000_000L,
        contextSize = 4096,
        tier = "HIGH",
        strengths = listOf("REASONING", "CODING", "CHAT"),
        note = "Precise, structured answers; strong at math and code.",
        sha256 = "88c00229914083cd112853aab84ed51b87bdf6b9ce42f532d8c85c7c63b1730a"
    ),
    ModelConfig(
        id = "Ministral-3-8B-Instruct-2512-Q4_K_M",
        name = "Ministral 3 8B (Mistral)",
        ggufFile = "Ministral-3-8B-Instruct-2512-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/mistralai/Ministral-3-8B-Instruct-2512-GGUF/resolve/main/",
        estimatedRamBytes = 6_500_000_000L,
        contextSize = 4096,
        tier = "HIGH",
        strengths = listOf("CHAT", "LANGUAGES", "SUMMARIES"),
        note = "High quality, great in European languages. Needs lots of RAM; slow.",
        sha256 = "33e7a72cf5e6e2cfc2f2847075acc013d68bba023e35310cef86b5cf8fdca761"
    ),

    // ── Mid: good balance of speed and quality ─────────────────────────────────
    ModelConfig(
        id = "Qwen3.5-2B-Q4_K_M",
        name = "Qwen3.5 2B (Alibaba)",
        ggufFile = "Qwen3.5-2B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/",
        estimatedRamBytes = 1_800_000_000L,
        contextSize = 8192,
        tier = "MID",
        strengths = listOf("CHAT", "SUMMARIES", "LANGUAGES", "FAST"),
        note = "Good balance of speed and quality for everyday use.",
        sha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"
    ),
    ModelConfig(
        id = "granite-4.2-3b-Q4_K_M",
        name = "Granite 4.2 3B (IBM)",
        ggufFile = "granite-4.2-3b-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/ibm-granite/granite-4.2-3b-GGUF/resolve/main/",
        estimatedRamBytes = 2_800_000_000L,
        contextSize = 4096,
        tier = "MID",
        strengths = listOf("SUMMARIES", "DOCUMENTS"),
        note = "Business-style, factual summaries; good with documents.",
        sha256 = "e0406663965846ae22a403456eb826ccce5f450840491f71952f18a7cb78e7d5"
    ),
    ModelConfig(
        id = "Ministral-3-3B-Instruct-2512-Q4_K_M",
        name = "Ministral 3 3B (Mistral)",
        ggufFile = "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512-GGUF/resolve/main/",
        estimatedRamBytes = 2_700_000_000L,
        contextSize = 4096,
        tier = "MID",
        strengths = listOf("CHAT", "LANGUAGES"),
        note = "Solid all-rounder with concise answers.",
        sha256 = "9ed150d4367e68df0ac8e1540f6ddc65b42d0ee26378329d1ecbca60f93fc5f8"
    ),
    ModelConfig(
        id = "Llama-3.2-3B-Instruct-Q4_K_M",
        name = "Llama 3.2 3B (Meta)",
        ggufFile = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/",
        estimatedRamBytes = 2_500_000_000L,
        contextSize = 4096,
        tier = "MID",
        strengths = listOf("CHAT"),
        note = "Dependable general chat; weaker on long inputs.",
        sha256 = "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff"
    ),
    ModelConfig(
        id = "SmolLM3-3B-Q4_K_M",
        name = "SmolLM3 3B (HuggingFace)",
        ggufFile = "SmolLM3-3B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/SmolLM3-3B-GGUF/resolve/main/",
        estimatedRamBytes = 2_500_000_000L,
        contextSize = 4096,
        tier = "MID",
        strengths = listOf("CHAT", "REASONING"),
        note = "Decent quality; sometimes drifts from requested formats.",
        sha256 = "4de907d2d388a5508fb7cb443a06effe14cce3518b0a78d3bdd9e74d9edce989"
    ),
    ModelConfig(
        id = "LFM2.5-1.2B-Instruct-Q4_K_M",
        name = "LFM2.5 1.2B (Liquid AI)",
        ggufFile = "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/resolve/main/",
        estimatedRamBytes = 1_200_000_000L,
        contextSize = 4096,
        tier = "MID",
        strengths = listOf("FAST", "CHAT"),
        note = "Very fast; fine for quick questions and brief summaries.",
        sha256 = "b1b3de114215d9507409a662a501a631095a479a419584e8a2ded6304b19b4f5"
    ),

    // ── Low: for older / low-RAM phones ────────────────────────────────────────
    ModelConfig(
        id = "Qwen3.5-0.8B-Q4_K_M",
        name = "Qwen3.5 0.8B (Alibaba)",
        ggufFile = "Qwen3.5-0.8B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/",
        estimatedRamBytes = 1_000_000_000L,
        contextSize = 8192,
        tier = "LOW",
        strengths = listOf("FAST"),
        note = "Tiny and fast for older phones (4 GB RAM); limited knowledge.",
        sha256 = "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517"
    )
)

/**
 * Models dropped from the catalogue. They are only offered when their weights
 * are already on the phone, so nobody loses a model they downloaded.
 */
val retiredBuiltIn = listOf(
    ModelConfig(
        id = "Olmo-3-7B-Instruct-Q4_K_M",
        name = "OLMo 3 7B (Ai2)",
        ggufFile = "Olmo-3-7B-Instruct-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/Olmo-3-7B-Instruct-GGUF/resolve/main/",
        estimatedRamBytes = 5_600_000_000L,
        tier = "RETIRED",
        note = "No longer recommended (a better model replaced it). Kept because it is on your phone."
    ),
    ModelConfig(
        id = "gemma-3n-E2B-it-Q4_K_M",
        name = "Gemma 3n E2B (Google, on-device)",
        ggufFile = "gemma-3n-E2B-it-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/gemma-3n-E2B-it-GGUF/resolve/main/",
        estimatedRamBytes = 3_600_000_000L,
        tier = "RETIRED",
        note = "No longer recommended (a better model replaced it). Kept because it is on your phone."
    ),
    ModelConfig(
        id = "GLM-Edge-4B-Chat-Q4_K_M",
        name = "GLM-Edge 4B (Zhipu)",
        ggufFile = "ggml-model-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/zai-org/glm-edge-4b-chat-gguf/resolve/main/",
        estimatedRamBytes = 3_200_000_000L,
        tier = "RETIRED",
        note = "No longer recommended (a better model replaced it). Kept because it is on your phone."
    ),
    ModelConfig(
        id = "Llama-3.2-1B-Instruct-Q4_K_M",
        name = "Llama 3.2 1B (Meta)",
        ggufFile = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/",
        estimatedRamBytes = 1_200_000_000L,
        tier = "RETIRED",
        note = "No longer recommended (a better model replaced it). Kept because it is on your phone."
    ),
    ModelConfig(
        id = "EXAONE-4.0-1.2B-Q4_K_M",
        name = "EXAONE 4.0 1.2B (LG)",
        ggufFile = "EXAONE-4.0-1.2B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/LGAI-EXAONE/EXAONE-4.0-1.2B-GGUF/resolve/main/",
        estimatedRamBytes = 1_200_000_000L,
        tier = "RETIRED",
        note = "No longer recommended (a better model replaced it). Kept because it is on your phone."
    ),
    ModelConfig(
        id = "GLM-Edge-1.5B-Chat-Q4_K_M",
        name = "GLM-Edge 1.5B (Zhipu)",
        ggufFile = "ggml-model-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/zai-org/glm-edge-1.5b-chat-gguf/resolve/main/",
        estimatedRamBytes = 1_400_000_000L,
        tier = "RETIRED",
        note = "No longer recommended (a better model replaced it). Kept because it is on your phone."
    ),
    ModelConfig(
        id = "Phi-4-mini-reasoning-Q4_K_M",
        name = "Phi-4 Mini Reasoning (Microsoft)",
        ggufFile = "Phi-4-mini-reasoning-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/lmstudio-community/Phi-4-mini-reasoning-GGUF/resolve/main/",
        estimatedRamBytes = 3_000_000_000L,
        tier = "RETIRED",
        note = "No longer recommended (a better model replaced it). Kept because it is on your phone."
    ),
    ModelConfig(
        id = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M",
        name = "DeepSeek R1 1.5B (DeepSeek)",
        ggufFile = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/",
        estimatedRamBytes = 1_600_000_000L,
        tier = "RETIRED",
        note = "No longer recommended (a better model replaced it). Kept because it is on your phone."
    ),
    ModelConfig(
        id = "Zyphra-ZR1-1.5B-Q4_K_M",
        name = "ZR1 1.5B (Zyphra)",
        ggufFile = "Zyphra_ZR1-1.5B-Q4_K_M.gguf",
        baseUrl = "https://huggingface.co/bartowski/Zyphra_ZR1-1.5B-GGUF/resolve/main/",
        estimatedRamBytes = 1_700_000_000L,
        tier = "RETIRED",
        note = "No longer recommended (a better model replaced it). Kept because it is on your phone."
    )
)
