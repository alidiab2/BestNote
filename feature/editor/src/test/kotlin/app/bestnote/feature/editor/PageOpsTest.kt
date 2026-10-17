package app.bestnote.feature.editor

import app.bestnote.core.model.PaperTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageOpsTest {
    /** Applies [moves] the way PagedDocument.movePage does. */
    private fun apply(n: Int, moves: List<Pair<Int, Int>>): List<Int> {
        val list = (0 until n).toMutableList()
        moves.forEach { (from, to) -> list.add(to, list.removeAt(from)) }
        return list
    }

    @Test
    fun `paper scope targets`() {
        assertEquals(listOf(2), PageOps.paperTargets(PaperScope.ThisPage, 2, 5))
        assertEquals(listOf(0, 1, 2, 3, 4), PageOps.paperTargets(PaperScope.AllPages, 2, 5))
        assertEquals(emptyList<Int>(), PageOps.paperTargets(PaperScope.NewPages, 2, 5))
        assertEquals(emptyList<Int>(), PageOps.paperTargets(PaperScope.ThisPage, 7, 5))
    }

    @Test
    fun `every template is in exactly one group, basic first`() {
        val all = PageOps.groups.values.flatten()
        assertEquals(PaperTemplate.entries.toSet(), all.toSet())
        assertEquals(all.size, all.toSet().size)
        assertEquals(PaperGroup.Basic, PageOps.groups.keys.first())
        assertEquals(6, PageOps.groups.getValue(PaperGroup.Basic).size)
    }

    @Test
    fun `move block to the start, middle and end`() {
        assertEquals(listOf(2, 4, 0, 1, 3, 5), PageOps.moveBlock(6, listOf(4, 2), 0))
        assertEquals(listOf(0, 1, 3, 4, 5, 2), PageOps.moveBlock(6, listOf(2), 6))
        // After page 4 (index 3): the selected pages land right behind it.
        assertEquals(listOf(1, 2, 3, 0, 5, 4), PageOps.moveBlock(6, listOf(0, 5), 4))
        // A selected page is never the place to move after: after page 2 with page 2 selected = after page 1.
        assertEquals(listOf(0, 1, 2, 3), PageOps.moveBlock(4, listOf(1, 2), 2))
    }

    @Test
    fun `moves replay into the wanted order`() {
        val orders = listOf(
            listOf(0, 1, 2, 3),
            listOf(3, 2, 1, 0),
            listOf(2, 4, 0, 1, 3, 5),
            listOf(1, 2, 3, 0, 5, 4),
            PageOps.moveBlock(9, listOf(1, 4, 7), 9),
        )
        for (order in orders) assertEquals(order, apply(order.size, PageOps.movesFor(order)))
        assertTrue(PageOps.movesFor(listOf(0, 1, 2)).isEmpty())
    }

    @Test
    fun `insert gap indices`() {
        assertEquals(0, PageOps.insertIndex(-1, 3))
        assertEquals(2, PageOps.insertIndex(1, 3))
        assertEquals(3, PageOps.insertIndex(2, 3))
        assertEquals(3, PageOps.insertIndex(9, 3))
    }

    @Test
    fun `select all toggles and indices follow document order`() {
        val ids = listOf("a", "b", "c")
        assertEquals(setOf("a", "b", "c"), PageOps.selectAll(ids, setOf("b")))
        assertEquals(emptySet<String>(), PageOps.selectAll(ids, setOf("a", "b", "c")))
        assertEquals(listOf(0, 2), PageOps.indicesOf(ids, setOf("c", "a")))
    }
}
