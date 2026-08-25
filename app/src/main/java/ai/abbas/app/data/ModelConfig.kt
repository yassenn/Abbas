package ai.abbas.app.data

data class ModelConfig(
    val id: String,
    val name: String,
    val modelLib: String,
    val baseUrl: String,
    val estimatedVramBytes: Long,
    val isCustom: Boolean = false
)

val availableModels = listOf(
    ModelConfig(
        id = "Qwen2.5-1.5B-Instruct-q4f16_1-MLC",
        name = "Qwen 2.5 1.5B (Alibaba)",
        modelLib = "qwen2_q4f16_1_2e221f430380225c03990ad24c3d030e",
        baseUrl = "https://huggingface.co/mlc-ai/Qwen2.5-1.5B-Instruct-q4f16_1-MLC/resolve/main/",
        estimatedVramBytes = 3980990464L,
        isCustom = false
    ),
    ModelConfig(
        id = "Qwen2.5-7B-Instruct-q4f16_1-MLC",
        name = "Qwen 2.5 7B (Alibaba)",
        modelLib = "qwen2_q4f16_1_2e221f430380225c03990ad24c3d030e",
        baseUrl = "https://huggingface.co/mlc-ai/Qwen2.5-7B-Instruct-q4f16_1-MLC/resolve/main/",
        estimatedVramBytes = 6000000000L,
        isCustom = false
    ),
    ModelConfig(
        id = "Phi-3.5-mini-instruct-q4f16_0-MLC",
        name = "Phi-3.5 Mini (Microsoft)",
        modelLib = "phi3_q4f16_0_5fe42298399a05eb2a1878fdc1c8c115",
        baseUrl = "https://huggingface.co/mlc-ai/Phi-3.5-mini-instruct-q4f16_0-MLC/resolve/main/",
        estimatedVramBytes = 4250586449L,
        isCustom = false
    ),
    ModelConfig(
        id = "gemma-4-E2B-it-q4f16_1-MLC",
        name = "Gemma 4 E2B (Google)",
        modelLib = "gemma4_q4f16_1_5cc7dbd3ae3d1040984d9720b2d7b7d4",
        baseUrl = "https://huggingface.co/welcoma/gemma-4-E2B-it-q4f16_1-MLC/resolve/main/",
        estimatedVramBytes = 2500000000L,
        isCustom = false
    ),
    ModelConfig(
        id = "gemma-4-E4B-it-q4f16_1-MLC",
        name = "Gemma 4 E4B (Google)",
        modelLib = "gemma4_q4f16_1_5cc7dbd3ae3d1040984d9720b2d7b7d4",
        baseUrl = "https://huggingface.co/welcoma/gemma-4-E4B-it-q4f16_1-MLC/resolve/main/",
        estimatedVramBytes = 3500000000L,
        isCustom = false
    ),
    ModelConfig(
        id = "Llama-3.2-3B-Instruct-q4f16_0-MLC",
        name = "Llama 3.2 3B (Meta)",
        modelLib = "llama_q4f16_0_2d32572d8a4ab2af20a1f587ef6c8c63",
        baseUrl = "https://huggingface.co/mlc-ai/Llama-3.2-3B-Instruct-q4f16_0-MLC/resolve/main/",
        estimatedVramBytes = 4679979417L,
        isCustom = false
    ),
    ModelConfig(
        id = "Qwen3-8B-q4f16_1-MLC",
        name = "Qwen 3 8B (Alibaba)",
        modelLib = "qwen2_q4f16_1_2e221f430380225c03990ad24c3d030e", // FIXME: needs qwen3 kernel in runtime
        baseUrl = "https://huggingface.co/mlc-ai/Qwen3-8B-q4f16_1-MLC/resolve/main/",
        estimatedVramBytes = 6000000000L,
        isCustom = false
    ),
    ModelConfig(
        id = "Mistral-7B-Instruct-v0.3-q4f16_1-MLC",
        name = "Mistral 7B v0.3",
        modelLib = "mistral_q4f16_1_c2cba77a6def4dd52f7e20b5d8576ab5",
        baseUrl = "https://huggingface.co/mlc-ai/Mistral-7B-Instruct-v0.3-q4f16_1-MLC/resolve/main/",
        estimatedVramBytes = 4115131883L,
        isCustom = false
    )
)
