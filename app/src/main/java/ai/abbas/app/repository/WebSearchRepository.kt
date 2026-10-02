package ai.abbas.app.repository

import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.InputStream
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
        /** Readable body text scraped from the result page (empty when unavailable). */
        val content: String = "",
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

    /**
     * Run [search], then fetch and extract the readable text of each result page
     * in parallel. This is what lets the model actually answer data questions
     * (weather, prices, dates) instead of hallucinating from link stubs.
     *
     * Runs synchronously; call from a coroutine. Pages that fail to load, are
     * non-HTML, or are empty contribute no [SearchResult.content].
     */
    suspend fun searchWithContent(
        query: String,
        maxResults: Int = 3,
        perPageChars: Int = 1200
    ): List<SearchResult> {
        val results = search(query, maxResults)
        if (results.isEmpty()) return results
        return coroutineScope {
            results
                .map { r -> async(Dispatchers.IO) { r.copy(content = fetchPageText(r.url, perPageChars)) } }
                .awaitAll()
        }
    }

    /** Download [url] and return its readable body text, capped at [maxChars]. */
    private fun fetchPageText(url: String, maxChars: Int): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return ""
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return try {
            client.newCall(request).execute().use { response ->
                val type = response.header("Content-Type").orEmpty()
                if (!type.contains("text/html", true) && !type.contains("text/plain", true)) return ""
                val body = response.body?.byteStream()?.use { readCapped(it, MAX_PAGE_BYTES) } ?: return ""
                htmlToText(body).take(maxChars)
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** Read at most [cap] bytes from [input] as UTF-8 (guards against huge pages). */
    private fun readCapped(input: InputStream, cap: Int): String {
        val buf = ByteArray(cap)
        var off = 0
        while (off < cap) {
            val n = input.read(buf, off, cap - off)
            if (n < 0) break
            off += n
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    /**
     * Crude but dependency-free HTML → text: drop script/style/head, break on
     * block tags, strip the rest, decode entities, collapse whitespace. Good
     * enough to surface page content to an LLM without pulling in a parser.
     *
     * Visible for testing.
     */
    internal fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style|noscript|svg|head|nav|footer)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|li|tr|h[1-6]|section|article|td)>"), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = htmlDecode(s)
        s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n\\s*\\n+"), "\n")
        return s.trim()
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    private const val MAX_PAGE_BYTES = 512 * 1024

    private fun fetch(query: String, limit: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://lite.duckduckgo.com/lite/?q=$encoded"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
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
