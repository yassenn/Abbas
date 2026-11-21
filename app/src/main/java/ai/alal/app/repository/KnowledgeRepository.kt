package ai.alal.app.repository

import ai.alal.app.data.*
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Result of ingesting a document: its row id, display title and chunk count. */
data class IngestedDocument(val docId: Long, val title: String, val chunkCount: Int)

/**
 * The per-chat document knowledge base: ingestion, storage and retrieval.
 *
 * Retrieval is BM25 keyword ranking over the chunks of documents attached to the
 * current chat (see [Bm25]). The previous "embedding" path ran a 132-byte
 * placeholder model on hashed tokens, so its vectors were noise; it was removed
 * together with the encrypted vector files it rewrote after every chunk.
 */
class KnowledgeRepository private constructor(
    private val context: Context,
    private val dao: KnowledgeDao
) {
    companion object {
        @Volatile private var INSTANCE: KnowledgeRepository? = null

        fun getInstance(context: Context): KnowledgeRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: KnowledgeRepository(
                    context.applicationContext,
                    AppDatabase.getInstance(context).knowledgeDao()
                ).also {
                    // Leftovers from the old vector store; chunks in the DB are all we need.
                    File(context.filesDir, "vectors.bin").delete()
                    File(context.filesDir, "ids.txt").delete()
                    INSTANCE = it
                }
            }
    }

    /**
     * Add a document from a URI (content:// or file://) to the chat identified by
     * [sessionId]. Returns the ingested document (id, title, chunk count).
     */
    suspend fun addDocument(uri: Uri, sessionId: String?): IngestedDocument = withContext(Dispatchers.Default) {
        val displayName = getDisplayName(uri)
        val mimeType = context.contentResolver.getType(uri)
        val rawText = DocumentTextExtractor.extract(context, uri, displayName, mimeType)
        if (rawText.isBlank()) {
            throw DocumentExtractionException("No readable text found in \"$displayName\".")
        }
        val docId = dao.insertDocument(DocumentEntity(title = displayName, contentUri = uri.toString(), sessionId = sessionId))
        val chunks = TextChunker.chunk(rawText)
        dao.insertChunks(chunks.mapIndexed { i, text -> ChunkEntity(docId = docId, ordinal = i, text = text) })
        IngestedDocument(docId, displayName, chunks.size)
    }

    /** Documents currently in the knowledge base, newest first, with chunk counts. */
    fun documents(): kotlinx.coroutines.flow.Flow<List<DocumentWithChunkCount>> =
        dao.getDocumentsWithChunkCounts()

    /** Remove a document and its chunks (cascade). */
    suspend fun deleteDocument(docId: Long) = withContext(Dispatchers.Default) { dao.deleteDocumentById(docId) }

    /** Remove every document owned by a chat (used when the chat itself is deleted). */
    suspend fun deleteDocumentsForSession(sessionId: String) =
        withContext(Dispatchers.Default) { dao.deleteDocumentsForSession(sessionId) }

    /** Remove every document in the knowledge base (used by "clear all chats"). */
    suspend fun deleteAllDocuments() = withContext(Dispatchers.Default) { dao.deleteAllDocuments() }

    /** Copy an existing document (and its chunks) into [sessionId]. Null if it no longer exists. */
    suspend fun attachExistingDocument(sourceDocId: Long, sessionId: String): IngestedDocument? =
        withContext(Dispatchers.Default) {
            val source = dao.getDocument(sourceDocId) ?: return@withContext null
            val sourceChunks = dao.getChunksForDocOnce(sourceDocId)
            if (sourceChunks.isEmpty()) return@withContext null
            val newDocId = dao.insertDocument(
                DocumentEntity(title = source.title, contentUri = source.contentUri, sessionId = sessionId)
            )
            dao.insertChunks(sourceChunks.map { ChunkEntity(docId = newDocId, ordinal = it.ordinal, text = it.text) })
            IngestedDocument(newDocId, source.title, sourceChunks.size)
        }

    /**
     * The [topK] most relevant chunks for [query] among documents attached to
     * [sessionId], each prefixed with its document title so the model can cite it.
     * A null session (no chat yet) yields no context.
     */
    suspend fun search(query: String, topK: Int = 4, sessionId: String?): List<String> =
        withContext(Dispatchers.Default) {
            if (sessionId == null) return@withContext emptyList()
            val chunks = dao.getChunksForSession(sessionId)
            if (chunks.isEmpty()) return@withContext emptyList()
            val byId = chunks.associateBy { it.id }
            Bm25.rank(query, chunks.map { Bm25.Doc(it.id, it.text, it.title) }, topK)
                .mapNotNull { byId[it.key] }
                // Present in reading order within each document.
                .sortedWith(compareBy({ it.docId }, { it.ordinal }))
                .map { "[${it.title}] ${it.text}" }
        }

    private fun getDisplayName(uri: Uri): String =
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            } ?: uri.lastPathSegment ?: "Unknown"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "Unknown"
        }
}
