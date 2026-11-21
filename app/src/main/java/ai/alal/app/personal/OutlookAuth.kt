package ai.alal.app.personal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Microsoft sign-in for Outlook.com / Hotmail IMAP, which no longer accepts
 * passwords. Uses the OAuth 2.0 device-code flow: Alal shows a short code, the
 * user signs in at microsoft.com/devicelogin, and Alal receives a refresh token.
 *
 * Needs an app (client) ID from the user's own free Azure app registration, so
 * Alal never borrows another app's identity.
 */
object OutlookAuth {

    private const val AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private const val SCOPE = "https://outlook.office.com/IMAP.AccessAsUser.All offline_access"

    data class DeviceCode(
        val userCode: String,
        val verificationUri: String,
        val deviceCode: String,
        val intervalSeconds: Int,
        val expiresAt: Long,
        val message: String
    )

    data class Tokens(val accessToken: String, val refreshToken: String)

    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun startDeviceCode(clientId: String): DeviceCode = withContext(Dispatchers.IO) {
        val json = post("$AUTHORITY/devicecode", FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", SCOPE)
            .build())
        DeviceCode(
            userCode = json.getString("user_code"),
            verificationUri = json.optString("verification_uri", "https://microsoft.com/devicelogin"),
            deviceCode = json.getString("device_code"),
            intervalSeconds = json.optInt("interval", 5),
            expiresAt = System.currentTimeMillis() + json.optInt("expires_in", 900) * 1000L,
            message = json.optString("message")
        )
    }

    /** Polls until the user finishes signing in (or the code expires). */
    suspend fun awaitTokens(clientId: String, code: DeviceCode): Tokens = withContext(Dispatchers.IO) {
        var interval = code.intervalSeconds
        while (System.currentTimeMillis() < code.expiresAt) {
            delay(interval * 1000L)
            val json = postRaw("$AUTHORITY/token", FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("client_id", clientId)
                .add("device_code", code.deviceCode)
                .build())
            when (json.optString("error")) {
                "" -> return@withContext Tokens(json.getString("access_token"), json.getString("refresh_token"))
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                else -> throw IllegalStateException(json.optString("error_description").ifBlank { json.optString("error") })
            }
        }
        throw IllegalStateException("The sign-in code expired. Try again.")
    }

    /** A fresh access token; Microsoft may rotate the refresh token too. */
    suspend fun refresh(clientId: String, refreshToken: String): Tokens = withContext(Dispatchers.IO) {
        val json = post("$AUTHORITY/token", FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("scope", SCOPE)
            .build())
        Tokens(json.getString("access_token"), json.optString("refresh_token").ifBlank { refreshToken })
    }

    private fun post(url: String, body: FormBody): JSONObject {
        val json = postRaw(url, body)
        if (json.has("error")) {
            throw IllegalStateException(json.optString("error_description").ifBlank { json.optString("error") })
        }
        return json
    }

    private fun postRaw(url: String, body: FormBody): JSONObject {
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
            return JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
        }
    }
}
