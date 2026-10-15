package app.bestnote.engine.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CommandStackTest {
    /** A fake document: a list of item ids, edited the way the page edits its strokes. */
    private class Doc {
        val items = ArrayList<Int>()
    }

    private data class Add(val ids: List<Int>, override val retainedBytes: Long = 100) : Command<Doc> {
        override fun apply(target: Doc) {
            target.items += ids
        }

        override fun revert(target: Doc) {
            target.items.removeAll(ids.toSet())
        }
    }

    private data class Erase(val ids: List<Int>, val at: List<Int>, override val retainedBytes: Long = 100) : Command<Doc> {
        override fun apply(target: Doc) {
            target.items.removeAll(ids.toSet())
        }

        /** Puts each id back at its original index, so undo restores the exact order. */
        override fun revert(target: Doc) {
            ids.zip(at).sortedBy { it.second }.forEach { (id, i) -> target.items.add(i, id) }
        }
    }

    /** Stands in for a transform (CANVAS-09). */
    private data class Move(val id: Int, val from: Int, val to: Int) : Command<Doc> {
        override val retainedBytes = 16L
        override fun apply(target: Doc) {
            target.items.removeAt(from)
            target.items.add(to, id)
        }

        override fun revert(target: Doc) {
            target.items.removeAt(to)
            target.items.add(from, id)
        }
    }

    @Test fun twoHundredMixedOperationsUndoAndRedoExactly() {
        val doc = Doc()
        val stack = CommandStack<Doc>()
        val random = Random(42)
        val snapshots = ArrayList<List<Int>>()
        snapshots += doc.items.toList()
        var next = 0
        repeat(200) {
            val command: Command<Doc> = when {
                doc.items.size < 3 || random.nextInt(3) == 0 -> Add(List(random.nextInt(1, 4)) { next++ })
                random.nextBoolean() -> {
                    val picked = doc.items.shuffled(random).take(random.nextInt(1, 3))
                    Erase(picked, picked.map(doc.items::indexOf))
                }
                else -> {
                    val from = random.nextInt(doc.items.size)
                    Move(doc.items[from], from, random.nextInt(doc.items.size))
                }
            }
            stack.execute(command, doc)
            snapshots += doc.items.toList()
        }
        assertEquals(200, stack.undoDepth)

        for (i in 199 downTo 0) {
            stack.undo(doc)
            assertEquals("after undoing step ${i + 1}", snapshots[i], doc.items)
        }
        assertFalse(stack.canUndo)
        assertNull(stack.undo(doc))

        for (i in 1..200) {
            stack.redo(doc)
            assertEquals("after redoing step $i", snapshots[i], doc.items)
        }
        assertFalse(stack.canRedo)
    }

    @Test fun interleavedUndoRedoKeepsState() {
        val doc = Doc()
        val stack = CommandStack<Doc>()
        repeat(50) { stack.execute(Add(listOf(it)), doc) }
        repeat(20) { stack.undo(doc) }
        repeat(10) { stack.redo(doc) }
        assertEquals((0 until 40).toList(), doc.items)
        assertEquals(40, stack.undoDepth)
        assertEquals(10, stack.redoDepth)
    }

    @Test fun newEditDiscardsRedoBranch() {
        val doc = Doc()
        val stack = CommandStack<Doc>()
        stack.execute(Add(listOf(1)), doc)
        stack.execute(Add(listOf(2)), doc)
        stack.undo(doc)
        stack.execute(Add(listOf(3)), doc)
        assertFalse(stack.canRedo)
        assertEquals(listOf(1, 3), doc.items)
        assertEquals(200L, stack.retainedBytes)
    }

    @Test fun operationCountIsBounded() {
        val doc = Doc()
        val stack = CommandStack<Doc>(maxOperations = 200)
        repeat(1_000) { stack.execute(Add(listOf(it)), doc) }
        assertEquals(200, stack.undoDepth)
        repeat(200) { assertTrue(stack.undo(doc) != null) }
        assertNull(stack.undo(doc))
        assertEquals((0 until 800).toList(), doc.items) // older steps are committed, not lost
    }

    @Test fun memoryIsBoundedAcrossUndoAndRedo() {
        val doc = Doc()
        val budget = 50_000L
        val stack = CommandStack<Doc>(maxOperations = 10_000, maxBytes = budget)
        repeat(5_000) {
            stack.execute(Add(listOf(it), retainedBytes = 1_000), doc)
            assertTrue(stack.retainedBytes <= budget)
        }
        assertEquals(50, stack.undoDepth)
        repeat(30) { stack.undo(doc) }
        assertEquals(budget, stack.retainedBytes) // moving between undo and redo costs nothing
        stack.execute(Add(listOf(-1), retainedBytes = 1_000), doc)
        assertEquals(0, stack.redoDepth)
        assertEquals(21_000L, stack.retainedBytes)
    }

    @Test fun oversizedStepIsStillUndoable() {
        val doc = Doc()
        val stack = CommandStack<Doc>(maxBytes = 1_000)
        stack.execute(Add(listOf(1), retainedBytes = 400), doc)
        stack.execute(Add(listOf(2), retainedBytes = 5_000), doc)
        assertEquals(1, stack.undoDepth)
        stack.undo(doc)
        assertEquals(listOf(1), doc.items)
    }

    @Test fun clearForgetsEverything() {
        val doc = Doc()
        val stack = CommandStack<Doc>()
        repeat(3) { stack.execute(Add(listOf(it)), doc) }
        stack.undo(doc)
        stack.clear()
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
        assertEquals(0L, stack.retainedBytes)
    }
}
