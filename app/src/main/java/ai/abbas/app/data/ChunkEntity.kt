package ai.abbas.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a text chunk belonging to a document.
 */
@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["docId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("docId")]
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: Long,
    val ordinal: Int, // position within document
    val text: String
)