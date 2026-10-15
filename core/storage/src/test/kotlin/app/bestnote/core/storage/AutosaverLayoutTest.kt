package app.bestnote.core.storage

import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperColor
import app.bestnote.core.model.PaperTemplate
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** PAGES-01/02 + CANVAS-07: added pages and per-page paper reach the disk through the autosaver. */
class AutosaverLayoutTest {
    private class RecordingStore : NotebookStore {
        val delegate = InMemoryNotebookStore()
        val commits = mutableListOf<Set<String>>()
        override suspend fun list() = delegate.list()
        override suspend fun read(id: NotebookId) = delegate.read(id)
        override suspend fun write(pkg: NotebookPackage) = writePages(pkg, pkg.pages.map { it.id }.toSet())
        override suspend fun delete(id: NotebookId) = delegate.delete(id)
        override suspend fun writePages(pkg: NotebookPackage, changedPageIds: Set<String>) {
            commits += changedPageIds
            delegate.write(pkg)
        }
    }

    private val nid = NotebookId("nb")
    private val initial = NotebookPackage(
        Notebook(nid, "Notes", 1, 1, pageCount = 2),
        List(2) { PageContent(Page(nid, it), InkChunkCodec.encode(emptyList()), "p$it") },
    )

    @Test fun addedPagesAndPaperChangesAreSaved() = runTest {
        val store = RecordingStore()
        val s = Autosaver(initial, store, backgroundScope, cpu = StandardTestDispatcher(testScheduler), timeSource = testScheduler.timeSource)
        val cornell = Page(nid, 0, PaperTemplate.Cornell, 420f, 595f, PaperColor.Dark)
        s.updateLayout(
            listOf(
                "p0" to Page(nid, 0),
                "p1" to cornell.copy(index = 7), // index is renumbered from the order
                "new-a" to Page(nid, 0, PaperTemplate.Grid, 842f, 595f),
            ),
        )
        val ink = randomStrokes(3, seed = 4)
        s.updatePage("new-a", ink) // a page known only from the pending layout
        assertTrue(s.hasUnsavedChanges)
        s.flush()
        assertFalse(s.hasUnsavedChanges)
        assertEquals(listOf(setOf("p1", "new-a")), store.commits) // p0 is untouched
        val pkg = store.read(nid)!!
        assertEquals(listOf("p0", "p1", "new-a"), pkg.pages.map { it.id })
        assertEquals(cornell.copy(index = 1), pkg.pages[1].page)
        assertEquals(Page(nid, 2, PaperTemplate.Grid, 842f, 595f), pkg.pages[2].page)
        assertEquals(ink, InkChunkCodec.decode(pkg.pages[2].strokes))
        assertEquals(emptyList<InkStroke>(), InkChunkCodec.decode(pkg.pages[0].strokes))
        assertEquals(3, pkg.notebook.pageCount)
        assertEquals(listOf("p0", "p1", "new-a"), s.pageIds)
    }

    @Test fun layoutIsValidated() = runTest {
        val s = Autosaver(initial, RecordingStore(), backgroundScope, cpu = StandardTestDispatcher(testScheduler), timeSource = testScheduler.timeSource)
        assertThrows(IllegalArgumentException::class.java) { s.updateLayout(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { s.updateLayout(listOf("a" to Page(nid, 0), "a" to Page(nid, 1))) }
        assertThrows(IllegalArgumentException::class.java) { s.updateLayout(listOf("../x" to Page(nid, 0))) }
        assertThrows(IllegalArgumentException::class.java) { s.updatePage("nope", emptyList()) }
    }

    @Test fun reorderDeleteAndRestoreKeepTheirInk() = runTest {
        val store = RecordingStore()
        val s = Autosaver(initial, store, backgroundScope, cpu = StandardTestDispatcher(testScheduler), timeSource = testScheduler.timeSource)
        val ink0 = randomStrokes(2, seed = 1)
        val ink1 = randomStrokes(2, seed = 2)
        s.updatePage("p0", ink0)
        s.updatePage("p1", ink1)
        s.flush()
        // Reorder: p1 first.
        s.updateLayout(listOf("p1" to Page(nid, 0), "p0" to Page(nid, 1)))
        s.flush()
        var pkg = store.read(nid)!!
        assertEquals(listOf("p1", "p0"), pkg.pages.map { it.id })
        assertEquals(ink1, InkChunkCodec.decode(pkg.pages[0].strokes))
        assertEquals(ink0, InkChunkCodec.decode(pkg.pages[1].strokes))
        // Delete p1, then undo (the page comes back with its ink, as the editor session does it).
        s.updateLayout(listOf("p0" to Page(nid, 0)))
        s.flush()
        assertEquals(listOf("p0"), store.read(nid)!!.pages.map { it.id })
        s.updateLayout(listOf("p1" to Page(nid, 0), "p0" to Page(nid, 1)))
        s.updatePage("p1", ink1)
        s.flush()
        pkg = store.read(nid)!!
        assertEquals(listOf("p1", "p0"), pkg.pages.map { it.id })
        assertEquals(ink1, InkChunkCodec.decode(pkg.pages[0].strokes))
        assertEquals(2, pkg.notebook.pageCount)
    }
}
