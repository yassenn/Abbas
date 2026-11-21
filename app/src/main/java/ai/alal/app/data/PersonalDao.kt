package ai.alal.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class SourceCount(val source: String, val count: Int)

@Dao
interface PersonalDao {

    // ── Ingested items ────────────────────────────────────────────

    /** Duplicates (same sourceKey) are skipped; returns -1 for those rows. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<IngestedItemEntity>): List<Long>

    @Query("SELECT * FROM ingested_items WHERE summarized = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun unsummarized(limit: Int): List<IngestedItemEntity>

    @Query("UPDATE ingested_items SET summarized = 1 WHERE id IN (:ids)")
    suspend fun markSummarized(ids: List<Long>)

    @Query("SELECT * FROM ingested_items WHERE timestamp >= :since ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentItems(since: Long, limit: Int): List<IngestedItemEntity>

    /** Older history mentioning [term] (e.g. a person's name), newest first. */
    @Query(
        "SELECT * FROM ingested_items WHERE timestamp < :before AND " +
            "(body LIKE '%' || :term || '%' OR sender LIKE '%' || :term || '%' OR title LIKE '%' || :term || '%') " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun searchItems(term: String, before: Long, limit: Int): List<IngestedItemEntity>

    @Query("SELECT COUNT(*) FROM ingested_items WHERE summarized = 0")
    fun pendingCount(): Flow<Int>

    @Query("SELECT source, COUNT(*) AS count FROM ingested_items GROUP BY source")
    fun countsBySource(): Flow<List<SourceCount>>

    /** Summarised items older than [cutoff] have served their purpose. */
    @Query("DELETE FROM ingested_items WHERE summarized = 1 AND timestamp < :cutoff")
    suspend fun pruneSummarized(cutoff: Long)

    @Query("DELETE FROM ingested_items")
    suspend fun deleteAllItems()

    // ── Digests ───────────────────────────────────────────────────

    @Insert
    suspend fun insertDigest(digest: DigestEntity): Long

    @Query("SELECT * FROM digests ORDER BY createdAt DESC LIMIT 50")
    fun digests(): Flow<List<DigestEntity>>

    @Query("SELECT * FROM digests ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestDigest(): DigestEntity?

    @Query("DELETE FROM digests WHERE id = :id")
    suspend fun deleteDigest(id: Long)

    @Query("DELETE FROM digests")
    suspend fun deleteAllDigests()

    // ── To-dos ────────────────────────────────────────────────────

    @Insert
    suspend fun insertTodos(todos: List<TodoEntity>)

    /** Open first, then by due date (undated last), newest first. */
    @Query("SELECT * FROM todos ORDER BY done ASC, CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC, createdAt DESC")
    fun todos(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE done = 0 ORDER BY CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC LIMIT :limit")
    suspend fun openTodos(limit: Int): List<TodoEntity>

    @Query("UPDATE todos SET done = :done WHERE id = :id")
    suspend fun setTodoDone(id: Long, done: Boolean)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun deleteTodo(id: Long)

    @Query("DELETE FROM todos WHERE done = 1")
    suspend fun deleteDoneTodos()

    @Query("DELETE FROM todos")
    suspend fun deleteAllTodos()

    // ── Location ──────────────────────────────────────────────────

    @Insert
    suspend fun insertPoint(point: LocationPointEntity)

    @Query("SELECT * FROM location_points WHERE time >= :since ORDER BY time ASC")
    suspend fun pointsSince(since: Long): List<LocationPointEntity>

    @Query("DELETE FROM location_points WHERE time < :cutoff")
    suspend fun prunePoints(cutoff: Long)

    @Query("DELETE FROM location_points")
    suspend fun deleteAllPoints()
}
