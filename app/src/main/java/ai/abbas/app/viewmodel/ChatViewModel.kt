package ai.abbas.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ai.abbas.app.inference.LlamaEngineManager
import ai.abbas.app.data.MessageDao
import ai.abbas.app.data.MessageEntity
import ai.abbas.app.data.ChatSessionEntity
import ai.abbas.app.data.availableModels
import ai.abbas.app.data.ModelConfig
import ai.abbas.app.data.GenerationSettings
import ai.abbas.app.data.PaymentConfig
import ai.abbas.app.data.StripePaymentConfig
import ai.abbas.app.data.PayPalPaymentConfig
import ai.abbas.app.data.AppDatabase
import ai.abbas.app.repository.KnowledgeRepository
import ai.abbas.app.repository.WebSearchRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.net.Uri
import ai.abbas.app.service.DownloadService
import ai.abbas.app.service.ServiceDownloadState
import java.util.UUID

data class ChatSession(
    val id: String,
    val title: String,
    val lastMessage: String,
    val timestamp: Long
)

class ChatViewModel(
    private val context: android.content.Context,
    private val llmEngineManager: LlamaEngineManager,
    private val messageDao: MessageDao,
    private val knowledgeRepository: KnowledgeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Initializing)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _errors = MutableSharedFlow<String>()
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("ChatViewModel", "Unhandled exception", throwable)
        viewModelScope.launch {
            _errors.emit(throwable.localizedMessage ?: "An unexpected error occurred")
            // Also update UI state if we are not in Ready mode (e.g. during initial loading)
            if (_uiState.value !is ChatUiState.Ready) {
                _uiState.value = ChatUiState.Error(throwable.localizedMessage ?: "An unexpected error occurred")
            }
        }
    }

    val currentModel: StateFlow<ModelConfig?> = llmEngineManager.currentModel

    private val prefs = ai.abbas.app.data.SecurityUtils.getEncryptedPrefs(context)
    private val customModelsKey = "custom_models_json"

    private val _isWebSearchEnabled = MutableStateFlow(false)
    val isWebSearchEnabled: StateFlow<Boolean> = _isWebSearchEnabled.asStateFlow()

    fun toggleWebSearch() {
        _isWebSearchEnabled.value = !_isWebSearchEnabled.value
    }

    // User-tunable sampling parameters (temperature, top_p, penalties, seed, max_tokens).
    // Persisted so the user's choices survive app restarts.
    private val _generationSettings = MutableStateFlow(loadGenerationSettings())
    val generationSettings: StateFlow<GenerationSettings> = _generationSettings.asStateFlow()

    fun updateGenerationSettings(settings: GenerationSettings) {
        _generationSettings.value = settings
        prefs.edit()
            .putFloat("gen_temperature", settings.temperature)
            .putFloat("gen_top_p", settings.topP)
            .putFloat("gen_frequency_penalty", settings.frequencyPenalty)
            .putFloat("gen_presence_penalty", settings.presencePenalty)
            .putInt("gen_max_tokens", settings.maxTokens)
            .putInt("gen_seed", settings.seed ?: -1)
            .apply()
    }

    private fun loadGenerationSettings(): GenerationSettings {
        return GenerationSettings(
            temperature = prefs.getFloat("gen_temperature", 0.7f),
            topP = prefs.getFloat("gen_top_p", 0.9f),
            frequencyPenalty = prefs.getFloat("gen_frequency_penalty", 0.0f),
            presencePenalty = prefs.getFloat("gen_presence_penalty", 0.0f),
            maxTokens = prefs.getInt("gen_max_tokens", 512),
            seed = prefs.getInt("gen_seed", -1).takeIf { it >= 0 }
        )
    }

    fun isModelDownloaded(model: ModelConfig): Boolean {
        return llmEngineManager.isModelDownloaded(model)
    }

    fun isModelAvailable(model: ModelConfig): Boolean {
        return llmEngineManager.isModelDownloaded(model) || llmEngineManager.isModelInAssets(model)
    }

    fun isModelCapable(model: ModelConfig): Boolean {
        return llmEngineManager.isDeviceCapable(model)
    }

    private val _dbMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _generatingMessage = MutableStateFlow<ChatMessage?>(null)

    val messages: StateFlow<List<ChatMessage>> = combine(
        _dbMessages,
        _generatingMessage
    ) { dbMsgs, genMsg ->
        if (genMsg != null) dbMsgs + genMsg else dbMsgs
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val llmHistory = mutableListOf<LlamaEngineManager.ChatMessage>()
    private var messagesJob: Job? = null
    private var initJob: Job? = null

    // Multi-download state
    private val _downloadStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = _downloadStates.asStateFlow()
    private val downloadJobs = mutableMapOf<String, Job>()

    // Reactive set of model IDs whose weights are present on disk.
    // Backs the AI Hub download badges and the Chat tab's initialize picker.
    private val _downloadedModelIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModelIds: StateFlow<Set<String>> = _downloadedModelIds.asStateFlow()

    /** Re-scan the filesystem for downloaded weights. Call after any download/delete. */
    fun refreshDownloadedModels() {
        _downloadedModelIds.value = allModels.value
            .filter { llmEngineManager.isModelDownloaded(it) || llmEngineManager.isModelInAssets(it) }
            .map { it.id }
            .toSet()
    }

    // Orphaned weights: folders left behind by models dropped from the catalogue.
    private val _orphanedWeights = MutableStateFlow(OrphanedWeights(emptyList(), 0L))
    val orphanedWeights: StateFlow<OrphanedWeights> = _orphanedWeights.asStateFlow()

    /** Re-scan for weight folders that no catalogue model claims. */
    fun refreshOrphanedWeights() {
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            val known = allModels.value.map { it.id }.toSet()
            val dirs = llmEngineManager.findOrphanedModelDirs(known)
            val bytes = dirs.sumOf { dir ->
                dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            }
            _orphanedWeights.value = OrphanedWeights(dirs.map { it.name }, bytes)
        }
    }

    /** Delete unused weight folders and report how much space was reclaimed. */
    fun clearOrphanedWeights() {
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            val known = allModels.value.map { it.id }.toSet()
            val freedMb = llmEngineManager.deleteOrphanedModelDirs(known) / 1_048_576
            refreshOrphanedWeights()
            refreshDownloadedModels()
            _errors.emit("Freed $freedMb MB of unused model files")
        }
    }

    private val _customModels = MutableStateFlow<List<ModelConfig>>(emptyList())

    private val _hfToken = MutableStateFlow(prefs.getString("hf_token", "") ?: "")
    val hfToken: StateFlow<String> = _hfToken.asStateFlow()

    fun updateHfToken(token: String) {
        _hfToken.value = token
        prefs.edit().putString("hf_token", token).apply()
    }

    val allModels: StateFlow<List<ModelConfig>> = combine(
        MutableStateFlow(availableModels),
        _customModels
    ) { predefined, custom -> predefined + custom }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        availableModels
    )

    init {
        // Load custom models
        val customJson = prefs.getString(customModelsKey, "[]")
        val type = object : com.google.gson.reflect.TypeToken<List<ModelConfig>>() {}.type
        _customModels.value = com.google.gson.Gson().fromJson(customJson, type)

        loadSessions()
        refreshDownloadedModels()
        refreshOrphanedWeights()
        observeDownloadService()

        val compatibleModels = allModels.value.filter { llmEngineManager.isDeviceCapable(it) }
        val lastModelId = prefs.getString("last_model_id", null)
        android.util.Log.d("Abbas", "Init: allModels=${allModels.value.map{it.id}}, compatibleModels=${compatibleModels.map{it.id}}, lastModelId=$lastModelId")

        if (compatibleModels.isEmpty()) {
            _uiState.value = ChatUiState.Incompatible
        } else {
            val lastModelId = prefs.getString("last_model_id", null)
            val lastModel = compatibleModels.find { it.id == lastModelId }

            if (lastModel != null && isModelAvailable(lastModel)) {
                initializeEngine(lastModel)
            } else {
                // No downloaded model — show the model picker so the user can choose
                // and download one. Also clear stale lastModelId so we don't loop.
                if (lastModel != null && !isModelAvailable(lastModel)) {
                    prefs.edit().remove("last_model_id").apply()
                }
                _uiState.value = ChatUiState.SelectingModel(compatibleModels)
            }
        }
    }

    fun addCustomModel(model: ModelConfig) {
        val newList = _customModels.value + model
        _customModels.value = newList
        val json = com.google.gson.Gson().toJson(newList)
        prefs.edit().putString(customModelsKey, json).apply()
        refreshDownloadedModels()
        refreshOrphanedWeights()
    }

    private fun loadSessions() {
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            messageDao.getAllSessions().collect { entities ->
                _sessions.value = entities.map {
                    ChatSession(it.id, it.title, it.lastMessage, it.timestamp)
                }
            }
        }
    }

    /** Called from the model dropdown in the Chat tab. */
    fun onModelSelected(model: ModelConfig) {
        if (isModelAvailable(model)) {
            synchronized(llmHistory) { llmHistory.clear() }
            initializeEngine(model)
        } else {
            // Weights are not on device. Downloading is a separate step handled in
            // the AI Hub tab — never triggered implicitly from chat.
            viewModelScope.launch {
                _errors.emit("${model.name} is not downloaded. Download it from the AI Hub tab first.")
            }
        }
    }

    /**
     * Load a model into the engine. Weights must already be on disk — this
     * never downloads. Download is a separate step in the AI Hub tab.
     */
    fun initModel(model: ModelConfig) {
        if (!isModelAvailable(model)) {
            viewModelScope.launch {
                _errors.emit("${model.name} is not downloaded yet. Download it from the AI Hub tab first.")
            }
            val compatibleModels = allModels.value.filter { llmEngineManager.isDeviceCapable(it) }
            _uiState.value = ChatUiState.SelectingModel(compatibleModels)
            return
        }
        synchronized(llmHistory) { llmHistory.clear() }
        initializeEngine(model)
    }

    /**
     * Delete downloaded weights for a model. Used by the AI Hub tab. The model
     * currently loaded into the engine cannot be deleted.
     */
    fun deleteModel(model: ModelConfig) {
        if (llmEngineManager.currentModel.value?.id == model.id) {
            viewModelScope.launch { _errors.emit("${model.name} is currently in use. Switch to another model before deleting it.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            llmEngineManager.deleteModelFiles(model)
            _downloadedModelIds.update { it - model.id }
        }
    }

    fun switchModel() {
        val compatibleModels = allModels.value.filter { llmEngineManager.isDeviceCapable(it) }
        _uiState.value = ChatUiState.SelectingModel(compatibleModels)
    }

    fun onCancelModelSelection() {
        if (llmEngineManager.currentModel.value != null) {
            _uiState.value = ChatUiState.Ready
        }
    }

    fun enterDonation() {
        _uiState.value = ChatUiState.Donating
    }

    fun exitDonation() {
        _uiState.value = if (llmEngineManager.currentModel.value != null) ChatUiState.Ready else {
            val compatibleModels = allModels.value.filter { llmEngineManager.isDeviceCapable(it) }
            ChatUiState.SelectingModel(compatibleModels)
        }
    }

    private val _stripePaymentEvent = MutableSharedFlow<StripePaymentConfig>()
    val stripePaymentEvent = _stripePaymentEvent.asSharedFlow()

    private val _payPalPaymentEvent = MutableSharedFlow<PayPalPaymentConfig>()
    val payPalPaymentEvent = _payPalPaymentEvent.asSharedFlow()

    fun processStripeDonation(amount: Double, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            try {
                // Mocking backend call for Stripe
                kotlinx.coroutines.delay(1000)
                val mockConfig = StripePaymentConfig(
                    paymentIntent = "pi_mock_secret_${UUID.randomUUID()}",
                    ephemeralKey = "ek_mock_${UUID.randomUUID()}",
                    customer = "cus_mock_${UUID.randomUUID()}"
                )
                _stripePaymentEvent.emit(mockConfig)
                viewModelScope.launch(Dispatchers.Main) { onResult(true, "Stripe ready") }
            } catch (e: Exception) {
                viewModelScope.launch(Dispatchers.Main) { onResult(false, "Stripe init failed: ${e.message}") }
            }
        }
    }

    fun processPayPalDonation(amount: Double, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            try {
                // Mocking backend call for PayPal
                kotlinx.coroutines.delay(1000)
                val mockConfig = PayPalPaymentConfig(orderId = "PAYID-MOCK-${UUID.randomUUID()}")
                _payPalPaymentEvent.emit(mockConfig)
                viewModelScope.launch(Dispatchers.Main) { onResult(true, "PayPal ready") }
            } catch (e: Exception) {
                viewModelScope.launch(Dispatchers.Main) { onResult(false, "PayPal init failed: ${e.message}") }
            }
        }
    }

    suspend fun fetchPayPalOrderId(amount: Double): String = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val json = JSONObject().apply {
            put("amount", amount)
            put("currency", "USD")
        }
        val body = json.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(PaymentConfig.PAYPAL_BACKEND_URL)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Backend error: ${response.code}")
            val responseData = response.body?.string() ?: throw Exception("Empty response")
            JSONObject(responseData).getString("orderId")
        }
    }

    suspend fun fetchStripeConfig(amount: Double): StripePaymentConfig = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val json = JSONObject().apply {
            put("amount", (amount * 100).toInt())
            put("currency", "usd")
        }
        val body = json.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(PaymentConfig.STRIPE_BACKEND_URL)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Backend error: ${response.code}")
            val responseData = response.body?.string() ?: throw Exception("Empty response")
            val jsonResponse = JSONObject(responseData)

            StripePaymentConfig(
                paymentIntent = jsonResponse.getString("paymentIntent"),
                ephemeralKey = jsonResponse.getString("ephemeralKey"),
                customer = jsonResponse.getString("customer")
            )
        }
    }

    private fun loadMessages(sessionId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            messageDao.getMessagesForSession(sessionId)
                .map { entities ->
                    entities.map { entity ->
                        ChatMessage(
                            id = entity.id,
                            text = entity.text,
                            isUser = entity.isUser,
                            thought = entity.thought,
                            thoughtTimeMs = entity.thoughtTimeMs,
                            generationTimeMs = entity.generationTimeMs,
                            tokensPerSecond = entity.tokensPerSecond?.toFloat()
                        )
                    }
                }
                .flowOn(Dispatchers.Default)
                .collect { chatMessages ->
                    _dbMessages.value = chatMessages

                    viewModelScope.launch(Dispatchers.Default + exceptionHandler) {
                        synchronized(llmHistory) {
                            llmHistory.clear()
                            chatMessages.forEach { msg ->
                                llmHistory.add(LlamaEngineManager.ChatMessage(
                                    role = if (msg.isUser) "user" else "assistant",
                                    content = msg.text
                                ))
                            }
                        }
                    }
                }
        }
    }

    private fun initializeEngine(model: ModelConfig) {
        initJob?.cancel()
        initJob = viewModelScope.launch(exceptionHandler) {
            val isInAssets = llmEngineManager.isModelInAssets(model)
            val initialStatus = if (isInAssets) "Extracting model..." else "Loading model into memory..."

            _uiState.value = ChatUiState.LoadingModel(model, 0f, initialStatus)

            val result = llmEngineManager.initializeEngine(model, _hfToken.value) { progress, status ->
                _uiState.value = ChatUiState.LoadingModel(model, progress, status)
            }
            if (result.isSuccess) {
                prefs.edit().putString("last_model_id", model.id).apply()
                _uiState.value = ChatUiState.Ready
            } else {
                val exception = result.exceptionOrNull()
                when (exception) {
                    is kotlinx.coroutines.CancellationException -> { /* ignore */ }
                    is ai.abbas.app.inference.CorruptedModelException -> {
                        _uiState.value = ChatUiState.Corrupted(model, exception.message ?: "Model files are corrupted")
                    }
                    else -> {
                        val msg = exception?.message ?: "An unexpected error occurred during initialization."
                        val protectedMsg = if (msg.contains("Access Denied", ignoreCase = true)) {
                            "This model is gated. Please visit the Hugging Face URL and accept the license, then try again."
                        } else {
                            msg
                        }
                        _uiState.value = ChatUiState.Error(protectedMsg)
                    }
                }
            }
        }
    }

    /**
     * Delete a corrupted model's files and re-queue the download. Initialization
     * is intentionally skipped here — the user re-loads from the Chat tab once
     * the AI Hub reports the download complete.
     */
    fun deleteCorruptedAndRetry(model: ModelConfig) {
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            llmEngineManager.deleteModelFiles(model)
            _downloadedModelIds.update { it - model.id }
            viewModelScope.launch(Dispatchers.Main) {
                val compatibleModels = allModels.value.filter { llmEngineManager.isDeviceCapable(it) }
                _uiState.value = ChatUiState.SelectingModel(compatibleModels)
                downloadModel(model)
            }
        }
    }

    fun deleteModelWeights(model: ModelConfig) {
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            llmEngineManager.deleteModelFiles(model)
            _downloadedModelIds.update { it - model.id }
            viewModelScope.launch(Dispatchers.Main) {
                val compatibleModels = allModels.value.filter { llmEngineManager.isDeviceCapable(it) }
                _uiState.value = ChatUiState.SelectingModel(compatibleModels)
            }
        }
    }

    /**
     * Start downloading a model's weights without initializing the engine.
     * Multiple models can be downloaded simultaneously. Downloading is a
     * separate concern from loading — see [initModel].
     */
    fun downloadModel(model: ModelConfig) {
        android.util.Log.d("Abbas", "downloadModel called for ${model.id}")
        if (downloadJobs.containsKey(model.id)) { android.util.Log.d("Abbas", "downloadModel: already in downloadJobs"); return }
        val isDownloaded = llmEngineManager.isModelDownloaded(model)
        val isInAssets = llmEngineManager.isModelInAssets(model)
        android.util.Log.d("Abbas", "downloadModel: isDownloaded=$isDownloaded, isInAssets=$isInAssets")
        if (isDownloaded || isInAssets) {
            android.util.Log.d("Abbas", "downloadModel: SKIPPING (already here)")
            return
        }

        // Track locally for UI
        downloadJobs[model.id] = viewModelScope.launch { /* placeholder — service does the work */ }

        _downloadStates.update { it + (model.id to ModelDownloadState(model, 0f, "Starting...")) }

        // Delegate to foreground service for background persistence.
        // NOTE: this never initializes the engine.
        DownloadService.startDownload(context, model, _hfToken.value)
    }

    /**
     * Single long-lived subscriber to [DownloadService.progressFlow]. Updates the
     * download map and refreshes the downloaded-weights set on completion so the
     * AI Hub badges and the Chat tab's initialize picker recompose.
     */
    private fun observeDownloadService() {
        viewModelScope.launch {
            DownloadService.progressFlow.collect { update ->
                val ds = _downloadStates.value[update.modelId]
                if (update.done) {
                    if (update.error != null) {
                        _errors.emit("${update.modelName}: ${update.error}")
                    }
                    _downloadStates.update { it - update.modelId }
                    downloadJobs.remove(update.modelId)
                    refreshDownloadedModels()
                } else {
                    val model = ds?.model ?: return@collect
                    _downloadStates.update {
                        it + (update.modelId to ModelDownloadState(model, update.progress, update.status))
                    }
                }
            }
        }
    }

    fun cancelModelDownload(modelId: String) {
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        _downloadStates.update { it - modelId }
        DownloadService.cancelDownload(context, modelId)
    }

    fun cancelAllDownloads() {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        _downloadStates.value = emptyMap()
        DownloadService.cancelAll(context)
    }

    fun isModelDownloading(modelId: String): Boolean {
        return downloadJobs.containsKey(modelId)
    }

    fun getDownloadState(modelId: String): ModelDownloadState? {
        return _downloadStates.value[modelId]
    }

    fun selectSession(sessionId: String) {
        _currentSessionId.value = sessionId
        loadMessages(sessionId)
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            messageDao.deleteMessagesForSession(sessionId)
            messageDao.deleteSession(sessionId)
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = null
                _dbMessages.value = emptyList()
                synchronized(llmHistory) {
                    llmHistory.clear()
                }
            }
        }
    }

    fun regenerate() {
        val sessionId = _currentSessionId.value ?: return
        val currentMessages = _dbMessages.value
        if (currentMessages.size < 2 || _uiState.value != ChatUiState.Ready) return

        val lastMessage = currentMessages.last()
        if (lastMessage.isUser || _generatingMessage.value != null) return

        val lastUserMessage = currentMessages[currentMessages.size - 2]

        synchronized(llmHistory) {
            if (llmHistory.isNotEmpty() && llmHistory.last().role == "assistant") {
                llmHistory.removeLast()
            }
            if (llmHistory.isNotEmpty() && llmHistory.last().role == "user") {
                llmHistory.removeLast()
            }
        }

        // Remove the old assistant message from UI and DB so the new answer replaces it
        _dbMessages.value = currentMessages.dropLast(1)
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            messageDao.deleteMessage(lastMessage.id)
        }

        generateInternal(lastUserMessage.text, sessionId, context = lastUserMessage.context ?: "")
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value != ChatUiState.Ready) return

        val sessionId = _currentSessionId.value ?: UUID.randomUUID().toString().also { newId ->
            _currentSessionId.value = newId
            val newSession = ChatSessionEntity(
                id = newId,
                title = if (text.length > 30) text.take(27) + "..." else text,
                lastMessage = text
            )
            viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
                messageDao.insertSession(newSession)
            }
            loadMessages(newId)
        }

        val userMessageId = UUID.randomUUID().toString()
        val botMessageId = UUID.randomUUID().toString()

        // --- SHOW USER MESSAGE IMMEDIATELY (before any async work) ---
        val userMessage = ChatMessage(
            id = userMessageId,
            text = text,
            isUser = true,
            context = null
        )
        _dbMessages.value = _dbMessages.value + userMessage

        // --- SHOW "THINKING" PLACEHOLDER IMMEDIATELY (so user knows something is happening) ---
        _generatingMessage.value = ChatMessage(
            id = botMessageId,
            text = "",
            isUser = false,
            isGenerating = true
        )

        // Save user message to DB in background (no .join() — don't block feedback)
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            messageDao.insertMessage(MessageEntity(
                id = userMessageId,
                sessionId = sessionId,
                text = text,
                isUser = true
            ))
            messageDao.updateSession(sessionId, (if (text.length > 30) text.take(27) + "..." else text), text, System.currentTimeMillis())
        }

        // RAG + inference on background thread — never touches Main
        viewModelScope.launch(Dispatchers.Default + exceptionHandler) {
            var webContext = ""
            if (_isWebSearchEnabled.value) {
                webContext = try {
                    val results = withContext(Dispatchers.IO) {
                        WebSearchRepository.search(text, maxResults = 3)
                    }
                    if (results.isNotEmpty()) {
                        buildString {
                            appendLine("Web Search Results:")
                            results.forEachIndexed { i, r ->
                                val line = "[${i + 1}] ${r.title}: ${r.snippet}"
                                appendLine(line)
                            }
                        }.trimEnd()
                    } else {
                        ""
                    }
                } catch (_: Exception) {
                    ""
                }
            }

            val contextChunks = knowledgeRepository.search(text, topK = 3)
            val localContext = if (contextChunks.isNotEmpty()) {
                contextChunks.joinToString(separator = "\n") { it }
            } else {
                ""
            }

            val currentDate = java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))

            val finalContext = buildString {
                appendLine("Current Date: $currentDate")
                if (webContext.isNotBlank()) {
                    appendLine()
                    appendLine(webContext)
                }
                if (localContext.isNotBlank()) {
                    if (isNotEmpty()) appendLine()
                    appendLine("Local Knowledge Sources:")
                    appendLine(localContext)
                }
            }.trim()

            android.util.Log.d(
                "RAG",
                "Retrieved ${contextChunks.size} local chunks. Web search enabled=${_isWebSearchEnabled.value}. Context: $finalContext"
            )
            generateInternal(text, sessionId, botMessageId, finalContext)
        }
    }

    fun ingestDocument(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            android.util.Log.d("RAG", "Starting ingestion for $uri")
            val docId = knowledgeRepository.addDocument(uri)
            android.util.Log.d("RAG", "Ingested document id=$docId")
            // Optionally emit a success event
            _errors.emit("Document ingested (id=$docId)")
        }
    }

    fun stopGeneration() {
        llmEngineManager.stopGeneration()
        val sessionId = _currentSessionId.value ?: return
        val currentGenMsg = _generatingMessage.value
        if (currentGenMsg != null) {
            val updated = currentGenMsg.copy(
                isGenerating = false,
                text = currentGenMsg.text + " [Interrupted]"
            )
            _generatingMessage.value = null
            viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
                messageDao.insertMessage(MessageEntity(
                    id = updated.id,
                    sessionId = sessionId,
                    text = updated.text,
                    isUser = updated.isUser,
                    thought = updated.thought,
                    thoughtTimeMs = updated.thoughtTimeMs,
                    generationTimeMs = updated.generationTimeMs,
                    tokensPerSecond = updated.tokensPerSecond?.toDouble()
                ))
            }
        }
    }

    private fun generateInternal(query: String, sessionId: String, botMessageId: String? = null, context: String = "") {
        val effectiveId = botMessageId ?: UUID.randomUUID().toString()
        // Only set generating message if not already showing (e.g. from regenerate which doesn't pre-set it)
        if (_generatingMessage.value?.id != effectiveId) {
            _generatingMessage.value = ChatMessage(id = effectiveId, text = "", isUser = false, isGenerating = true)
        }

        viewModelScope.launch(Dispatchers.Default + exceptionHandler) {
            val startTimeMs = System.currentTimeMillis()
            var thoughtEndTimeMs: Long? = null
            var tokenCount = 0

            try {
                // Retrieved context is passed as a system message instead of being
                // glued into the user turn ("Context:...\nQuestion:...\nAnswer:"). The
                // old wrapper bled scaffolding into replies and confused small models.
                val systemContext = context.trim()

                // Capture the current history to avoid concurrent modification issues.
                // The current turn's RAW user message may already have been mirrored into
                // llmHistory by the DB collector. Drop it: generateResponse() appends the
                // user query as the current user turn, so keeping both sends two
                // consecutive user messages and the model degenerates into repetition.
                val historySnapshot = synchronized(llmHistory) {
                    llmHistory.toList().let { h ->
                        if (h.isNotEmpty() && h.last().role == "user") h.dropLast(1) else h
                    }
                }

                var fullContent = ""
                var lastUiUpdateTime = 0L

                llmEngineManager.generateResponse(query, historySnapshot, _generationSettings.value, systemContext).collect { chunk ->
                    fullContent += chunk
                    tokenCount += 1

                    val currentTime = System.currentTimeMillis()
                    // Throttle UI updates to every 100ms to prevent UI thread saturation
                    if (currentTime - lastUiUpdateTime > 100) {
                        val split = splitThinking(fullContent)
                        if (thoughtEndTimeMs == null && split.closed) thoughtEndTimeMs = currentTime

                        _generatingMessage.update { message ->
                            message?.copy(
                                text = split.text,
                                thought = split.thought
                            )
                        }
                        lastUiUpdateTime = currentTime
                    }
                }

                // Final update to ensure all tokens are rendered and the trace/answer
                // split reflects the complete output.
                val finalSplit = splitThinking(fullContent)
                _generatingMessage.update { it?.copy(text = finalSplit.text, thought = finalSplit.thought) }

                val endTimeMs = System.currentTimeMillis()
                val totalTimeMs = endTimeMs - startTimeMs

                val finalThoughtTimeMs = when {
                    thoughtEndTimeMs != null -> thoughtEndTimeMs!! - startTimeMs
                    finalSplit.thought != null -> totalTimeMs
                    else -> null
                }

                val generateTimeMs = if (finalThoughtTimeMs != null) totalTimeMs - finalThoughtTimeMs else totalTimeMs
                val tps = if (generateTimeMs > 0) (tokenCount / (generateTimeMs / 1000f)) else (tokenCount / (totalTimeMs / 1000f).coerceAtLeast(0.1f))

                val finalGeneratingMsg = _generatingMessage.value
                if (finalGeneratingMsg != null) {
                    val updated = finalGeneratingMsg.copy(
                        isGenerating = false,
                        thoughtTimeMs = finalThoughtTimeMs,
                        generationTimeMs = generateTimeMs,
                        tokensPerSecond = tps,
                        text = finalGeneratingMsg.text.trim()
                    )

                    _generatingMessage.value = null

                    viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
                        messageDao.insertMessage(MessageEntity(
                            id = updated.id,
                            sessionId = sessionId,
                            text = updated.text,
                            isUser = updated.isUser,
                            thought = updated.thought,
                            thoughtTimeMs = updated.thoughtTimeMs,
                            generationTimeMs = generateTimeMs,
                            tokensPerSecond = updated.tokensPerSecond?.toDouble()
                        ))
                        messageDao.updateSession(sessionId, updated.text.take(30), updated.text.take(100), System.currentTimeMillis())
                    }

                    synchronized(llmHistory) {
                        llmHistory.add(LlamaEngineManager.ChatMessage(role = "user", content = query))
                        llmHistory.add(LlamaEngineManager.ChatMessage(role = "assistant", content = updated.text))
                    }
                }
            } catch (e: Exception) {
                val errorMsg = _generatingMessage.value?.let { it.text + "\n[Error: ${e.message}]" } ?: "[Error: ${e.message}]"
                _generatingMessage.update { it?.copy(text = errorMsg, isGenerating = false) }
            }
        }
    }

    // ── Thinking / answer splitting ──────────────────────────────────

    /**
     * Split streamed output using the loaded model's chat-template markers so the
     * reasoning trace lands in the "Thought" block and only the final answer is
     * shown in the message body. See [ai.abbas.app.viewmodel.splitThinking].
     */
    private fun splitThinking(full: String): ThinkingSplit {
        val tags = llmEngineManager.thinkingTags
        return ai.abbas.app.viewmodel.splitThinking(full, tags.start, tags.ends, tags.supported)
    }

    fun newChat() {
        _currentSessionId.value = null
        _dbMessages.value = emptyList()
        _generatingMessage.value = null
        synchronized(llmHistory) {
            llmHistory.clear()
        }
        messagesJob?.cancel()
    }

    fun clearModelCache() {
        llmEngineManager.clearModelCache()
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            _sessions.value.forEach {
                messageDao.deleteMessagesForSession(it.id)
                messageDao.deleteSession(it.id)
            }
        }
        newChat()
        refreshDownloadedModels()
        refreshOrphanedWeights()
        val compatibleModels = allModels.value.filter { llmEngineManager.isDeviceCapable(it) }
        _uiState.value = ChatUiState.SelectingModel(compatibleModels)
    }

    override fun onCleared() {
        super.onCleared()
        llmEngineManager.release()
    }
}

class ChatViewModelFactory(
    private val context: android.content.Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            val db = ai.abbas.app.data.AppDatabase.getInstance(context)
            val knowledgeRepo = KnowledgeRepository.getInstance(context)
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(
                context,
                LlamaEngineManager(context),
                db.messageDao(),
                knowledgeRepo
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}