package ai.abbas.app.rag

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern

/**
 * Simple knowledge base that loads text files from assets and provides keyword-based retrieval.
 */
class KnowledgeBase(private val assetManager: AssetManager) {

    private val documents = mutableListOf<Document>()

    init {
        loadDocumentsFromAssets("knowledge_base")
    }

    private fun loadDocumentsFromAssets(folderName: String) {
        try {
            val files = assetManager.list(folderName) ?: return
            for (fileName in files) {
                val input = assetManager.open("$folderName/$fileName")
                val content = input.bufferedReader().use { it.readText() }
                documents.add(Document(fileName, content))
                input.close()
            }
        } catch (e: Exception) {
            // If the folder doesn't exist or any other error, we start with an empty knowledge base.
            // In a real app, you might want to log this.
        }
    }

    /**
     * Simple search: returns the top k documents that contain the most query terms.
     * @param query The user's query.
     * @param k Number of top results to return.
     * @return List of document contents, ordered by relevance.
     */
    suspend fun search(query: String, k: Int = 3): List<String> = withContext(Dispatchers.Default) {
        val queryTerms = tokenize(query.toLowerCase())
        if (queryTerms.isEmpty()) {
            emptyList()
        } else {
            val scoredDocs = documents.map { doc ->
                val contentLower = doc.content.toLowerCase()
                var score = 0
                for (term in queryTerms) {
                    if (contentLower.contains(term)) {
                        score++
                    }
                }
                Pair(score, doc)
            }.filter { it.first > 0 }
                .sortedByDescending { it.first }
                .take(k)
                .map { it.second.content }

            scoredDocs
        }
    }

    private fun tokenize(text: String): List<String> {
        // Simple tokenization: split by non-letter characters and keep non-empty tokens.
        return Pattern.compile("[^a-zA-Z]+").split(text).filter { it.isNotEmpty() }
    }

    private data class Document(val title: String, val content: String)
}