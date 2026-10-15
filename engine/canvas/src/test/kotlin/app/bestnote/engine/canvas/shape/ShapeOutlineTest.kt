package app.bestnote.engine.canvas.shape

import app.bestnote.engine.recognition.RecognizedShape
import app.bestnote.engine.recognition.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

class ShapeOutlineTest {
    @Test
    fun `a rectangle outline starts on its edge where the pen came down, keeps direction and closes`() {
        val rect = RecognizedShape.Polygon(listOf(0f to 0f, 100f to 0f, 100f to 50f, 0f to 50f), closed = true, kind = ShapeKind.Rectangle)
        val o = ShapeOutline.of(rect, startX = 98f, startY = 2f)
        assertEquals(98f, o.xs[0], 1e-3f)
        assertEquals(0f, o.ys[0], 1e-3f)
        // Clockwise as given: next corner is (100, 50).
        assertTrue((0 until o.size).first { o.ys[it] > 49.9f }.let { o.xs[it] } > 99.9f)
        assertEquals(o.xs[0], o.xs.last(), 1e-3f)
        assertEquals(o.ys[0], o.ys.last(), 1e-3f)
        assertEquals(300f, o.length(), 0.01f)
        for (i in 1 until o.size) assertTrue(hypot(o.xs[i] - o.xs[i - 1], o.ys[i] - o.ys[i - 1]) <= ShapeOutline.DEFAULT_SPACING + 1e-3f)
    }

    @Test
    fun `a polygon started mid-edge starts on that edge`() {
        val tri = RecognizedShape.Polygon(listOf(0f to 0f, 100f to 0f, 50f to 80f), closed = true)
        val o = ShapeOutline.of(tri, startX = 40f, startY = 3f)
        assertEquals(40f, o.xs[0], 1e-3f)
        assertEquals(0f, o.ys[0], 1e-3f)
    }

    @Test
    fun `an ellipse outline starts at its start angle and goes the drawn way round`() {
        val e = RecognizedShape.Ellipse(50f, 50f, 40f, 20f, 0f, startAngleRad = (-PI / 2).toFloat(), clockwise = false)
        val o = ShapeOutline.of(e, 50f, 30f)
        assertEquals(50f, o.xs[0], 1e-3f)
        assertEquals(30f, o.ys[0], 1e-3f)
        // Counter-clockwise on a y-down screen from the top: heads left first.
        assertTrue(o.xs[3] < o.xs[0])
        assertEquals(o.xs[0], o.xs.last(), 1e-3f)
        for (i in 0 until o.size) {
            val u = (o.xs[i] - 50f) / 40f; val v = (o.ys[i] - 50f) / 20f
            assertEquals(1f, u * u + v * v, 1e-3f)
        }
    }

    @Test
    fun `an arrow is drawn shaft, barb, back to the tip, other barb`() {
        val a = RecognizedShape.Arrow(0f, 0f, 100f, 0f, listOf(90f to -6f, 90f to 6f))
        val o = ShapeOutline.of(a, 0f, 0f)
        assertEquals(0f, o.xs[0])
        assertEquals(90f, o.xs.last(), 1e-3f)
        assertEquals(6f, o.ys.last(), 1e-3f)
        assertTrue((0 until o.size).count { o.xs[it] == 100f && o.ys[it] == 0f } == 2)
    }

    @Test
    fun `morph frames blend point by point`() {
        val a = Polyline(floatArrayOf(0f, 10f, 20f), floatArrayOf(0f, 5f, 0f)).resample(5)
        val b = Polyline(floatArrayOf(0f, 20f), floatArrayOf(0f, 0f)).resample(5)
        val mid = Polyline.lerp(a, b, 0.5f)
        assertEquals(5, mid.size)
        assertEquals(1.25f, mid.ys[1], 1e-3f)
        assertEquals(b.xs.toList(), Polyline.lerp(a, b, 1f).xs.toList())
    }
}
