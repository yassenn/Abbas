package ai.abbas.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val lastMessage: String,
    val timestamp: Long = System.currentTimeMillis()
)
