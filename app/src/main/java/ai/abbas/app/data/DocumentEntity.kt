package ai.abbas.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a document added to the knowledge base.
 */
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val contentUri: String, // content:// or file:// URI
    val addedAt: Long = System.currentTimeMillis()
)