package ai.alal.app.share

import ai.alal.app.data.ModelConfig
import ai.alal.app.data.validateCustomModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelImportNamesTest {

    @Test
    fun `model ids that could escape the model folder are rejected`() {
        assertTrue(isSafeModelId("Qwen3.5-2B-Q4_K_M"))
        assertFalse(isSafeModelId(".."))
        assertFalse(isSafeModelId("."))
        assertFalse(isSafeModelId("a/b"))
        assertFalse(isSafeModelId(""))
        assertFalse(isSafeModelId("x".repeat(65)))
    }

    @Test
    fun `only plain gguf file names are shared`() {
        assertTrue(isSafeGgufName("model.gguf"))
        assertFalse(isSafeGgufName("../secret.gguf"))
        assertFalse(isSafeGgufName("dir/model.gguf"))
        assertFalse(isSafeGgufName("model.bin"))
        assertFalse(isSafeGgufName("shared_prefs.xml"))
    }

    @Test
    fun `picked file names are made safe for disk`() {
        assertEquals("My_Model_v2.gguf", sanitizeGgufName("My Model v2.GGUF"))
        assertEquals("evil.gguf", sanitizeGgufName("../../evil.gguf"))
        assertNull(sanitizeGgufName("notes.txt"))
        assertNull(sanitizeGgufName(".gguf"))
        assertNull(sanitizeGgufName("a..b.gguf"))
        val long = sanitizeGgufName("x".repeat(300) + ".gguf")
        assertNotNull(long)
        assertTrue(isSafeModelId(long!!.removeSuffix(".gguf")))
    }

    @Test
    fun `imported file models need no download url`() {
        val imported = ModelConfig("local", "local", "local.gguf", "", 1L, isCustom = true, isImported = true)
        assertNull(validateCustomModel(imported))
        assertNotNull(validateCustomModel(imported.copy(isImported = false)))
    }

    @Test
    fun `ram estimate adds headroom over the file size`() {
        assertEquals(1_250_000_000L, estimateRam(1_000_000_000L))
        assertTrue(estimateRam(-1) > 0)
    }
}
