package app.bestnote.engine.canvas.select

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class LassoPolygonTest {
    /** A 100 x 100 square lasso from (0, 0). */
    private val square = LassoPolygon(floatArrayOf(0f, 100f, 100f, 0f), floatArrayOf(0f, 0f, 100f, 100f))

    /** A horizontal stroke at y = 50 from [x0] to [x1], a sample every 2 pt. */
    private fun line(x0: Float, x1: Float): Pair<FloatArray, FloatArray> {
        val n = ((x1 - x0) / 2).toInt() + 1
        return FloatArray(n) { x0 + it * 2f } to FloatArray(n) { 50f }
    }

    @Test fun containsUsesEvenOdd() {
        assertTrue(square.contains(50f, 50f))
        assertFalse(square.contains(150f, 50f))
        assertFalse(square.contains(50f, -1f))
    }

    @Test fun fullyEnclosedStrokeIsSelected() {
        val (xs, ys) = line(10f, 90f)
        assertEquals(1f, square.fractionInside(xs, ys), 1e-4f)
        assertTrue(square.selects(xs, ys))
    }

    @Test fun moreThanHalfInsideIsSelected() {
        val (xs, ys) = line(40f, 140f) // 60 of 100 pt inside
        assertEquals(0.6f, square.fractionInside(xs, ys), 0.03f)
        assertTrue(square.selects(xs, ys))
    }

    @Test fun lessThanHalfInsideIsNotSelected() {
        val (xs, ys) = line(60f, 160f) // 40 of 100 pt inside
        assertEquals(0.4f, square.fractionInside(xs, ys), 0.03f)
        assertFalse(square.selects(xs, ys))
    }

    @Test fun exactlyHalfIsNotSelected() {
        val (xs, ys) = line(50f, 150f)
        assertFalse(square.selects(xs, ys))
    }

    @Test fun twoPointStraightLineIsWeighedAlongItsLength() {
        val xs = floatArrayOf(30f, 130f)
        val ys = floatArrayOf(50f, 50f)
        assertEquals(0.7f, square.fractionInside(xs, ys), 0.05f)
    }

    @Test fun dotIsInOrOut() {
        assertTrue(square.selects(floatArrayOf(5f), floatArrayOf(5f)))
        assertFalse(square.selects(floatArrayOf(-5f), floatArrayOf(5f)))
    }

    @Test fun concaveFreehandLassoExcludesTheNotch() {
        // A "U": the notch between x 40..60 from y 0..70 is outside.
        val u = LassoPolygon(
            floatArrayOf(0f, 40f, 40f, 60f, 60f, 100f, 100f, 0f),
            floatArrayOf(0f, 0f, 70f, 70f, 0f, 0f, 100f, 100f),
        )
        assertFalse(u.contains(50f, 30f))
        assertTrue(u.contains(20f, 30f))
        assertTrue(u.contains(50f, 90f))
    }

    @Test fun manyVertexCircleMatchesGeometry() {
        val n = 720
        val xs = FloatArray(n) { 100f + 80f * cos(it * 2 * Math.PI / n).toFloat() }
        val ys = FloatArray(n) { 100f + 80f * sin(it * 2 * Math.PI / n).toFloat() }
        val circle = LassoPolygon(xs, ys)
        assertTrue(circle.contains(100f, 100f))
        assertTrue(circle.contains(170f, 100f))
        assertFalse(circle.contains(170f, 170f))
    }
}
