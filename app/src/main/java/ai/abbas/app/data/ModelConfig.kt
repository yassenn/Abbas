package ai.abbas.app.data

/**
 * Model configuration for llama.cpp GGUF models.
 *
 * [ggufFile] is the filename within the baseUrl directory (typically "*.gguf").
 * [baseUrl] should be the HuggingFace resolve/main/ URL.
 * [estimatedRamBytes] is used for device capability check (total RAM >= this).
 * [contextSize] is the context window size for this model.
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

val availableModels = listOf(
    ModelConfig(
        id = "Qwen2.5-1.5B-Instruct-Q4_K_M",
        name = "Qwen 2.5 1.5B (Alibaba)",
        ggufFile = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        baseUrl = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/",
        estimatedRamBytes = 2000000000L,
        contextSize = 4096,
        isCustom = false
    ),
    ModelConfig(
        id = "Qwen2.5-3B-Instruct-Q4_K_M",
        name = "Qwen 2.5 3B (Alibaba)",
        ggufFile = "qwen2.5-3b-instruct-q4_k_m.gguf",
        baseUrl = "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/",
        estimatedRamBytes = 3000000000L,
        contextSize = 4096,
        isCustom = false
    ),
    ModelConfig(
        id = "Llama-3.2-3B-Instruct-Q4_K_M",
        name = "Llama 3.2 3B (Meta)",
        ggufFile = "llama-3.2-3b-instruct-q4_k_m.gguf",
        baseUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/",
        estimatedRamBytes = 3000000000L,
        contextSize = 4096,
        isCustom = false
    ),
    ModelConfig(
        id = "Phi-3.5-mini-instruct-Q4_K_M",
        name = "Phi-3.5 Mini (Microsoft)",
        ggufFile = "phi-3.5-mini-instruct-q4_k_m.gguf",
        baseUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/",
        estimatedRamBytes = 3000000000L,
        contextSize = 4096,
        isCustom = false
    ),
    ModelConfig(
        id = "Phi-4-mini-instruct-Q4_K_M",
        name = "Phi-4 Mini (Microsoft)",
        ggufFile = "phi-4-mini-instruct-q4_k_m.gguf",
        baseUrl = "https://huggingface.co/bartowski/Phi-4-mini-instruct-GGUF/resolve/main/",
        estimatedRamBytes = 3000000000L,
        contextSize = 4096,
        isCustom = false
    ),
    ModelConfig(
        id = "Gemma-2-2B-it-Q4_K_M",
        name = "Gemma 2 2B (Google)",
        ggufFile = "gemma-2-2b-it-q4_k_m.gguf",
        baseUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/",
        estimatedRamBytes = 2000000000L,
        contextSize = 4096,
        isCustom = false
    ),
    ModelConfig(
        id = "Mistral-7B-Instruct-v0.3-Q4_K_M",
        name = "Mistral 7B v0.3",
        ggufFile = "mistral-7b-instruct-v0.3-q4_k_m.gguf",
        baseUrl = "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/",
        estimatedRamBytes = 5500000000L,
        contextSize = 4096,
        isCustom = false
    )
)