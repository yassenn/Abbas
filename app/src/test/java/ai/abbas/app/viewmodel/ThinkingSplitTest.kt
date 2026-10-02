package ai.abbas.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit test for the reasoning-trace splitter.
 *
 * Regression: the chat screen only recognised `<thought>…</thought>`, but reasoning
 * models (Qwen3.x, DeepSeek R1, …) frame their trace via the chat template with
 * ` thinking … </think>`, so the trace leaked into the final answer. The markers are
 * now read from the model's own template (see `getThinkingTags` in the JNI layer).
 */
class ThinkingSplitTest {

    // Markers built by concatenation so the literal tags survive tooling/encoding.
    private val thinkOpen = "<" + "think" + ">"
    private val thinkClose = "<" + "/think" + ">"

    // Qwen3.x / DeepSeek style: template pre-opens the trace, model emits its close.
    private val qwenStart = thinkOpen
    private val qwenEnds = listOf(thinkClose, "<tool_call>")

    @Test
    fun `reasoning still streaming is routed to thought, body stays empty`() {
        val split = splitThinking(
            "Let me work out 2 + 2 step by step",
            qwenStart, qwenEnds, supportsThinking = true
        )
        assertEquals("Let me work out 2 + 2 step by step", split.thought)
        assertEquals("", split.text)
        assertFalse(split.closed)
    }

    @Test
    fun `only the closing marker in stream splits trace from answer`() {
        val split = splitThinking(
            "Let me reason about this.${thinkClose}2 + 2 is 4.",
            qwenStart, qwenEnds, supportsThinking = true
        )
        assertEquals("Let me reason about this.", split.thought)
        assertEquals("2 + 2 is 4.", split.text)
        assertTrue(split.closed)
    }

    @Test
    fun `empty reasoning yields no thought block and full answer`() {
        val split = splitThinking(
            "$thinkClose\n\nParis is the capital of France.",
            qwenStart, qwenEnds, supportsThinking = true
        )
        assertNull(split.thought)
        assertEquals("Paris is the capital of France.", split.text)
        assertTrue(split.closed)
    }

    @Test
    fun `explicit start and end markers in stream are both honoured`() {
        val split = splitThinking(
            "$thinkOpen reasoning here$thinkClose the answer",
            qwenStart, qwenEnds, supportsThinking = true
        )
        assertEquals("reasoning here", split.thought)
        assertEquals("the answer", split.text)
        assertTrue(split.closed)
    }

    @Test
    fun `tool-call end tag also closes the reasoning section`() {
        val split = splitThinking(
            "deciding to call a tool<tool_call>",
            qwenStart, qwenEnds, supportsThinking = true
        )
        assertEquals("deciding to call a tool", split.thought)
        assertEquals("", split.text)
        assertTrue(split.closed)
    }

    @Test
    fun `non-thinking model passes whole output through as the answer`() {
        val split = splitThinking("The answer is 42.")
        assertNull(split.thought)
        assertEquals("The answer is 42.", split.text)
        assertFalse(split.closed)
    }

    @Test
    fun `legacy thought tags still route the trace to the thinking block`() {
        val split = splitThinking("<thought>step 1\nstep 2</thought>Final answer: 4")
        assertEquals("step 1\nstep 2", split.thought)
        assertEquals("Final answer: 4", split.text)
        assertTrue(split.closed)
    }

    @Test
    fun `thinking model mid-reasoning with blank buffer reports no thought`() {
        // Nothing generated yet — don't show an empty Thinking block.
        val split = splitThinking("", qwenStart, qwenEnds, supportsThinking = true)
        assertNull(split.thought)
        assertEquals("", split.text)
        assertFalse(split.closed)
    }

    @Test
    fun `thinking disabled treats markerless output as the answer`() {
        // With thinking off the model answers directly; a markerless stream must NOT
        // be misread as an open reasoning trace.
        val split = splitThinking(
            "Paris is the capital of France.", qwenStart, qwenEnds,
            supportsThinking = true, thinkingEnabled = false
        )
        assertNull(split.thought)
        assertEquals("Paris is the capital of France.", split.text)
        assertFalse(split.closed)
    }

    @Test
    fun `thinking disabled with stray close marker reports no thought`() {
        // Some models emit a redundant close marker even with thinking off — that must
        // not surface an empty Thought block or a "Thought for" timer.
        val split = splitThinking(
            "$thinkClose\n\nParis is the capital of France.", qwenStart, qwenEnds,
            supportsThinking = true, thinkingEnabled = false
        )
        assertNull(split.thought)
        assertEquals("Paris is the capital of France.", split.text)
        assertTrue(split.closed)
    }
}
