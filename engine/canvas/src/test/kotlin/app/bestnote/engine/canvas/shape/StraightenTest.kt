package app.bestnote.engine.canvas.shape

import app.bestnote.engine.recognition.RecognizedShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class StraightenTest {
    /** A line from (x0, y0) to (x1, y1) with a sideways wobble [amp] pts and a pen held still at the end. */
    private fun wobbly(x0: Float, y0: Float, x1: Float, y1: Float, amp: Float, periods: Float = 3f, hold: Int = 20, seed: Int = 1): Pair<FloatArray, FloatArray> {
        val n = 120
        val r = Random(seed)
        val len = kotlin.math.hypot(x1 - x0, y1 - y0)
        val nx = -(y1 - y0) / len; val ny = (x1 - x0) / len
        val xs = FloatArray(n + hold); val ys = FloatArray(n + hold)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            val off = amp * sin(2 * PI.toFloat() * periods * t) + (r.nextFloat() - 0.5f) * 0.4f
            xs[i] = x0 + (x1 - x0) * t + nx * off
            ys[i] = y0 + (y1 - y0) * t + ny * off
        }
        for (i in n until n + hold) { // held still: a hand trembles a little
            xs[i] = x1 + (r.nextFloat() - 0.5f) * 0.3f
            ys[i] = y1 + (r.nextFloat() - 0.5f) * 0.3f
        }
        return xs to ys
    }

    @Test fun `a wobbly line straightens from its start to where the pen is held`() {
        val (xs, ys) = wobbly(10f, 50f, 210f, 62f, amp = 16f, periods = 2f)
        val line = Straighten.line(xs, ys, xs.size)!!
        assertEquals(10f, line.x0, 0.5f)
        assertEquals(50f, line.y0, 0.5f)
        assertEquals(210f, line.x1, 0.5f)
        assertEquals(62f, line.y1, 0.5f)
    }

    @Test fun `the recognizer misses it but the hold ghost still straightens it`() {
        val (xs, ys) = wobbly(10f, 50f, 210f, 62f, amp = 16f, periods = 2f)
        val g = ShapePreview().begin(xs, ys, xs.size, xs.last(), ys.last(), emptyList(), 6f, 20f)
        assertNotNull(g)
        assertEquals(ShapeLabel.Line, g!!.label)
    }

    @Test fun `words, scribbles, arcs and hooks don't straighten`() {
        // Handwriting-like loops along a line: far more path than chord.
        val n = 300
        val loopsX = FloatArray(n) { 10f + it * 0.6f + 8f * cos(it * 0.35f) }
        val loopsY = FloatArray(n) { 40f + 8f * sin(it * 0.35f) }
        assertNull(Straighten.line(loopsX, loopsY, n))
        // A half circle.
        val arcX = FloatArray(80) { 100f + 50f * cos(PI.toFloat() * it / 79f) }
        val arcY = FloatArray(80) { 100f - 50f * sin(PI.toFloat() * it / 79f) }
        assertNull(Straighten.line(arcX, arcY, 80))
        // A line that doubles back at the end (a hook).
        val hookX = floatArrayOf(0f, 50f, 100f, 140f, 100f, 80f)
        val hookY = floatArrayOf(0f, 0f, 0f, 0f, 2f, 3f)
        assertNull(Straighten.line(hookX, hookY, hookX.size))
        // Too short to matter (a dash of a letter).
        val (sx, sy) = wobbly(0f, 0f, 6f, 0f, amp = 0.5f)
        assertNull(Straighten.line(sx, sy, sx.size))
    }

    @Test fun `deviation and directness are relative, so zoom doesn't change the answer`() {
        val (xs, ys) = wobbly(0f, 0f, 100f, 0f, amp = 12f, periods = 1.5f)
        val small = Straighten.fit(xs, ys, xs.size)!!
        val big = Straighten.fit(FloatArray(xs.size) { xs[it] * 4f }, FloatArray(ys.size) { ys[it] * 4f }, xs.size)!!
        assertEquals(small.deviation, big.deviation, 1e-4f)
        assertEquals(small.directness, big.directness, 1e-4f)
    }

    @Test fun `real shapes win over straightening, a slightly zigzag line doesn't`() {
        val (xs, ys) = wobbly(0f, 0f, 200f, 0f, amp = 4f, periods = 1f)
        val tri = RecognizedShape.Polygon(listOf(0f to 0f, 50f to 50f, 100f to 0f), closed = true)
        assertEquals(tri, Straighten.resolve(tri, xs, ys, xs.size))
        val zig = RecognizedShape.Polygon(listOf(0f to 0f, 100f to 4f, 200f to 0f), closed = false)
        assertTrue(Straighten.resolve(zig, xs, ys, xs.size) is RecognizedShape.Line)
        // A real angle (two sides of a corner) stays an angle.
        val angleX = FloatArray(100) { if (it < 50) it * 2f else 100f }
        val angleY = FloatArray(100) { if (it < 50) 0f else (it - 50) * 2f }
        val angle = RecognizedShape.Polygon(listOf(0f to 0f, 100f to 0f, 100f to 100f), closed = false)
        assertEquals(angle, Straighten.resolve(angle, angleX, angleY, 100))
    }
}
