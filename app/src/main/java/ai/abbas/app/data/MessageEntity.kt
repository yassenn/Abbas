package ai.abbas.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val thought: String? = null,
    val thoughtTimeMs: Long? = null,
    val generationTimeMs: Long? = null,
    val tokensPerSecond: Double? = null
)
