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

    /** The redraw alone, twice its size (build/smartboard-preview/drawing-*.png). */
    private fun drawing(d: app.bestnote.core.model.VectorDiagram, name: String) {
        val k = 2f
        val img = java.awt.image.BufferedImage((d.viewBox.width * k).toInt(), (d.viewBox.height * k).toInt(), java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.scale(k.toDouble(), k.toDouble())
        g.translate(-d.viewBox.left.toDouble(), -d.viewBox.top.toDouble())
        fun line(a: app.bestnote.core.model.Vec2, b: app.bestnote.core.model.Vec2) = g.draw(java.awt.geom.Line2D.Float(a.x, a.y, b.x, b.y))
        for (s in d.shapes) {
            g.color = java.awt.Color(s.style.colorArgb.toInt())
            g.stroke = java.awt.BasicStroke(1.5f, 1, 1, 1f, if (s.style.dashed) floatArrayOf(6f, 5f) else null, 0f)
            when (s) {
                is app.bestnote.core.model.VectorShape.Line -> line(s.from, s.to)
                is app.bestnote.core.model.VectorShape.Arrow -> {
                    line(s.from, s.to)
                    val back = kotlin.math.atan2(s.from.y - s.to.y, s.from.x - s.to.x)
                    for (t in listOf(-0.45f, 0.45f)) line(s.to, app.bestnote.core.model.Vec2(s.to.x + s.headSize * kotlin.math.cos(back + t), s.to.y + s.headSize * kotlin.math.sin(back + t)))
                }
                is app.bestnote.core.model.VectorShape.Polyline -> (if (s.closed) s.points + s.points.first() else s.points).zipWithNext().forEach { (a, b) -> line(a, b) }
                is app.bestnote.core.model.VectorShape.Ellipse -> g.draw(java.awt.geom.Ellipse2D.Float(s.bounds.left, s.bounds.top, s.bounds.width, s.bounds.height))
                is app.bestnote.core.model.VectorShape.Label -> { g.font = g.font.deriveFont(9f); g.drawString(s.text, s.at.x - 4, s.at.y + 4) }
                else -> Unit
            }
        }
        g.dispose()
        javax.imageio.ImageIO.write(img, "png", java.io.File("build/smartboard-preview").apply { mkdirs() }.resolve("$name.png"))
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
                    f.diagram?.let { drawing(it, "drawing-$notes${if (enhanced) "-enhanced" else ""}") }
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
