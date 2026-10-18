package app.bestnote.engine.layout.figlib

import app.bestnote.core.model.Vec2
import app.bestnote.core.model.VectorShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2

class FigureLibraryTest {
    private fun r(vararg v: Any): List<Any> = v.map { if (it is Int) it.toDouble() else it }

    private fun deg(a: Vec2, b: Vec2) = Math.toDegrees(atan2(-(b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()

    private fun axis(d: Float) = ((d % 180f) + 180f) % 180f

    @Test fun `every example renders and every kind has one`() {
        val out = FigExamples.all.map { s ->
            val r = FigureLibrary.render(s)
            assertNotNull("${s.kind} renders", r)
            s.kind to r!!.diagram
        }
        FigPng.sheet(out, File("build/figlib/examples.png"))
        assertEquals(FigureLibrary.elements.map { it.kind }.toSet(), FigExamples.all.map { it.kind }.toSet())
    }

    /** The owner's checks on the AI-12b incline redraw, as rules of the parametric incline. */
    @Test fun `an incline is drawn cleanly with forces anchored and turned to the slope`() {
        val spec = FigSpec("incline", listOf(r("a", 37), r("blk", "m"), r("f", "N", "n"), r("f", "F", 20), r("f", "f_k", "down"), r("f", "mg", "g"), r("ang", "37^\\circ"), r("f", "N", "n")))
        val d = FigureLibrary.render(spec)!!.diagram
        val tri = d.shapes.filterIsInstance<VectorShape.Polyline>().single { it.closed && it.points.size == 3 }
        val block = d.shapes.filterIsInstance<VectorShape.Polyline>().single { it.closed && it.points.size == 4 }
        val centre = Vec2(block.points.map { it.x }.average().toFloat(), block.points.map { it.y }.average().toFloat())
        val sides = tri.points.indices.map { tri.points[it] to tri.points[(it + 1) % 3] }
        val slope = sides.first { (a, b) -> axis(deg(a, b)).let { x -> abs(x - 90f) > 1f && x > 1f && x < 179f } }
        val s = deg(slope.first, slope.second)
        assertEquals(37f, axis(s).let { if (it > 90f) 180f - it else it }, 0.1f)
        // Exactly four force arrows (N given twice is drawn once), all from the block centre.
        val arrows = d.shapes.filterIsInstance<VectorShape.Arrow>()
        assertEquals(4, arrows.size)
        for (a in arrows) assertTrue("anchored at the centre", abs(a.from.x - centre.x) < 0.01f && abs(a.from.y - centre.y) < 0.01f)
        val labels = d.shapes.filterIsInstance<VectorShape.Label>()
        fun arrow(name: String) = arrows.minBy { a -> labels.first { it.text == name }.at.let { (it - a.to).length() } }
        assertEquals(90f, abs(axis(deg(arrow("N").from, arrow("N").to)) - axis(s)), 0.5f) // N ⟂ slope
        assertEquals(0f, axis(deg(arrow("f_k").from, arrow("f_k").to)) - axis(s), 0.5f) // f_k ∥ slope
        assertEquals(-90f, deg(arrow("mg").from, arrow("mg").to), 0.5f) // mg straight down
        assertEquals(20f, abs(axis(deg(arrow("F").from, arrow("F").to)) - axis(s)), 0.5f) // F at its given angle
        // One angle label and one arc, the right-angle mark in the right-angle corner.
        assertEquals(1, labels.count { it.text == "37^\\circ" })
        val corner = tri.points.first { it != slope.first && it != slope.second }
        val opens = d.shapes.filterIsInstance<VectorShape.Polyline>().filter { !it.closed }
        assertEquals(1, opens.count { p -> p.points.size == 3 && p.points.all { (it - corner).length() < 12f } })
        assertEquals(1, opens.count { it.points.size > 3 })
        // Nothing else: triangle, block, right-angle mark, arc, four arrows, labels, the centre dot.
        assertEquals(0, d.shapes.count { it is VectorShape.Line })
    }

    @Test fun `bad parameters are clamped or refused, never crash`() {
        assertNull(FigureLibrary.render(FigSpec("teleporter", listOf(r("a", 1)))))
        assertNull(FigureLibrary.render(FigSpec("circuit", listOf(r("zz", 1)))))
        assertNotNull(FigureLibrary.render(FigSpec("incline", listOf(r("a", 900), r("f", "N", "sideways")))))
        assertNotNull(FigureLibrary.render(FigSpec("plot", listOf(r("f", "1/x"), r("x", 5, 5)))))
        assertNotNull(FigureLibrary.render(FigSpec("Inclined Plane", listOf(r("a", "37°")))))
        assertEquals(37f, FigSpec("incline", listOf(r("a", "37°"))).params.num("a", default = 0f), 1e-4f)
    }

    @Test fun `a netlist becomes a series-parallel schematic with junction dots`() {
        val d = FigureLibrary.render(FigSpec("circuit", listOf(r("bat", "E", "a", "b"), r("R", "R_1", "a", "c"), r("R", "R_2", "c", "b"), r("bulb", "c", "b"))))!!.diagram
        assertEquals(2, d.shapes.count { it is VectorShape.Polyline && it.points.size == 10 }) // two zigzags
        assertTrue(d.shapes.filterIsInstance<VectorShape.Label>().map { it.text }.containsAll(listOf("E", "R_1", "R_2")))
    }

    @Test fun `a thin lens forms its image where the lens equation puts it`() {
        val d = FigureLibrary.render(FigSpec("lens", listOf(r("type", "convex"), r("f", 1), r("obj", 3, 1))))!!.diagram
        val image = d.shapes.filterIsInstance<VectorShape.Arrow>().single { it.style.colorArgb == app.bestnote.engine.layout.NotebookPalette.ESSENTIAL }
        val f = d.shapes.filterIsInstance<VectorShape.Ellipse>().filter { it.bounds.width < 4f }.minOf { abs(it.bounds.center.x) }
        assertEquals(1.5f, image.from.x / f, 0.01f) // 1/3 + 1/1.5 = 1
    }
}
