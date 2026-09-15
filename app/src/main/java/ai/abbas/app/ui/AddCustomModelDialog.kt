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
    var ggufFile by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var ramStr by remember { mutableStateOf("") }
    var ctxStr by remember { mutableStateOf("4096") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("Model ID") })
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display Name") })
                OutlinedTextField(value = ggufFile, onValueChange = { ggufFile = it }, label = { Text("GGUF Filename (e.g. model-q4_k_m.gguf)") })
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL (HuggingFace Resolve)") })
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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ram = ramStr.toLongOrNull() ?: 0L
                val ctx = ctxStr.toIntOrNull() ?: 4096
                if (id.isNotBlank() && name.isNotBlank() && ggufFile.isNotBlank() && baseUrl.isNotBlank()) {
                    onAdd(ModelConfig(id, name, ggufFile, baseUrl, ram, ctx, isCustom = true))
                }
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}