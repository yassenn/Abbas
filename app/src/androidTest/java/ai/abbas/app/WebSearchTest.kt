package ai.abbas.app

import ai.abbas.app.repository.WebSearchRepository
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebSearchTest {

    @Test
    fun testSearchReturnsResults() {
        val results = WebSearchRepository.search("capital of France", maxResults = 3)
        assertTrue("Expected at least 1 result but got ${results.size}", results.isNotEmpty())
        assertTrue("Expected Paris in one of the snippets", results.any { it.snippet.contains("Paris", ignoreCase = true) || it.title.contains("Paris", ignoreCase = true) })
    }

    @Test
    fun testSearchLimitRespected() {
        val results = WebSearchRepository.search("kotlin programming", maxResults = 2)
        assertTrue("Should return at most 2 results", results.size <= 2)
    }
}
