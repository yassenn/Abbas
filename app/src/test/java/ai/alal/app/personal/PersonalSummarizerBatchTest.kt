package ai.alal.app.personal

import ai.alal.app.data.IngestedItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalSummarizerBatchTest {

    private fun item(body: String, id: Long = 1) = IngestedItemEntity(
        id = id, source = "SMS", sourceKey = "k$id", sender = "Ana", body = body, timestamp = 1_700_000_000_000L
    )

    @Test
    fun oversizedItemIsTruncatedWithinBudget() {
        val budget = 1_500
        val line = PersonalSummarizer.formatItem(item("x".repeat(80_000)), budget)
        assertTrue(line.length <= budget)
        assertTrue(line.endsWith("…"))
        // Header is kept intact.
        assertTrue(line.startsWith(PersonalSummarizer.formatItem(item("")).trimEnd()))
    }

    @Test
    fun normalItemIsUnchanged() {
        val normal = item("See you at 6\nbring the keys")
        assertEquals(PersonalSummarizer.formatItem(normal), PersonalSummarizer.formatItem(normal, 1_500))
        assertTrue(PersonalSummarizer.formatItem(normal, 1_500).endsWith("See you at 6 bring the keys"))
    }

    @Test
    fun itemExactlyAtBudgetIsUnchanged() {
        val base = PersonalSummarizer.formatItem(item(""))
        val body = "y".repeat(1_500 - base.length)
        val line = PersonalSummarizer.formatItem(item(body), 1_500)
        assertEquals(1_500, line.length)
        assertTrue(line.endsWith(body))
    }

    @Test
    fun everyBatchStaysWithinBudgetWhenFormatted() {
        val budget = 1_500
        val items = listOf(item("a".repeat(200), 1), item("b".repeat(80_000), 2), item("c".repeat(200), 3))
        val batches = PersonalSummarizer.batch(items, budget)
        assertEquals(items.size, batches.sumOf { it.size })
        for (b in batches) {
            val text = b.joinToString("\n") { PersonalSummarizer.formatItem(it, budget) }
            assertTrue(text.length <= budget + b.size) // newline separators only
            b.forEach { assertTrue(PersonalSummarizer.formatItem(it, budget).length <= budget) }
        }
    }
}
