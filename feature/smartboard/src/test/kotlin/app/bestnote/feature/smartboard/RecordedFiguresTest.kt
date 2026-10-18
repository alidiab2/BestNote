package app.bestnote.feature.smartboard

import app.bestnote.ai.scan.Enhance
import app.bestnote.ai.scan.Raster
import app.bestnote.ai.scan.figure.FigureRedraw
import app.bestnote.ai.vision.Canary
import app.bestnote.ai.vision.NoteWire
import app.bestnote.ai.vision.VisionImage
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.FigureRaster
import app.bestnote.engine.layout.FigureSource
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteTemplates
import app.bestnote.engine.layout.RuledPaper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO

/**
 * AI-12 acceptance on the owner's boards: real DeepSeek (boards 2, 3) and Claude (board 4, coordinates in pixels) answers (scripts/agent/ai-live.sh, recorded in
 * notes/live_board*_figures.json) for the whiteboard crops (people masked out) in boards/, through the
 * app's own path: figures redrawn from the board the AI saw, then the page laid out. Pages are written
 * to build/smartboard-preview/figures-*.png.
 */
class RecordedFiguresTest {
    private fun board(name: String): Raster {
        val img = ImageIO.read(javaClass.getResource("/boards/$name.jpg"))
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        return Raster(img.width, img.height, px)
    }

    @Test fun `the teacher's drawings are redrawn into the notes and nothing is lost`() {
        for ((notes, image) in listOf("live_board2_figures" to "board2-whiteboard", "live_board3_figures" to "board3-whiteboard", "live_board4_figures" to "board4-whiteboard")) {
            val doc = NoteWire.answer(javaClass.getResource("/notes/$notes.json")!!.readText())
            for (enhanced in listOf(false, true)) {
                // The app's default: the board is auto-enhanced before the AI and the redraw see it.
                val raster = board(image).let { if (enhanced) Enhance.apply(it) else it }
                val yScale = (raster.height + Canary.stripHeight(raster.width, raster.height)).toFloat() / raster.height
                val template = NoteTemplates.byId(NoteTemplates.STUDY_COLUMNS)
                val n = TemplateNotes(AwtFonts)
                System.setProperty("figure.debug", "1")
                runBlocking {
                    n.read(RecordedVisionProvider(doc), listOf(VisionImage(ByteArray(0), "image/jpeg")), template, rtl = true) { d, _ ->
                        FigureRedraw.resolve(d, raster, yScale) { FigureRaster("assets/img-test.png", it.width, it.height) }
                    }
                }
                val figures = n.raw!!.flatten().filter { it.element == NoteElement.Diagram }
                assertTrue("$notes: a figure", figures.isNotEmpty())
                for (f in figures) {
                    println("$notes enhanced=$enhanced: ${f.head} → ${f.figure?.source} shapes=${f.diagram?.shapes?.size}")
                    assertTrue("$notes: drawn", f.figure!!.source != FigureSource.Pending)
                }
                assertTrue("$notes: redrawn, not a picture", figures.any { it.figure!!.source == FigureSource.Redrawn })
                for (paperTemplate in listOf(PaperTemplate.Grid, PaperTemplate.Ruled)) {
                    val paper = RuledPaper.of(paperTemplate, PageSize.A4)
                    val pages = n.render(template, paper, rtlHint = true)
                    pages.pages.pages.forEachIndexed { i, p -> TypesetPng.png(paper, p, "figures-$notes${if (enhanced) "-enhanced" else ""}-${paperTemplate.name}-$i", dir = "build/smartboard-preview") }
                    assertEquals(pages.doc.elements.size, pages.placed.map { it.second.key }.distinct().size)
                }
            }
        }
    }
}
