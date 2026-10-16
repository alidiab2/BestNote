package app.bestnote.engine.canvas.select

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FillGeometryTest {
    private fun circle(turns: Float, n: Int = 60, r: Float = 50f): Pair<FloatArray, FloatArray> {
        val xs = FloatArray(n) { 100f + r * cos(turns * 2f * Math.PI.toFloat() * it / (n - 1)) }
        val ys = FloatArray(n) { 100f + r * sin(turns * 2f * Math.PI.toFloat() * it / (n - 1)) }
        return xs to ys
    }

    @Test fun fullCircleAndSlightlyOpenLoopAreClosed() {
        assertTrue(circle(1f).let { FillGeometry.isClosed(it.first, it.second, 4f) })
        assertTrue(circle(0.95f).let { FillGeometry.isClosed(it.first, it.second, 4f) })
    }

    @Test fun arcAndLineAreNotClosed() {
        assertFalse(circle(0.6f).let { FillGeometry.isClosed(it.first, it.second, 4f) })
        val xs = FloatArray(20) { it * 10f }
        val ys = FloatArray(20) { 5f }
        assertFalse(FillGeometry.isClosed(xs, ys, 4f))
    }

    @Test fun tinyDotsAndFlatOutAndBackAreNotClosed() {
        assertFalse(FillGeometry.isClosed(floatArrayOf(0f, 1f, 0f, 1f, 0f), floatArrayOf(0f, 0f, 0f, 0f, 0f), 4f))
        assertFalse(FillGeometry.isClosed(floatArrayOf(1f), floatArrayOf(1f), 4f))
    }

    @Test fun rectangleFromAShapeStrokeIsClosed() {
        val xs = floatArrayOf(0f, 100f, 100f, 0f, 0f)
        val ys = floatArrayOf(0f, 0f, 60f, 60f, 0f)
        assertTrue(FillGeometry.isClosed(xs, ys, 4f))
    }
}
