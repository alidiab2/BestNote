package app.bestnote.engine.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StrokeSpliceTest {
    /** Strokes compare by identity, like androidx.ink strokes. */
    private class S(val name: String) {
        override fun toString() = name
    }

    private class Page(val list: MutableList<S>)

    private class Edit(val splice: StrokeSplice<S>) : Command<Page> {
        override val retainedBytes = 1L
        override fun apply(target: Page) = splice.apply(target.list)
        override fun revert(target: Page) = splice.revert(target.list)
    }

    private fun assertSameOrder(expected: List<S>, actual: List<S>) {
        assertEquals(expected.toString(), actual.toString())
        expected.zip(actual).forEach { (e, a) -> assertSame(e, a) }
    }

    @Test fun pixelEraseGestureIsOneUndoStepThatRestoresOrderExactly() {
        val a = S("a"); val b = S("b"); val c = S("c"); val d = S("d")
        val page = Page(mutableListOf(a, b, c, d))
        val before = page.list.toList()
        // During the gesture: b is split in place, then one of its pieces is split again, d is erased whole.
        val b1 = S("b1"); val b2 = S("b2"); val b2a = S("b2a"); val b2b = S("b2b")
        page.list.apply { removeAt(1); addAll(1, listOf(b1, b2)) }
        page.list.apply { val i = indexOf(b2); removeAt(i); addAll(i, listOf(b2a, b2b)) }
        page.list.remove(d)
        val after = page.list.toList()
        val splice = StrokeSplice.diff(before, after)!!
        // Intermediate piece b2 never shows up in history: only originals out, final pieces in.
        assertEquals("[b, d]", splice.removed.map { it.value }.toString())
        assertEquals("[b1, b2a, b2b]", splice.added.map { it.value }.toString())

        val history = CommandStack<Page>()
        history.record(Edit(splice))
        history.undo(page)
        assertSameOrder(before, page.list)
        history.redo(page)
        assertSameOrder(after, page.list)
        history.undo(page)
        assertSameOrder(before, page.list)
    }

    @Test fun strokeEraserUndoPutsTheStrokeBackUnderTheOnesAboveIt() {
        val hl = S("highlight"); val word = S("word"); val top = S("top")
        val page = Page(mutableListOf(hl, word, top))
        val before = page.list.toList()
        page.list.remove(hl)
        val splice = StrokeSplice.diff(before, page.list)!!
        splice.revert(page.list)
        assertSameOrder(listOf(hl, word, top), page.list)
    }

    @Test fun nothingChangedGivesNoSplice() {
        val a = S("a")
        assertNull(StrokeSplice.diff(listOf(a), listOf(a)))
    }

    @Test fun manyGesturesUndoAndRedoInAnyOrder() {
        val strokes = List(20) { S("s$it") }
        val page = Page(strokes.toMutableList())
        val history = CommandStack<Page>()
        val states = mutableListOf(page.list.toList())
        repeat(30) { step ->
            val before = page.list.toList()
            val i = (step * 7) % page.list.size
            val victim = page.list[i]
            page.list.removeAt(i)
            if (step % 2 == 0) page.list.addAll(i, listOf(S("${victim}L"), S("${victim}R")))
            history.record(Edit(StrokeSplice.diff(before, page.list)!!))
            states += page.list.toList()
        }
        for (k in states.size - 2 downTo 0) {
            history.undo(page)
            assertSameOrder(states[k], page.list)
        }
        for (k in 1 until states.size) {
            history.redo(page)
            assertSameOrder(states[k], page.list)
        }
    }
}
