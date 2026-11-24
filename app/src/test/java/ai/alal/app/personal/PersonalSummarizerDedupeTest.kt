package ai.alal.app.personal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the to-do dedupe so short or empty open to-dos can't hide unrelated tasks. */
class PersonalSummarizerDedupeTest {

    @Test
    fun shortOpenTodoDoesNotSuppressLongerUnrelatedTodo() {
        assertFalse(PersonalSummarizer.isDuplicateTodo("Pay the electricity bill by Friday", "pay"))
    }

    @Test
    fun openTodoThatNormalizesToEmptyDoesNotSuppressAnything() {
        assertFalse(PersonalSummarizer.isDuplicateTodo("Pay the electricity bill by Friday", "!!!"))
        assertFalse(PersonalSummarizer.isDuplicateTodo("Pay the electricity bill by Friday", ""))
    }

    @Test
    fun emptyNewTodoIsNotSuppressedByEmptyOpenTodo() {
        assertFalse(PersonalSummarizer.isDuplicateTodo("...", "..."))
    }

    @Test
    fun trueDuplicateIsStillDeduped() {
        assertTrue(PersonalSummarizer.isDuplicateTodo("Reply to Ana!", "reply to ana"))
    }

    @Test
    fun containmentDuplicateWithBothLongIsStillDeduped() {
        assertTrue(
            PersonalSummarizer.isDuplicateTodo(
                "Call the plumber about the leaking kitchen sink tomorrow",
                "call the plumber about the leaking kitchen sink"
            )
        )
    }
}
