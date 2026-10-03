package ai.abbas.app.repository

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Thrown when a document's text cannot be extracted (e.g. scanned PDF, legacy .doc). */
class DocumentExtractionException(message: String) : Exception(message)

/**
 * Extracts plain text from user-attached documents for the knowledge base.
 *
 * Supported: plain text / Markdown / CSV, PDF (text-based, via PDFBox-Android),
 * and .docx (Office Open XML, unpacked locally). Scanned/image-only PDFs and the
 * legacy binary .doc format carry no extractable text and are rejected with a
 * clear message rather than ingesting binary noise.
 */
object DocumentTextExtractor {

    private const val MAX_BYTES = 32 * 1024 * 1024 // guard against zip bombs / huge docs
    @Volatile private var pdfBoxReady = false

    fun extract(context: Context, uri: Uri, displayName: String, mimeType: String?): String {
        val name = displayName.lowercase()
        val mime = mimeType?.lowercase().orEmpty()

        return when {
            mime == "application/pdf" || name.endsWith(".pdf") ->
                extractPdf(context, uri, displayName)

            mime.contains("wordprocessingml") || name.endsWith(".docx") ->
                extractDocx(context, uri, displayName)

            mime == "application/msword" || name.endsWith(".doc") ->
                throw DocumentExtractionException(
                    "\"$displayName\" is a legacy .doc file, which has no supported text extractor. " +
                        "Please re-save it as .docx or PDF."
                )

            else -> extractRawText(context, uri, displayName)
        }
    }

    private fun extractRawText(context: Context, uri: Uri, displayName: String): String {
        val bytes = open(context, uri).use { readCapped(it, MAX_BYTES) }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun extractPdf(context: Context, uri: Uri, displayName: String): String {
        ensurePdfBox(context)
        return try {
            open(context, uri).use { input ->
                PDDocument.load(input).use { doc ->
                    PDFTextStripper().getText(doc).trim()
                }
            }
        } catch (e: DocumentExtractionException) {
            throw e
        } catch (e: Exception) {
            throw DocumentExtractionException(
                "Could not read \"$displayName\". If it is a scanned/image-only PDF it has no " +
                    "extractable text; try a text-based PDF."
            )
        }
    }

    private fun extractDocx(context: Context, uri: Uri, displayName: String): String {
        return try {
            open(context, uri).use { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == "word/document.xml") {
                            val xml = readCapped(zip, MAX_BYTES).toString(Charsets.UTF_8)
                            return docxXmlToText(xml)
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            throw DocumentExtractionException("\"$displayName\" is not a valid .docx file.")
        } catch (e: DocumentExtractionException) {
            throw e
        } catch (e: Exception) {
            throw DocumentExtractionException("Could not read \"$displayName\": ${e.message}")
        }
    }

    private fun ensurePdfBox(context: Context) {
        if (!pdfBoxReady) {
            synchronized(this) {
                if (!pdfBoxReady) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    pdfBoxReady = true
                }
            }
        }
    }

    private fun open(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw DocumentExtractionException("Could not open the selected file.")

    private fun readCapped(input: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) throw DocumentExtractionException("Document is too large to ingest.")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Turn WordprocessingML (<w:t>/<w:p>/…) into readable text. */
    internal fun docxXmlToText(xml: String): String = xml
        .replace(Regex("</w:p>"), "\n")
        .replace(Regex("<w:tab[^>]*/>"), "\t")
        .replace(Regex("<w:br[^>]*/>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex(" {2,}"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n")
        .trim()
}
