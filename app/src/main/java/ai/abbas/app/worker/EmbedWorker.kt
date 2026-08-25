package ai.abbas.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Worker that creates embeddings for text chunks using a TFLite model.
 * Input: JSON array of strings (chunks)
 * Output: JSON array of float arrays (embeddings)
 *
 * Note: This worker is currently unused — KnowledgeRepository performs
 * embedding inline. It is kept for future WorkManager-based batching.
 */
class EmbedWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_INPUT_CHUNKS = "input_chunks"
        const val KEY_OUTPUT_EMBEDDINGS = "output_embeddings"
        const val ASSET_MODEL = "embedder.tflite"
        private const val NUM_THREADS = 2
    }

    // Lazy-initialized to avoid crashing on Worker construction
    // if the TFLite model is missing from assets.
    private var interpreter: Interpreter? = null
    private var inputBuffer: TensorBuffer? = null
    private var outputBuffer: TensorBuffer? = null
    private var initError: String? = null

    init {
        try {
            val options = Interpreter.Options().setNumThreads(NUM_THREADS)
            // CPU-only — NNAPI delegates to GPU on Snapdragon
            interpreter = Interpreter(loadModelFile(appContext, ASSET_MODEL), options)
            val inputShape = interpreter!!.getInputTensor(0).shape()
            val outputShape = interpreter!!.getOutputTensor(0).shape()
            inputBuffer = TensorBuffer.createFixedSize(inputShape, DataType.FLOAT32)
            outputBuffer = TensorBuffer.createFixedSize(outputShape, DataType.FLOAT32)
        } catch (e: Exception) {
            initError = e.message
            android.util.Log.w("EmbedWorker", "TFLite unavailable: ${e.message}")
        }
    }

    override suspend fun doWork(): Result {
        if (interpreter == null) {
            return Result.failure(Data.Builder()
                .putString("error", "Embedding model not available: $initError")
                .build())
        }
        val json = inputData.getString(KEY_INPUT_CHUNKS) ?: return Result.failure()
        val chunks = com.google.gson.Gson().fromJson(json, Array<String>::class.java).toList()
        val embeddings = mutableListOf<FloatArray>()

        for (chunk in chunks) {
            val input = preprocess(chunk)
            inputBuffer!!.loadArray(input)
            interpreter!!.run(inputBuffer!!.buffer, outputBuffer!!.buffer)
            val embed = outputBuffer!!.floatArray.copyOf()
            embeddings.add(embed)
        }

        val outputJson = com.google.gson.Gson().toJson(embeddings)
        val output = Data.Builder().putString(KEY_OUTPUT_EMBEDDINGS, outputJson).build()
        return Result.success(output)
    }

    private fun preprocess(text: String): FloatArray {
        val inputSize = inputBuffer!!.shape.reduce { acc, i -> acc * i }
        val vals = FloatArray(inputSize) { 0f }
        for (i in 0 until Math.min(text.length, inputSize)) {
            val charValue = text.codePointAt(i)
            vals[i] = charValue.toFloat() / 1000f
        }
        return vals
    }

    private fun loadModelFile(context: Context, assetName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(assetName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel: FileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
}
