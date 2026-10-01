package ai.abbas.app.inference

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the "Clear unused models" feature: only folders that no catalogue model
 * claims may be reported as orphaned, and the folder of the model currently loaded
 * into the engine must never be offered for deletion.
 */
class OrphanedWeightsTest {

    @Test
    fun keepsEveryCatalogueModelAndDropsTheRest() {
        val onDisk = listOf("Qwen3.5-4B-Q4_K_M", "Qwen2.5-3B-Instruct-Q4_K_M", "smollm3-3B_Q4_K_M")
        val known = setOf("Qwen3.5-4B-Q4_K_M", "SmolLM3-3B-Q4_K_M")

        assertEquals(
            listOf("Qwen2.5-3B-Instruct-Q4_K_M", "smollm3-3B_Q4_K_M"),
            orphanedModelDirNames(onDisk, known, activeId = null)
        )
    }

    @Test
    fun neverOrphansTheModelLoadedInTheEngine() {
        // Active model's id is absent from the catalogue (e.g. catalogue was swapped) —
        // it must still be protected, because its weights are in use right now.
        val onDisk = listOf("old-live-model", "stale-model")
        val known = setOf("Qwen3.5-4B-Q4_K_M")

        assertEquals(
            listOf("stale-model"),
            orphanedModelDirNames(onDisk, known, activeId = "old-live-model")
        )
    }

    @Test
    fun nothingIsOrphanedWhenEveryFolderIsKnown() {
        val onDisk = listOf("a", "b")
        assertEquals(emptyList<String>(), orphanedModelDirNames(onDisk, setOf("a", "b"), "a"))
    }

    @Test
    fun emptyDiskYieldsNoOrphans() {
        assertEquals(emptyList<String>(), orphanedModelDirNames(emptyList(), setOf("a"), null))
    }
}
