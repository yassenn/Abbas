package ai.abbas.app

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.abbas.app.repository.KnowledgeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChunkOverlapBugTest {

    private lateinit var context: Context
    private lateinit var knowledgeRepository: KnowledgeRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        knowledgeRepository = KnowledgeRepository.getInstance(context)
    }

    /**
     * RED test: With chunkSize=200 and overlap=20, each subsequent chunk
     * should advance by ~180 words (chunkSize - overlap), not by ~20 words.
     * 
     * Bug: start = end - chunkSize + overlap = end - (chunkSize-overlap) = end - 180.
     * But the correct formula should be: start = end - overlap.
     * 
     * For 1000 words, correct ≈ floor(1000/180) + 1 ≈ 6 chunks.
     * Buggy code gives ≈ floor(1000/20) ≈ 50 chunks — 8x redundancy.
     * 
     * We verify that chunks are not massively redundant.
     */
    @Test
    fun testChunksAreNotMassivelyRedundant() = runBlocking {
        // Generate 600 distinct words so each chunk should have mostly new content
        val words = (1..600).joinToString(" ") { "word$it" }

        val file = File(context.cacheDir, "chunk_overlap_test.txt")
        file.writeText(words)

        val uri = Uri.fromFile(file)
        val docId = knowledgeRepository.addDocument(uri)
        assertTrue("Document ID should be positive", docId > 0)

        // With chunkSize≈200, overlap≈20, we expect roughly 600/(200-20) ≈ 3-4 chunks.
        // Buggy code gives ~600/20 = ~30 chunks.
        val chunks = knowledgeRepository.search("word300 word301 word302", topK = 30)
        assertTrue("Should have at least 1 chunk", chunks.isNotEmpty())

        // A chunk created with proper overlap should NOT have the same content
        // appearing in most chunks. The bug makes each successive chunk
        // overlap ~90% with the previous. We check that we didn't get an
        // absurd number of chunks for 600 words.
        // Even generous: with correct overlap, max ≈ 10 (600/(200-20)=3.3 + some buffer).
        // Buggy: ~30 chunks. Let's assert less than 12.
        assertTrue(
            "Expected <= 12 chunks but got ${chunks.size}. Overlap formula may be buggy.",
            chunks.size <= 12
        )
    }
}
