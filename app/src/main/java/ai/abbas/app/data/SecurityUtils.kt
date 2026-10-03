package ai.abbas.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.security.crypto.MasterKeys
import java.util.UUID

object SecurityUtils {
    private const val PREFS_FILE = "alal_secure_prefs"
    private const val DB_PASSPHRASE_KEY = "db_passphrase"

    /**
     * Keystore-backed AES-256-GCM master key shared by all encrypted storage
     * (EncryptedSharedPreferences, EncryptedFile). Hardware-backed where the
     * device supports it.
     */
    fun getMasterKey(context: Context): MasterKey =
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    fun getEncryptedPrefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getDatabasePassphrase(context: Context): ByteArray {
        val prefs = getEncryptedPrefs(context)
        var passphrase = prefs.getString(DB_PASSPHRASE_KEY, null)
        if (passphrase == null) {
            passphrase = UUID.randomUUID().toString()
            prefs.edit().putString(DB_PASSPHRASE_KEY, passphrase).apply()
        }
        return passphrase.toByteArray()
    }
}
