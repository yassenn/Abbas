package ai.alal.app.personal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoDedupeTest {
    @Test
    fun shortOpenTodoDoesNotSuppressLongNewTodo() {
        assertFalse(isDuplicateTodo("pay the electricity bill by friday", "pay"))
    }

    @Test
    fun emptyOpenTodoDoesNotSuppressNewTodo() {
        assertFalse(isDuplicateTodo("pay the electricity bill by friday", ""))
    }

    @Test
    fun identicalTodoIsDuplicate() {
        assertTrue(isDuplicateTodo("reply to ana", "reply to ana"))
    }

    @Test
    fun longContainmentIsDuplicate() {
        assertTrue(isDuplicateTodo("pay the electricity bill", "pay the electricity bill by friday"))
        assertTrue(isDuplicateTodo("pay the electricity bill by friday", "pay the electricity bill"))
    }
}
