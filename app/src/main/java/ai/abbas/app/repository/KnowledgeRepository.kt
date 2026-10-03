package ai.abbas.app.repository

import ai.abbas.app.data.*
import ai.abbas.app.storage.SimpleVectorStore
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min
import kotlin.system.measureTimeMillis

/**
 * Repository that manages the knowledge base: ingestion, storage, and retrieval.
 * All heavy work is offloaded to Dispatchers.Default via coroutines.
 */
class KnowledgeRepository private constructor(
    private val context: Context,
    private val appDatabase: AppDatabase,
    private val dao: KnowledgeDao,
    private val vectorStore: SimpleVectorStore,
    private val tfliteInterpreter: Interpreter?,
    private val inputTensorBuffer: TensorBuffer?,
    private val outputTensorBuffer: TensorBuffer?,
    private val vectorDim: Int = 384
) {
    companion object {
        @Volatile private var INSTANCE: KnowledgeRepository? = null

        fun getInstance(context: Context): KnowledgeRepository {
            return INSTANCE ?: synchronized(this) {
                val db = AppDatabase.getInstance(context)
                val dao = db.knowledgeDao()
                val vectorStore = SimpleVectorStore(context, 384) // Example dimension for MiniLM
                val (interpreter, inputTB, outputTB) = initTflite(context)
                val instance = KnowledgeRepository(
                    context.applicationContext,
                    db,
                    dao,
                    vectorStore,
                    interpreter,
                    inputTB,
                    outputTB,
                    384
                )
                INSTANCE = instance
                instance
            }
        }

        private fun initTflite(context: Context): Triple<Interpreter?, TensorBuffer?, TensorBuffer?> {
            val options = Interpreter.Options().setNumThreads(2)
            // CPU-only — NNAPI delegates to GPU on Snapdragon even with hw accel off
            return try {
                val modelFile = loadModelFile(context, "embedder.tflite")
                val interpreter = Interpreter(modelFile, options)
                val inputShape = interpreter.getInputTensor(0).shape()
                val outputShape = interpreter.getOutputTensor(0).shape()
                val inputTB = TensorBuffer.createFixedSize(inputShape, DataType.FLOAT32)
                val outputTB = TensorBuffer.createFixedSize(outputShape, DataType.FLOAT32)
                Triple(interpreter, inputTB, outputTB)
            } catch (e: IllegalArgumentException) {
                // Invalid or missing model – fall back to dummy embeddings
                Triple(null, null, null)
            }
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

    /**
     * Add a document from a URI (content:// or file://). Returns the document ID.
     * The operation is performed off‑main thread.
     */
    suspend fun addDocument(uri: Uri): Long = withContext(Dispatchers.Default) {
        val displayName = getDisplayName(uri)
        val mimeType = context.contentResolver.getType(uri)
        val rawText = DocumentTextExtractor.extract(context, uri, displayName, mimeType)
        if (rawText.isBlank()) {
            throw DocumentExtractionException("No readable text found in \"$displayName\".")
        }
        val document = DocumentEntity(
            title = displayName,
            contentUri = uri.toString()
        )
        val docId = dao.insertDocument(document)

        // Chunking
        val chunks = chunkText(rawText) // List<String>
        val chunkEntities = chunks.mapIndexed { index, text ->
            ChunkEntity(
                docId = docId,
                ordinal = index,
                text = text
            )
        }
        // Insert chunks and get their IDs
        val chunkIds = chunkEntities.map { chunk ->
            dao.insertChunk(chunk)
        }

        // Embed chunks
        val embeddings = chunkIds.mapIndexed { index, chunkId ->
            val text = chunks[index]
            val embedding = embedText(text) // FloatArray
            Pair(chunkId, embedding)
        }

        // Store vectors in our simple index
        embeddings.forEach { (chunkId, vector) ->
            vectorStore.add(chunkId, vector)
        }

        // Optionally persist vectors to disk (omitted for brevity)

        return@withContext docId
    }

    /**
     * Search the knowledge base for a query and return top‑k chunk texts.
     */
    suspend fun search(query: String, topK: Int = 3): List<String> = withContext(Dispatchers.Default) {
        val queryVector = embedText(query)
        val topChunkIds = vectorStore.search(queryVector, topK)
        if (topChunkIds.isEmpty()) return@withContext emptyList()
        val chunkEntities = dao.getChunksByIds(topChunkIds)
        return@withContext chunkEntities.map { it.text }
    }

    private fun embedText(text: String): FloatArray {
        if (tfliteInterpreter == null || inputTensorBuffer == null || outputTensorBuffer == null) {
            // Fallback: simple keyword-hash bag-of-words embedding (deterministic, L2-normalized)
            return embedTextFallback(text)
        }
        // Simple tokenization: split by whitespace and pad/truncate to fixed length
        // Replace with proper tokenizer (e.g., Wordpiece) for real model.
        val tokens = tokenize(text)
        val inputArray = FloatArray(inputTensorBuffer.buffer.capacity() / 4) { 0f }
        for (i in tokens.indices.take(inputArray.size)) {
            // Dummy feature: hash of token normalized to [0,1]
            val hash = tokens[i].hashCode().toInt()
            inputArray[i] = (hash and Integer.MAX_VALUE).toFloat() / Integer.MAX_VALUE
        }
        inputTensorBuffer.loadArray(inputArray)
        tfliteInterpreter.run(inputTensorBuffer.buffer, outputTensorBuffer.buffer)
        val output = outputTensorBuffer.floatArray
        return output.clone()
    }

    private fun embedTextFallback(text: String): FloatArray {
        val vector = FloatArray(vectorDim) { 0f }
        val tokens = tokenize(text)
        for (token in tokens) {
            val idx = kotlin.math.floor(token.hashCode().toDouble() % vectorDim).toInt()
            val safeIdx = if (idx < 0) idx + vectorDim else idx
            vector[safeIdx] += 1f
        }
        val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
        return vector
    }

    private fun tokenize(text: String): List<String> {
        return tokenizeRegex.split(text.lowercase()).filter { it.isNotBlank() }
    }

    private val tokenizeRegex = Regex("""[\s\p{Punct}]+""")

    private fun getDisplayName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            } ?: uri.lastPathSegment ?: "Unknown"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "Unknown"
        }
    }

    private fun chunkText(text: String): List<String> {
        // Simple whitespace chunking aiming for ~256 tokens (approx)
        val words = tokenize(text)
        val chunkSize = 200 // words per chunk
        val overlap = 20
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < words.size) {
            val end = minOf(start + chunkSize, words.size)
            val chunk = words.subList(start, end).joinToString(" ")
            chunks.add(chunk)
            if (end == words.size) break
            start = end - overlap
        }
        return chunks
    }
}