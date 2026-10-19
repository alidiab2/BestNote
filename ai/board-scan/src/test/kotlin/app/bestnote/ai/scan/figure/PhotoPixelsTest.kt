package app.bestnote.ai.scan.figure

import app.bestnote.ai.scan.Perspective
import app.bestnote.ai.scan.Pt
import app.bestnote.ai.scan.Quad
import app.bestnote.ai.scan.Raster
import app.bestnote.engine.layout.FigureBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import kotlin.math.abs

/** REQ-001: drawings cut from the full-resolution photo, only their region decoded. */
class PhotoPixelsTest {
    private val quad = Quad(Pt(0.1f, 0.12f), Pt(0.92f, 0.08f), Pt(0.95f, 0.9f), Pt(0.06f, 0.86f))

    /** A 2400 × 1800 "camera photo": a light board inside [quad] with thin dark lines (a faint blue one too). */
    private fun photo(): Raster {
        val img = BufferedImage(2400, 1800, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(90, 80, 70); g.fillRect(0, 0, 2400, 1800)
        val p = java.awt.Polygon(quad.corners.map { (it.x * 2400).toInt() }.toIntArray(), quad.corners.map { (it.y * 1800).toInt() }.toIntArray(), 4)
        g.color = Color(225, 228, 230); g.fillPolygon(p)
        val map = Perspective.mapper(quad)
        fun at(u: Float, v: Float) = map(u, v).let { it.x * 2400 to it.y * 1800 }
        g.stroke = BasicStroke(2.2f)
        g.color = Color(40, 40, 46)
        for (k in 0 until 12) { val (x0, y0) = at(0.4f, 0.4f + k * 0.01f); val (x1, y1) = at(0.6f, 0.4f + k * 0.01f); g.draw(Line2D.Float(x0, y0, x1, y1)) }
        g.color = Color(170, 190, 225)
        val (a, b) = at(0.4f, 0.55f); val (c, d) = at(0.6f, 0.55f); g.draw(Line2D.Float(a, b, c, d))
        g.dispose()
        val px = IntArray(2400 * 1800)
        img.getRGB(0, 0, 2400, 1800, px, 0, 2400)
        return Raster(2400, 1800, px)
    }

    @Test fun `a drawing is cut from the photo at full resolution, decoding only its region`() {
        val src = photo()
        val board = Perspective.warp(src, quad, 800, 600) // the copy the model reads, a third of the photo's detail
        val asked = ArrayList<IntArray>()
        val pixels = PhotoPixels(src.width, src.height, quad, enhance = false) { x0, y0, x1, y1, sample ->
            asked += intArrayOf(x0, y0, x1, y1, sample)
            val w = (x1 - x0) / sample
            val h = (y1 - y0) / sample
            Raster(w, h, IntArray(w * h) { i -> src[x0 + (i % w) * sample, y0 + (i / w) * sample] })
        }
        assertTrue("gain ${pixels.gainOver(board)}", pixels.gainOver(board) > 2.5f)
        val box = FigureBox(0.38f, 0.37f, 0.62f, 0.58f)
        val fine = FigureRedraw.crop(board, box, pixels)
        val coarse = FigureRedraw.crop(board, box)
        assertEquals(coarse.image.width, fine.image.width)
        // Only the drawing's part of the photo was read (under a tenth of it).
        val (x0, y0, x1, y1, _) = asked.single().toList()
        assertTrue("region ${asked.single().toList()}", (x1 - x0).toLong() * (y1 - y0) < src.width.toLong() * src.height / 10)
        // The same place: the crops agree where the board is plain, and the photo's crop is sharper (more contrast).
        fun luma(c: Int) = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
        val diff = fine.image.argb.indices.map { abs(luma(fine.image.argb[it]) - luma(coarse.image.argb[it])) }.sorted()
        assertTrue("median difference ${diff[diff.size / 2]}", diff[diff.size / 2] < 6)
        fun contrast(r: Raster) = r.argb.map(::luma).let { l -> l.max() - l.min() }
        assertTrue("sharper: ${contrast(fine.image)} vs ${contrast(coarse.image)}", contrast(fine.image) > contrast(coarse.image))
    }

    @Test fun `an upright rectangle maps onto the stored pixels of a turned photo`() {
        // A stored 400 × 300 photo turned 90° clockwise is 300 × 400 upright.
        assertEquals(listOf(20, 300 - 50, 80, 300 - 10), PhotoPixels.storedRect(90, 400, 300, 10, 20, 50, 80).toList())
        assertEquals(listOf(400 - 50, 300 - 80, 400 - 10, 300 - 20), PhotoPixels.storedRect(180, 400, 300, 10, 20, 50, 80).toList())
        assertEquals(listOf(400 - 80, 10, 400 - 20, 50), PhotoPixels.storedRect(270, 400, 300, 10, 20, 50, 80).toList())
        assertEquals(listOf(10, 20, 50, 80), PhotoPixels.storedRect(0, 400, 300, 10, 20, 50, 80).toList())
    }
}
