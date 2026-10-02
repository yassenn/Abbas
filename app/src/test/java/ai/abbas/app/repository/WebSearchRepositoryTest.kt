package ai.abbas.app.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit test for the dependency-free HTML → text extractor that turns fetched
 * result pages into model-consumable content (see [WebSearchRepository.searchWithContent]).
 *
 * Regression context: web answers were generic because search results carried only
 * DDG Lite link stubs. The extractor now surfaces the real page body text.
 */
class WebSearchRepositoryTest {

    private fun text(html: String) = WebSearchRepository.htmlToText(html)

    @Test
    fun `drops script, style and head content`() {
        val html = """
            <html><head><title>t</title><style>.a{color:red}</style></head>
            <body><script>var x = 1;</script><p>Real content here</p></body></html>
        """.trimIndent()
        val out = text(html)
        assertTrue(out.contains("Real content here"))
        assertFalse(out.contains("var x"))
        assertFalse(out.contains("color:red"))
        assertFalse(out.contains("<title>"))
    }

    @Test
    fun `breaks block tags onto separate lines and strips tags`() {
        val out = text("<div><p>First sentence.</p><p>Second sentence.</p></div>")
        assertEquals("First sentence.\nSecond sentence.", out)
    }

    @Test
    fun `decodes common html entities`() {
        val out = text("<p>Fish &amp; Chips &quot;today&quot; &lt;5</p>")
        assertTrue(out.contains("Fish & Chips \"today\" <5"))
    }

    @Test
    fun `collapses runs of whitespace`() {
        assertEquals("a b c", text("<p>a    b\t\tc</p>"))
    }

    @Test
    fun `treats br as a line break`() {
        assertEquals("line one\nline two\nline three", text("line one<br>line two<br/>line three"))
    }

    @Test
    fun `empty html yields empty string`() {
        assertEquals("", text(""))
    }
}
