package ai.alal.app.personal

import ai.alal.app.data.DigestEntity
import ai.alal.app.data.IngestedItemEntity
import ai.alal.app.data.SummaryMigration
import ai.alal.app.data.SummaryOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** In-memory [SummaryStore] mirroring the Room semantics (FK cascade drops links only). */
private class FakeStore : SummaryStore {
    val rows = linkedMapOf<Long, DigestEntity>()
    val links = mutableSetOf<Pair<Long, Long>>() // child to parent
    var items = listOf<IngestedItemEntity>()
    private var next = 1L

    override suspend fun create(entity: DigestEntity, parentIds: List<Long>): Long {
        val id = next++
        rows[id] = entity.copy(id = id)
        parentIds.forEach { links += id to it }
        return id
    }
    override suspend fun get(id: Long) = rows[id]
    override suspend fun parents(id: Long) = links.filter { it.first == id }.mapNotNull { rows[it.second] }
    override suspend fun updateText(id: Long, text: String, now: Long) { rows[id] = rows[id]!!.copy(summary = text, updatedAt = now) }
    override suspend fun rename(id: Long, title: String?, now: Long) { rows[id] = rows[id]!!.copy(title = title, updatedAt = now) }
    override suspend fun setPinned(id: Long, pinned: Boolean) { rows[id] = rows[id]!!.copy(pinned = pinned) }
    override suspend fun delete(id: Long) { rows.remove(id); links.removeAll { it.first == id || it.second == id } }
    override suspend fun itemsForPeriod(start: Long, end: Long) = items.filter { it.timestamp in start..end }
}

private class FakeGenerator(override val charBudget: Int = 10_000) : SummaryGenerator {
    val prompts = mutableListOf<String>()
    override suspend fun generate(system: String, prompt: String): String {
        prompts += prompt
        return "- merged(${prompt.length})"
    }
}

class SummaryManagerTest {
    private val store = FakeStore()
    private val gen = FakeGenerator()
    private var clock = 1_000L
    private val manager = SummaryManager(store, gen, clock = { clock++ }, formatItem = { it.body }, itemsSystemPrompt = { "sys" })

    private fun seed(text: String, start: Long = 10, end: Long = 20): Long = runBlocking {
        store.create(
            DigestEntity(createdAt = start, periodStart = start, periodEnd = end, itemCount = 3, summary = text, sourceRef = SummaryOrigin.ITEMS),
            emptyList()
        )
    }

    // ── per-summary CRUD ──

    @Test fun createReadUpdateDelete() = runBlocking {
        val id = seed("- a")
        assertEquals("- a", store.get(id)!!.summary)
        manager.rename(id, "  Trip  ")
        assertEquals("Trip", store.get(id)!!.title)
        manager.rename(id, "   ")
        assertNull(store.get(id)!!.title)
        manager.delete(id)
        assertNull(store.get(id))
    }

    @Test fun renamePinDeleteLeaveOthersUnchanged() = runBlocking {
        val a = seed("- a"); val b = seed("- b"); val c = seed("- c")
        val bBefore = store.get(b)!!; val cBefore = store.get(c)!!
        manager.rename(a, "Renamed")
        manager.setPinned(a, true)
        assertEquals(bBefore, store.get(b)); assertEquals(cBefore, store.get(c))
        manager.delete(a)
        assertEquals(bBefore, store.get(b)); assertEquals(cBefore, store.get(c))
        assertEquals(2, store.rows.size)
    }

    @Test fun pinAndUnpin() = runBlocking {
        val a = seed("- a")
        manager.setPinned(a, true); assertTrue(store.get(a)!!.pinned)
        manager.setPinned(a, false); assertFalse(store.get(a)!!.pinned)
    }

    // ── summarize summaries ──

    @Test fun summarizeCreatesNewLinkedRecordAndLeavesSourcesUntouched() = runBlocking {
        val a = seed("- alpha", 10, 20); val b = seed("- beta", 30, 40)
        val aBefore = store.get(a)!!; val bBefore = store.get(b)!!
        val id = manager.summarizeSummaries(listOf(a, b))
        assertNotEquals(a, id); assertNotEquals(b, id)
        val created = store.get(id)!!
        assertEquals(SummaryOrigin.SUMMARIES, created.sourceRef)
        assertEquals(setOf(a, b), store.parents(id).map { it.id }.toSet())
        assertEquals(10L, created.periodStart); assertEquals(40L, created.periodEnd); assertEquals(6, created.itemCount)
        assertEquals(aBefore, store.get(a)); assertEquals(bBefore, store.get(b))
        assertTrue(gen.prompts.single().contains("- alpha") && gen.prompts.single().contains("- beta"))
    }

    @Test fun summarizeNeedsTwoDistinctSummaries() = runBlocking {
        val a = seed("- a")
        for (ids in listOf(listOf(a), listOf(a, a))) {
            try { manager.summarizeSummaries(ids); fail("expected SummaryException") } catch (_: SummaryException) {}
        }
        assertEquals(1, store.rows.size)
    }

    @Test fun deletingParentKeepsChildAndDropsOnlyTheLink() = runBlocking {
        val a = seed("- a"); val b = seed("- b")
        val child = manager.summarizeSummaries(listOf(a, b))
        manager.delete(a)
        assertEquals(listOf(b), store.parents(child).map { it.id })
        assertTrue(store.get(child) != null)
    }

    // ── chunking ──

    @Test fun oversizeInputIsChunkedThenCombined() = runBlocking {
        val small = FakeGenerator(charBudget = 1_000)
        val m = SummaryManager(store, small, clock = { clock++ })
        val a = seed("- " + "x".repeat(900)); val b = seed("- " + "y".repeat(900)); val c = seed("- " + "z".repeat(900))
        val id = m.summarizeSummaries(listOf(a, b, c))
        // 3 parts summarized individually, then one combine call.
        assertTrue("expected several calls, got ${small.prompts.size}", small.prompts.size >= 4)
        assertTrue(small.prompts.dropLast(1).all { it.startsWith("Summarize this part") })
        assertTrue(small.prompts.last().startsWith("Merge these summaries"))
        assertTrue(small.prompts.all { it.length <= 1_000 + 200 })
        assertEquals(3, store.parents(id).size)
    }

    @Test fun chunkerPacksAndSplits() {
        assertEquals(listOf("a\n\nb"), SummaryChunker.chunk(listOf("a", "b"), 10))
        assertEquals(listOf("aaaa", "bbbb"), SummaryChunker.chunk(listOf("aaaa", "bbbb"), 6))
        val long = (1..10).joinToString("\n") { "line$it" }
        val chunks = SummaryChunker.chunk(listOf(long), 20)
        assertTrue(chunks.all { it.length <= 20 })
        assertEquals(long.replace("\n", ""), chunks.joinToString("").replace("\n", ""))
        assertEquals(5, SummaryChunker.chunk(listOf("x".repeat(25)), 5).size)
    }

    @Test fun rambling_modelStillTerminates() = runBlocking {
        val noisy = object : SummaryGenerator {
            override val charBudget = 600
            var calls = 0
            override suspend fun generate(system: String, prompt: String): String { calls++; return "y".repeat(500) }
        }
        val m = SummaryManager(store, noisy, clock = { clock++ })
        val ids = (1..6).map { seed("- " + "w".repeat(400)) }
        m.summarizeSummaries(ids)
        assertTrue(noisy.calls < 200)
    }

    // ── regenerate ──

    @Test fun regenerateFromParentsReplacesOnlyThatSummary() = runBlocking {
        val a = seed("- a"); val b = seed("- b")
        val child = manager.summarizeSummaries(listOf(a, b))
        val other = seed("- other")
        val aBefore = store.get(a)!!; val otherBefore = store.get(other)!!
        store.updateText(child, "stale", 5)
        manager.regenerate(child)
        assertTrue(store.get(child)!!.summary.startsWith("- merged"))
        assertEquals(aBefore, store.get(a)); assertEquals(otherBefore, store.get(other))
        assertEquals(setOf(a, b), store.parents(child).map { it.id }.toSet())
    }

    @Test fun regenerateFromItemsUsesOriginalPeriod() = runBlocking {
        val id = seed("- old", 100, 200)
        store.items = listOf(item(1, 150, "hello"), item(2, 999, "outside"))
        manager.regenerate(id)
        assertEquals("- merged", store.get(id)!!.summary.substringBefore("("))
        assertTrue(gen.prompts.single().contains("hello") && !gen.prompts.single().contains("outside"))
    }

    @Test fun regenerateFailsClearlyWhenSourcesAreGone() = runBlocking {
        val itemsOnly = seed("- keep")
        try { manager.regenerate(itemsOnly); fail() } catch (_: SummaryException) {}
        assertEquals("- keep", store.get(itemsOnly)!!.summary)
        val a = seed("- a"); val b = seed("- b")
        val child = manager.summarizeSummaries(listOf(a, b))
        manager.delete(a); manager.delete(b)
        try { manager.regenerate(child); fail() } catch (_: SummaryException) {}
    }

    // ── migration ──

    @Test fun migrationIsAdditiveAndPreservesRows() {
        val sql = SummaryMigration.STATEMENTS_5_6
        assertTrue(sql.none { Regex("DROP|^DELETE|INSERT|RENAME", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        val alters = sql.filter { it.startsWith("ALTER TABLE `digests` ADD COLUMN") }
        assertEquals(listOf("title", "updatedAt", "pinned", "sourceRef"), alters.map { Regex("`(\\w+)`( TEXT| INTEGER)").findAll(it).last().groupValues[1] })
        // NOT NULL columns must carry a default so existing rows stay valid.
        assertTrue(alters.filter { it.contains("NOT NULL") }.all { it.contains("DEFAULT") })
        val update = sql.single { it.startsWith("UPDATE") }
        assertFalse(update.contains("WHERE")) // backfills every row, touches no text/id
        assertFalse(update.contains("`summary`") || update.contains("`id`"))
        assertTrue(sql.any { it.contains("summary_links") && it.contains("ON DELETE CASCADE") })
    }

    private fun item(id: Long, ts: Long, body: String) = IngestedItemEntity(
        id = id, source = "SMS", sourceKey = "k$id", body = body, timestamp = ts
    )
}
