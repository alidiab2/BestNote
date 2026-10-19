package app.bestnote.ai.scan.figure

import app.bestnote.ai.scan.Raster
import app.bestnote.core.model.Vec2
import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.FigureBox
import app.bestnote.engine.layout.FigureLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.atan2

/**
 * REQ-001 (alidiab2/BestNote-requests#2): the owner's boards whose drawings came out wrong — a smooth
 * U(x) curve redrawn as a jagged polyline without its dotted guides, the F–Δt graph without its pale
 * level line and the end of its axis, the ground under m_1 and m_2 turned into a dimension arrow, a
 * velocity's letters taken for a block. Crops of the
 * attached photos; boxes and labels written as the AI's figure contract gives them (crop pixels).
 */
class Req001FiguresTest {
    private fun load(name: String): Raster {
        val img = ImageIO.read(javaClass.getResource("/figures/$name.jpg"))
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        return Raster(img.width, img.height, px)
    }

    private class Case(val board: Raster, val box: FigureBox, val labels: List<FigureLabel>)

    private fun case(name: String, box: IntArray, vararg labels: Pair<String, Pair<Int, Int>>): Case {
        val b = load(name)
        fun fx(x: Int) = x / b.width.toFloat()
        fun fy(y: Int) = y / b.height.toFloat()
        return Case(b, FigureBox(fx(box[0]), fy(box[1]), fx(box[2]), fy(box[3])), labels.map { (t, at) -> FigureLabel(t, Vec2(fx(at.first), fy(at.second))) })
    }

    private val graph by lazy {
        case("graph-req001", intArrayOf(45, 30, 345, 232), "U(x)" to (75 to 38), "30" to (38 to 68), "2" to (148 to 145), "5" to (255 to 185), "7" to (325 to 165), "x" to (352 to 178))
    }
    private val impulse by lazy {
        case("impulse-req001", intArrayOf(50, 60, 460, 420), "F\\,(N)" to (135 to 40), "F" to (45 to 170), "\\Delta t" to (322 to 435), "\\Delta t" to (470 to 455))
    }
    private val before by lazy {
        case("collision-req001", intArrayOf(20, 55, 310, 120), "\\vec{v}_1" to (105 to 40), "\\vec{v}_2" to (232 to 40), "m_1" to (105 to 95), "m_2" to (245 to 97))
    }
    private val after by lazy {
        case("collision-req001", intArrayOf(25, 255, 330, 345), "\\vec{u}_1" to (130 to 240), "\\vec{u}_2" to (232 to 245), "m_1" to (147 to 293), "m_2" to (243 to 305))
    }

    private fun run(name: String, c: Case): FigureRedraw.Result {
        val r = FigureRedraw.redraw(c.board, c.box, c.labels)
        println("$name: accepted=${r.accepted} recall=${r.recall} precision=${r.precision}")
        r.prims.forEach { println("  $it") }
        r.diagram?.let { render(c.board, c.box, it, name) }
        return r
    }

    private fun Prim.Seg.upright() = FigureFit.angleDiff(angle, (Math.PI / 2).toFloat()) < 10f

    @Test fun `a smooth graph stays smooth and keeps its dotted guides`() {
        val r = run("req001-graph", graph)
        assertTrue("accepted", r.accepted)
        // The valley and the hill between the axis crossings: smooth curves, not chains of straight pieces.
        val curves = r.prims.filterIsInstance<Prim.Curve>()
        assertTrue("a smooth curve: ${r.prims}", curves.any { c -> c.points.maxOf { it.x } - c.points.minOf { it.x } > 0.4f * FigureRedraw.WORK_SIDE })
        // The dotted drops at x = 2, 5 and 7.
        val guides = r.prims.filterIsInstance<Prim.Seg>().filter { it.dashed && it.upright() }
        assertEquals("three dotted guides: $guides", 3, guides.size)
    }

    @Test fun `a graph drawn in light marker keeps its level and its whole axis`() {
        val r = run("req001-impulse", impulse)
        assertTrue("accepted", r.accepted)
        val segs = r.prims.filterIsInstance<Prim.Seg>()
        // The pale blue F level runs from the F axis to the dashed Δt line, and names nothing: no arrow.
        val level = segs.filter { !it.dashed && FigureFit.angleDiff(it.angle, 0f) < 3f && it.a.y in 140f..190f }.maxByOrNull { it.length }
        assertTrue("the F level: $segs", level != null && level.length > 0.45f * FigureRedraw.WORK_SIDE)
        assertTrue("the F level is no force", !level!!.head && !level.tail)
        // The Δt axis reaches its arrowhead (its faint end was lost).
        val axis = segs.filter { FigureFit.angleDiff(it.angle, 0f) < 3f && it.a.y > 380f }.maxByOrNull { it.length }
        assertTrue("the Δt axis: $segs", axis != null && maxOf(axis.a.x, axis.b.x) > 0.8f * FigureRedraw.WORK_SIDE)
        assertTrue("the dashed Δt line", segs.any { it.dashed && it.upright() })
    }

    @Test fun `the ground under named blocks is no dimension arrow`() {
        val r = run("req001-before", before)
        assertTrue("accepted", r.accepted)
        assertEquals("m_1 and m_2: ${r.prims}", 2, r.prims.count { it is Prim.Block })
        val ground = r.prims.filterIsInstance<Prim.Seg>().maxByOrNull { it.length }!!
        assertTrue("a plain ground line: $ground", !ground.head && !ground.tail)
    }

    @Test fun `a velocity written over a block is not taken for a block`() {
        val r = run("req001-after", after)
        // Where the velocities' names were written (crop pixels, as the redraw placed them).
        val vecs = r.diagram!!.shapes.filterIsInstance<VectorShape.Label>().filter { it.text.startsWith("\\vec") }.map { XY(it.at.x, it.at.y) }
        assertEquals(2, vecs.size)
        val blocks = r.prims.filterIsInstance<Prim.Block>()
        assertTrue("no block around ${vecs}: $blocks", blocks.none { b ->
            vecs.any { v -> v.x in b.corners.minOf { it.x }..b.corners.maxOf { it.x } && v.y in b.corners.minOf { it.y }..b.corners.maxOf { it.y } }
        })
    }

    @Test fun `a sharp corner is a corner, a tight bend is not`() {
        val fit = FigureFit(3f, 600f)
        val bend = (0..60).map { i -> val t = Math.PI * i / 60; XY((40 * Math.cos(t)).toFloat(), (40 * Math.sin(t)).toFloat()) }
        assertTrue(fit.sharpestTurn(bend, 12f) < 25f)
        val corner = (0..30).map { XY(it.toFloat() * 2, 0f) } + (1..30).map { XY(60f, it.toFloat() * 2) }
        assertTrue(fit.sharpestTurn(corner, 12f) > 80f)
    }

    @Test fun `dots in a row are one dashed line, scattered dots are none`() {
        val fit = FigureFit(2.5f, 600f)
        val row = (0..6).map { XY(100f, 50f + it * 9f) }
        val out = fit.dotted(emptyList(), row)
        assertEquals(1, out.count { it is Prim.Seg && it.dashed })
        val scattered = listOf(XY(10f, 10f), XY(30f, 14f), XY(18f, 33f), XY(40f, 40f))
        assertTrue(fit.dotted(emptyList(), scattered).isEmpty())
    }

    private fun java.awt.Graphics2D.line(x0: Number, y0: Number, x1: Number, y1: Number) = draw(Line2D.Float(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat()))

    /** Before/after: the crop, and the redraw beside it (build/figure-redraw/NAME.png). */
    private fun render(board: Raster, box: FigureBox, d: VectorDiagram, name: String) {
        val crop = FigureRedraw.crop(board, box).image
        val w = crop.width
        val h = crop.height
        val out = BufferedImage(w * 2 + 10, h, BufferedImage.TYPE_INT_RGB)
        val src = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, w, h, crop.argb, 0, w) }
        val g = out.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, out.width, h)
        g.drawImage(src, 0, 0, null)
        g.translate(w + 10, 0)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        for (s in d.shapes) {
            g.color = Color(s.style.colorArgb.toInt())
            g.stroke = if (s.style.dashed) BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(8f, 7f), 0f) else BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            when (s) {
                is VectorShape.Line -> g.line(s.from.x, s.from.y, s.to.x, s.to.y)
                is VectorShape.Arrow -> {
                    g.line(s.from.x, s.from.y, s.to.x, s.to.y)
                    val back = atan2((s.from.y - s.to.y).toDouble(), (s.from.x - s.to.x).toDouble())
                    for (d2 in listOf(-0.45, 0.45)) g.line(s.to.x, s.to.y, s.to.x + s.headSize * Math.cos(back + d2), s.to.y + s.headSize * Math.sin(back + d2))
                }
                is VectorShape.Polyline -> (if (s.closed) s.points + s.points.first() else s.points).zipWithNext().forEach { (a, b) -> g.line(a.x, a.y, b.x, b.y) }
                is VectorShape.Ellipse -> g.draw(java.awt.geom.Ellipse2D.Float(s.bounds.left, s.bounds.top, s.bounds.width, s.bounds.height))
                is VectorShape.Label -> { g.color = Color.BLACK; g.font = g.font.deriveFont(18f); g.drawString(s.text, s.at.x - 6, s.at.y + 6) }
                else -> Unit
            }
        }
        g.dispose()
        val dir = File("build/figure-redraw").apply { mkdirs() }
        ImageIO.write(out, "png", File(dir, "$name.png"))
    }
}
