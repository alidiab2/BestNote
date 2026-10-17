package app.bestnote.engine.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CANVAS-17: recognizer accuracy over the labelled shape corpora (tools/handwriting/corpus, "labels") and
 * the false-positive rate on handwriting. Prints a per-label table; run with `--info` to see it.
 */
class ShapeAccuracyTest {
    private val recognizer = GeometricShapeRecognizer()

    /** The label the recognizer gives [stroke]: a [ShapeKind] name, "Polyline" for open polygons, "None" for no shape. */
    private fun labelOf(stroke: InkStrokeSamples): String {
        val m = recognizer.match(stroke) ?: return "None"
        val s = m.shape
        return if (s is RecognizedShape.Polygon && !s.closed) "Polyline" else m.kind.name
    }

    data class Score(val correct: Int, val total: Int, val misses: List<String>) {
        val accuracy get() = if (total == 0) 1.0 else correct.toDouble() / total
    }

    private fun score(corpus: String): Score {
        val strokes = Corpus.strokes(corpus)
        val labels = Corpus.labels(corpus)
        assertEquals("$corpus: one label per ink stroke", strokes.size, labels.size)
        val byLabel = LinkedHashMap<String, IntArray>()
        val misses = ArrayList<String>()
        strokes.zip(labels).forEachIndexed { i, (stroke, want) ->
            val got = labelOf(stroke)
            val cell = byLabel.getOrPut(want) { IntArray(2) }
            cell[1]++
            if (got == want) cell[0]++ else misses += "#$i $want -> $got"
        }
        val table = byLabel.entries.joinToString("\n") { (k, v) -> "  %-10s %2d / %2d".format(k, v[0], v[1]) }
        val correct = byLabel.values.sumOf { it[0] }
        println("$corpus: $correct / ${strokes.size} (%.1f%%)\n$table\n  misses: $misses".format(100.0 * correct / strokes.size))
        return Score(correct, strokes.size, misses)
    }

    @Test
    fun `sloppy shapes are recognized as what was drawn`() {
        val s = score("shapes_sloppy")
        assertTrue("accuracy ${s.accuracy}: ${s.misses}", s.accuracy >= MIN_ACCURACY)
    }

    @Test
    fun `handwriting strokes rarely turn into shapes`() {
        val text = Corpus.strokes("lecture_en") + Corpus.strokes("slow_large") + Corpus.strokes("arabic")
        val closed = setOf("Circle", "Ellipse", "Rectangle", "Triangle", "Diamond", "Star", "Polygon")
        val hits = text.count { labelOf(it) in closed }
        println("handwriting: $hits of ${text.size} strokes looked like closed shapes (%.1f%%)".format(100.0 * hits / text.size))
        assertTrue("$hits of ${text.size}", hits <= text.size * MAX_FALSE_CLOSED)
    }

    @Test
    fun `diamonds and stars come out regular`() {
        val s = Corpus.strokes("shapes_sloppy")
        val labels = Corpus.labels("shapes_sloppy")
        val diamond = recognizer.match(s[labels.indexOf("Diamond")])!!.shape as RecognizedShape.Polygon
        assertEquals(ShapeKind.Diamond, diamond.kind)
        // Two corners share the center's x, two its y.
        val cx = (diamond.points.maxOf { it.first } + diamond.points.minOf { it.first }) / 2
        val cy = (diamond.points.maxOf { it.second } + diamond.points.minOf { it.second }) / 2
        assertEquals(2, diamond.points.count { kotlin.math.abs(it.first - cx) < 1e-3f })
        assertEquals(2, diamond.points.count { kotlin.math.abs(it.second - cy) < 1e-3f })
        val star = recognizer.match(s[labels.indexOf("Star")])!!.shape as RecognizedShape.Polygon
        assertEquals(ShapeKind.Star, star.kind)
        assertEquals(5, star.points.size)
        // Upright: the top point straight above the center.
        val mx = star.points.map { it.first }.average().toFloat()
        val top = star.points.minBy { it.second }
        assertEquals(mx, top.first, 0.5f)
    }

    @Test
    fun `an open circle becomes a whole circle`() {
        val s = Corpus.strokes("shapes_sloppy")
        val labels = Corpus.labels("shapes_sloppy")
        val i = labels.indexOf("Circle")
        val e = recognizer.match(s[i])!!.shape as RecognizedShape.Ellipse
        assertEquals(ShapeKind.Circle, e.kind)
    }

    private companion object {
        /** Before CANVAS-17: 45 / 59 (76.3%). After: 58 / 59 (98.3%). */
        const val MIN_ACCURACY = 0.95
        const val MAX_FALSE_CLOSED = 0.1
    }
}
