package app.bestnote.core.storage

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.PdfPageRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** PDF-01: a page remembers which PDF page it shows, through the v1 format and the directory store. */
class PdfBackgroundRoundTripTest {
    @get:Rule val tmp = TemporaryFolder()
    private val nid = NotebookId("pdf")
    private val asset = "assets/source.pdf"
    private val pdfBytes = "%PDF-1.4 fake".encodeToByteArray()

    private fun pkg(): NotebookPackage {
        val pages = listOf(
            PageContent(Page(nid, 0, PaperTemplate.Blank, 612f, 792f, pdf = PdfPageRef(asset, 0)), InkChunkCodec.encode(emptyList()), "p0"),
            PageContent(Page(nid, 1, PaperTemplate.Blank, 792f, 612f, pdf = PdfPageRef(asset, 41)), InkChunkCodec.encode(emptyList()), "p1"),
            PageContent(Page(nid, 2, PaperTemplate.Ruled), InkChunkCodec.encode(emptyList()), "p2"), // an added blank page
        )
        return NotebookPackage(Notebook(nid, "Slides", 1, 2, PaperTemplate.Blank, 3), pages, mapOf(asset to pdfBytes))
    }

    @Test fun pdfReferenceAndAssetRoundTrip() {
        val entries = BnoteFormat.toEntries(pkg())
        val back = BnoteFormat.fromEntries { entries[it] }
        assertEquals(pkg().pages.map { it.page }, back.pages.map { it.page })
        assertEquals(PdfPageRef(asset, 41), back.pages[1].page.pdf)
        assertNull(back.pages[2].page.pdf)
        assertArrayEquals(pdfBytes, back.assets.getValue(asset))
    }

    @Test fun survivesTheDirectoryStore() = runTest {
        val store = FileNotebookStore(tmp.root)
        store.write(pkg())
        val back = store.read(nid)!!
        assertEquals(PdfPageRef(asset, 0), back.pages[0].page.pdf)
        assertArrayEquals(pdfBytes, back.assets.getValue(asset))
        // A partial save (ink on one page) keeps the PDF asset and references.
        store.writePages(back, setOf("p2"))
        assertEquals(PdfPageRef(asset, 41), store.read(nid)!!.pages[1].page.pdf)
    }

    @Test fun legacyPagesHaveNoPdf() {
        val entries = BnoteFormat.toEntries(pkg())
        val meta = BnoteFormat.pageMetaPath("p0")
        entries[meta] = entries.getValue(meta).decodeToString()
            .replace(Regex(",\\s*\"pdfAsset\"[^,]*"), "").replace(Regex(",\\s*\"pdfPage\"[^,\\n}]*"), "").encodeToByteArray()
        assertNull(BnoteFormat.fromEntries { entries[it] }.pages[0].page.pdf)
    }

    @Test fun pdfReferenceToATraversalPathIsCorrupt() {
        val entries = BnoteFormat.toEntries(pkg())
        val meta = BnoteFormat.pageMetaPath("p0")
        entries[meta] = entries.getValue(meta).decodeToString().replace(asset, "assets/../x.pdf").encodeToByteArray()
        assertThrows(CorruptChunkException::class.java) { BnoteFormat.fromEntries { entries[it] } }
    }
}
