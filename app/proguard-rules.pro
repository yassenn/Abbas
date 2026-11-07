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

# Lifecycle / SavedState persist a Gson TypeToken whose generic signature R8
# would otherwise strip — the app crashed on launch with "TypeToken must be
# created with a type argument" until these were kept.
-keep class androidx.lifecycle.** { *; }
-keep class androidx.savedstate.** { *; }

# Gson reflected model classes (custom model catalogue, payment config).
-keep class ai.abbas.app.data.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# LiteRT / TensorFlow Lite JNI surface.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# SQLCipher
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class net.zetetic.database.** { *; }
# PDFBox-Android (PDF text extraction)
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-keep class ai.abbas.app.inference.DownloadWorker { *; }
