package app.bestnote.engine.layout

import app.bestnote.core.model.RectPt
import app.bestnote.core.model.Vec2
import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

class RulerGeometryTest {
    private val box = RectPt(0f, 0f, 200f, 200f)
    private fun straighten(vararg shapes: VectorShape) = RulerGeometry().straighten(VectorDiagram(box, shapes.toList())).shapes

    @Test fun `wobbly axes become exact and meet at the origin`() {
        val (x, y) = straighten(
            VectorShape.Arrow(Vec2(20f, 181f), Vec2(190f, 176f)), // x-axis, ~1.7° off
            VectorShape.Arrow(Vec2(23f, 183f), Vec2(26f, 10f)), // y-axis, ~1° off, start 3pt away
        ).map { it as VectorShape.Arrow }
        assertEquals(x.from.y, x.to.y)
        assertEquals(y.from.x, y.to.x)
        assertEquals(x.from, y.from)
    }

    @Test fun `a hand-drawn triangle closes and keeps its corners shared`() {
        val t = straighten(
            VectorShape.Polyline(listOf(Vec2(10f, 150f), Vec2(150f, 152f), Vec2(148f, 20f), Vec2(13f, 148f))),
        ).single() as VectorShape.Polyline
        assertTrue(t.closed)
        assertEquals(3, t.points.size)
        assertEquals(t.points[0].y, t.points[1].y) // base is horizontal
        assertEquals(t.points[1].x, t.points[2].x) // right side is vertical: a right angle
    }

    @Test fun `near-diagonal lines snap to 45 degrees`() {
        val l = straighten(VectorShape.Line(Vec2(0f, 0f), Vec2(100f, 92f))).single() as VectorShape.Line
        val deg = Math.toDegrees(atan2((l.to.y - l.from.y).toDouble(), (l.to.x - l.from.x).toDouble()))
        assertTrue("was $deg", abs(deg - 45.0) < 0.5)
    }

    @Test fun `near circles become circles and small rotations vanish`() {
        val (e, r) = straighten(
            VectorShape.Ellipse(RectPt(50f, 50f, 103f, 98f)),
            VectorShape.Rect(RectPt(10f, 10f, 40f, 30f), rotationDeg = 3f),
        )
        e as VectorShape.Ellipse; r as VectorShape.Rect
        assertEquals(e.bounds.width, e.bounds.height, 0.5f)
        assertEquals(0f, r.rotationDeg)
    }

    @Test fun `a loose arrow end lands on the box it points at`() {
        val shapes = straighten(
            VectorShape.Rect(RectPt(120f, 80f, 180f, 120f)),
            VectorShape.Arrow(Vec2(20f, 101f), Vec2(115f, 99f)), // stops 5pt short of the box
        )
        val arrow = shapes[1] as VectorShape.Arrow
        assertEquals(120f, arrow.to.x)
        assertEquals(arrow.from.y, arrow.to.y)
    }

    @Test fun `deliberate angles are left alone`() {
        val l = straighten(VectorShape.Line(Vec2(0f, 0f), Vec2(100f, 37f))).single() as VectorShape.Line
        assertEquals(Vec2(100f, 37f), l.to)
    }
}
