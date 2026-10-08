package ai.abbas.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a document attached to a chat session and added to the knowledge base.
 *
 * A document is scoped to the chat that owns it: its chunks are only ever retrieved
 * while that session is active, so attaching a file to one chat never leaks its
 * contents into another. [sessionId] is null for legacy rows created before
 * documents were scoped to chats (they are treated as belonging to no chat).
 */
@Entity(
    tableName = "documents",
    indices = [Index("sessionId")]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val contentUri: String, // content:// or file:// URI
    val sessionId: String? = null, // owning chat session (null = unattached legacy row)
    val addedAt: Long = System.currentTimeMillis()
)
