package ai.abbas.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ai.abbas.app.data.GenerationSettings
import ai.abbas.app.ui.theme.AbbasBlue
import kotlin.math.roundToInt

/**
 * Dialog that lets the user tune sampling parameters for text generation.
 * Changes are applied immediately on Save and persisted across restarts.
 */
@Composable
fun GenerationSettingsDialog(
    settings: GenerationSettings,
    onDismiss: () -> Unit,
    onSave: (GenerationSettings) -> Unit
) {
    var temperature by remember { mutableStateOf(settings.temperature) }
    var topP by remember { mutableStateOf(settings.topP) }
    var frequencyPenalty by remember { mutableStateOf(settings.frequencyPenalty) }
    var presencePenalty by remember { mutableStateOf(settings.presencePenalty) }
    var maxTokens by remember { mutableStateOf(settings.maxTokens.toFloat()) }
    var seedText by remember { mutableStateOf(settings.seed?.toString() ?: "") }
    var enableThinking by remember { mutableStateOf(settings.enableThinking) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generation Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SliderRow(
                    label = "Temperature",
                    info = "Controls randomness. 0 = deterministic and factual, higher = more creative.",
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                    steps = 19,
                    valueFormat = { String.format("%.1f", it) }
                )

                SliderRow(
                    label = "Top P",
                    info = "Nucleus sampling — only considers tokens in the top probability mass. Lower = more focused.",
                    value = topP,
                    onValueChange = { topP = it },
                    valueRange = 0f..1f,
                    steps = 19,
                    valueFormat = { String.format("%.2f", it) }
                )

                SliderRow(
                    label = "Frequency Penalty",
                    info = "Reduces word repetition. Positive values penalize tokens that have already appeared.",
                    value = frequencyPenalty,
                    onValueChange = { frequencyPenalty = it },
                    valueRange = -2f..2f,
                    steps = 19,
                    valueFormat = { String.format("%.2f", it) }
                )

                SliderRow(
                    label = "Presence Penalty",
                    info = "Encourages topic diversity. Positive values push toward ideas that haven't been mentioned yet.",
                    value = presencePenalty,
                    onValueChange = { presencePenalty = it },
                    valueRange = -2f..2f,
                    steps = 19,
                    valueFormat = { String.format("%.2f", it) }
                )

                SliderRow(
                    label = "Max Tokens",
                    info = "Maximum length of the response. Higher allows longer answers, lower keeps them concise.",
                    value = maxTokens,
                    onValueChange = { maxTokens = it },
                    valueRange = 128f..2048f,
                    steps = 29,
                    valueFormat = { it.roundToInt().toString() }
                )

                // Thinking toggle: reasoning models trace before answering; off skips
                // the trace for a fast direct answer.
                var showThinkingInfo by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Thinking", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "About Thinking",
                            tint = if (showThinkingInfo) AbbasBlue else Color.Gray.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp).clickable { showThinkingInfo = !showThinkingInfo }
                        )
                    }
                    Switch(checked = enableThinking, onCheckedChange = { enableThinking = it })
                }
                if (showThinkingInfo) {
                    Text(
                        "Reasoning models (Qwen3, DeepSeek R1, …) show a thinking trace before answering. " +
                            "Turn off for faster, direct replies — the model answers without reasoning first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Seed with info icon
                var showSeedInfo by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Seed", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "About Seed",
                        tint = if (showSeedInfo) AbbasBlue else Color.Gray.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp).clickable { showSeedInfo = !showSeedInfo }
                    )
                }
                if (showSeedInfo) {
                    Text(
                        "Fixes the random number sequence so the same seed produces the same response.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(2.dp))

                OutlinedTextField(
                    value = seedText,
                    onValueChange = { newVal ->
                        seedText = newVal.filter { c -> c.isDigit() }
                    },
                    label = { Text("Seed (empty = random)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                TextButton(
                    onClick = {
                        temperature = 0.7f
                        topP = 0.9f
                        frequencyPenalty = 0f
                        presencePenalty = 0f
                        maxTokens = 2048f
                        seedText = ""
                        enableThinking = true
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Reset to Defaults", color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        GenerationSettings(
                            temperature = temperature,
                            topP = topP,
                            frequencyPenalty = frequencyPenalty,
                            presencePenalty = presencePenalty,
                            maxTokens = maxTokens.roundToInt().coerceIn(64, 2048),
                            seed = seedText.toIntOrNull(),
                            enableThinking = enableThinking
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = AbbasBlue)
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SliderRow(
    label: String,
    info: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueFormat: (Float) -> String
) {
    var showInfo by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "About $label",
                    tint = if (showInfo) AbbasBlue else Color.Gray.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp).clickable { showInfo = !showInfo }
                )
            }
            Text(
                valueFormat(value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showInfo) {
            Text(
                info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}