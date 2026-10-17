package app.bestnote.engine.canvas.shape

import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.ShapeInfo
import app.bestnote.core.model.ShapeLink
import app.bestnote.core.model.Tool
import app.bestnote.engine.canvas.select.Affine
import app.bestnote.engine.recognition.RecognizedShape
import app.bestnote.engine.recognition.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** CANVAS-17: shape glue points, snapping shapes together, and connectors that stay attached. */
class ShapeGlueTest {
    private fun box(id: String, x: Float, y: Float, w: Float, h: Float) = ShapeGeometry.infoOf(
        RecognizedShape.Polygon(listOf(x to y, x + w to y, x + w to y + h, x to y + h), closed = true, kind = ShapeKind.Rectangle), id,
    )

    private fun assertPoint(ex: Float, ey: Float, p: Pair<Float, Float>, tol: Float = 1e-3f) {
        assertEquals(ex, p.first, tol)
        assertEquals(ey, p.second, tol)
    }

    @Test
    fun `a rectangle glues at its corners, edge middles and center`() {
        val b = box("b", 0f, 0f, 40f, 20f)
        val a = ShapeGeometry.anchors(b)
        assertEquals(4, a.count { it.type == AnchorType.Corner })
        assertEquals(4, a.count { it.type == AnchorType.Midpoint })
        assertEquals(1, a.count { it.type == AnchorType.Center })
        assertPoint(40f, 10f, ShapeGeometry.pointAt(b, 1.5f))
        assertPoint(20f, 10f, ShapeGeometry.pointAt(b, -1f))
        // Edge places wrap round a closed outline.
        assertPoint(0f, 10f, ShapeGeometry.pointAt(b, 3.5f))
        assertPoint(0f, 0f, ShapeGeometry.pointAt(b, 4f))
    }

    @Test
    fun `places on a shape survive moving, resizing and turning it`() {
        val shapes = listOf(
            box("b", 10f, 10f, 40f, 20f),
            ShapeGeometry.infoOf(RecognizedShape.Ellipse(30f, 40f, 20f, 10f, 0.3f), "e"),
            ShapeGeometry.infoOf(RecognizedShape.Line(0f, 0f, 50f, 10f), "l"),
        )
        val m = Affine.rotateAbout(0.7f, 5f, 5f).then(Affine.scaleAbout(1.8f, 0f, 0f)).then(Affine.translate(13f, -4f))
        for (s in shapes) {
            val t = ShapeGeometry.transformed(s, m)
            for (at in listOf(-1f, 0f, 0.25f, 0.5f, 1f, 1.5f, 2.75f, 3.2f)) {
                val (x, y) = ShapeGeometry.pointAt(s, at)
                assertPoint(m.mapX(x, y), m.mapY(x, y), ShapeGeometry.pointAt(t, at), 1e-2f)
            }
        }
    }

    @Test
    fun `a line end glues to the nearest glue point, else to an edge, else nowhere`() {
        val b = box("b", 0f, 0f, 40f, 20f)
        val corner = ShapeGlue.snapEnd(41f, -1.5f, listOf(b), 6f)!!
        assertEquals(ShapeLink("b", 1f), corner.link)
        assertEquals(AnchorType.Corner, corner.type)
        val mid = ShapeGlue.snapEnd(22f, 21f, listOf(b), 6f)!!
        assertEquals(2.5f, mid.link.at, 1e-4f)
        // Between glue points: onto the edge (a little less eagerly).
        val edge = ShapeGlue.snapEnd(30f, 22f, listOf(b), 6f)!!
        assertNull(edge.type)
        assertPoint(30f, 20f, edge.x to edge.y)
        assertEquals(2.25f, edge.link.at, 1e-4f)
        assertNull("too far from the edge for it", ShapeGlue.snapEnd(30f, 24f, listOf(b), 6f))
        assertNull(ShapeGlue.snapEnd(80f, 80f, listOf(b), 6f))
    }

    @Test
    fun `a shape moved next to another tiles edge to edge`() {
        val a = box("a", 0f, 0f, 40f, 20f)
        // Corner on corner: b's top-left lands just off a's top-right.
        val b = box("b", 41.5f, 0.8f, 30f, 20f)
        val (anchors, bounds) = ShapeGlue.movingOf(listOf(b))!!
        val move = ShapeGlue.snapMove(anchors, bounds, listOf(a), 4f)
        assertEquals(-1.5f, move.dx, 1e-4f)
        assertEquals(-0.8f, move.dy, 1e-4f)
        assertEquals(1, move.markers.size)
        // No glue points close, but the edges line up: snaps on that axis only, with a guide.
        val c = box("c", 42f, 30f, 10f, 10f)
        val (ca, cb) = ShapeGlue.movingOf(listOf(c))!!
        val align = ShapeGlue.snapMove(ca, cb, listOf(a), 4f)
        assertEquals(-2f, align.dx, 1e-4f)
        assertEquals(0f, align.dy, 1e-4f)
        assertEquals(40f, align.guideX!!, 1e-4f)
        assertTrue("nothing near", !ShapeGlue.snapMove(ca, cb, listOf(box("far", 0f, 200f, 5f, 5f)), 4f).snapped)
    }

    @Test
    fun `a connector follows the shapes it is glued to through move and resize`() {
        val left = box("left", 0f, 0f, 40f, 20f)
        val right = box("right", 100f, 0f, 30f, 30f)
        // From the middle of left's right edge to right's top-left corner.
        val arrow = ShapeGeometry.infoOf(
            RecognizedShape.Arrow(40f, 10f, 100f, 0f, listOf(95f to -3f, 95f to 3f)), "a",
            start = ShapeLink("left", 1.5f), end = ShapeLink("right", 0f),
        )
        val moved = ShapeGeometry.transformed(right, Affine.translate(20f, 50f))
        val follow = ShapeGeometry.follow(listOf(arrow), mapOf("right" to moved))
        val (shape, info) = follow.getValue("a")
        shape as RecognizedShape.Arrow
        assertPoint(40f, 10f, shape.x0 to shape.y0)
        assertPoint(120f, 50f, shape.x1 to shape.y1)
        assertEquals(arrow.start, info.start)
        assertEquals(arrow.end, info.end)
        // The head turned with the shaft and kept its size.
        val oldLen = hypot(95f - 100f, -3f - 0f)
        shape.barbs.forEach { (bx, by) -> assertEquals(oldLen, hypot(bx - shape.x1, by - shape.y1), 1e-3f) }
        // Resizing left (twice as big about its top-left) moves the start to the new edge middle.
        val bigger = ShapeGeometry.transformed(left, Affine.scaleAbout(2f, 0f, 0f))
        val (s2, _) = ShapeGeometry.follow(listOf(info), mapOf("left" to bigger)).getValue("a")
        s2 as RecognizedShape.Arrow
        assertPoint(80f, 20f, s2.x0 to s2.y0)
        assertPoint(120f, 50f, s2.x1 to s2.y1)
        // Connectors not glued to what moved, and ones that moved themselves, are left alone.
        assertTrue(ShapeGeometry.follow(listOf(arrow), mapOf("other" to moved)).isEmpty())
        assertTrue(ShapeGeometry.follow(listOf(arrow), mapOf("a" to arrow, "right" to moved)).isEmpty())
    }

    @Test
    fun `a moved line keeps ends glued to shapes moving with it and re-glues the others where they land`() {
        val target = box("t", 0f, 0f, 40f, 20f)
        val line = ShapeGeometry.infoOf(RecognizedShape.Line(100f, 100f, 140f, 100f), "l", start = ShapeLink("gone", 0f), end = null)
        // Moved so its start lands on the target's bottom-right corner.
        val m = Affine.translate(-60f, -80f)
        val moved = ShapeEdits.movedInfo(line, m, movedIds = setOf("l"), others = listOf(target), tolerance = 2f)
        assertEquals(ShapeLink("t", 2f), moved.start)
        assertNull(moved.end)
        // Moving together with the shape it is glued to keeps the link.
        val glued = line.copy(start = ShapeLink("t", 2f))
        assertEquals(ShapeLink("t", 2f), ShapeEdits.movedInfo(glued, Affine.translate(500f, 0f), setOf("l", "t"), emptyList(), 2f).start)
    }

    @Test
    fun `copies get new ids and keep links only between copies`() {
        val brush = InkBrush(Tool.Pen, size = 2f)
        val b = brush.copy(shape = box("b", 0f, 0f, 10f, 10f))
        val l = brush.copy(shape = ShapeGeometry.infoOf(RecognizedShape.Line(10f, 5f, 30f, 5f), "l", start = ShapeLink("b", 1.5f), end = ShapeLink("x", 0f)))
        val renamed = ShapeEdits.renamer(listOf("b", "l"))
        val cb = ShapeEdits.copied(b, renamed, Affine.translate(5f, 5f)).shape!!
        val cl = ShapeEdits.copied(l, renamed, Affine.translate(5f, 5f)).shape!!
        assertNotEquals("b", cb.id)
        assertEquals(cb.id, cl.start!!.targetId)
        assertNull(cl.end)
        assertEquals(15f, cl.points[0], 1e-4f)
    }

    @Test
    fun `the ghost previews, follows the pen, snaps and cancels`() {
        val preview = ShapePreview()
        // A hand-drawn circle, radius 40 round (100, 100), with a little wobble.
        val n = 200
        val xs = FloatArray(n) { 100f + (40f + 0.8f * sin(it * 0.7f)) * cos(2 * PI.toFloat() * it / (n - 1) - 1.4f) }
        val ys = FloatArray(n) { 100f + (40f + 0.8f * sin(it * 0.7f)) * sin(2 * PI.toFloat() * it / (n - 1) - 1.4f) }
        val g = preview.begin(xs, ys, n, xs.last(), ys.last(), emptyList(), 6f, 20f)!!
        assertEquals(ShapeLabel.Circle, g.label)
        assertTrue(ShapeHint.PerfectCircle in g.hints)
        val r0 = (g.shape as RecognizedShape.Ellipse).rx
        // Pen moves out a little while held: the circle grows about its center.
        val cx = (g.shape as RecognizedShape.Ellipse).cx
        val cy = (g.shape as RecognizedShape.Ellipse).cy
        val dx = xs.last() - cx; val dy = ys.last() - cy
        val g2 = preview.move(cx + dx * 1.2f, cy + dy * 1.2f, 100)!!
        assertEquals(r0 * 1.2f, (g2.shape as RecognizedShape.Ellipse).rx, r0 * 0.02f)
        // Far away: cancelled, the stroke stays free ink.
        assertNull(preview.move(xs.last() + 200f, ys.last(), 200))
        assertTrue(preview.cancelled)
        assertNull("no new ghost for this stroke", preview.begin(xs, ys, n, xs.last(), ys.last(), emptyList(), 6f, 20f))

        // A line whose end lands near a box's corner: glued, and it starts exactly level.
        preview.reset()
        val lx = FloatArray(60) { 10f + it * 1.5f }
        val ly = FloatArray(60) { 50f + 0.6f * sin(it * 0.3f) + it * 0.02f }
        val target = box("t", lx.last() + 2f, 48f, 30f, 30f)
        val lg = preview.begin(lx, ly, 60, lx.last(), ly.last(), listOf(target), 6f, 20f)!!
        assertEquals(ShapeLabel.Line, lg.label)
        assertNotNull(lg.end)
        assertEquals("t", lg.end!!.targetId)
        assertTrue(ShapeHint.Glued in lg.hints)
        val line = lg.shape as RecognizedShape.Line
        assertPoint(target.points[0], target.points[1], line.x1 to line.y1)

        // Shaking the pen cancels too.
        preview.reset()
        preview.begin(xs, ys, n, xs.last(), ys.last(), emptyList(), 6f, 30f)!!
        var t = 0L
        var x = xs.last()
        var last: ShapePreview.Ghost? = preview.ghost
        repeat(8) { i ->
            x += if (i % 2 == 0) 8f else -8f
            t += 60
            last = preview.move(x, ys.last(), t)
        }
        assertNull(last)
        assertTrue(preview.cancelled)
    }

    @Test
    fun `a near-square rectangle becomes an exact square`() {
        val p = RecognizedShape.Polygon(listOf(0f to 0f, 50f to 0f, 50f to 47f, 0f to 47f), closed = true, kind = ShapeKind.Rectangle)
        val sq = ShapePreview.squared(p)!!
        val w = hypot(sq.points[1].first - sq.points[0].first, sq.points[1].second - sq.points[0].second)
        val h = hypot(sq.points[2].first - sq.points[1].first, sq.points[2].second - sq.points[1].second)
        assertEquals(w, h, 1e-3f)
        assertEquals(ShapeLabel.Square, ShapePreview.labelOf(sq))
        assertNull(ShapePreview.squared(p.copy(points = listOf(0f to 0f, 50f to 0f, 50f to 30f, 0f to 30f))))
    }

    @Test
    fun `stored records round-trip the recognized shapes connectors need`() {
        val arrow = RecognizedShape.Arrow(1f, 2f, 30f, 40f, listOf(25f to 33f, 22f to 38f))
        val info: ShapeInfo = ShapeGeometry.infoOf(arrow, "a")
        assertEquals(arrow, ShapeGeometry.connectorShape(info))
        assertNull(ShapeGeometry.connectorShape(box("b", 0f, 0f, 1f, 1f)))
    }
}
