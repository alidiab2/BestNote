package app.bestnote.engine.recognition

import app.bestnote.core.model.StylusSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** REC-09 on hand-drawn strokes from the handwriting corpus (tools/handwriting) and noisy synthetic ones. */
class GeometricShapeRecognizerTest {
    private val recognizer = GeometricShapeRecognizer()
    private val mm = 72f / 25.4f

    @Test
    fun `every held shape in the shapes corpus is recognized as what was drawn`() {
        val kinds = Corpus.strokes("shapes_hold").map { recognizer.match(it)?.kind }
        assertEquals(
            listOf(
                ShapeKind.Line, ShapeKind.Arrow, ShapeKind.Circle, ShapeKind.Ellipse, ShapeKind.Rectangle,
                ShapeKind.Triangle, ShapeKind.Polygon, ShapeKind.Line, ShapeKind.Circle,
            ),
            kinds,
        )
    }

    @Test
    fun `corpus diagram shapes are recognized`() {
        val d = Corpus.strokes("drawings")
        assertEquals(ShapeKind.Circle, recognizer.match(d[0])?.kind)
        assertEquals(ShapeKind.Rectangle, recognizer.match(d[3])?.kind)
        assertEquals(ShapeKind.Circle, recognizer.match(d[24])?.kind)
        val s = Corpus.strokes("scribble")
        assertEquals(ShapeKind.Rectangle, recognizer.match(s[0])?.kind)
        assertNull("hatching is not a shape", recognizer.match(s[1]))
        assertEquals(ShapeKind.Circle, recognizer.match(s[2])?.kind)
        assertNull("hatching is not a shape", recognizer.match(s[3]))
    }

    @Test
    fun `a circle's center and radius match the drawing`() {
        // drawings.json: circle at (22, 22) mm, radius 14 mm, drawn counter-clockwise from near the top.
        val e = recognizer.match(Corpus.strokes("drawings")[0])!!.shape as RecognizedShape.Ellipse
        assertEquals(22 * mm, e.cx, 1.2f * mm)
        assertEquals(22 * mm, e.cy, 1.2f * mm)
        assertEquals(14 * mm, e.rx, 1.2f * mm)
        assertEquals(e.rx, e.ry)
        assertEquals(false, e.clockwise)
        assertTrue("starts near the top", abs(e.startAngleRad + PI.toFloat() / 2) < 0.8f)
    }

    @Test
    fun `an upright hand-drawn box becomes an exact axis-aligned rectangle`() {
        // drawings.json: box at (76, 8) mm, 40 x 22 mm.
        val r = recognizer.match(Corpus.strokes("drawings")[3])!!.shape as RecognizedShape.Polygon
        assertEquals(ShapeKind.Rectangle, r.kind)
        assertEquals(4, r.points.size)
        assertEquals(2, r.points.map { it.first }.distinct().size)
        assertEquals(2, r.points.map { it.second }.distinct().size)
        assertEquals(40 * mm, r.points.maxOf { it.first } - r.points.minOf { it.first }, 1.5f * mm)
        assertEquals(22 * mm, r.points.maxOf { it.second } - r.points.minOf { it.second }, 1.5f * mm)
    }

    @Test
    fun `a rotated ellipse keeps its tilt and axes`() {
        // shapes_hold.json: ellipse 26 x 12 mm turned 8 degrees (beyond the 6 degree snap).
        val e = recognizer.match(Corpus.strokes("shapes_hold")[3])!!.shape as RecognizedShape.Ellipse
        assertEquals(ShapeKind.Ellipse, e.kind)
        assertEquals(26 * mm, e.rx, 2f * mm)
        assertEquals(12 * mm, e.ry, 1.5f * mm)
        assertEquals(Math.toRadians(8.0).toFloat(), e.rotationRad, 0.06f)
    }

    @Test
    fun `a shaft and a separately drawn head make one arrow`() {
        val d = Corpus.strokes("drawings")
        val a = recognizer.matchArrow(d[1], d[2])!!.shape as RecognizedShape.Arrow
        // drawings.json: arrow from (42, 24) to (72, 18) mm.
        assertEquals(72 * mm, a.x1, 1.5f * mm)
        assertEquals(18 * mm, a.y1, 1.5f * mm)
        assertEquals(42 * mm, a.x0, 1.5f * mm)
        assertEquals(2, a.barbs.size)
        a.barbs.forEach { (bx, by) -> assertTrue(hypot(bx - a.x1, by - a.y1) in 2f * mm..4.5f * mm) }
        // The head alone next to an unrelated line is no arrow.
        assertNull(recognizer.matchArrow(d[1], d[3]))
    }

    @Test
    fun `a one-stroke arrow gets a symmetric head on its tip`() {
        val a = recognizer.match(Corpus.strokes("shapes_hold")[1])!!.shape as RecognizedShape.Arrow
        // shapes_hold.json: arrow from (78, 22) to (120, 8) mm.
        assertEquals(120 * mm, a.x1, 1.5f * mm)
        assertEquals(8 * mm, a.y1, 1.5f * mm)
        val l1 = hypot(a.barbs[0].first - a.x1, a.barbs[0].second - a.y1)
        val l2 = hypot(a.barbs[1].first - a.x1, a.barbs[1].second - a.y1)
        assertEquals(l1, l2, 0.01f)
    }

    @Test
    fun `lines close to 0, 45 and 90 degrees snap onto them, others keep their angle`() {
        for ((deg, want) in listOf(3.5 to 0.0, -4.0 to 0.0, 41.0 to 45.0, 93.0 to 90.0, 20.0 to 20.0, 60.0 to 60.0)) {
            val l = recognizer.match(wobblyLine(100f, 100f, 160f, deg))!!.shape as RecognizedShape.Line
            val got = Math.toDegrees(atan2((l.y1 - l.y0).toDouble(), (l.x1 - l.x0).toDouble()))
            assertEquals("drawn at $deg", want, got, if (want == deg) 2.0 else 1e-3)
        }
    }

    @Test
    fun `the still end of a hold does not change the shape`() {
        val circle = Corpus.strokes("drawings")[0]
        val last = circle.last()
        val rng = Random(3)
        val held = circle + (1..160).map { i ->
            last.copy(x = last.x + rng.nextFloat() * 0.4f - 0.2f, y = last.y + rng.nextFloat() * 0.4f - 0.2f, timeMillis = last.timeMillis + i * 4L)
        }
        val a = recognizer.match(circle)!!.shape as RecognizedShape.Ellipse
        val b = recognizer.match(held)!!.shape as RecognizedShape.Ellipse
        assertEquals(ShapeKind.Circle, b.kind)
        assertEquals(a.cx, b.cx, 0.5f)
        assertEquals(a.rx, b.rx, 0.5f)
    }

    @Test
    fun `handwriting is rarely mistaken for a closed shape`() {
        val text = Corpus.strokes("lecture_en") + Corpus.strokes("slow_large")
        val closed = setOf(ShapeKind.Circle, ShapeKind.Ellipse, ShapeKind.Rectangle, ShapeKind.Triangle)
        val hits = text.count { recognizer.match(it)?.kind in closed }
        assertTrue("$hits of ${text.size} letter strokes looked closed", hits <= text.size / 10)
        assertTrue(text.none { recognizer.match(it)?.kind == ShapeKind.Rectangle })
    }

    @Test
    fun `dots and tiny strokes are not shapes`() {
        assertNull(recognizer.match(listOf(sample(10f, 10f, 0))))
        assertNull(recognizer.match(listOf(sample(10f, 10f, 0), sample(11f, 11f, 4), sample(10.5f, 12f, 8))))
    }

    @Test
    fun `recognition takes well under 30 ms per stroke`() {
        val strokes = Corpus.strokes("shapes_hold") + Corpus.strokes("drawings")
        repeat(20) { strokes.forEach(recognizer::match) } // warm up the JIT
        var worst = 0L
        for (s in strokes) {
            val t = System.nanoTime()
            recognizer.match(s)
            worst = maxOf(worst, System.nanoTime() - t)
        }
        assertTrue("worst ${worst / 1_000_000.0} ms", worst < 30_000_000L)
    }

    @Test
    fun `confidence is higher for a cleaner drawing`() {
        val clean = recognizer.match(wobblyLine(0f, 0f, 200f, 20.0, noise = 0.2f))!!
        val rough = recognizer.match(wobblyLine(0f, 0f, 200f, 20.0, noise = 3f))
        assertNotNull(rough)
        assertTrue(clean.confidence > rough!!.confidence)
    }

    private fun sample(x: Float, y: Float, t: Long) = StylusSample(x, y, 0.5f, 0.7f, 2.3f, t)

    /** A hand-drawn-ish line: a slight bow plus smooth tremor, sampled at 240 Hz. */
    private fun wobblyLine(x0: Float, y0: Float, length: Float, deg: Double, noise: Float = 0.8f): List<StylusSample> {
        val rng = Random(deg.toInt() + 11)
        val a = Math.toRadians(deg)
        val n = 90
        val bow = length * 0.01f
        var drift = 0f
        return (0..n).map { i ->
            val u = i / n.toFloat()
            drift = drift * 0.8f + (rng.nextFloat() - 0.5f) * noise
            val off = bow * sin(PI * u).toFloat() + drift
            val x = x0 + (cos(a) * length * u - sin(a) * off).toFloat()
            val y = y0 + (sin(a) * length * u + cos(a) * off).toFloat()
            sample(x, y, i * 4L)
        }
    }
}
