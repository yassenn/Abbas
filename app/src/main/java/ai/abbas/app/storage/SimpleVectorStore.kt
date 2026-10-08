package ai.abbas.app.storage

import android.content.Context
import ai.abbas.app.data.SecurityUtils
import androidx.security.crypto.EncryptedFile
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Simple vector store that keeps embeddings in memory and persists to disk.
 * For production, replace with a memory-mapped FAISS index (IVF-PQ or HNSW) via JNI.
 *
 * On disk both the float vectors and the chunk-id list are written through
 * [EncryptedFile] (Keystore-backed AES-256-GCM + HMAC), so the embeddings — which
 * encode the semantic content of ingested documents — are never stored in cleartext.
 */
class SimpleVectorStore(private val context: Context, private val vectorDim: Int) {
    companion object {
        private const val VECTOR_FILE_NAME = "vectors.bin"
        private const val IDS_FILE_NAME = "ids.txt"
    }

    // In-memory storage: list of vectors parallel to chunkIds list
    private val vectors = mutableListOf<FloatArray>()
    private val chunkIds = mutableListOf<Long>() // corresponds to ChunkEntity row id

    private val masterKey = SecurityUtils.getMasterKey(context)

    init {
        loadFromDisk()
    }

    private fun encryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()

    /** Add a vector with its associated chunkId */
    fun add(chunkId: Long, vector: FloatArray) {
        require(vector.size == vectorDim) { "Vector dimension mismatch. Expected $vectorDim, got ${vector.size}" }
        vectors.add(vector.clone())
        chunkIds.add(chunkId)
        // Persist immediately (could be batched for performance)
        saveToDisk()
    }

    /** Add many vectors at once, persisting only once at the end. */
    fun addAll(entries: List<Pair<Long, FloatArray>>) {
        if (entries.isEmpty()) return
        for ((chunkId, vector) in entries) {
            require(vector.size == vectorDim) { "Vector dimension mismatch. Expected $vectorDim, got ${vector.size}" }
            vectors.add(vector.clone())
            chunkIds.add(chunkId)
        }
        saveToDisk()
    }

    /** The stored embedding for a chunk id, or null if it is not indexed. */
    fun getVector(chunkId: Long): FloatArray? {
        val idx = chunkIds.indexOf(chunkId)
        return if (idx >= 0) vectors[idx].clone() else null
    }

    /**
     * Search top-k similar vectors to query vector using dot product (assuming normalized vectors).
     * When [allowedChunkIds] is non-null only those chunk ids are scored, which is how a chat
     * restricts retrieval to its own documents.
     */
    fun search(query: FloatArray, k: Int, allowedChunkIds: Set<Long>? = null): List<Long> {
        if (vectors.isEmpty()) return emptyList()
        require(query.size == vectorDim) { "Query vector dimension mismatch" }
        val scored = chunkIds.mapIndexedNotNull { idx, id ->
            if (allowedChunkIds != null && id !in allowedChunkIds) return@mapIndexedNotNull null
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

    /** Drop the vectors for the given chunk ids (e.g. when a document is deleted). */
    fun removeChunks(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val keep = chunkIds.indices.filter { chunkIds[it] !in ids }
        val keptVectors = keep.map { vectors[it] }
        val keptIds = keep.map { chunkIds[it] }
        vectors.clear(); vectors.addAll(keptVectors)
        chunkIds.clear(); chunkIds.addAll(keptIds)
        if (vectors.isEmpty()) deleteFiles() else saveToDisk()
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
        // EncryptedFile.openFileOutput() refuses to overwrite an existing file,
        // so every rewrite first clears its target (see writeEncrypted).
        val vectorFile = File(context.filesDir, VECTOR_FILE_NAME)
        val byteBuffer = ByteBuffer
            .allocate(vectors.size * vectorDim * 4)
            .order(ByteOrder.nativeOrder())
        for (vec in vectors) {
            for (f in vec) {
                byteBuffer.putFloat(f)
            }
        }
        writeEncrypted(vectorFile) { it.write(byteBuffer.array(), 0, byteBuffer.position()) }

        // Save IDs as UTF-8 lines (one per line)
        val idsFile = File(context.filesDir, IDS_FILE_NAME)
        writeEncrypted(idsFile) { it.write(chunkIds.joinToString("\n").toByteArray(Charsets.UTF_8)) }
    }

    /** Write an encrypted file from scratch, deleting any previous copy first. */
    private fun writeEncrypted(file: File, block: (OutputStream) -> Unit) {
        if (file.exists()) file.delete()
        encryptedFile(file).openFileOutput().use(block)
    }

    private fun loadFromDisk() {
        val vectorFile = File(context.filesDir, VECTOR_FILE_NAME)
        val idsFile = File(context.filesDir, IDS_FILE_NAME)
        if (!vectorFile.exists() || !idsFile.exists()) return

        try {
            // Load vectors — payload stores raw bytes: vectors.size * vectorDim * 4 bytes.
            val vectorBytes = encryptedFile(vectorFile).openFileInput().use { it.readBytes() }
            val byteBuffer = ByteBuffer.wrap(vectorBytes).order(ByteOrder.nativeOrder())
            val floatBuffer = byteBuffer.asFloatBuffer()
            vectors.clear()
            for (i in 0 until vectorBytes.size / (vectorDim * 4)) {
                val vec = FloatArray(vectorDim)
                floatBuffer.get(vec)
                vectors.add(vec)
            }

            // Load IDs
            val idsText = encryptedFile(idsFile).openFileInput().use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            chunkIds.clear()
            if (idsText.isNotBlank()) {
                val idsList = idsText.split("\n").filter { it.isNotBlank() }.map { it.toLong() }
                chunkIds.addAll(idsList)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Corrupt or legacy (plaintext) files – drop them and reset.
            vectors.clear()
            chunkIds.clear()
            deleteFiles()
        }
    }

    private fun deleteFiles() {
        File(context.filesDir, VECTOR_FILE_NAME).delete()
        File(context.filesDir, IDS_FILE_NAME).delete()
    }
}
