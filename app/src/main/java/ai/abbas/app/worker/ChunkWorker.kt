package ai.abbas.app.worker

import android.content.Context
import android.os.CancellationSignal
import android.os.CancellationSignal.OnCancelListener
import androidx.annotation.MainThread
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Worker that splits a document (PDF, TXT, MD) into chunks suitable for embedding.
 * Output: a list of chunks stored as a JSON array in outputData.
 */
class ChunkWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_OUTPUT_CHUNKS = "output_chunks"
        const val KEY_CHUNK_SIZE = "chunk_size"
        const val KEY_CHUNK_OVERLAP = "chunk_overlap"
        private const val DEFAULT_CHUNK_SIZE = 256 // tokens approx
        private const val DEFAULT_CHUNK_OVERLAP = 32
    }

    override suspend fun doWork(): Result {
        val inputUri = inputData.getString(KEY_INPUT_URI) ?: return Result.failure()
        val chunkSize = inputData.getInt(KEY_CHUNK_SIZE, DEFAULT_CHUNK_SIZE)
        val chunkOverlap = inputData.getInt(KEY_CHUNK_OVERLAP, DEFAULT_CHUNK_OVERLAP)

        val text = when {
            inputUri.startsWith("file://") -> readFile(File(inputUri.substring("file://".length)))
            inputUri.startsWith("content://") -> readContentUri(applicationContext, inputUri)
            else -> inputUri // treat as raw text
        }

        val chunks = chunkText(text, chunkSize, chunkOverlap)
        val output = Data.Builder().putString(KEY_OUTPUT_CHUNKS, gson.toJson(chunks)).build()
        return Result.success(output)
    }

    private suspend fun readFile(file: File): String = withContext(Dispatchers.Default) {
        file.readText(StandardCharsets.UTF_8)
    }

    private suspend fun readContentUri(context: Context, uriString: String): String = withContext(Dispatchers.Default) {
        val uri = android.net.Uri.parse(uriString)
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val reader = InputStreamReader(stream, StandardCharsets.UTF_8)
            val builder = StringBuilder()
            val buffer = CharArray(8192)
            var read: Int
            while (reader.read(buffer).also { read = it } != -1) {
                builder.append(buffer, 0, read)
            }
            builder.toString()
        } ?: ""
    }

    /**
     * Simple whitespace-based chunking approximating token count.
     * For production, replace with a proper tokenizer (e.g., sentencepiece).
     */
    private fun chunkText(text: String, chunkSize: Int, chunkOverlap: Int): List<String> {
        val words = tokenize(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < words.size) {
            val end = Math.min(start + chunkSize, words.size)
            val chunk = words.subList(start, end).joinToString(" ")
            chunks.add(chunk)
            if (end == words.size) break
            start = end - chunkOverlap
        }
        return chunks
    }

    private fun tokenize(text: String): List<String> {
        // Basic tokenization: split by whitespace and punctuation
        return tokenizeRegex.split(text).filter { it.isNotBlank() }.map { it.trim().lowercase() }
    }

    private val tokenizeRegex = Regex("""[\s\p{Punct}]+""")

    // Simple Gson instance for JSON serialization
    private val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
}