package ai.alal.app.repository

import kotlin.math.ln

/**
 * Splits text into ~[targetWords]-word chunks on sentence boundaries, keeping the
 * original casing and punctuation (the model reads these verbatim). Consecutive
 * chunks share about [overlapWords] words so facts on a boundary aren't lost.
 */
object TextChunker {
    private val sentenceEnd = Regex("(?<=[.!?…])\\s+|\\n\\s*\\n")

    fun chunk(text: String, targetWords: Int = 180, overlapWords: Int = 30): List<String> {
        val sentences = text.split(sentenceEnd)
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .flatMap { s ->
                // A run-on "sentence" (tables, PDFs without punctuation) is split by words.
                val words = s.split(' ')
                if (words.size <= targetWords) listOf(s)
                else words.chunked(targetWords).map { it.joinToString(" ") }
            }
        val chunks = mutableListOf<String>()
        val current = ArrayList<String>()
        var count = 0
        for (sentence in sentences) {
            val n = sentence.count { it == ' ' } + 1
            if (count + n > targetWords && current.isNotEmpty()) {
                chunks += current.joinToString(" ")
                // Carry trailing sentences (up to overlapWords) into the next chunk.
                val carry = ArrayList<String>()
                var carried = 0
                for (s in current.asReversed()) {
                    val w = s.count { it == ' ' } + 1
                    if (carried + w > overlapWords) break
                    carry.add(0, s); carried += w
                }
                current.clear(); current.addAll(carry); count = carried
            }
            current += sentence; count += n
        }
        if (current.isNotEmpty()) chunks += current.joinToString(" ")
        return chunks
    }
}

/**
 * Okapi BM25 keyword ranking: the standard lexical search scoring used by search
 * engines. Works fully offline with no model, handles names, numbers and exact
 * terms well, and is fast for the few hundred chunks a chat holds.
 */
object Bm25 {
    private const val K1 = 1.2
    private const val B = 0.75

    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "were", "be", "it",
        "this", "that", "with", "as", "at", "by", "from", "what", "which", "who", "how", "when", "where", "why",
        "do", "does", "did", "i", "you", "me", "my", "your", "we", "our", "they", "their", "about", "can", "could",
        "please", "tell", "give", "say", "says", "there", "any", "some", "into", "than", "then", "so", "if",
        "el", "la", "los", "las", "de", "del", "y", "en", "un", "una", "que", "es", "por", "para", "con", "se"
    )
    private val splitter = Regex("[^\\p{L}\\p{N}]+")

    /** Lower-cased, stop-word-free, lightly stemmed terms. */
    fun terms(text: String): List<String> =
        text.lowercase().split(splitter).filter { it.length > 1 && it !in stopWords }.map(::stem)

    private fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 4 && w.endsWith("es") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    data class Doc<T>(val key: T, val text: String, val title: String = "")
    data class Hit<T>(val key: T, val score: Double)

    /**
     * Top [k] documents for [query]. Hits scoring under [relativeFloor] × the best
     * score are dropped so weak, off-topic chunks don't pad the prompt.
     */
    fun <T> rank(query: String, docs: List<Doc<T>>, k: Int, relativeFloor: Double = 0.25): List<Hit<T>> {
        val q = terms(query).distinct()
        if (q.isEmpty() || docs.isEmpty()) return emptyList()
        val tokenized = docs.map { terms(it.text) + terms(it.title) }
        val avgLen = tokenized.sumOf { it.size }.toDouble() / docs.size
        val df = HashMap<String, Int>()
        tokenized.forEach { toks -> toks.toSet().forEach { if (it in q) df[it] = (df[it] ?: 0) + 1 } }
        val n = docs.size.toDouble()
        val scored = tokenized.mapIndexed { i, toks ->
            val tf = toks.groupingBy { it }.eachCount()
            var score = 0.0
            for (term in q) {
                val f = tf[term] ?: continue
                val idf = ln(1 + (n - df.getValue(term) + 0.5) / (df.getValue(term) + 0.5))
                score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * toks.size / avgLen.coerceAtLeast(1.0)))
            }
            Hit(docs[i].key, score)
        }.filter { it.score > 0 }.sortedByDescending { it.score }
        val best = scored.firstOrNull()?.score ?: return emptyList()
        return scored.filter { it.score >= best * relativeFloor }.take(k)
    }
}
