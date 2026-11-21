package ai.alal.app.personal

import android.content.Context
import ai.alal.app.data.EmailAccount
import ai.alal.app.data.SecurityUtils
import com.google.gson.Gson

/** Email accounts in encrypted preferences. */
class EmailAccounts(context: Context) {
    private val prefs = SecurityUtils.getEncryptedPrefs(context)
    private val gson = Gson()

    fun all(): List<EmailAccount> = runCatching {
        gson.fromJson(prefs.getString(KEY, "[]"), Array<EmailAccount>::class.java)?.toList().orEmpty()
    }.getOrDefault(emptyList())

    @Synchronized
    fun upsert(account: EmailAccount) {
        save(all().filterNot { it.id == account.id } + account)
    }

    @Synchronized
    fun remove(id: String) = save(all().filterNot { it.id == id })

    private fun save(list: List<EmailAccount>) {
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }

    companion object {
        private const val KEY = "email_accounts_json"
    }
}
