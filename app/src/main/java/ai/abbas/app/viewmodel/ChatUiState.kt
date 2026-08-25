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

sealed interface ChatUiState {
    object Initializing : ChatUiState
    data class SelectingModel(val models: List<ModelConfig>) : ChatUiState
    data class DownloadingModels(
        val active: Map<String, ModelDownloadState>
    ) : ChatUiState
    object Ready : ChatUiState
    object Donating : ChatUiState
    object Incompatible : ChatUiState
    data class Error(val message: String) : ChatUiState
    data class Corrupted(val model: ModelConfig, val message: String) : ChatUiState
}