package ai.abbas.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.abbas.app.repository.IngestedDocument
import ai.abbas.app.ui.theme.AbbasBlue

/**
 * Horizontal strip of documents attached to the current chat, shown directly above the
 * composer. Each chip carries a small square file-type preview, the document title, and a
 * dismiss button. Documents are scoped to the chat that owns them, so dismissing a chip
 * removes the document from this chat (and the knowledge base).
 */
@Composable
fun AttachmentStrip(
    documents: List<IngestedDocument>,
    onDetach: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(documents, key = { it.docId }) { doc ->
            AttachmentChip(doc = doc, onDetach = { onDetach(doc.docId) })
        }
    }
}

@Composable
private fun AttachmentChip(doc: IngestedDocument, onDetach: () -> Unit) {
    val badge = fileBadgeFor(doc.title)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Small square preview: a colour-coded file-type thumbnail.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(badge.color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    badge.label,
                    color = badge.color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.widthIn(max = 130.dp)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${doc.chunkCount} chunk${if (doc.chunkCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onDetach, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove document from chat",
                    tint = Color.Gray.copy(alpha = 0.8f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

private data class FileBadge(val label: String, val color: Color)

/** Maps a document title's extension to a short badge label and accent colour. */
private fun fileBadgeFor(title: String): FileBadge {
    val ext = title.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> FileBadge("PDF", Color(0xFFD32F2F))
        "doc", "docx" -> FileBadge("DOC", Color(0xFF1565C0))
        "txt", "md" -> FileBadge("TXT", Color(0xFF00695C))
        "csv" -> FileBadge("CSV", Color(0xFF2E7D32))
        else -> FileBadge("FILE", AbbasBlue)
    }
}
