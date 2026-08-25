package ai.abbas.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDocument(document: DocumentEntity): Long

    @Query("SELECT * FROM documents WHERE id = :docId")
    suspend fun getDocument(docId: Long): DocumentEntity?

    @Query("SELECT * FROM documents ORDER BY addedAt DESC")
    fun getAllDocuments(): Flow<List<DocumentEntity>>

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