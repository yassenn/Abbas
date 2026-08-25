package ai.abbas.app.repository

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * Privacy-focused web search via DuckDuckGo Lite.
 *
 * No API keys required and no user tracking is performed.
 *
 * DDG Lite v2 layout (2025+): result-link and result-snippet are in separate
 * adjacent <tr> rows. Title/URL come from the row containing class='result-link';
 * snippet text comes from the next row's <td class='result-snippet'>.
 */
object WebSearchRepository {

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .build()
    }

    data class SearchResult(
        val title: String,
        val snippet: String,
        val url: String
    )

    /**
     * Search DuckDuckGo Lite for the given query and return a list of
     * up to [maxResults] search results.
     *
     * This runs synchronously; should be called from a coroutine.
     */
    @JvmStatic
    fun search(query: String, maxResults: Int = 3): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://lite.duckduckgo.com/lite/?q=$encoded"
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            )
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return emptyList()
                parseResultsV2(body, maxResults)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * DDG Lite v2 parser: result-link <a> tags and result-snippet <td> blocks
     * appear in separate rows. Extract each independently, then pair by order.
     */
    private fun parseResultsV2(html: String, maxResults: Int): List<SearchResult> {
        // Extract all result-link anchors: <a ... class='result-link' ... href="...">Title</a>
        val linkMatcher = RESULT_LINK_PATTERN.matcher(html)
        val links = mutableListOf<Pair<String, String>>() // (title, url)

        while (linkMatcher.find()) {
            if (links.size >= maxResults) break
            val rawTitle = linkMatcher.group(2)?.trim() ?: continue
            val rawUrl = linkMatcher.group(1)?.trim() ?: ""
            val title = htmlDecode(rawTitle)
            val url = htmlDecode(rawUrl)
            if (title.isNotBlank()) {
                links.add(title to url)
            }
        }

        // Extract all result-snippet blocks: <td class='result-snippet'>...</td>
        val snippetMatcher = RESULT_SNIPPET_PATTERN.matcher(html)
        val snippets = mutableListOf<String>()

        while (snippetMatcher.find()) {
            if (snippets.size >= maxResults) break
            val raw = snippetMatcher.group(1) ?: ""
            val cleaned = htmlDecode(raw)
                .replace(Regex("<[^>]+>"), " ")   // strip remaining tags
                .replace(Regex("\\s+"), " ")        // collapse whitespace
                .trim()
            if (cleaned.isNotBlank()) {
                snippets.add(cleaned)
            }
        }

        // Pair links with snippets (snippets appear after their corresponding link)
        val results = mutableListOf<SearchResult>()
        for (i in links.indices) {
            if (results.size >= maxResults) break
            results.add(
                SearchResult(
                    title = links[i].first,
                    url = links[i].second,
                    snippet = snippets.getOrElse(i) { "" }
                )
            )
        }

        return results
    }

    // Matches: <a ... href="URL" ... class='result-link' ...>Title</a>
    // OR:      <a ... class='result-link' ... href="URL" ...>Title</a>
    // Group 1 = href value, Group 2 = inner text
    private val RESULT_LINK_PATTERN = Pattern.compile(
        """<a[^>]*\bhref\s*=\s*['"]([^'"]*?)['"][^>]*\bclass\s*=\s*['"]result-link['"][^>]*>(.*?)</a>""",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    // Matches: <td class='result-snippet'>...</td>
    // Group 1 = inner content
    private val RESULT_SNIPPET_PATTERN = Pattern.compile(
        """<td[^>]*\bclass\s*=\s*['"]result-snippet['"][^>]*>(.*?)</td>""",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    /**
     * Minimal HTML entity decoder for the most common cases.
     */
    private fun htmlDecode(input: String): String {
        return input
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&#x2F;", "/")
            .replace("&apos;", "'")
    }
}
