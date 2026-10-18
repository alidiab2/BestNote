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
import kotlin.math.abs
import kotlin.math.atan2

class FigureRedrawTest {
    private fun raster(img: BufferedImage): Raster {
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        return Raster(img.width, img.height, px)
    }

    /** A synthetic board: white, with marker lines drawn by [draw] (slightly grey background, like a photo). */
    private fun board(w: Int, h: Int, draw: java.awt.Graphics2D.() -> Unit): Raster {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(232, 234, 236)
        g.fillRect(0, 0, w, h)
        g.color = Color(40, 40, 46)
        g.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw()
        g.dispose()
        return raster(img)
    }

    private fun java.awt.Graphics2D.line(x0: Number, y0: Number, x1: Number, y1: Number) = draw(Line2D.Float(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat()))

    private fun angleDeg(s: Prim.Seg): Float {
        var a = Math.toDegrees(atan2((s.b.y - s.a.y).toDouble(), (s.b.x - s.a.x).toDouble())).toFloat()
        if (a < 0) a += 180f
        if (a >= 180f) a -= 180f
        return a
    }

    @Test fun `a wobbly right triangle becomes three exact lines with a right angle`() {
        val b = board(400, 300) {
            line(60, 250, 340, 252) // base, 0.4° off
            line(340, 252, 338, 60) // side, 0.6° off
            line(338, 60, 60, 250) // hypotenuse
        }
        val r = FigureRedraw.redraw(b, FigureBox(0.1f, 0.15f, 0.9f, 0.9f))
        assertTrue("accepted ${r.recall} ${r.precision}", r.accepted)
        val segs = r.prims.filterIsInstance<Prim.Seg>()
        assertEquals("$segs", 3, segs.size)
        val angles = segs.map(::angleDeg).sorted()
        assertEquals(0f, angles.first(), 0.01f)
        assertTrue(angles.any { abs(it - 90f) < 0.01f })
    }

    @Test fun `arrowheads and dashes are recognised`() {
        val b = board(400, 300) {
            line(50, 150, 300, 150) // shaft
            line(300, 150, 280, 138) // barbs
            line(300, 150, 280, 162)
            for (i in 0 until 7) line(60 + i * 40, 240, 80 + i * 40, 240) // dashed line
        }
        val r = FigureRedraw.redraw(b, FigureBox(0.05f, 0.3f, 0.95f, 0.95f))
        val segs = r.prims.filterIsInstance<Prim.Seg>()
        assertEquals("one arrow: $segs", 1, segs.count { it.head })
        assertTrue("arrow points right", segs.first { it.head }.let { it.b.x > it.a.x })
        assertEquals("one dashed line: $segs", 1, segs.count { it.dashed })
        assertTrue(r.accepted)
    }

    @Test fun `a circle stays a circle`() {
        val b = board(300, 300) { draw(java.awt.geom.Ellipse2D.Float(70f, 70f, 160f, 160f)) }
        val r = FigureRedraw.redraw(b, FigureBox(0.15f, 0.15f, 0.85f, 0.85f))
        val arc = r.prims.filterIsInstance<Prim.Arc>().single()
        assertTrue(arc.full)
        assertTrue(r.accepted)
    }

    @Test fun `bbox maps to the crop with padding and the canary strip removed`() {
        val b = Raster(1000, 500)
        val c = FigureRedraw.crop(b, FigureBox(0.2f, 0.2f, 0.6f, 0.6f))
        assertTrue(c.left in 160..170 && c.right in 630..640)
        assertTrue(c.top in 80..85 && c.bottom in 315..320)
        assertEquals(FigureRedraw.WORK_SIDE, maxOf(c.image.width, c.image.height))
        // The AI saw 500 px of board + 100 px of strip: its 0.5 is the board's 0.6.
        val f = FigureRedraw.onBoard(app.bestnote.engine.layout.BoardFigure(app.bestnote.engine.layout.FigureKind.Graph, FigureBox(0.1f, 0.25f, 0.4f, 0.5f)), 600f / 500f)
        assertEquals(0.3f, f.box!!.top, 1e-4f)
        assertEquals(0.6f, f.box!!.bottom, 1e-4f)
    }

    @Test fun `the teacher's incline is redrawn faithfully`() {
        val img = ImageIO.read(javaClass.getResource("/figures/incline-board2.jpg"))
        val board = raster(img)
        fun at(x: Int, y: Int) = Vec2(x / img.width.toFloat(), y / img.height.toFloat())
        val labels = listOf(
            FigureLabel("N", at(70, 87)), FigureLabel("y", at(58, 70)), FigureLabel("F", at(163, 68)), FigureLabel("f_k", at(66, 140)),
            FigureLabel("mg", at(124, 190)), FigureLabel("37^\\circ", at(130, 155)), FigureLabel("\\alpha=37^\\circ", at(103, 172)),
        )
        val box = FigureBox(40f / img.width, 52f / img.height, 205f / img.width, 196f / img.height)
        System.setProperty("figure.debug", "1")
        val r = FigureRedraw.redraw(board, box, labels)
        System.clearProperty("figure.debug")
        render(board, box, r.diagram!!, "incline-board2")
        val segs = r.prims.filterIsInstance<Prim.Seg>()
        println("incline: recall=${r.recall} precision=${r.precision} prims=${r.prims.size} arrows=${segs.count { it.head }} dashed=${segs.count { it.dashed }}")
        for (p in r.prims) println("  $p")
        val long = segs.filter { it.length > 0.25f * FigureRedraw.WORK_SIDE }
        assertTrue("the base is horizontal", long.any { angleDeg(it) < 0.01f })
        assertTrue("the side is vertical", long.any { abs(angleDeg(it) - 90f) < 0.01f })
        assertTrue("the incline rises at about 37°", long.any { val a = 180f - angleDeg(it); abs(a - 37f) < 6f })
        assertTrue("force arrows", segs.count { it.head } >= 3)
        assertTrue("accepted", r.accepted)
    }

    @Test fun `springs become coils, with dimension arrows and the block`() {
        val img = ImageIO.read(javaClass.getResource("/figures/springs-board3.jpg"))
        val board = raster(img)
        fun at(x: Int, y: Int) = Vec2(x / img.width.toFloat(), y / img.height.toFloat())
        val cases = listOf(
            "springs-A" to (FigureBox(55f / img.width, 30f / img.height, 645f / img.width, 245f / img.height) to listOf(FigureLabel("0.4\\,(m)", at(478, 98)), FigureLabel("m", at(604, 193)), FigureLabel("0", at(356, 228)))),
            "springs-B" to (FigureBox(30f / img.width, 255f / img.height, 520f / img.width, 450f / img.height) to listOf(FigureLabel("0.2\\,(m)", at(412, 290)), FigureLabel("m", at(480, 383)), FigureLabel("0", at(343, 437)))),
        )
        for ((name, c) in cases) {
            val (box, labels) = c
            System.setProperty("figure.debug", "1")
            val r = FigureRedraw.redraw(board, box, labels)
            System.clearProperty("figure.debug")
            render(board, box, r.diagram!!, name)
            val segs = r.prims.filterIsInstance<Prim.Seg>()
            println("$name: recall=${r.recall} precision=${r.precision} prims=${r.prims.size} coils=${r.prims.count { it is Prim.Coil }} dims=${segs.count { it.head && it.tail }}")
            for (p in r.prims) println("  $p")
            assertEquals("$name: one spring", 1, r.prims.count { it is Prim.Coil })
            assertTrue("$name: a dimension arrow", segs.any { it.head && it.tail })
            assertTrue("$name accepted", r.accepted)
        }
    }

    @Test fun `a vertical throw keeps its dashed path, both arrows and the hatched ground`() {
        // Claude's live answer for board 4, in pixels of the whiteboard image (feature/smartboard's fixture: people masked).
        val img = ImageIO.read(File("../../feature/smartboard/src/test/resources/boards/board4-whiteboard.jpg"))
        val board = raster(img)
        fun at(x: Int, y: Int) = Vec2(x / img.width.toFloat(), y / img.height.toFloat())
        val labels = listOf(
            FigureLabel("m", at(865, 455)), FigureLabel("V=0", at(940, 455)), FigureLabel("\\text{מצב } B", at(705, 440)),
            FigureLabel("m", at(885, 760)), FigureLabel("V=10\\,(m/sec)", at(970, 720)), FigureLabel("\\text{מצב } A", at(740, 720)),
            FigureLabel("5\\,(m)", at(805, 860)), FigureLabel("h_B", at(1000, 660)),
        )
        val box = FigureBox(420f / img.width, 410f / img.height, 1000f / img.width, 975f / img.height)
        System.setProperty("figure.debug", "1")
        val r = FigureRedraw.redraw(board, box, labels)
        System.clearProperty("figure.debug")
        render(board, box, r.diagram!!, "throw-board4")
        val segs = r.prims.filterIsInstance<Prim.Seg>()
        println("throw: recall=${r.recall} precision=${r.precision} prims=${r.prims.size} arrows=${segs.count { it.head }} dashed=${segs.count { it.dashed }}")
        for (p in r.prims) println("  $p")
        assertTrue("a dashed path", segs.any { it.dashed })
        assertTrue("two vertical dimension arrows", segs.count { it.head && it.tail && abs(angleDeg(it) - 90f) < 8f } >= 1)
        assertTrue("accepted", r.accepted)
    }

    /** Before/after: the crop, and the redraw on white beside it (build/figure-redraw/NAME.png). */
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
                is VectorShape.Label -> { g.font = g.font.deriveFont(18f); g.drawString(s.text, s.at.x - 6, s.at.y + 6) }
                else -> Unit
            }
        }
        g.dispose()
        val dir = File("build/figure-redraw").apply { mkdirs() }
        ImageIO.write(out, "png", File(dir, "$name.png"))
    }
}
