package ai.abbas.app.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Pure-logic tests for the DOCX text extraction helper (no Android deps). */
class DocumentTextExtractorTest {

    @Test
    fun `paragraphs become newlines and tags are stripped`() {
        val xml = "<w:body><w:p><w:r><w:t>Hello</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>World</w:t></w:r></w:p></w:body>"
        assertEquals("Hello\nWorld", DocumentTextExtractor.docxXmlToText(xml))
    }

    @Test
    fun `tabs and breaks are preserved`() {
        val xml = "<w:p><w:r><w:t>A</w:t></w:r><w:tab/><w:r><w:t>B</w:t></w:r><w:br/></w:p>"
        assertEquals("A\tB", DocumentTextExtractor.docxXmlToText(xml))
    }

    @Test
    fun `entities are decoded`() {
        val xml = "<w:p><w:r><w:t>a &amp; b &lt;x&gt;</w:t></w:r></w:p>"
        assertEquals("a & b <x>", DocumentTextExtractor.docxXmlToText(xml))
    }

    @Test
    fun `output never contains markup`() {
        val xml = "<w:p><w:r><w:t>keep</w:t></w:r></w:p>"
        assertFalse(DocumentTextExtractor.docxXmlToText(xml).contains("<"))
    }
}
