package ai.abbas.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit test exposing the chunkText overlap formula bug.
 * 
 * Bug: start = end - chunkSize + overlap  → advances by 'overlap' per chunk
 * Correct: start = end - overlap           → advances by (chunkSize - overlap) per chunk
 */
class ChunkOverlapUnitTest {

    private val tokenizeRegex = Regex("""[\s\p{Punct}]+""")

    private fun tokenize(text: String): List<String> =
        tokenizeRegex.split(text).filter { it.isNotBlank() }

    // BUGGY version (current code in KnowledgeRepository.kt + ChunkWorker.kt)
    private fun chunkTextBuggy(text: String, chunkSize: Int, overlap: Int): List<String> {
        val words = tokenize(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < words.size) {
            val end = minOf(start + chunkSize, words.size)
            val chunk = words.subList(start, end).joinToString(" ")
            chunks.add(chunk)
            if (end == words.size) break
            start = end - chunkSize + overlap  // ← BUG: advances by overlap, not chunkSize-overlap
        }
        return chunks
    }

    // FIXED version
    private fun chunkTextFixed(text: String, chunkSize: Int, overlap: Int): List<String> {
        val words = tokenize(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < words.size) {
            val end = minOf(start + chunkSize, words.size)
            val chunk = words.subList(start, end).joinToString(" ")
            chunks.add(chunk)
            if (end == words.size) break
            start = end - overlap  // ← FIXED: start at the overlap point
        }
        return chunks
    }

    @Test
    fun `buggy chunking creates far too many chunks`() {
        val words = (1..1000).joinToString(" ") { "w$it" }
        val buggyChunks = chunkTextBuggy(words, chunkSize = 200, overlap = 20)

        // Buggy: advances by 20 each iteration, so ~50 chunks for 1000 words
        assertTrue("BUG: Expected > 40 chunks (got ${buggyChunks.size}) — proves overlap bug",
            buggyChunks.size > 40)
    }

    @Test
    fun `fixed chunking creates reasonable number of chunks`() {
        val words = (1..1000).joinToString(" ") { "w$it" }
        val fixedChunks = chunkTextFixed(words, chunkSize = 200, overlap = 20)

        // Fixed: advances by 180 each iteration
        // 1000 words: chunk 0→200, chunk 180→380, chunk 360→560, chunk 540→740,
        // chunk 720→920, chunk 900→1000 = 6 chunks
        assertEquals("Should produce ~6 chunks for 1000 words with chunk=200, overlap=20",
            6, fixedChunks.size)
    }

    @Test
    fun `chunks have correct overlap`() {
        val words = (1..1000).joinToString(" ") { "w$it" }
        val fixedChunks = chunkTextFixed(words, chunkSize = 200, overlap = 20)

        // Verify chunk 1 starts where chunk 0's overlap region begins
        val chunk0Words = tokenize(fixedChunks[0])
        val chunk1Words = tokenize(fixedChunks[1])

        // Chunk 1 should start at word index 180 (which is "w181" in 1-indexed)
        assertTrue("Chunk 1 should start near the overlap region of chunk 0",
            chunk1Words.first() == "w181")
        // Chunk 0's last 20 words should overlap with chunk 1's first 20 words
        val overlapFrom0 = chunk0Words.takeLast(20)
        val overlapFrom1 = chunk1Words.take(20)
        assertEquals("Chunks should have 20-word overlap", overlapFrom0, overlapFrom1)
    }

    @Test
    fun `buggy overlap is 80 percent not 10 percent`() {
        val words = (1..1000).joinToString(" ") { "w$it" }
        val buggyChunks = chunkTextBuggy(words, chunkSize = 200, overlap = 20)

        val chunk0Words = tokenize(buggyChunks[1])
        // Buggy code: start = 200-200+20 = 20. So chunk 1 starts at word index 20 ("w21")
        assertTrue("BUG: Chunk 1 starts at w21 instead of w181 (80% overlap instead of 10%)",
            chunk0Words.first() == "w21")
    }
}
