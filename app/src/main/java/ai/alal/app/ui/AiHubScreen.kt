@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.alal.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.alal.app.data.ModelConfig
import ai.alal.app.ui.theme.AlalBlue
import ai.alal.app.viewmodel.ChatViewModel
import ai.alal.app.viewmodel.ModelDownloadState

/**
 * AI Hub — the single place to browse and manage model weights.
 *
 * This tab is intentionally limited to DOWNLOADING and DELETING weights.
 * Loading a model into the inference engine happens in the Chat tab, so the two
 * concerns stay separate.
 */
@Composable
fun AiHubScreen(
    viewModel: ChatViewModel,
    onOpenChat: () -> Unit
) {
    val models by viewModel.allModels.collectAsState()
    var strengthFilter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("ALL") }
    val downloadedIds by viewModel.downloadedModelIds.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val orphaned by viewModel.orphanedWeights.collectAsState()
    val legacy by viewModel.legacyImport.collectAsState()

    // Re-check earlier installs each time the Hub opens (e.g. after updating Abbas).
    LaunchedEffect(Unit) { viewModel.refreshLegacyModels() }

    var showClearOrphans by remember { mutableStateOf(false) }
    var ramBlockedModel by remember { mutableStateOf<ModelConfig?>(null) }

    ramBlockedModel?.let { blocked ->
        val gb = String.format("%.1f", blocked.estimatedRamBytes / (1024 * 1024 * 1024.0))
        AlertDialog(
            onDismissRequest = { ramBlockedModel = null },
            icon = { Icon(Icons.Outlined.Memory, contentDescription = null, tint = Color(0xFFB26A00)) },
            title = { Text("Device can't run this model") },
            text = {
                Text(
                    "\u201C${blocked.name}\u201D needs roughly $gb GB of RAM to run, which is " +
                        "more than this device can spare. Loading it could make the app slow " +
                        "or unstable. Please pick a smaller model instead."
                )
            },
            confirmButton = {
                TextButton(onClick = { ramBlockedModel = null }) {
                    Text("OK", color = AlalBlue, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    if (showClearOrphans) {
        AlertDialog(
            onDismissRequest = { showClearOrphans = false },
            title = { Text("Delete unused model files?") },
            text = {
                Text(
                    "${orphaned.count} weight folder(s) left over from models that are no " +
                        "longer in the catalogue will be deleted, freeing about " +
                        "${orphaned.megabytes} MB. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearOrphanedWeights()
                        showClearOrphans = false
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearOrphans = false }) { Text("Cancel") }
            }
        )
    }

    val activeDownloads = downloadStates.size

    // No top bar here on purpose: ChatScreen renders the Hub's top bar in its
    // Scaffold topBar slot so both screens share identical inset handling
    // (avoids double status-bar padding).
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            item {
                HubHeader(
                    downloadedCount = downloadedIds.size,
                    totalCount = models.size
                )
            }

            val importable = legacy.models.filter { it.model.id !in downloadStates }
            if (importable.isNotEmpty()) {
                item {
                    LegacyImportCard(
                        modelNames = importable.map { it.model.name },
                        totalMb = importable.sumOf { it.sizeBytes } / 1_048_576,
                        onImport = viewModel::importLegacyModels
                    )
                }
            } else if (legacy.outdatedPackages.isNotEmpty() && legacy.models.isEmpty()) {
                item { LegacyOutdatedCard() }
            }

            item(key = "model-updates") { ModelUpdatesRow(viewModel) }
            item(key = "strength-filter") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    STRENGTH_FILTERS.forEach { (value, label) ->
                        FilterChip(
                            selected = strengthFilter == value,
                            onClick = { strengthFilter = value },
                            label = { Text(label) }
                        )
                    }
                }
            }
            // Filter by what the user needs, then group by overall quality.
            val shown = if (strengthFilter == "ALL") models
            else models.filter { it.strengths.orEmpty().contains(strengthFilter) || it.tier == null }
            val tierOrder = listOf("HIGH", "MID", "LOW", "RETIRED", null)
            val grouped = shown.groupBy { it.tier?.takeIf { t -> t in tierOrder } }
            tierOrder.forEach { tier ->
                val group = grouped[tier].orEmpty()
                if (group.isEmpty()) return@forEach
                item(key = "tier-$tier") {
                    Text(
                        when (tier) {
                            "HIGH" -> "High performing · best summaries"
                            "MID" -> "Mid · speed and quality balance"
                            "LOW" -> "Low · for older or low-RAM phones"
                            "RETIRED" -> "Retired · on your phone, no longer recommended"
                            else -> "Custom"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = AlalBlue,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                    )
                }
            items(group, key = { it.id }) { model ->
                AiHubModelCard(
                    model = model,
                    isDownloaded = downloadedIds.contains(model.id),
                    isCapable = viewModel.isModelCapable(model),
                    isActive = currentModel?.id == model.id,
                    downloadState = downloadStates[model.id],
                    onDownload = { viewModel.downloadModel(model) },
                    onCancel = { viewModel.cancelModelDownload(model.id) },
                    onDelete = { viewModel.deleteModel(model) },
                    onBlocked = { ramBlockedModel = model }
                )
            }
            }

            item {
                Spacer(Modifier.height(8.dp))
                HfTokenItem(viewModel)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Gated models (e.g. newer Qwen / Gemma / Llama releases) need a " +
                        "Hugging Face read token. Accept the license on the model page first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            if (orphaned.count > 0) {
                item {
                    OutlinedButton(
                        onClick = { showClearOrphans = true },
                        border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color.Gray
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Clear unused models (${orphaned.count} • ${orphaned.megabytes} MB)",
                            color = Color.Gray
                        )
                    }
                }
            }

            if (activeDownloads > 1) {
                item {
                    OutlinedButton(
                        onClick = { viewModel.cancelAllDownloads() },
                        border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel all downloads ($activeDownloads)", color = Color.Gray)
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onOpenChat,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Chat, contentDescription = null, modifier = Modifier.size(18.dp), tint = AlalBlue)
                    Spacer(Modifier.width(8.dp))
                    Text("Go to Chat to load a model", color = AlalBlue, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(16.dp))
            }
    }
}

@Composable
private fun LegacyImportCard(modelNames: List<String>, totalMb: Long, onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, AlalBlue.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = AlalBlue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Models found from Abbas",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            modelNames.joinToString(", ") + " ($totalMb MB). Import them to use here " +
                "without downloading again. Needs that much free storage; you can " +
                "uninstall Abbas afterwards to get the space back.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onImport,
            colors = ButtonDefaults.buttonColors(containerColor = AlalBlue),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (modelNames.size == 1) "Import model" else "Import ${modelNames.size} models")
        }
    }
}

@Composable
private fun LegacyOutdatedCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFB26A00).copy(alpha = 0.08f))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = Color(0xFFB26A00), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Update Abbas to bring its models over",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Abbas is installed, but its version can't share downloaded models. Update " +
                "Abbas to the latest version, then come back here to import them. " +
                "You can also import a .gguf file with the button at the top.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

@Composable
private fun HubHeader(downloadedCount: Int, totalCount: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AlalBlue.copy(alpha = 0.06f))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = AlalBlue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Download models here",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Weights are stored on device. Downloading never starts the engine — " +
                "open the Chat tab (or the button below) to load a downloaded model.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "$downloadedCount of $totalCount models on device",
            style = MaterialTheme.typography.labelMedium,
            color = AlalBlue,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AiHubModelCard(
    model: ModelConfig,
    isDownloaded: Boolean,
    isCapable: Boolean,
    isActive: Boolean,
    downloadState: ModelDownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onBlocked: () -> Unit
) {
    val downloading = downloadState != null
    val sizeGb = String.format("%.1f", model.estimatedRamBytes / (1024 * 1024 * 1024.0))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Models this device can't run are dimmed; tapping them explains why
            // instead of starting a download that would fail or thrash memory.
            .alpha(if (isCapable) 1f else 0.45f)
            .clickable(enabled = !isCapable) { onBlocked() },
        colors = CardDefaults.cardColors(
            containerColor = if (isCapable) MaterialTheme.colorScheme.surface
            else Color.Gray.copy(alpha = 0.12f)
        ),
        shape = RoundedCornerShape(12.dp),
        border = when {
            isActive -> BorderStroke(1.dp, AlalBlue)
            isDownloaded -> BorderStroke(1.dp, AlalBlue.copy(alpha = 0.25f))
            else -> null
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "~$sizeGb GB RAM",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    model.note?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    model.strengths?.takeIf { it.isNotEmpty() }?.let { tags ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            tags.mapNotNull { t -> STRENGTH_FILTERS.firstOrNull { it.first == t }?.second }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = AlalBlue
                        )
                    }
                    if (!isCapable) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.WarningAmber,
                                contentDescription = null,
                                tint = Color(0xFFB26A00),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Insufficient RAM on this device",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFB26A00)
                            )
                        }
                    }
                    if (isActive) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Loaded in engine",
                            style = MaterialTheme.typography.labelSmall,
                            color = AlalBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                when {
                    downloading -> {
                        IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel download",
                                tint = Color.Gray
                            )
                        }
                    }
                    isDownloaded -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Downloaded",
                                tint = AlalBlue,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = "Delete weights",
                                    tint = Color.Gray
                                )
                            }
                        }
                    }
                    else -> {
                        FilledTonalButton(
                            onClick = onDownload,
                            enabled = isCapable,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = AlalBlue.copy(alpha = 0.12f),
                                contentColor = AlalBlue
                            )
                        ) {
                            Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Download", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (downloading && downloadState != null) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = downloadState.progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = AlalBlue,
                    trackColor = AlalBlue.copy(alpha = 0.1f)
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        downloadState.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${(downloadState.progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = AlalBlue,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/** AI Hub filter: what a model is best at (values match ModelConfig.strengths). */
internal val STRENGTH_FILTERS = listOf(
    "ALL" to "All",
    "CHAT" to "Answers",
    "REASONING" to "Reasoning & math",
    "CODING" to "Coding",
    "SUMMARIES" to "Summaries",
    "DOCUMENTS" to "Documents",
    "LANGUAGES" to "Languages",
    "FAST" to "Fast / low RAM"
)

/** Signed model-list updates: weekly automatic check (Wi-Fi) plus a manual button. */
@Composable
private fun ModelUpdatesRow(viewModel: ai.alal.app.viewmodel.ChatViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var auto by remember { mutableStateOf(ai.alal.app.inference.ModelCatalog.isAutoUpdate(context)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Keep model list updated", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Weekly on Wi-Fi · list v${ai.alal.app.inference.ModelCatalog.version} · signed and verified",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray
            )
        }
        TextButton(onClick = { viewModel.checkModelUpdates() }) { Text("Check now") }
        Switch(
            checked = auto,
            onCheckedChange = { auto = it; ai.alal.app.inference.ModelCatalog.setAutoUpdate(context, it) },
            colors = SwitchDefaults.colors(checkedTrackColor = AlalBlue)
        )
    }
}
