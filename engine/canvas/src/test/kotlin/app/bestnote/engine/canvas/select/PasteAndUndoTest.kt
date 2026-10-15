package app.bestnote.engine.canvas.select

import app.bestnote.engine.canvas.Command
import app.bestnote.engine.canvas.CommandStack
import app.bestnote.engine.canvas.StrokeSplice
import org.junit.Assert.assertEquals
import org.junit.Test

class PasteAndUndoTest {
    @Test fun pasteCentersOnTheLastTouch() {
        val (dx, dy) = PastePlacement.offset(Box(10f, 10f, 30f, 20f), 200f, 300f, 600f, 800f)
        // Center (20, 15) moves to (200, 300).
        assertEquals(180f, dx, 1e-4f)
        assertEquals(285f, dy, 1e-4f)
    }

    @Test fun pasteNearTheEdgeStaysOnThePage() {
        val (dx, dy) = PastePlacement.offset(Box(0f, 0f, 100f, 40f), 590f, 5f, 600f, 800f)
        assertEquals(500f, dx, 1e-4f) // right edge at 600
        assertEquals(0f, dy, 1e-4f)
    }

    @Test fun contentLargerThanThePageStaysCenteredOnThePoint() {
        val (dx, _) = PastePlacement.offset(Box(0f, 0f, 1000f, 10f), 300f, 5f, 600f, 800f)
        assertEquals(-200f, dx, 1e-4f)
    }

    /** What PagedDocument.replaceStrokes records: one order-preserving splice. */
    private class Splice(val s: StrokeSplice<String>) : Command<MutableList<String>> {
        override val retainedBytes = 1L
        override fun apply(target: MutableList<String>) = s.apply(target)
        override fun revert(target: MutableList<String>) = s.revert(target)
    }

    @Test fun aTransformIsOneUndoStepThatRestoresZOrder() {
        val page = mutableListOf("a", "b", "hl", "c", "d")
        val history = CommandStack<MutableList<String>>()
        // Move b and d (the selection): each swapped in place for its moved copy.
        val before = page.toList()
        val moved = mapOf("b" to "b'", "d" to "d'")
        page.replaceAll { moved[it] ?: it }
        history.record(Splice(StrokeSplice.diff(before, page)!!))
        assertEquals(listOf("a", "b'", "hl", "c", "d'"), page)
        assertEquals(1, history.undoDepth)

        history.undo(page)
        assertEquals(listOf("a", "b", "hl", "c", "d"), page)
        history.redo(page)
        assertEquals(listOf("a", "b'", "hl", "c", "d'"), page)
    }

    @Test fun cutThenPasteUndoBothWays() {
        val page = mutableListOf("a", "b", "c")
        val history = CommandStack<MutableList<String>>()
        val before = page.toList()
        page.remove("b")
        history.record(Splice(StrokeSplice.diff(before, page)!!))
        val beforePaste = page.toList()
        page += "b*"
        history.record(Splice(StrokeSplice.diff(beforePaste, page)!!))
        assertEquals(listOf("a", "c", "b*"), page)
        history.undo(page)
        history.undo(page)
        assertEquals(listOf("a", "b", "c"), page)
    }
}
