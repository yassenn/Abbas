package ai.abbas.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.abbas.app.data.PromptSuggestion
import ai.abbas.app.data.PromptSuggestions
import ai.abbas.app.data.SystemPromptSettings
import ai.abbas.app.ui.theme.AbbasBlue

/**
 * System Prompt tab.
 *
 * Lets the user shape *who* the assistant is (persona), *how* it speaks
 * (form of speech), and a handful of behavioural extras, without having to
 * write prompt-engineering text by hand. The selections are folded into a
 * single system turn by [SystemPromptSettings.buildPrompt] and applied to every
 * new message (see ChatViewModel.generateInternal).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SystemPromptScreen(
    savedSettings: SystemPromptSettings,
    onSave: (SystemPromptSettings) -> Unit,
    onReset: () -> Unit
) {
    // Local draft — the model only sees the prompt after the user taps Apply,
    // so experimenting with chips can't silently change the assistant mid-chat.
    var personaId by remember(savedSettings) { mutableStateOf(savedSettings.personaId) }
    var styleIds by remember(savedSettings) { mutableStateOf(savedSettings.styleIds) }
    var optionIds by remember(savedSettings) { mutableStateOf(savedSettings.optionIds) }
    var customInstructions by remember(savedSettings) {
        mutableStateOf(savedSettings.customInstructions)
    }

    val draft = SystemPromptSettings(
        customInstructions = customInstructions,
        personaId = personaId,
        styleIds = styleIds,
        optionIds = optionIds
    )
    val preview = draft.buildPrompt()
    val dirty = draft != savedSettings

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { PromptHeader(active = savedSettings.isConfigured) }

        item {
            SectionHeader(
                icon = Icons.Outlined.TheaterComedy,
                title = "Persona",
                subtitle = "Who should the assistant be? Pick one."
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonaRow(
                    label = "No persona",
                    description = "Use the model's default assistant voice",
                    selected = personaId == null,
                    onClick = { personaId = null }
                )
                PromptSuggestions.personas.forEach { persona ->
                    PersonaRow(
                        label = persona.label,
                        description = persona.description,
                        selected = personaId == persona.id,
                        onClick = {
                            personaId = if (personaId == persona.id) null else persona.id
                        }
                    )
                }
            }
        }

        item {
            SectionHeader(
                icon = Icons.Outlined.RecordVoiceOver,
                title = "Form of speech",
                subtitle = "How should it talk? Pick as many as you like."
            )
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PromptSuggestions.speechStyles.forEach { style ->
                    FilterChip(
                        selected = style.id in styleIds,
                        onClick = { styleIds = styleIds.toggle(style.id) },
                        label = { Text(style.label) },
                        leadingIcon = if (style.id in styleIds) {
                            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AbbasBlue.copy(alpha = 0.14f),
                            selectedLabelColor = AbbasBlue,
                            selectedLeadingIconColor = AbbasBlue
                        )
                    )
                }
            }
        }

        item {
            SectionHeader(
                icon = Icons.Outlined.AutoAwesome,
                title = "Other options",
                subtitle = "Behaviour tweaks that apply to every answer."
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PromptSuggestions.extraOptions.forEach { option ->
                    OptionToggle(
                        option = option,
                        checked = option.id in optionIds,
                        onCheckedChange = { optionIds = optionIds.toggle(option.id) }
                    )
                }
            }
        }

        item {
            SectionHeader(
                icon = Icons.Outlined.Edit,
                title = "Custom instructions",
                subtitle = "Anything else the assistant should know. Written in your own words."
            )
        }
        item {
            OutlinedTextField(
                value = customInstructions,
                onValueChange = { if (it.length <= MAX_CUSTOM_LENGTH) customInstructions = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. I'm learning Android development — quiz me at the end of explanations.") },
                minLines = 4,
                maxLines = 8,
                supportingText = {
                    Text("${customInstructions.length} / $MAX_CUSTOM_LENGTH")
                },
                shape = RoundedCornerShape(12.dp)
            )
        }

        item {
            Text(
                "Prompt preview",
                style = MaterialTheme.typography.labelMedium,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ) {
                Text(
                    text = preview.ifBlank {
                        "No system prompt — the assistant will use its default behaviour."
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = if (preview.isBlank()) FontFamily.Default else FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = if (preview.isBlank()) Color.Gray else MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { onSave(draft) },
                enabled = dirty,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AbbasBlue)
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (dirty) "Apply system prompt" else "System prompt applied",
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (savedSettings.isConfigured || draft.isConfigured) {
                OutlinedButton(
                    onClick = {
                        personaId = null
                        styleIds = emptySet()
                        optionIds = emptySet()
                        customInstructions = ""
                        onReset()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.4f))
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.Gray
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Clear all", color = Color.Gray)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private const val MAX_CUSTOM_LENGTH = 2000

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id

@Composable
private fun PromptHeader(active: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AbbasBlue.copy(alpha = 0.06f))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = AbbasBlue,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Shape how Abbas behaves",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "A system prompt is a private set of instructions sent with every " +
                "message. Mix a persona, a form of speech and a few rules — the " +
                "assistant follows them for the rest of the conversation.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (active) "Active — applied to new messages" else "Not set — using the default assistant voice",
            style = MaterialTheme.typography.labelMedium,
            color = if (active) AbbasBlue else Color.Gray,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = AbbasBlue, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.padding(start = 26.dp)
        )
    }
}

@Composable
private fun PersonaRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) AbbasBlue.copy(alpha = 0.08f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = if (selected) BorderStroke(1.dp, AbbasBlue.copy(alpha = 0.6f)) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = AbbasBlue)
            )
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) AbbasBlue else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun OptionToggle(
    option: PromptSuggestion,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                option.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                option.description,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AbbasBlue)
        )
    }
}