package ai.alal.app.data

/**
 * How the user wants digests written. Stored as JSON in encrypted prefs (Gson),
 * so values are plain strings rather than enums: an unknown value from an older
 * or newer build falls back to the default instead of crashing.
 */
data class SummaryOptions(
    /** BRIEF | STANDARD | DETAILED */
    val length: String = "STANDARD",
    /** IMPORTANCE | PERSON | SOURCE | TIME */
    val grouping: String = "IMPORTANCE",
    /** NEUTRAL | CASUAL | PROFESSIONAL | KEYWORDS */
    val tone: String = "NEUTRAL",
    /** Output language; blank = English. */
    val language: String = "",
    /** What a run covers: NEW (since last summary) | DAY (last 24 h) | WEEK (last 7 days). */
    val scope: String = "NEW",
    /** PersonalSource names left out of summaries (still available to chat). */
    val excludedSources: List<String> = emptyList(),
    /** OFF | DEADLINES | ALL */
    val todoMode: String = "ALL",
    val suggestReplies: Boolean = false,
    val markUrgent: Boolean = true,
    /** Comma-separated people or topics to always surface. */
    val vip: String = "",
    /** Comma-separated senders, apps or words to skip entirely. */
    val mute: String = "",
    /** Combine partial digests into one ranked digest when a run needs several passes. */
    val mergePass: Boolean = true,
    /** 0.0–1.0: low = literal, high = more interpretive. */
    val creativity: Float = 0.2f,
    /** Background run interval in hours. */
    val scheduleHours: Int = 4,
    val requireCharging: Boolean = true
) {
    fun vipTerms(): List<String> = terms(vip)
    fun muteTerms(): List<String> = terms(mute)

    val maxTokens: Int
        get() = when (length) {
            "BRIEF" -> 300
            "DETAILED" -> 1_000
            else -> 550
        }

    private fun terms(raw: String) = raw.split(',', '\n').map { it.trim() }.filter { it.length >= 2 }.distinct()
}
