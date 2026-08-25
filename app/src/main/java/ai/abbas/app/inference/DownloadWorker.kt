package ai.abbas.app.inference

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.workDataOf
import ai.abbas.app.inference.ModelDownloader
import com.google.gson.JsonObject
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_MODEL_ID = "modelId"
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_TOKEN = "token"
        const val KEY_OUTPUT_DIR = "outputDir"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val baseUrl = inputData.getString(KEY_BASE_URL) ?: return Result.failure()
        val token = inputData.getString(KEY_TOKEN)
        val outputDirString = inputData.getString(KEY_OUTPUT_DIR) ?: return Result.failure()
        val outputDir = File(outputDirString)

        if (!outputDir.exists()) {
            if (!outputDir.mkdirs()) {
                return Result.failure()
            }
        }

        try {
            val downloader = ModelDownloader(token)
            // Collect progress and update WorkManager
            downloader.downloadModel(outputDir, baseUrl).collect { progress ->
                val progressPercent = (progress.progress * 100).toInt()
                val data = workDataOf(
                    KEY_PROGRESS to progressPercent,
                    KEY_STATUS to progress.status
                )
                setProgressAsync(data)
            }
            return Result.success()
        } catch (e: Exception) {
            val errorData = workDataOf(KEY_ERROR to (e.message ?: "Unknown error" as Any?))
            return Result.failure(errorData)
        }
    }
}