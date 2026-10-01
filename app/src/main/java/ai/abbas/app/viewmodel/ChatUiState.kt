package ai.abbas.app.viewmodel

import ai.abbas.app.data.ModelConfig

data class ChatMessage(
    val id: String,
    val text: String,
    val isUser: Boolean,
    val isGenerating: Boolean = false,
    val thought: String? = null,
    val thoughtTimeMs: Long? = null,
    val generationTimeMs: Long? = null,
    val tokensPerSecond: Float? = null,
    val context: String? = null
)

data class ModelDownloadState(
    val model: ModelConfig,
    val progress: Float,
    val status: String
)

/** Weight folders on disk that no catalogue model claims (leftovers from an older list). */
data class OrphanedWeights(val ids: List<String>, val bytes: Long) {
    val count: Int get() = ids.size
    val megabytes: Long get() = bytes / 1_048_576
}

sealed interface ChatUiState {
    object Initializing : ChatUiState
    data class SelectingModel(val models: List<ModelConfig>) : ChatUiState
    data class LoadingModel(
        val model: ModelConfig,
        val progress: Float,
        val status: String
    ) : ChatUiState
    object Ready : ChatUiState
    object Donating : ChatUiState
    object Incompatible : ChatUiState
    data class Error(val message: String) : ChatUiState
    data class Corrupted(val model: ModelConfig, val message: String) : ChatUiState
}