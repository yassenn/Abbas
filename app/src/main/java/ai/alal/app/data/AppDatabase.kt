package ai.alal.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ai.alal.app.data.MessageEntity
import ai.alal.app.data.ChatSessionEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

@Database(
    entities = [
        DocumentEntity::class, ChunkEntity::class, MessageEntity::class, ChatSessionEntity::class,
        IngestedItemEntity::class, DigestEntity::class, TodoEntity::class, LocationPointEntity::class,
        SummaryLinkEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun knowledgeDao(): KnowledgeDao

    abstract fun messageDao(): MessageDao

    abstract fun personalDao(): PersonalDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /** Database file name (also used as the Room DB name). */
        private const val DB_NAME = "knowledge_database"

        /**
         * v2 → v3: documents gained an owning [DocumentEntity.sessionId] so their chunks
         * are only retrievable inside the chat that attached them. Existing documents are
         * left with a null sessionId (unattached legacy rows) rather than being deleted.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN sessionId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_sessionId ON documents(sessionId)")
            }
        }

        /**
         * v3 → v4: phone-activity tables for the Summaries & To-dos tab. Additive only,
         * so existing chats and documents are untouched. Column types and index names
         * mirror what Room generates for the entities (validated on open).
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ingested_items` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, " +
                        "`sourceKey` TEXT NOT NULL, `app` TEXT, `sender` TEXT, `title` TEXT, " +
                        "`body` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `summarized` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ingested_items_sourceKey` ON `ingested_items` (`sourceKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ingested_items_timestamp` ON `ingested_items` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ingested_items_summarized` ON `ingested_items` (`summarized`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `digests` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`periodStart` INTEGER NOT NULL, `periodEnd` INTEGER NOT NULL, " +
                        "`itemCount` INTEGER NOT NULL, `summary` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_digests_createdAt` ON `digests` (`createdAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `todos` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, " +
                        "`dueAt` INTEGER, `origin` TEXT, `digestId` INTEGER, " +
                        "`done` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_todos_done` ON `todos` (`done`)")
            }
        }

        /** v4 → v5: raw location fixes for the Location source (places & visits). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `location_points` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, `accuracy` REAL NOT NULL, `time` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_points_time` ON `location_points` (`time`)")
            }
        }

        /** v5 → v6: summaries become independent records with parent links (see [SummaryMigration]). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SummaryMigration.STATEMENTS_5_6.forEach { db.execSQL(it) }
            }
        }

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
                // Load the SQLCipher native library (required before any DB operation).
                // sqlcipher-android dropped SQLiteDatabase.loadLibs(); it ships a
                // plain libsqlcipher.so instead.
                System.loadLibrary("sqlcipher")

                val passphrase = SecurityUtils.getDatabasePassphrase(context)
                migratePlaintextToEncrypted(context, passphrase)

                val factory = SupportOpenHelperFactory(passphrase)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .openHelperFactory(factory)
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
