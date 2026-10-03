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
         * Ensures the on-disk DB is one SQLCipher can open with [passphrase].
         *
         * If the existing file cannot be opened with our passphrase it is either a
         * legacy plaintext database or one encrypted under a different/lost key.
         * In that case the file (and its journals) is *moved aside* rather than
         * deleted, so no user data is ever destroyed, and Room is then free to
         * create a fresh encrypted database.
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
                // Not openable with our passphrase (plaintext legacy DB, or a DB
                // encrypted under a different key). Preserve it — rename, don't delete.
                quarantineDatabase(dbFile)
            }
        }

        /** Move an unreadable DB and its sidecar journals aside for recovery. */
        private fun quarantineDatabase(dbFile: File) {
            val stamped = dbFile.absolutePath + ".unreadable-" + System.currentTimeMillis()
            dbFile.renameTo(File(stamped))
            for (ext in listOf("-wal", "-shm", "-journal")) {
                val sidecar = File(dbFile.absolutePath + ext)
                if (sidecar.exists()) sidecar.renameTo(File(sidecar.absolutePath + ".unreadable"))
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
