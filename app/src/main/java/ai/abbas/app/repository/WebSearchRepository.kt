package ai.abbas.app.repository

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.regex.Pattern

/** How aggressively non-authoritative sources are filtered out of a result set. */
enum class SourcePriority {
    /** Authoritative sources first, others kept as filler. */
    CREDIBLE_FIRST,
    /** Only authoritative sources — falls back to the full pool if none exist. */
    CREDIBLE_ONLY
}

/**
 * Domains operated by governments, universities, international bodies, and
 * established reference/news organisations — the sources the "search mode"
 * summaries are allowed to lean on. Matched against the registrable-domain
 * suffix of the result's host.
 */
private val CREDIBLE_DOMAIN_SUFFIXES = listOf(
    ".gov", ".mil", ".edu", ".int", ".ac.uk", ".gov.uk", ".nhs.uk",
    "who.int", "un.org", "unesco.org", "worldbank.org", "imf.org", "oecd.org",
    "europa.eu", "nih.gov", "cdc.gov", "nasa.gov", "noaa.gov", "mayoclinic.org",
    "nature.com", "science.org", "sciencedirect.com", "ieee.org", "acm.org",
    "arxiv.org", "pubmed.ncbi.nlm.nih.gov", "jstor.org",
    "wikipedia.org", "britannica.com",
    "reuters.com", "apnews.com", "bbc.com", "bbc.co.uk", "npr.org",
    "economist.com", "ft.com", "nytimes.com", "theguardian.com"
)

/**
 * DuckDuckGo Lite returns redirects (`//duckduckgo.com/l/?uddg=<real-url>`), so the
 * href must be unwrapped before the real host can be judged. Returns the input
 * unchanged when it is already direct or cannot be parsed.
 */
internal fun ddgResolvedUrl(raw: String): String {
    val normalized = if (raw.startsWith("//")) "https:$raw" else raw
    return try {
        val uri = URI(normalized)
        if (uri.host?.contains("duckduckgo.com") != true) return normalized
        val target = uri.query
            ?.split("&")
            ?.firstOrNull { it.startsWith("uddg=") }
            ?.removePrefix("uddg=")
            ?.let { URLDecoder.decode(it, "UTF-8") }
        target?.takeIf { it.isNotBlank() } ?: normalized
    } catch (_: Exception) {
        raw
    }
}

/** Lower-cased host of [url], or null when it cannot be parsed. */
internal fun hostOf(url: String): String? = try {
    URI(if (url.startsWith("//")) "https:$url" else url).host?.lowercase()
} catch (_: Exception) {
    null
}

/** True when [url]'s host belongs to a credible organisation. */
internal fun isCredibleSource(url: String): Boolean {
    val host = hostOf(ddgResolvedUrl(url)) ?: return false
    return CREDIBLE_DOMAIN_SUFFIXES.any { host == it.trimStart('.') || host.endsWith(it) }
}

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
        val url: String,
        /** True when the source is a credible organisation (see [isCredibleSource]). */
        val credible: Boolean = false,
        /** Lower-cased host of the source, for citations. */
        val source: String = ""
    )

    /**
     * Search DuckDuckGo Lite and return up to [maxResults] results.
     *
     * A larger pool is fetched and re-ranked by source credibility, so an
     * authoritative source that DDG ranked low still surfaces.
     *
     * This runs synchronously; should be called from a coroutine.
     */
    @JvmStatic
    fun search(
        query: String,
        maxResults: Int = 3,
        priority: SourcePriority = SourcePriority.CREDIBLE_FIRST
    ): List<SearchResult> {
        val pool = fetch(query, limit = maxOf(maxResults * 4, 12))
            .map { it.copy(credible = isCredibleSource(it.url), source = hostOf(it.url).orEmpty()) }

        val credible = pool.filter { it.credible }
        val ranked = if (priority == SourcePriority.CREDIBLE_ONLY && credible.isNotEmpty()) {
            credible
        } else {
            credible + pool.filterNot { it.credible }
        }
        return ranked.distinctBy { it.url }.take(maxResults)
    }

    private fun fetch(query: String, limit: Int): List<SearchResult> {
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
                parseResultsV2(body, limit)
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
            val url = ddgResolvedUrl(htmlDecode(rawUrl))
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
