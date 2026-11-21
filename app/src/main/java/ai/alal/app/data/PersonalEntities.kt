package ai.alal.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where an [IngestedItemEntity] came from. Stored as its [name]. */
enum class PersonalSource(val label: String) {
    SMS("SMS"),
    CALL("Call"),
    NOTIFICATION("Notification"),
    FILE("File"),
    USAGE("App usage"),
    CALENDAR("Calendar"),
    HEALTH("Health"),
    LOCATION("Location"),
    SCREEN("Screen"),
    EMAIL("Email"),
    IMPORT("Imported chat")
}

/**
 * One piece of phone activity collected for summarisation: an SMS, a call-log
 * entry, an incoming notification (WhatsApp, Telegram, mail…) or a document
 * found in a watched folder. Lives only in the encrypted on-device database.
 *
 * [sourceKey] is a stable id from the origin (SMS row id, notification key +
 * post time, document uri + mtime) so re-collecting never duplicates a row.
 */
@Entity(
    tableName = "ingested_items",
    indices = [
        Index(value = ["sourceKey"], unique = true),
        Index("timestamp"),
        Index("summarized")
    ]
)
data class IngestedItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val sourceKey: String,
    /** Package or app label for notifications; null otherwise. */
    val app: String? = null,
    /** Contact name / number, or null. */
    val sender: String? = null,
    /** Notification title or file name. */
    val title: String? = null,
    val body: String,
    val timestamp: Long,
    val summarized: Boolean = false
)

/** A digest written by the model from a batch of [IngestedItemEntity]s. */
@Entity(tableName = "digests", indices = [Index("createdAt")])
data class DigestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val periodStart: Long,
    val periodEnd: Long,
    val itemCount: Int,
    /** Markdown bullet list. */
    val summary: String
)

/** A to-do drafted by the model (from a digest) or added from chat. */
@Entity(tableName = "todos", indices = [Index("done")])
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val dueAt: Long? = null,
    /** Free-form origin label, e.g. "Digest" or "Chat". */
    val origin: String? = null,
    val digestId: Long? = null,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A raw location fix, recorded every ~15 minutes while the Location source is on.
 * Clustered into places/visits by PlaceAnalyzer; pruned after 30 days.
 */
@Entity(tableName = "location_points", indices = [Index("time")])
data class LocationPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val time: Long
)
