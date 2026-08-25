package ai.abbas.app.data

/**
 * User-tunable sampling parameters for text generation.
 *
 * These map 1:1 onto the OpenAI-protocol fields in `ChatCompletionRequest`
 * (see OpenAIProtocol.kt). Each field is optional at the engine level, but the
 * app always sends concrete values so the user has predictable control over
 * output quality/creativity.
 *
 * - [temperature] 0.0 = greedy (most factual, can loop); 1.0+ = creative.
 * - [topP] nucleus sampling: fraction of probability mass to sample from.
 * - [frequencyPenalty] reduces word repetition (-2.0 .. 2.0).
 * - [presencePenalty] encourages topic diversity (-2.0 .. 2.0).
 * - [maxTokens] output length cap.
 * - [seed] fixed seed for reproducible output (null = random).
 */
data class GenerationSettings(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val frequencyPenalty: Float = 0.0f,
    val presencePenalty: Float = 0.0f,
    val maxTokens: Int = 512,
    val seed: Int? = null
)
