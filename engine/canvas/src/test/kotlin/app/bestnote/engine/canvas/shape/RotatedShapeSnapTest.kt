package app.bestnote.engine.canvas.shape

import app.bestnote.engine.ink.InkFamily
import app.bestnote.engine.ink.WidthModel
import app.bestnote.engine.recognition.GeometricShapeRecognizer
import app.bestnote.engine.recognition.RecognizedShape
import app.bestnote.engine.recognition.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** REQ-007: a square drawn on a triangle's slope lies flush on it at its angle, and closed shapes are one thickness all round. */
class RotatedShapeSnapTest {
    private val slopeDeg = 37.0
    private val slope = Math.toRadians(-slopeDeg) // y-down: rising to the right
    private val ux = cos(slope).toFloat()
    private val uy = sin(slope).toFloat()

    // An incline: right triangle with its hypotenuse (0, 300) -> (400, 300 - 400·tan 37°).
    private val top = 300f - 400f * kotlin.math.tan(Math.toRadians(slopeDeg)).toFloat()
    private val triangle = ShapeGeometry.infoOf(
        RecognizedShape.Polygon(listOf(0f to 300f, 400f to top, 400f to 300f), closed = true, kind = ShapeKind.Triangle), "tri",
    )

    /** Distance of ([x], [y]) from the hypotenuse's line, positive above it (outside the triangle). */
    private fun aboveSlope(x: Float, y: Float) = -((x - 0f) * -uy + (y - 300f) * ux)

    /** A hand-drawn square of [side] standing [gap] above the slope at [along] on it, turned [deg] (y-down, negative = rising). */
    private fun handSquare(along: Float, gap: Float, side: Float, deg: Double, seed: Int): Pair<FloatArray, FloatArray> {
        val a = Math.toRadians(deg)
        val cx = along * ux + uy * (side / 2 + gap)
        val cy = 300f + along * uy - ux * (side / 2 + gap)
        val rng = Random(seed)
        val h = side / 2
        val corners = listOf(-h to -h, h to -h, h to h, -h to h)
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        var drift = 0f
        for (k in 0 until 4) {
            val (x0, y0) = corners[k]; val (x1, y1) = corners[(k + 1) % 4]
            val nx = -(y1 - y0) / side; val ny = (x1 - x0) / side
            val end = if (k == 3) 1.05f else 1f
            for (i in 0 until 50) {
                val u = i / 50f * end
                drift = drift * 0.85f + (rng.nextFloat() - 0.5f) * 1.2f
                val lx = x0 + (x1 - x0) * u + nx * drift
                val ly = y0 + (y1 - y0) * u + ny * drift
                xs += cx + (lx * cos(a) - ly * sin(a)).toFloat()
                ys += cy + (lx * sin(a) + ly * cos(a)).toFloat()
            }
        }
        return xs.toFloatArray() to ys.toFloatArray()
    }

    @Test
    fun `a square drawn on a slope comes out a square lying flush on it at the slope's angle`() {
        val recognizer = GeometricShapeRecognizer()
        for ((deg, seed) in listOf(-33.0 to 1, -37.0 to 2, -38.0 to 3, -30.0 to 4)) {
            val (xs, ys) = handSquare(along = 220f, gap = 3f, side = 80f, deg = deg, seed = seed)
            val match = recognizer.match(xs, ys)
            assertNotNull("drawn at $deg°", match)
            val g = ShapePreview().settle(match!!.shape, listOf(triangle), tolerance = 8f)
            assertEquals("drawn at $deg°", ShapeLabel.Square, g.label)
            assertTrue(ShapeHint.Glued in g.hints)
            val p = (g.shape as RecognizedShape.Polygon).points
            // Two corners on the slope's line (one shared line, nothing beside it), the other two above it.
            val d = p.map { (x, y) -> aboveSlope(x, y) }.sorted()
            assertEquals("drawn at $deg°: $d", 0f, d[0], 0.05f)
            assertEquals("drawn at $deg°: $d", 0f, d[1], 0.05f)
            assertTrue("drawn at $deg°: $d", d[2] > 70f)
            // Sides at the slope's angle (mod 90°).
            val a = Math.toDegrees(atan2((p[1].second - p[0].second).toDouble(), (p[1].first - p[0].first).toDouble()))
            assertEquals("drawn at $deg°", 0.0, Math.IEEEremainder(a + slopeDeg, 90.0), 0.05)
        }
    }

    @Test
    fun `a square near the slope's foot slides so its corner meets the corner`() {
        val sq = squareOnSlope(along = 4f, gap = 2f, side = 60f, turnDeg = -33.0)
        val g = ShapePreview().settle(sq, listOf(triangle), tolerance = 8f)
        val p = (g.shape as RecognizedShape.Polygon).points
        assertTrue("$p", p.any { (x, y) -> hypot(x - 0f, y - 300f) < 0.05f })
    }

    @Test
    fun `an upright box next to another still tiles as before`() {
        val a = ShapeGeometry.infoOf(RecognizedShape.Polygon(listOf(0f to 0f, 40f to 0f, 40f to 20f, 0f to 20f), true, ShapeKind.Rectangle), "a")
        val b = RecognizedShape.Polygon(listOf(41.5f to 0.8f, 71.5f to 0.8f, 71.5f to 20.8f, 41.5f to 20.8f), true, ShapeKind.Rectangle)
        val p = (ShapePreview().settle(b, listOf(a), 4f).shape as RecognizedShape.Polygon).points
        assertEquals(40f, p[0].first, 1e-3f)
        assertEquals(0f, p[0].second, 1e-3f)
    }

    /** An exact square with its bottom-left corner [along] up the slope, [gap] above it, turned [turnDeg]. */
    private fun squareOnSlope(along: Float, gap: Float, side: Float, turnDeg: Double): RecognizedShape.Polygon {
        val a = Math.toRadians(turnDeg)
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        val ox = along * ux + uy * gap
        val oy = 300f + along * uy - ux * gap
        val local = listOf(0f to 0f, side to 0f, side to -side, 0f to -side)
        return RecognizedShape.Polygon(local.map { (x, y) -> ox + x * c - y * s to oy + x * s + y * c }, true, ShapeKind.Rectangle)
    }

    @Test
    fun `a closed shape drawn with a tapered pen is full width at its seam`() {
        val size = 4f
        val sq = RecognizedShape.Polygon(listOf(0f to 0f, 80f to 0f, 80f to 80f, 0f to 80f), closed = true, kind = ShapeKind.Rectangle)
        for (family in listOf(InkFamily.Fountain, InkFamily.BrushPen)) {
            val o = ShapeOutline.of(sq, 40f, 0f, spacing = 1f, closeOverlap = ShapeOutline.closingOverlap(family, size))
            val total = o.length()
            val perimeter = 320f
            // Width at each place round the square: the widest pass over it (the stroke covers the seam twice).
            val body = WidthModel.multiplier(family, 0.6f, 100f, total / 2 / size, total / 2 / size)
            var s = 0f
            while (s < perimeter) {
                var w = WidthModel.multiplier(family, 0.6f, 100f, s / size, (total - s) / size)
                val again = s + perimeter
                if (again <= total) w = maxOf(w, WidthModel.multiplier(family, 0.6f, 100f, again / size, (total - again) / size))
                assertEquals("$family at $s of $perimeter", body, w, body * 0.02f)
                s += 0.5f
            }
        }
    }

    @Test
    fun `pens without tapers don't overlap, open shapes never do`() {
        assertEquals(0f, ShapeOutline.closingOverlap(InkFamily.Ballpoint, 4f))
        assertEquals(0f, ShapeOutline.closingOverlap(InkFamily.Highlighter, 4f))
        assertEquals(0f, ShapeOutline.closingOverlap(null, 4f))
        val line = ShapeOutline.of(RecognizedShape.Line(0f, 0f, 100f, 0f), 0f, 0f, closeOverlap = 20f)
        assertEquals(100f, line.length(), 1e-3f)
        val sq = RecognizedShape.Polygon(listOf(0f to 0f, 80f to 0f, 80f to 80f, 0f to 80f), closed = true, kind = ShapeKind.Rectangle)
        assertEquals(320f + 20f, ShapeOutline.of(sq, 40f, 0f, closeOverlap = 20f).length(), 1e-2f)
        assertTrue(abs(ShapeOutline.of(sq, 40f, 0f).length() - 320f) < 1e-2f)
    }
}
