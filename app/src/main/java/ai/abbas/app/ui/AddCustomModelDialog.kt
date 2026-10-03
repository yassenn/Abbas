package ai.abbas.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ai.abbas.app.data.ModelConfig
import ai.abbas.app.data.validateCustomModel

@Composable
fun AddCustomModelDialog(onDismiss: () -> Unit, onAdd: (ModelConfig) -> Unit) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var ggufFile by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var ramStr by remember { mutableStateOf("") }
    var ctxStr by remember { mutableStateOf("4096") }

    // Validate the candidate as the user types; custom model inputs are untrusted.
    val candidate = ModelConfig(
        id = id,
        name = name,
        ggufFile = ggufFile,
        baseUrl = baseUrl,
        estimatedRamBytes = ramStr.toLongOrNull() ?: 0L,
        contextSize = ctxStr.toIntOrNull() ?: 4096,
        isCustom = true
    )
    val validationError = validateCustomModel(candidate)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("Model ID") })
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display Name") })
                OutlinedTextField(value = ggufFile, onValueChange = { ggufFile = it }, label = { Text("GGUF Filename (e.g. model-q4_k_m.gguf)") })
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL (https:// HuggingFace Resolve)") })
                OutlinedTextField(
                    value = ramStr,
                    onValueChange = { ramStr = it.filter { c -> c.isDigit() } },
                    label = { Text("Estimated RAM (Bytes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = ctxStr,
                    onValueChange = { ctxStr = it.filter { c -> c.isDigit() } },
                    label = { Text("Context Size") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                if (validationError != null && (id.isNotBlank() || ggufFile.isNotBlank() || baseUrl.isNotBlank())) {
                    Text(
                        validationError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = validationError == null,
                onClick = { onAdd(candidate) }
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
