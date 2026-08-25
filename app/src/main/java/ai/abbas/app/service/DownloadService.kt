package ai.abbas.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ai.abbas.app.MainActivity
import ai.abbas.app.R
import ai.abbas.app.data.ModelConfig
import ai.abbas.app.inference.MlcEngineManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class ServiceDownloadState(
    val modelId: String,
    val modelName: String,
    val progress: Float,
    val status: String,
    val done: Boolean = false,
    val error: String? = null
)

class DownloadService : Service() {

    companion object {
        const val ACTION_START_DOWNLOAD = "ai.abbas.app.action.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "ai.abbas.app.action.CANCEL_DOWNLOAD"
        const val ACTION_CANCEL_ALL = "ai.abbas.app.action.CANCEL_ALL"
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_MODEL_BASE_URL = "model_base_url"
        const val EXTRA_MODEL_LIB = "model_lib"
        const val EXTRA_ESTIMATED_VRAM = "estimated_vram_bytes"
        const val EXTRA_HF_TOKEN = "hf_token"

        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1

        // Shared flow for UI progress updates
        private val _progressFlow = MutableSharedFlow<ServiceDownloadState>(replay = 16)
        val progressFlow: SharedFlow<ServiceDownloadState> = _progressFlow.asSharedFlow()

        // Current download states
        private val _currentStates = mutableMapOf<String, ServiceDownloadState>()
        val currentStates: Map<String, ServiceDownloadState> get() = _currentStates.toMap()

        fun startDownload(context: Context, model: ModelConfig, hfToken: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_MODEL_ID, model.id)
                putExtra(EXTRA_MODEL_NAME, model.name)
                putExtra(EXTRA_MODEL_BASE_URL, model.baseUrl)
                putExtra(EXTRA_MODEL_LIB, model.modelLib)
                putExtra(EXTRA_ESTIMATED_VRAM, model.estimatedVramBytes)
                putExtra(EXTRA_HF_TOKEN, hfToken)
            }
            context.startForegroundService(intent)
        }

        fun cancelDownload(context: Context, modelId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            context.startService(intent)
        }

        fun cancelAll(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_ALL
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private var mlcEngineManager: MlcEngineManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val model = intentToModel(intent)
                val hfToken = intent.getStringExtra(EXTRA_HF_TOKEN) ?: ""
                startDownloadTask(model, hfToken)
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return START_NOT_STICKY
                cancelDownloadTask(modelId)
            }
            ACTION_CANCEL_ALL -> {
                cancelAllTasks()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startDownloadTask(model: ModelConfig, hfToken: String) {
        if (jobs.containsKey(model.id)) return

        if (mlcEngineManager == null) {
            mlcEngineManager = MlcEngineManager(applicationContext)
        }

        val state = ServiceDownloadState(model.id, model.name, 0f, "Starting...")
        _currentStates[model.id] = state
        _progressFlow.tryEmit(state)
        updateNotification()

        val job = scope.launch {
            try {
                val mgr = mlcEngineManager!!
                val result = mgr.downloadWeightsOnly(model, hfToken.ifBlank { null }) { progress, status ->
                    val s = state.copy(progress = progress, status = status)
                    _currentStates[model.id] = s
                    _progressFlow.tryEmit(s)
                    updateNotification()
                }
                if (result.isSuccess) {
                    val done = state.copy(progress = 1f, status = "Download complete", done = true)
                    _currentStates[model.id] = done
                    _progressFlow.tryEmit(done)
                } else {
                    val err = result.exceptionOrNull()
                    if (err !is CancellationException) {
                        val failed = state.copy(
                            progress = 1f,
                            status = "Failed",
                            done = true,
                            error = err?.message ?: "Unknown error"
                        )
                        _currentStates[model.id] = failed
                        _progressFlow.tryEmit(failed)
                    }
                }
            } catch (e: CancellationException) {
                _currentStates.remove(model.id)
                _progressFlow.tryEmit(state.copy(status = "Cancelled", done = true))
            } catch (e: Exception) {
                val failed = state.copy(status = "Failed: ${e.message}", done = true, error = e.message)
                _currentStates[model.id] = failed
                _progressFlow.tryEmit(failed)
            }
            jobs.remove(model.id)
            updateNotification()
            stopIfIdle()
        }
        jobs[model.id] = job
    }

    private fun cancelDownloadTask(modelId: String) {
        jobs[modelId]?.cancel()
        jobs.remove(modelId)
        _currentStates.remove(modelId)
        updateNotification()
        stopIfIdle()
    }

    private fun cancelAllTasks() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _currentStates.clear()
        stopSelf()
    }

    private fun stopIfIdle() {
        if (jobs.isEmpty() && _currentStates.all { it.value.done || it.value.error != null }) {
            // All done — keep notification for a moment then stop
            android.os.Handler(mainLooper).postDelayed({ stopSelf() }, 2000)
        }
        if (jobs.isEmpty()) {
            updateNotification()
        }
    }

    private fun updateNotification() {
        val states = _currentStates.values.toList()
        val active = states.count { !it.done }
        val complete = states.count { it.done && it.error == null }
        val failed = states.count { it.error != null }

        val title = when {
            active > 1 -> "Downloading $active models..."
            active == 1 -> "Downloading ${states.first { !it.done }.modelName}"
            failed > 0 -> "Downloads finished ($complete ok, $failed failed)"
            else -> "Downloads complete"
        }

        val body = when {
            active > 0 -> states.filter { !it.done }
                .joinToString("\n") { "${it.modelName}: ${(it.progress * 100).toInt()}%" }
            else -> states.joinToString(", ") { "${it.modelName}: ${if (it.error != null) "Failed" else "Done"}" }
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(active > 0)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress of model weight downloads"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun intentToModel(intent: Intent): ModelConfig {
        return ModelConfig(
            id = intent.getStringExtra(EXTRA_MODEL_ID) ?: "",
            name = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "",
            modelLib = intent.getStringExtra(EXTRA_MODEL_LIB) ?: "",
            baseUrl = intent.getStringExtra(EXTRA_MODEL_BASE_URL) ?: "",
            estimatedVramBytes = intent.getLongExtra(EXTRA_ESTIMATED_VRAM, 0L)
        )
    }
}