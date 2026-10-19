package app.bestnote.engine.recognition

import app.bestnote.core.model.StylusSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** REQ-007: a square drawn turned (a block on an incline) is recognized as a square at the angle it was drawn. */
class RotatedShapeTest {
    private val recognizer = GeometricShapeRecognizer()

    @Test
    fun `a hand-drawn square turned 20, 37 or 45 degrees is a square at that angle`() {
        for (deg in listOf(20.0, 37.0, 45.0, -30.0)) for (seed in 1..6) {
            val m = recognizer.match(handSquare(300f, 300f, 120f, deg, seed))
            assertNotNull("$deg° seed $seed: not recognized", m)
            val p = m!!.shape as? RecognizedShape.Polygon
            // Standing on its corner (45°) it may be called a diamond, but it must still be the square drawn.
            val kinds = if (deg == 45.0) setOf(ShapeKind.Rectangle, ShapeKind.Diamond) else setOf(ShapeKind.Rectangle)
            assertTrue("$deg° seed $seed: kind ${m.kind}", m.kind in kinds)
            p!!
            assertEquals(4, p.points.size)
            val sides = (0 until 4).map { k ->
                val (ax, ay) = p.points[k]; val (bx, by) = p.points[(k + 1) % 4]
                hypot(bx - ax, by - ay)
            }
            assertEquals("$deg° seed $seed: equal sides $sides", 0f, (sides.max() - sides.min()) / sides.max(), 0.1f)
            assertEquals("$deg° seed $seed: angle", 0.0, angleOffMod90(p, deg), 2.5)
        }
    }

    /** Difference between the polygon's side direction and [deg], modulo 90°, in degrees. */
    private fun angleOffMod90(p: RecognizedShape.Polygon, deg: Double): Double {
        val (ax, ay) = p.points[0]; val (bx, by) = p.points[1]
        val a = Math.toDegrees(atan2((by - ay).toDouble(), (bx - ax).toDouble()))
        return Math.IEEEremainder(a - deg, 90.0)
    }

    companion object {
        /**
         * A square of side [side] centred at ([cx], [cy]) turned [deg], drawn in one stroke like a hand: each side bows
         * a little and trembles, corners are slightly rounded, the end overshoots the start a bit. 240 Hz samples.
         */
        fun handSquare(cx: Float, cy: Float, side: Float, deg: Double, seed: Int): List<StylusSample> {
            val rng = Random(seed * 31 + deg.toInt())
            val a = Math.toRadians(deg)
            val h = side / 2
            val local = listOf(-h to -h, h to -h, h to h, -h to h)
            val out = ArrayList<StylusSample>()
            var t = 0L
            var drift = 0f
            val per = 60
            for (k in 0 until 4) {
                val (x0, y0) = local[k]; val (x1, y1) = local[(k + 1) % 4]
                val bow = side * (rng.nextFloat() * 0.04f - 0.02f)
                val nx = -(y1 - y0) / side; val ny = (x1 - x0) / side
                val endU = if (k == 3) 1.06f else 1f
                for (i in 0 until per) {
                    val u = i / per.toFloat() * endU
                    drift = drift * 0.85f + (rng.nextFloat() - 0.5f) * 1.2f
                    val off = bow * sin(PI * u.coerceAtMost(1f)).toFloat() + drift
                    // Round the corner a little: ease into the next side over the last 4%.
                    val lx = x0 + (x1 - x0) * u + nx * off
                    val ly = y0 + (y1 - y0) * u + ny * off
                    val px = cx + (lx * cos(a) - ly * sin(a)).toFloat()
                    val py = cy + (lx * sin(a) + ly * cos(a)).toFloat()
                    out += StylusSample(px, py, 0.5f, 0.7f, 2.3f, t)
                    t += 4
                }
            }
            return out
        }
    }
}
