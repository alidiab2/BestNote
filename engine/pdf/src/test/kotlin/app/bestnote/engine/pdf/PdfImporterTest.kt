package app.bestnote.engine.pdf

import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.PdfPageRef
import app.bestnote.core.storage.InMemoryNotebookStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException

class PdfImporterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val pdf = "%PDF-1.4 pretend".encodeToByteArray()
    private val sizes = listOf(PdfPageSize(612, 792), PdfPageSize(792, 612), PdfPageSize(595, 842))

    private fun importer(store: InMemoryNotebookStore, sizes: (java.io.File) -> List<PdfPageSize> = { this.sizes }) =
        PdfImporter(store, sizes, kotlinx.coroutines.Dispatchers.Unconfined, { 1000L }, { "nb-1" })

    @Test fun everyPdfPageBecomesAPageThatShowsIt() = runTest {
        val store = InMemoryNotebookStore()
        val nb = importer(store).import(ByteArrayInputStream(pdf), "Lecture 3.pdf", tmp.root)
        assertEquals("Lecture 3", nb.title)
        assertEquals(3, nb.pageCount)
        val pkg = store.read(nb.id)!!
        assertEquals(listOf(PdfPageRef(PdfImporter.ASSET, 0), PdfPageRef(PdfImporter.ASSET, 1), PdfPageRef(PdfImporter.ASSET, 2)), pkg.pages.map { it.page.pdf })
        assertEquals(listOf(612f to 792f, 792f to 612f, 595f to 842f), pkg.pages.map { it.page.widthPt to it.page.heightPt })
        assertTrue(pkg.pages.all { it.page.template == PaperTemplate.Blank })
        assertArrayEquals(pdf, pkg.assets.getValue(PdfImporter.ASSET))
        assertEquals(0, tmp.root.listFiles()!!.size) // temp copy removed
    }

    @Test fun anUnreadablePdfIsRejectedAndNothingIsStored() = runTest {
        val store = InMemoryNotebookStore()
        assertThrows(PdfImportException::class.java) {
            kotlinx.coroutines.runBlocking { importer(store) { throw IOException("bad") }.import(ByteArrayInputStream(pdf), "x", tmp.root) }
        }
        assertThrows(PdfImportException::class.java) {
            kotlinx.coroutines.runBlocking { importer(store) { throw SecurityException("password") }.import(ByteArrayInputStream(pdf), "x", tmp.root) }
        }
        assertThrows(PdfImportException::class.java) {
            kotlinx.coroutines.runBlocking { importer(store) { emptyList() }.import(ByteArrayInputStream(pdf), "x", tmp.root) }
        }
        assertTrue(store.list().isEmpty())
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun blankNamesGetADefaultTitle() {
        assertEquals("PDF", PdfImporter.cleanTitle("  .pdf "))
        assertEquals("Notes v2", PdfImporter.cleanTitle("Notes v2.PDF"))
    }
}
