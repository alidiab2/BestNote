package app.bestnote.core.storage

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperColor
import app.bestnote.core.model.PaperOrientation
import app.bestnote.core.model.PaperSizePreset
import app.bestnote.core.model.PaperSizes
import app.bestnote.core.model.PaperTemplate
import org.junit.Assert.assertEquals
import org.junit.Test

/** PAGES-01/02: every page keeps its own template, paper color and size through the v1 format. */
class PaperRoundTripTest {
    private val nid = NotebookId("paper")

    @Test fun everyTemplateColorAndSizeRoundTrips() {
        val sizes = listOf(
            PaperSizes.of(PaperSizePreset.A4),
            PaperSizes.of(PaperSizePreset.A5, PaperOrientation.Landscape),
            PaperSizes.of(PaperSizePreset.Letter),
            PaperSizes.of(PaperSizePreset.FitToScreen, viewportAspect = 1.6f),
            PaperSizes.of(PaperSizePreset.Custom, custom = app.bestnote.core.model.PageSize(300.5f, 1000.25f)),
        )
        var i = 0
        val pages = PaperTemplate.entries.flatMap { t ->
            PaperColor.entries.map { c ->
                val s = sizes[i % sizes.size]
                PageContent(Page(nid, i, t, s.widthPt, s.heightPt, c), InkChunkCodec.encode(emptyList()), "p${i++}")
            }
        }
        val pkg = NotebookPackage(Notebook(nid, "Paper", 0, 0, PaperTemplate.Cornell, pages.size), pages)
        val entries = BnoteFormat.toEntries(pkg)
        val back = BnoteFormat.fromEntries { entries[it] }
        assertEquals(pkg.notebook, back.notebook)
        assertEquals(pages.map { it.page }, back.pages.map { it.page })
    }

    @Test fun pagesWrittenBeforePaperColorReadAsWhite() {
        val pkg = NotebookPackage(
            Notebook(nid, "Old", 0, 0),
            listOf(PageContent(Page(nid, 0, PaperTemplate.Grid), InkChunkCodec.encode(emptyList()), "p0")),
        )
        val entries = BnoteFormat.toEntries(pkg)
        val meta = BnoteFormat.pageMetaPath("p0")
        val legacy = entries.getValue(meta).decodeToString().replace(Regex(",\\s*\"paper\": \"White\""), "")
        check(!legacy.contains("paper")) { legacy }
        entries[meta] = legacy.encodeToByteArray()
        assertEquals(Page(nid, 0, PaperTemplate.Grid), BnoteFormat.fromEntries { entries[it] }.pages.single().page)

        entries[meta] = entries.getValue(meta).decodeToString().replace("\"Grid\"", "\"Grid\", \"paper\": \"Holographic\"").encodeToByteArray()
        assertEquals(PaperColor.White, BnoteFormat.fromEntries { entries[it] }.pages.single().page.paperColor)
    }
}
