package ai.abbas.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.abbas.app.data.DocumentWithChunkCount
import ai.abbas.app.ui.theme.AbbasBlue

/**
 * Lets the user pull documents that already exist in the app into the current chat.
 *
 * Documents that already belong to the active chat (or hold no readable text) are excluded,
 * so the picker only ever offers genuinely new material. Selecting is optional: cancelling,
 * or confirming with nothing ticked, simply adds nothing.
 */
@Composable
fun DocumentPickerDialog(
    documents: List<DocumentWithChunkCount>,
    currentSessionId: String?,
    onDismiss: () -> Unit,
    onPick: (List<Long>) -> Unit
) {
    val selectable = documents.filter { doc ->
        doc.chunkCount > 0 && !(currentSessionId != null && doc.sessionId == currentSessionId)
    }
    val selected = remember { mutableStateListOf<Long>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add from documents") },
        text = {
            when {
                documents.isEmpty() -> Text(
                    "No documents in the app yet. Attach a file to a chat first.",
                    style = MaterialTheme.typography.bodyMedium
                )
                selectable.isEmpty() -> Text(
                    "Every existing document is already attached to this chat.",
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> Column {
                    Text(
                        "Include documents that already exist in the app. A copy is added to " +
                            "this chat, so the original chat keeps its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(selectable, key = { it.id }) { doc ->
                            val checked = doc.id in selected
                            PickerRow(
                                doc = doc,
                                checked = checked,
                                onToggle = {
                                    if (checked) selected.remove(doc.id) else selected.add(doc.id)
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(selected.toList()) },
                enabled = selected.isNotEmpty()
            ) {
                Text("Add", color = AbbasBlue)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) }
        }
    )
}

@Composable
private fun PickerRow(
    doc: DocumentWithChunkCount,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggle() },
        color = if (checked) AbbasBlue.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = AbbasBlue)
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                tint = AbbasBlue,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Chat,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${doc.sessionTitle ?: "No chat"} · ${doc.chunkCount} chunk${if (doc.chunkCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
