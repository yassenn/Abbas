package ai.alal.app.personal

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import ai.alal.app.ui.theme.AlalBlue
import ai.alal.app.ui.theme.GemmaAndroidAppTheme
import kotlinx.coroutines.launch

/**
 * "Share to Alal" target: WhatsApp → ⋮ → More → Export chat → Alal, or a
 * Telegram / Instagram / Facebook / X data download shared from Files.
 */
class ImportActivity : ComponentActivity() {

    private var status by mutableStateOf("")
    private var done by mutableStateOf(false)
    /** Files waiting for the user's OK. Any app can share to Alal, so nothing is imported unasked. */
    private var pending by mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        setContent {
            GemmaAndroidAppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (pending.isNotEmpty()) {
                            Text("Import into Alal?", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            pending.forEach { Text(fileName(it), style = MaterialTheme.typography.bodySmall) }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Messages are stored encrypted on this phone and used for summaries and chat.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { runImport(pending) },
                                colors = ButtonDefaults.buttonColors(containerColor = AlalBlue)
                            ) { Text("Import") }
                            TextButton(onClick = { finish() }) { Text("Cancel") }
                            return@Column
                        }
                        if (!done) CircularProgressIndicator(color = AlalBlue)
                        Spacer(Modifier.height(16.dp))
                        Text(status, fontWeight = FontWeight.Medium)
                        if (done) {
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
                                    finish()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AlalBlue)
                            ) { Text("Open Alal") }
                            TextButton(onClick = { finish() }) { Text("Close") }
                        }
                    }
                }
            }
        }
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            status = "Nothing to import. Share a chat export or a data download file."
            done = true
            return
        }
        pending = uris
    }

    private fun fileName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun runImport(uris: List<Uri>) {
        pending = emptyList()
        status = "Importing…"
        lifecycleScope.launch {
            status = try {
                val r = ChatImporter.import(applicationContext, uris)
                "Imported ${r.messages} messages from ${r.conversations} conversation(s) · ${r.platforms.joinToString()}"
            } catch (e: Exception) {
                "Import failed: ${e.message ?: e.javaClass.simpleName}"
            }
            done = true
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE -> (
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            ).orEmpty().filter {
                // WhatsApp shares the chat .txt next to every photo/voice note; only text is useful here.
                val type = contentResolver.getType(it).orEmpty()
                type.startsWith("text/") || type.contains("zip") || type.contains("json") || it.toString().endsWith(".txt")
            }
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        else -> emptyList()
    }
}
