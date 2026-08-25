package ai.abbas.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ai.abbas.app.data.ModelConfig

@Composable
fun AddCustomModelDialog(onDismiss: () -> Unit, onAdd: (ModelConfig) -> Unit) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var modelLib by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var vramStr by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("Model ID") })
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display Name") })
                OutlinedTextField(value = modelLib, onValueChange = { modelLib = it }, label = { Text("Model Lib Identifier") })
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL (HuggingFace Resolve)") })
                OutlinedTextField(
                    value = vramStr,
                    onValueChange = { vramStr = it.filter { c -> c.isDigit() } },
                    label = { Text("Estimated VRAM (Bytes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val vram = vramStr.toLongOrNull() ?: 0L
                if (id.isNotBlank() && name.isNotBlank() && modelLib.isNotBlank() && baseUrl.isNotBlank()) {
                    onAdd(ModelConfig(id, name, modelLib, baseUrl, vram, isCustom = true))
                }
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
