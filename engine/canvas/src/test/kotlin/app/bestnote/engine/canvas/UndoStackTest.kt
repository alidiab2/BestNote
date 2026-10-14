package app.bestnote.engine.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoStackTest {
    @Test fun undoRedoOrder() {
        val stack = UndoStack<String>()
        stack.push("a")
        stack.push("b")
        assertEquals("b", stack.undo())
        assertEquals("a", stack.undo())
        assertNull(stack.undo())
        assertEquals("a", stack.redo())
        assertTrue(stack.canRedo)
    }

    @Test fun pushClearsRedo() {
        val stack = UndoStack<String>()
        stack.push("a")
        stack.undo()
        stack.push("c")
        assertFalse(stack.canRedo)
    }

    @Test fun capacityDropsOldest() {
        val stack = UndoStack<Int>(capacity = 2)
        (1..3).forEach(stack::push)
        assertEquals(3, stack.undo())
        assertEquals(2, stack.undo())
        assertNull(stack.undo())
    }
}
