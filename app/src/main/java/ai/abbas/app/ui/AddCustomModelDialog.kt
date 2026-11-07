package ai.abbas.app.ui

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
import ai.abbas.app.data.ModelConfig
import ai.abbas.app.data.validateCustomModel
import ai.abbas.app.ui.theme.AbbasBlue

/** Everything needed to explain one field of the custom-model form. */
private data class FieldHelp(
    val title: String,
    val what: String,
    val how: String,
    val example: String
)

private val HELP_ID = FieldHelp(
    title = "Model ID",
    what = "A short, unique key for this model. It names the folder on disk and " +
        "remembers your choice between launches.",
    how = "Invent it yourself — letters, digits, '.', '_' and '-' only, up to 64 " +
        "characters. It does not have to match anything on Hugging Face.",
    example = "Qwen3.5-4B-Q4_K_M"
)

private val HELP_NAME = FieldHelp(
    title = "Display Name",
    what = "The label shown in the model picker at the top of the chat.",
    how = "Type anything readable — model family, size and quantisation are the " +
        "usual convention.",
    example = "Qwen 3.5 4B (my quant)"
)

private val HELP_GGUF = FieldHelp(
    title = "GGUF Filename",
    what = "The exact name of the .gguf file to download, inside the repository.",
    how = "On the model's Hugging Face page open the Files tab and copy the file " +
        "name character for character (it is case-sensitive). It must end in " +
        ".gguf and cannot contain folders.\n\n" +
        "Tip: prefer a Q4_K_M quant — 4-bit, good quality/size balance for phones.",
    example = "Qwen3.5-4B-Q4_K_M.gguf"
)

private val HELP_URL = FieldHelp(
    title = "Base URL",
    what = "The https address of the folder that holds the .gguf file.",
    how = "On the model page: Files tab → open the .gguf → menu → Copy download " +
        "link. You get something like\n" +
        "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf\n" +
        "Delete the filename at the end and keep the trailing slash. The URL must " +
        "contain /resolve/main/ — /blob/ links point at the web page, not the file.\n\n" +
        "Gated repos (Llama, some Gemma/Qwen) fail with 401/403: accept the licence " +
        "on the model page first and paste a read token in AI Hub.",
    example = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/"
)

private val HELP_RAM = FieldHelp(
    title = "Estimated RAM (bytes)",
    what = "How much memory the model occupies while loaded. The app refuses to " +
        "load models that need more RAM than the device can spare.",
    how = "Use the .gguf file size shown on the Files tab, plus roughly 20% for the " +
        "context window. Convert GB to bytes by adding nine zeros.",
    example = "2.5 GB → 2500000000"
)

private val HELP_CTX = FieldHelp(
    title = "Context Size",
    what = "How many tokens (roughly words) the model can keep in mind at once — " +
        "the conversation length it can actually remember.",
    how = "4096 is a safe default. The model card lists a maximum; going above it " +
        "will error. Bigger context also means more RAM.",
    example = "4096"
)

/**
 * Form for adding a GGUF model that is not in the catalogue.
 *
 * Every field has an info button explaining where its value comes from on
 * Hugging Face, because the form is only useful to someone who already knows
 * how a GGUF repo is laid out.
 */
@Composable
fun AddCustomModelDialog(onDismiss: () -> Unit, onAdd: (ModelConfig) -> Unit) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var ggufFile by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var ramStr by remember { mutableStateOf("") }
    var ctxStr by remember { mutableStateOf("4096") }
    var help by remember { mutableStateOf<FieldHelp?>(null) }

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

    help?.let { field ->
        AlertDialog(
            onDismissRequest = { help = null },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null, tint = AbbasBlue) },
            title = { Text(field.title) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(field.what, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(field.how, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Example: ${field.example}",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = AbbasBlue
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { help = null }) { Text("Got it", color = AbbasBlue) }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Model") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CustomModelGuide()
                Spacer(Modifier.height(4.dp))

                HelpField(
                    value = id,
                    onValueChange = { id = it },
                    label = "Model ID",
                    placeholder = HELP_ID.example,
                    onHelp = { help = HELP_ID }
                )
                HelpField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Display Name",
                    placeholder = "Qwen 3.5 4B",
                    onHelp = { help = HELP_NAME }
                )
                HelpField(
                    value = ggufFile,
                    onValueChange = { ggufFile = it },
                    label = "GGUF Filename",
                    placeholder = HELP_GGUF.example,
                    onHelp = { help = HELP_GGUF }
                )
                HelpField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = "Base URL",
                    placeholder = "https://huggingface.co/…/resolve/main/",
                    onHelp = { help = HELP_URL },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                HelpField(
                    value = ramStr,
                    onValueChange = { ramStr = it.filter { c -> c.isDigit() } },
                    label = "Estimated RAM (bytes)",
                    placeholder = HELP_RAM.example,
                    onHelp = { help = HELP_RAM },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                HelpField(
                    value = ctxStr,
                    onValueChange = { ctxStr = it.filter { c -> c.isDigit() } },
                    label = "Context Size",
                    placeholder = HELP_CTX.example,
                    onHelp = { help = HELP_CTX },
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

/** Three-step cheat sheet so the form makes sense before the first field. */
@Composable
private fun CustomModelGuide() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = AbbasBlue.copy(alpha = 0.07f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Any GGUF model works — Hugging Face is the usual source.",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            GuideStep("1", "Open the model's page on huggingface.co and pick the Files tab.")
            GuideStep("2", "Copy a .gguf filename and its /resolve/main/ URL (tap ⓘ for where each goes).")
            GuideStep("3", "Add it here, then download it from AI Hub — weights never leave the phone.")
        }
    }
}

@Composable
private fun GuideStep(number: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            number,
            style = MaterialTheme.typography.labelSmall,
            color = AbbasBlue,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

/** Outlined text field with an info button that opens [onHelp]. */
@Composable
private fun HelpField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    onHelp: () -> Unit,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = keyboardOptions,
        trailingIcon = {
            IconButton(onClick = onHelp, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = "How to find $label",
                    tint = AbbasBlue.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    )
}