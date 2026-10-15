package app.bestnote.engine.canvas.select

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI

class SelectionTransformTest {
    private val box = Box(0f, 0f, 100f, 50f)

    @Test fun thenComposesInOrder() {
        val m = Affine.translate(10f, 0f).then(Affine.scaleAbout(2f, 0f, 0f))
        assertEquals(20f, m.mapX(0f, 0f), 1e-4f)
        assertEquals(22f, m.mapX(1f, 0f), 1e-4f)
    }

    @Test fun scaleAndRotateKeepTheirPivot() {
        val s = Affine.scaleAbout(3f, 10f, 20f)
        assertEquals(10f, s.mapX(10f, 20f), 1e-4f)
        assertEquals(20f, s.mapY(10f, 20f), 1e-4f)
        val r = Affine.rotateAbout((PI / 2).toFloat(), 50f, 50f)
        assertEquals(50f, r.mapX(50f, 50f), 1e-3f)
        // (60, 50) turns a quarter clockwise on screen (y down) to (50, 60).
        assertEquals(50f, r.mapX(60f, 50f), 1e-3f)
        assertEquals(60f, r.mapY(60f, 50f), 1e-3f)
        assertEquals(1f, r.lengthScale, 1e-4f)
        assertEquals(3f, s.lengthScale, 1e-4f)
    }

    @Test fun rotationSnapsNearMultiplesOf45() {
        val (a, deg) = RotationSnap.snap(Math.toRadians(43.0).toFloat())
        assertEquals(45, deg)
        assertEquals(Math.toRadians(45.0).toFloat(), a, 1e-5f)
        assertEquals(0, RotationSnap.snap(Math.toRadians(-3.0).toFloat()).second)
        assertEquals(270, RotationSnap.snap(Math.toRadians(-88.0).toFloat()).second)
        val free = RotationSnap.snap(Math.toRadians(30.0).toFloat())
        assertNull(free.second)
        assertEquals(Math.toRadians(30.0).toFloat(), free.first, 1e-5f)
    }

    @Test fun moveFollowsThePen() {
        val g = TransformGesture(Grip.Move, box, 10f, 10f)
        val m = g.update(25f, 4f)
        assertEquals(15f, m.tx, 1e-4f)
        assertEquals(-6f, m.ty, 1e-4f)
    }

    @Test fun cornerResizeIsUniformAboutTheOppositeCorner() {
        val g = TransformGesture(Grip.BottomRight, box, 100f, 50f)
        val m = g.update(200f, 100f)
        assertEquals(2f, m.a, 1e-4f)
        assertEquals(2f, m.d, 1e-4f)
        // Top-left stays put.
        assertEquals(0f, m.mapX(0f, 0f), 1e-4f)
        assertEquals(0f, m.mapY(0f, 0f), 1e-4f)
    }

    @Test fun resizeNeverCollapsesOrFlips() {
        val g = TransformGesture(Grip.TopLeft, box, 0f, 0f, minSize = 10f)
        val m = g.update(500f, 500f) // dragged past the pivot
        assertEquals(0.1f, m.a, 1e-4f)
    }

    @Test fun rotateSnapsToNinetyAndReportsIt() {
        // Knob above the center (50, 25); dragging it to the right of the center is a quarter turn.
        val g = TransformGesture(Grip.Rotate, box, 50f, -30f)
        g.update(105f, 27f) // ~88°
        assertEquals(90, g.snappedDeg)
        assertEquals((PI / 2).toFloat(), g.angle, 1e-4f)
        g.update(90f, -10f) // ~45° + a bit
        assertEquals(45, g.snappedDeg)
        g.update(80f, -30f) // ~31°, free
        assertNull(g.snappedDeg)
    }
}
