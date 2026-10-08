package ai.abbas.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.abbas.app.data.DocumentWithChunkCount
import ai.abbas.app.ui.theme.AbbasBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists the documents that have been joined to the knowledge base. Each document is shown
 * with the title of the chat it is attached to, so the user can see at a glance which
 * conversation owns it (documents are scoped to a single chat).
 */
@Composable
fun DocumentsScreen(
    documents: List<DocumentWithChunkCount>,
    onDelete: (Long) -> Unit
) {
    if (documents.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = Color.Gray.copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "No documents yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Attach a PDF, .docx or text file from the chat composer (+ → Attach file) to add it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "${documents.size} document${if (documents.size == 1) "" else "s"} in the knowledge base",
            style = MaterialTheme.typography.labelMedium,
            color = Color.Gray,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(documents, key = { it.id }) { doc ->
                DocumentRow(doc = doc, onDelete = { onDelete(doc.id) })
            }
        }
    }
}

@Composable
private fun DocumentRow(doc: DocumentWithChunkCount, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                tint = AbbasBlue,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                // The chat this document belongs to.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Chat,
                        contentDescription = null,
                        tint = AbbasBlue.copy(alpha = 0.8f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        doc.sessionTitle ?: "No chat",
                        style = MaterialTheme.typography.labelSmall,
                        color = AbbasBlue.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${doc.chunkCount} chunk${if (doc.chunkCount == 1) "" else "s"} · ${formatDate(doc.addedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "Remove document",
                    tint = Color.Gray.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private val DATE_FORMAT = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())

private fun formatDate(millis: Long): String = DATE_FORMAT.format(Date(millis))
