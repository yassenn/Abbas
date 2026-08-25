# Room rules
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-keep class * extends androidx.room.RoomDatabase
-keep class androidx.room.concurrent.TableWatcher
-keep class androidx.room.concurrent.TableWatcher$*

# MLC LLM rules
-keep class ai.mlc.mlcllm.** { *; }

# Serialization
-keepattributes *Annotation*, EnclosingMethod, Signature
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# SQLCipher
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class net.zetetic.database.** { *; }
-keep class ai.abbas.app.inference.DownloadWorker { *; }
