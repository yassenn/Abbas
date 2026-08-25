package ai.abbas.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import ai.abbas.app.data.MessageEntity
import ai.abbas.app.data.ChatSessionEntity
import net.sqlcipher.database.SupportFactory
import net.sqlcipher.database.SQLiteDatabase
import java.io.File

@Database(entities = [DocumentEntity::class, ChunkEntity::class, MessageEntity::class, ChatSessionEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun knowledgeDao(): KnowledgeDao

    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /** Database file name (also used as the Room DB name). */
        private const val DB_NAME = "knowledge_database"

        /**
         * Deletes the old plaintext database file so SQLCipher can create
         * a fresh encrypted one. This is a one-time migration when upgrading
         * from unencrypted to encrypted storage.
         */
        private fun migratePlaintextToEncrypted(context: Context, passphrase: ByteArray) {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return

            try {
                // Try opening with SQLCipher and the real passphrase.
                // If it works, the DB is already encrypted — nothing to do.
                val db = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    passphrase,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                    null,  // no hook
                    null   // no error handler
                )
                db.close()
            } catch (_: Exception) {
                // Can't open as SQLCipher — old plaintext DB. Delete it.
                dbFile.delete()
                File(dbFile.absolutePath + "-wal").delete()
                File(dbFile.absolutePath + "-shm").delete()
                File(dbFile.absolutePath + "-journal").delete()
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // Load SQLCipher native library (required before any DB operations)
                SQLiteDatabase.loadLibs(context)

                val passphrase = SecurityUtils.getDatabasePassphrase(context)
                migratePlaintextToEncrypted(context, passphrase)

                val factory = SupportFactory(passphrase)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .openHelperFactory(factory)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
