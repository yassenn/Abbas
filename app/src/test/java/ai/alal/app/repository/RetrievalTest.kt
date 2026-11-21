package ai.alal.app.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalTest {

    @Test
    fun chunksKeepPunctuationCaseAndOverlap() {
        val text = (1..60).joinToString(" ") { "Sentence number $it ends here." }
        val chunks = TextChunker.chunk(text, targetWords = 40, overlapWords = 10)
        assertTrue(chunks.size > 3)
        assertTrue(chunks.first().startsWith("Sentence number 1 ends here."))
        // The last sentence of a chunk reappears at the start of the next one.
        val lastOfFirst = chunks[0].substringAfterLast("Sentence")
        assertTrue(chunks[1].contains("Sentence$lastOfFirst"))
    }

    @Test
    fun bm25FindsTheRightChunkAndUsesTitles() {
        val docs = listOf(
            Bm25.Doc(1, "The capital of Alalistan is Zorblax, a city of libraries."),
            Bm25.Doc(2, "Photosynthesis converts light into chemical energy in plants."),
            Bm25.Doc(3, "Invoice total is 450 euros, due on Friday.", title = "Plumber invoice.pdf")
        )
        assertEquals(1, Bm25.rank("What is the capital of Alalistan?", docs, 3).first().key)
        assertEquals(3, Bm25.rank("how much is the plumber invoice", docs, 3).first().key)
        // Off-topic chunks are not returned at all.
        assertTrue(Bm25.rank("capital of Alalistan", docs, 3).none { it.key == 2 })
        assertTrue(Bm25.rank("the of and", docs, 3).isEmpty())
    }
}
