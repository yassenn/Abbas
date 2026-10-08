package ai.abbas.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A knowledge-base document plus the chat that owns it and how many chunks it was split into. */
data class DocumentWithChunkCount(
    val id: Long,
    val title: String,
    val contentUri: String,
    val sessionId: String?,
    val sessionTitle: String?,
    val addedAt: Long,
    val chunkCount: Int
)

@Dao
interface KnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDocument(document: DocumentEntity): Long

    @Query("SELECT * FROM documents WHERE id = :docId")
    suspend fun getDocument(docId: Long): DocumentEntity?

    @Query("SELECT * FROM documents ORDER BY addedAt DESC")
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    /** Documents owned by a single chat, newest first. */
    @Query("SELECT * FROM documents WHERE sessionId = :sessionId ORDER BY addedAt DESC")
    fun getDocumentsForSession(sessionId: String): Flow<List<DocumentEntity>>

    /** Every document with its owning-chat title and chunk count, newest first — backs the Documents screen. */
    @Query(
        "SELECT d.id AS id, d.title AS title, d.contentUri AS contentUri, " +
            "d.sessionId AS sessionId, s.title AS sessionTitle, " +
            "d.addedAt AS addedAt, COUNT(c.id) AS chunkCount " +
            "FROM documents d LEFT JOIN chunks c ON c.docId = d.id " +
            "LEFT JOIN chat_sessions s ON s.id = d.sessionId " +
            "GROUP BY d.id ORDER BY d.addedAt DESC"
    )
    fun getDocumentsWithChunkCounts(): Flow<List<DocumentWithChunkCount>>

    @Query("SELECT id FROM chunks WHERE docId = :docId")
    suspend fun getChunkIdsForDoc(docId: Long): List<Long>

    /** Chunk ids belonging to a chat (used to scope vector search), across all its documents. */
    @Query(
        "SELECT c.id FROM chunks c INNER JOIN documents d ON c.docId = d.id " +
            "WHERE d.sessionId = :sessionId"
    )
    suspend fun getChunkIdsForSession(sessionId: String): List<Long>

    /** Full chunk rows for a document, in reading order — used to copy a document into another chat. */
    @Query("SELECT * FROM chunks WHERE docId = :docId ORDER BY ordinal")
    suspend fun getChunksForDocOnce(docId: Long): List<ChunkEntity>

    @Query("DELETE FROM documents WHERE id = :docId")
    suspend fun deleteDocumentById(docId: Long)

    @Query("DELETE FROM documents WHERE sessionId = :sessionId")
    suspend fun deleteDocumentsForSession(sessionId: String)

    @Query("DELETE FROM documents")
    suspend fun deleteAllDocuments()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<ChunkEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: ChunkEntity): Long

    @Query("SELECT * FROM chunks WHERE docId = :docId ORDER BY ordinal")
    fun getChunksForDocument(docId: Long): Flow<List<ChunkEntity>>

    @Query("SELECT * FROM chunks WHERE id IN (:ids)")
    suspend fun getChunksByIds(ids: List<Long>): List<ChunkEntity>

    @Delete
    suspend fun deleteDocument(document: DocumentEntity)

    // Delete all chunks for a document (handled by cascade foreign key, but explicit for clarity)
    @Query("DELETE FROM chunks WHERE docId = :docId")
    suspend fun deleteChunksForDoc(docId: Long)
}
