package ai.abbas.app.viewmodel

/**
 * One streamed model output split into its reasoning trace and final answer.
 *
 * @param thought the reasoning trace, or null when the model produced none
 * @param text the user-facing answer
 * @param closed true once an end-of-thinking marker has been seen
 */
internal data class ThinkingSplit(val thought: String?, val text: String, val closed: Boolean)

/** Marker families emitted as literal text by models without template-driven reasoning. */
private const val THINK_OPEN = "<" + "think" + ">"
private const val THINK_CLOSE = "<" + "/think" + ">"

internal val LEGACY_THINK_STARTS = listOf(THINK_OPEN, "<thought>")
internal val LEGACY_THINK_ENDS = listOf("</thought>", THINK_CLOSE)

/**
 * Split streamed output into the model's reasoning trace and its final answer.
 *
 * Reasoning models open the trace inside the generation prompt (their chat template
 * ends the prompt with the start marker), so the visible stream usually carries only
 * the END marker — everything before it is the trace. Templates that emit both
 * markers, and legacy `<thought>`-style output, are handled too. While a thinking
 * model is still reasoning, no answer text is surfaced yet.
 *
 * Pure (no Android), so it is unit tested directly.
 *
 * @param startTag the template's opening marker ("" when none)
 * @param endTags every marker that closes the reasoning section
 * @param supportsThinking whether the template frames a reasoning section at all
 * @param thinkingEnabled whether the user allowed a reasoning trace. When false, an
 *   output with no markers is treated as the answer (never as an open trace), and a
 *   blank trace is reported as "no thought" rather than an empty block.
 */
internal fun splitThinking(
    full: String,
    startTag: String = "",
    endTags: List<String> = emptyList(),
    supportsThinking: Boolean = false,
    thinkingEnabled: Boolean = true
): ThinkingSplit {
    val starts = (listOf(startTag) + LEGACY_THINK_STARTS).filter { it.isNotEmpty() }
    val ends = (endTags + LEGACY_THINK_ENDS).filter { it.isNotEmpty() }

    val start = firstMarker(full, starts)
    val end = firstMarker(full, ends)

    return when {
        start != null && end != null && end.first > start.first ->
            ThinkingSplit(
                thought = full.substring(start.first + start.second.length, end.first).trim().ifBlank { null },
                text = full.substring(end.first + end.second.length).trimStart(),
                closed = true
            )
        start != null ->
            ThinkingSplit(full.substring(start.first + start.second.length), "", closed = false)
        end != null ->
            ThinkingSplit(
                thought = full.substring(0, end.first).trim().ifBlank { null },
                text = full.substring(end.first + end.second.length).trimStart(),
                closed = true
            )
        // No marker: a template-driven thinking model is still reasoning (the opening
        // marker lived in the prompt, not in the stream). Only valid while thinking is
        // allowed — with it disabled the whole output is the answer.
        supportsThinking && thinkingEnabled -> ThinkingSplit(full.takeIf { it.isNotBlank() }, "", closed = false)
        else -> ThinkingSplit(null, full, closed = false)
    }
}

/** Earliest occurrence of any [markers] in [text], as (index, marker), or null. */
private fun firstMarker(text: String, markers: List<String>): Pair<Int, String>? {
    var best: Pair<Int, String>? = null
    for (m in markers) {
        val i = text.indexOf(m)
        if (i >= 0 && (best == null || i < best.first)) best = i to m
    }
    return best
}
