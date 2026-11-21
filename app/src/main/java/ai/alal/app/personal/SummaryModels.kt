package ai.alal.app.personal

import android.content.Context
import ai.alal.app.data.ModelConfig
import ai.alal.app.data.SecurityUtils
import ai.alal.app.data.availableModels
import ai.alal.app.data.retiredModels
import ai.alal.app.data.validateCustomModel
import ai.alal.app.inference.LlamaEngineManager

/** Picks the model used for summaries. */
object SummaryModels {

    /**
     * Best-first for digests: faithful, follows the SUMMARY/TODOS format, handles
     * long batches, and is fast enough to run in the background.
     */
    private val PREFERENCE = listOf(
        "Qwen3.5-4B-Q4_K_M", "gemma-4-E4B-it-Q4_K_M", "NVIDIA-Nemotron3-Nano-4B-Q4_K_M", "MiniCPM5-2B-Q4_K_M",
        "gemma-4-E2B-it-Q4_K_M", "Qwen3.5-9B-Q4_K_M", "granite-4.2-3b-Q4_K_M", "Qwen3.5-2B-Q4_K_M",
        "Ministral-3-8B-Instruct-2512-Q4_K_M", "Phi-4-mini-instruct-Q4_K_M", "Ministral-3-3B-Instruct-2512-Q4_K_M",
        "Llama-3.2-3B-Instruct-Q4_K_M", "SmolLM3-3B-Q4_K_M", "LFM2.5-1.2B-Instruct-Q4_K_M", "Qwen3.5-0.8B-Q4_K_M"
    )

    fun all(context: Context): List<ModelConfig> {
        val prefs = SecurityUtils.getEncryptedPrefs(context)
        val custom = runCatching {
            com.google.gson.Gson()
                .fromJson(prefs.getString("custom_models_json", "[]"), Array<ModelConfig>::class.java)
                ?.toList().orEmpty().filter { validateCustomModel(it) == null }
        }.getOrDefault(emptyList())
        return availableModels + retiredModels + custom
    }

    /** Downloaded models this phone can run, best for summaries first. */
    fun downloaded(context: Context): List<ModelConfig> {
        val engine = LlamaEngineManager.shared(context)
        return all(context)
            .filter { (engine.isModelDownloaded(it) || engine.isModelInAssets(it)) && engine.isDeviceCapable(it) }
            .sortedBy { PREFERENCE.indexOf(it.id).let { i -> if (i < 0) PREFERENCE.size + if (it.tier == "RETIRED") 1 else 0 else i } }
    }

    fun recommend(context: Context): ModelConfig? = downloaded(context).firstOrNull()

    /** The user's choice if it's still on the phone, otherwise the recommendation. */
    fun resolve(context: Context, chosenId: String): ModelConfig? {
        val options = downloaded(context)
        return options.firstOrNull { it.id == chosenId } ?: options.firstOrNull()
    }
}
