package ai.alal.app.personal

import ai.alal.app.data.IngestedItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the per-item character budget used when batching activity into model prompts. */
class PersonalSummarizerFormatTest {

    private val budget = 1_500

    private fun item(body: String, sender: String? = "Ana") = IngestedItemEntity(
        source = "SMS",
        sourceKey = "key",
        sender = sender,
        body = body,
        timestamp = 1_700_000_000_000L
    )

    @Test
    fun oversizedItemIsTruncatedToWithinBudget() {
        val line = PersonalSummarizer.formatItem(item("x".repeat(80_000)), budget)

        assertTrue("length was ${line.length}", line.length <= budget)
        assertTrue(line.endsWith("…"))
        // The header format is kept intact.
        assertTrue(line.startsWith("[SMS "))
        assertTrue(line.contains("] Ana: "))
    }

    @Test
    fun oversizedItemInBatchIsCappedAndKeepsItsOwnBatch() {
        val small = item("short note")
        val huge = item("y".repeat(80_000))

        val batches = PersonalSummarizer.batch(listOf(small, huge, small), budget)

        assertEquals(listOf(listOf(small), listOf(huge), listOf(small)), batches)
        batches.flatten().forEach { assertTrue(PersonalSummarizer.formatItem(it, budget).length <= budget) }
    }

    @Test
    fun normalItemIsUnchanged() {
        val normal = item("Lunch at noon?\nBring the forms")

        val formatted = PersonalSummarizer.formatItem(normal, budget)

        assertEquals(PersonalSummarizer.formatItem(normal), formatted)
        assertTrue(formatted.endsWith("Lunch at noon? Bring the forms"))
        assertFalse(formatted.contains("…"))
    }

    @Test
    fun itemExactlyAtBudgetIsNotTruncated() {
        val full = PersonalSummarizer.formatItem(item("a".repeat(200)))

        assertEquals(full, PersonalSummarizer.formatItem(item("a".repeat(200)), full.length))
    }

    @Test
    fun budgetSmallerThanHeaderStillCapsLength() {
        val line = PersonalSummarizer.formatItem(item("z".repeat(500)), 10)

        assertTrue(line.length <= 10)
    }
}
