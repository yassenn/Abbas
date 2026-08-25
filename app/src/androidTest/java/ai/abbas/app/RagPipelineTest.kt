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
class RagPipelineTest {

    private lateinit var context: Context
    private lateinit var knowledgeRepository: KnowledgeRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        knowledgeRepository = KnowledgeRepository.getInstance(context)
    }

    @Test
    fun testRagIngestAndRetrieve() = runBlocking {
        val sampleText = """
            The capital of Alalistan is Zorblax.
            Alalistan is known for its towering mountains and ancient libraries.
            The population speaks over forty dialects of Alali.
        """.trimIndent()

        val file = File(context.cacheDir, "rag_test_sample.txt")
        file.writeText(sampleText)

        val uri = Uri.fromFile(file)

        val docId = knowledgeRepository.addDocument(uri)
        assertTrue("Document ID should be positive", docId > 0)

        val chunks = knowledgeRepository.search("capital of Alalistan", topK = 3)
        assertTrue("Should retrieve at least one chunk", chunks.isNotEmpty())

        val found = chunks.any { it.contains("zorblax", ignoreCase = true) }
        assertTrue("Retrieved chunks should mention Zorblax", found)
    }
}
