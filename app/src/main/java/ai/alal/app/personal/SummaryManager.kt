package ai.alal.app.personal

import ai.alal.app.data.DigestEntity
import ai.alal.app.data.IngestedItemEntity
import ai.alal.app.data.SummaryOrigin

/** Persistence the manager needs; backed by Room in the app and by memory in tests. */
interface SummaryStore {
    /** Inserts [entity] and links it to [parentIds] atomically; returns the new id. */
    suspend fun create(entity: DigestEntity, parentIds: List<Long>): Long
    suspend fun get(id: Long): DigestEntity?
    suspend fun parents(id: Long): List<DigestEntity>
    suspend fun updateText(id: Long, text: String, now: Long)
    suspend fun rename(id: Long, title: String?, now: Long)
    suspend fun setPinned(id: Long, pinned: Boolean)
    suspend fun delete(id: Long)
    suspend fun itemsForPeriod(start: Long, end: Long): List<IngestedItemEntity>
}

/** The on-device model. Implementations must never touch the network. */
interface SummaryGenerator {
    /** Max characters of input per call; larger input is chunked. */
    val charBudget: Int
    suspend fun generate(system: String, prompt: String): String
}

class SummaryException(message: String) : Exception(message)

/**
 * Independent summary management. Every operation touches exactly one summary row
 * (creating a combined summary only *adds* a row plus links; sources are read-only).
 * Summary text is never logged.
 */
class SummaryManager(
    private val store: SummaryStore,
    private val generator: SummaryGenerator,
    private val clock: () -> Long = System::currentTimeMillis,
    private val formatItem: (IngestedItemEntity) -> String = PersonalSummarizer::formatItem,
    private val itemsSystemPrompt: () -> String = { PersonalSummarizer.systemPrompt() }
) {

    /** Builds a NEW summary from [ids] (2+). Returns its id; the sources are never modified. */
    suspend fun summarizeSummaries(ids: List<Long>, onProgress: (String) -> Unit = {}): Long {
        val distinct = ids.distinct()
        if (distinct.size < 2) throw SummaryException("Pick at least two summaries")
        val parents = distinct.map { store.get(it) ?: throw SummaryException("A selected summary no longer exists") }
        val text = combine(parents, onProgress)
        val now = clock()
        return store.create(
            DigestEntity(
                createdAt = now,
                periodStart = parents.minOf { it.periodStart },
                periodEnd = parents.maxOf { it.periodEnd },
                itemCount = parents.sumOf { it.itemCount },
                summary = text,
                updatedAt = now,
                sourceRef = SummaryOrigin.SUMMARIES
            ),
            distinct
        )
    }

    /** Re-runs [id] from its original source and replaces only that summary's text. */
    suspend fun regenerate(id: Long, onProgress: (String) -> Unit = {}) {
        val target = store.get(id) ?: throw SummaryException("Summary no longer exists")
        val text = when (target.sourceRef) {
            SummaryOrigin.SUMMARIES -> {
                val parents = store.parents(id)
                if (parents.isEmpty()) throw SummaryException("The summaries this was generated from were deleted")
                combine(parents, onProgress)
            }
            else -> {
                val items = store.itemsForPeriod(target.periodStart, target.periodEnd)
                if (items.isEmpty()) throw SummaryException("The original messages were already cleared, so this can't be regenerated")
                val raw = reduce(items.map(formatItem), itemsSystemPrompt(), ITEMS_COMBINE, onProgress)
                val lines = DigestParser.parse(raw).summary
                if (lines.isEmpty()) raw.trim() else lines.joinToString("\n") { "- $it" }
            }
        }
        store.updateText(id, text.ifBlank { "Nothing important." }, clock())
    }

    suspend fun rename(id: Long, title: String) = store.rename(id, title.trim().ifBlank { null }, clock())
    suspend fun setPinned(id: Long, pinned: Boolean) = store.setPinned(id, pinned)
    suspend fun delete(id: Long) = store.delete(id)

    private suspend fun combine(parents: List<DigestEntity>, onProgress: (String) -> Unit): String {
        val sections = parents.mapIndexed { i, p -> "Summary ${i + 1}:\n${p.summary.trim()}" }
        return reduce(sections, COMBINE_SYSTEM, COMBINE_PROMPT, onProgress).trim().ifBlank { "Nothing important." }
    }

    /**
     * Map-reduce: pack [sections] into chunks that fit the model's budget; if there is
     * more than one chunk, summarize each, then repeat on the partial results until a
     * single chunk remains. Rounds are capped so a rambling model can't loop forever.
     */
    internal suspend fun reduce(sections: List<String>, system: String, instruction: String, onProgress: (String) -> Unit): String {
        val budget = (generator.charBudget - instruction.length).coerceAtLeast(MIN_BUDGET)
        var texts = sections
        var round = 0
        while (true) {
            val chunks = SummaryChunker.chunk(texts, budget)
            if (chunks.size <= 1) {
                onProgress("Writing summary…")
                return generator.generate(system, "$instruction\n\n${chunks.firstOrNull().orEmpty()}")
            }
            if (round >= MAX_ROUNDS) {
                // Still too big after repeated passes: keep what fits rather than loop.
                onProgress("Writing summary…")
                return generator.generate(system, "$instruction\n\n${chunks.joinToString("\n\n").take(budget)}")
            }
            texts = chunks.mapIndexed { i, chunk ->
                onProgress("Summarizing part ${i + 1} of ${chunks.size}…")
                generator.generate(system, "$PART_PROMPT\n\n$chunk").trim()
            }.filter { it.isNotBlank() }
            round++
        }
    }

    companion object {
        const val MAX_ROUNDS = 4
        private const val MIN_BUDGET = 400

        private const val COMBINE_SYSTEM =
            "You merge several summaries into one concise summary. Use only the text you are given. " +
                "Remove duplicates, combine lines about the same person or topic, and keep names, dates, amounts and action items. " +
                "Reply with a markdown bullet list, one idea per line, and nothing else."
        private const val COMBINE_PROMPT = "Merge these summaries into one."
        private const val PART_PROMPT = "Summarize this part briefly. It will be merged with other parts later."
        private const val ITEMS_COMBINE = "Summarize this recent activity."
    }
}

/** Splits text into pieces that fit a character budget. Pure, so it is unit-tested directly. */
object SummaryChunker {
    /** Packs [sections] (in order) into chunks of at most [budget] chars; oversize sections are split on line breaks. */
    fun chunk(sections: List<String>, budget: Int): List<String> {
        require(budget > 0)
        val pieces = sections.flatMap { split(it, budget) }
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty() && current.length + 2 + piece.length > budget) {
                out += current.toString(); current.clear()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(piece)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun split(text: String, budget: Int): List<String> {
        if (text.length <= budget) return listOf(text)
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (line in text.lines()) {
            // A single line longer than the budget is hard-cut.
            for (part in line.chunked(budget)) {
                if (current.isNotEmpty() && current.length + 1 + part.length > budget) {
                    out += current.toString(); current.clear()
                }
                if (current.isNotEmpty()) current.append('\n')
                current.append(part)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}
