package ai.abbas.app.storage

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Simple vector store that keeps embeddings in memory and persists to disk.
 * For production, replace with a memory-mapped FAISS index (IVF-PQ or HNSW) via JNI.
 */
class SimpleVectorStore(private val context: Context, private val vectorDim: Int) {
    companion object {
        private const val VECTOR_FILE_NAME = "vectors.bin"
        private const val IDS_FILE_NAME = "ids.txt"
    }

    // In-memory storage: list of vectors parallel to chunkIds list
    private val vectors = mutableListOf<FloatArray>()
    private val chunkIds = mutableListOf<Long>() // corresponds to ChunkEntity row id

    init {
        loadFromDisk()
    }

    /** Add a vector with its associated chunkId */
    fun add(chunkId: Long, vector: FloatArray) {
        require(vector.size == vectorDim) { "Vector dimension mismatch. Expected \$vectorDim, got \${vector.size}" }
        vectors.add(vector.clone())
        chunkIds.add(chunkId)
        // Persist immediately (could be batched for performance)
        saveToDisk()
    }

    /** Search top-k similar vectors to query vector using dot product (assuming normalized vectors) */
    fun search(query: FloatArray, k: Int): List<Long> {
        if (vectors.isEmpty()) return emptyList()
        require(query.size == vectorDim) { "Query vector dimension mismatch" }
        val scored = chunkIds.mapIndexed { idx, id ->
            val score = dotProduct(vectors[idx], query)
            Pair(id, score)
        }
        return scored.sortedByDescending { pair -> pair.second }
            .take(k)
            .map { pair: Pair<Long, Float> -> pair.first }
    }

    /** Clear all data (used on logout or reset) */
    fun clear() {
        vectors.clear()
        chunkIds.clear()
        deleteFiles()
    }

    /** Number of vectors stored */
    fun size(): Int = vectors.size

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector dimensions must match" }
        var sum = 0.0f
        for (i in a.indices) {
            sum += a[i] * b[i]
        }
        return sum
    }

    private fun saveToDisk() {
        // Save vectors as contiguous float32 array
        val vectorFile = File(context.filesDir, VECTOR_FILE_NAME)
        val totalFloats = vectors.size * vectorDim
        val byteBuffer = ByteBuffer.allocateDirect(totalFloats * 4)
            .order(ByteOrder.nativeOrder())
        for (vec in vectors) {
            for (f in vec) {
                byteBuffer.putFloat(f)
            }
        }
        byteBuffer.rewind()
        FileOutputStream(vectorFile).use { fos ->
            val channel = fos.channel
            channel.write(byteBuffer)
        }

        // Save IDs as UTF-8 lines (one per line)
        val idsFile = File(context.filesDir, IDS_FILE_NAME)
        FileOutputStream(idsFile).use { fos ->
            val idsText = chunkIds.joinToString(separator = "\n")
            fos.write(idsText.toByteArray())
        }
    }

    private fun loadFromDisk() {
        val vectorFile = File(context.filesDir, VECTOR_FILE_NAME)
        val idsFile = File(context.filesDir, IDS_FILE_NAME)
        if (!vectorFile.exists() || !idsFile.exists()) return

        try {
            // Load vectors — file stores raw bytes: vectors.size * vectorDim * 4 bytes.
            val byteBuffer = ByteBuffer.allocate(vectorFile.length().toInt())
                .order(ByteOrder.nativeOrder())
            FileInputStream(vectorFile).use { fis ->
                val channel = fis.channel
                channel.read(byteBuffer)
            }
            byteBuffer.rewind()
            val floatBuffer = byteBuffer.asFloatBuffer()
            vectors.clear()
            for (i in 0 until vectorFile.length() / (vectorDim * 4)) {
                val vec = FloatArray(vectorDim)
                floatBuffer.get(vec)
                vectors.add(vec)
            }

            // Load IDs
            val idsText = FileInputStream(idsFile).bufferedReader().use { it.readText() }
            chunkIds.clear()
            if (idsText.isNotBlank()) {
                val idsList = idsText.split("\n").filter { it.isNotBlank() }.map { it.toLong() }
                chunkIds.addAll(idsList)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Corrupt files – reset
            vectors.clear()
            chunkIds.clear()
        }
    }

    private fun deleteFiles() {
        File(context.filesDir, VECTOR_FILE_NAME).delete()
        File(context.filesDir, IDS_FILE_NAME).delete()
    }
}