package app.bestnote.feature.editor

import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperColor
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.canvas.document.PagedDocument
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
    fun `a template the curated groups forgot still shows up in the picker (REQ-004)`() {
        val curated = linkedMapOf(
            PaperGroup.Basic to listOf(PaperTemplate.Ruled, PaperTemplate.Blank),
            PaperGroup.Study to listOf(PaperTemplate.Cornell),
        )
        val groups = PageOps.templateGroups(curated)
        // The picker offers the same complete list the library derives from the enum, each exactly once.
        assertEquals(PaperTemplate.entries.toSet(), groups.values.flatten().toSet())
        assertEquals(PaperTemplate.entries.size, groups.values.flatten().size)
        // The forgotten ones land in Basic, behind the templates placed there (enum order).
        val basic = groups.getValue(PaperGroup.Basic)
        assertEquals(listOf(PaperTemplate.Ruled, PaperTemplate.Blank), basic.take(2))
        assertEquals(PaperTemplate.Grid, basic[2])
        assertTrue(groups.getValue(PaperGroup.Study).contains(PaperTemplate.Cornell))
    }

    @Test
    fun `complete curated groups pass through untouched`() {
        val curated = linkedMapOf(PaperGroup.Basic to PaperTemplate.entries.toList())
        assertSame(curated, PageOps.templateGroups(curated))
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

    // PAGES-11: change paper from inside the editor.
    private val nid = NotebookId("nb")
    private fun document(count: Int = 4) = PagedDocument().apply { reset(List(count) { Page(nid, it) }, List(count) { "p$it" }) }
    private fun PagedDocument.templates() = pages.map { it.page.template }
    private val toGrid: (Page) -> Page = { it.copy(template = PaperTemplate.Grid) }

    @Test
    fun `this page changes only the current page and undo restores it`() {
        val d = document()
        assertEquals(listOf(2), PageOps.applyPaper(d, PaperScope.ThisPage, 2, d.pages[2].page, toGrid))
        assertEquals(listOf(Ruled, Ruled, PaperTemplate.Grid, Ruled), d.templates())
        d.undo()
        assertEquals(List(4) { Ruled }, d.templates())
        d.redo()
        assertEquals(PaperTemplate.Grid, d.pages[2].page.template)
    }

    @Test
    fun `all pages is one undo step`() {
        val d = document()
        PageOps.applyPaper(d, PaperScope.AllPages, 1, d.pages[1].page, toGrid)
        assertEquals(List(4) { PaperTemplate.Grid }, d.templates())
        d.undo()
        assertEquals(List(4) { Ruled }, d.templates())
        assertFalse(d.canUndo)
    }

    @Test
    fun `new pages leaves existing pages alone, drives add page, and undoes`() {
        val d = document()
        val cream = { p: Page -> p.copy(template = PaperTemplate.Dotted, paperColor = PaperColor.Cream) }
        assertEquals(emptyList<Int>(), PageOps.applyPaper(d, PaperScope.NewPages, 0, d.pages[0].page, cream))
        assertEquals(List(4) { Ruled }, d.templates())
        val at = d.addPage()
        assertEquals(PaperTemplate.Dotted, d.pages[at].page.template)
        assertEquals(PaperColor.Cream, d.pages[at].page.paperColor)
        d.undo() // the added page
        d.undo() // the default
        assertNull(d.newPagePaper)
        assertEquals(Ruled, d.pages[d.addPage()].page.template)
    }

    @Test
    fun `default paper change notifies listeners also on undo`() {
        val d = document()
        var calls = 0
        d.addListener(object : PagedDocument.Listener { override fun onNewPagePaperChanged() { calls++ } })
        d.setNewPagePaper(Page(nid, 0, PaperTemplate.Cornell))
        d.undo()
        d.redo()
        assertEquals(3, calls)
    }

    @Test
    fun `a no-op paper change records nothing`() {
        val d = document()
        assertEquals(emptyList<Int>(), PageOps.applyPaper(d, PaperScope.AllPages, 0, d.pages[0].page) { it })
        assertFalse(d.canUndo)
    }

    @Test
    fun `selected pages change as one step`() {
        val d = document()
        assertEquals(listOf(1, 3), PageOps.applyToPages(d, listOf(3, 1, 9), toGrid))
        assertEquals(listOf(Ruled, PaperTemplate.Grid, Ruled, PaperTemplate.Grid), d.templates())
        d.undo()
        assertEquals(List(4) { Ruled }, d.templates())
    }

    @Test
    fun `ink stays when paper changes`() {
        val d = document(2)
        PageOps.applyPaper(d, PaperScope.AllPages, 0, d.pages[0].page) { it.copy(template = PaperTemplate.Blank, widthPt = 400f, heightPt = 300f) }
        assertEquals(400f, d.pages[0].page.widthPt)
        assertEquals(0, d.pages[0].strokes.size)
    }

    @Test
    fun `default paper survives storage and bad data reads as none`() {
        val paper = Page(nid, 5, PaperTemplate.MusicStaff, 420f, 595f, PaperColor.Dark)
        val back = PageOps.decodeDefaultPaper(PageOps.encodeDefaultPaper(paper), nid)!!
        assertEquals(paper.copy(index = 0), back)
        assertNull(PageOps.decodeDefaultPaper(null, nid))
        assertNull(PageOps.decodeDefaultPaper(PageOps.encodeDefaultPaper(null), nid))
        assertNull(PageOps.decodeDefaultPaper("garbage".encodeToByteArray(), nid))
        assertNull(PageOps.decodeDefaultPaper("""{"template":"Nope","color":"White","width":1,"height":1}""".encodeToByteArray(), nid))
        assertNull(PageOps.decodeDefaultPaper("""{"template":"Grid","color":"White","width":-1,"height":9}""".encodeToByteArray(), nid))
    }

    private companion object {
        val Ruled = PaperTemplate.Ruled
    }
}
