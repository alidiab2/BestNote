package app.bestnote.engine.canvas.erase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class StrokeSplitterTest {
    /** A horizontal stroke from x=0 to x=100 at y=0, a sample every 10 pt. */
    private val xs = FloatArray(11) { it * 10f }
    private val ys = FloatArray(11)

    private fun dot(x: Float, y: Float, r: Float) = EraserCapsule(x, y, x, y, r)

    @Test fun missLeavesTheStrokeUntouched() {
        assertNull(StrokeSplitter.keptRanges(xs, ys, 11, listOf(dot(50f, 30f, 5f)), extra = 1f))
        assertFalse(StrokeSplitter.touches(xs, ys, 11, listOf(dot(50f, 30f, 5f)), extra = 1f))
    }

    @Test fun erasingThroughTheMiddleLeavesTwoPiecesWithCleanEnds() {
        val r = 5f
        val half = 1f
        val kept = StrokeSplitter.keptRanges(xs, ys, 11, listOf(EraserCapsule(53f, -20f, 53f, 20f, r)), extra = half)!!
        assertEquals(2, kept.size)
        // The cut ends exactly r + half from the eraser's center line, so each round end stops at its edge.
        assertEquals(53f - r - half, StrokeSplitter.lerpAt(xs, kept[0].endInclusive), 1e-3f)
        assertEquals(53f + r + half, StrokeSplitter.lerpAt(xs, kept[1].start), 1e-3f)
        assertEquals(0f, kept[0].start, 0f)
        assertEquals(10f, kept[1].endInclusive, 0f)
    }

    @Test fun innerSamplesSkipTheInterpolatedEnds() {
        assertEquals(1..4, StrokeSplitter.innerSamples(0f..4.7f))
        assertEquals(6..9, StrokeSplitter.innerSamples(5.3f..10f))
        assertEquals(3..3, StrokeSplitter.innerSamples(2.5f..3.5f))
        assertTrue(StrokeSplitter.innerSamples(2.2f..2.8f).isEmpty())
    }

    @Test fun fastEraserBetweenSamplesStillCuts() {
        // Two eraser samples far above and below the stroke: only the swept capsule crosses it.
        val kept = StrokeSplitter.keptRanges(xs, ys, 11, listOf(EraserCapsule(25f, -40f, 25f, 40f, 2f)))!!
        assertEquals(2, kept.size)
        assertEquals(23f, StrokeSplitter.lerpAt(xs, kept[0].endInclusive), 1e-3f)
        assertEquals(27f, StrokeSplitter.lerpAt(xs, kept[1].start), 1e-3f)
    }

    @Test fun diagonalSweepHitsTheConvexIntervalOnly() {
        val c = EraserCapsule(0f, 0f, 10f, 10f, 1f)
        val iv = StrokeSplitter.segmentInCapsule(0f, 5f, 10f, 5f, c, 1f)!!
        // Points within distance 1 of the diagonal y = x along y = 5: |x - 5| / sqrt(2) <= 1.
        val d = 1f * hypot(1f, 1f)
        assertEquals((5f - d) / 10f, iv[0], 1e-4f)
        assertEquals((5f + d) / 10f, iv[1], 1e-4f)
    }

    @Test fun erasingTheWholeStrokeLeavesNothing() {
        val sweep = EraserCapsule(-10f, 0f, 110f, 0f, 3f)
        assertEquals(emptyList<ClosedFloatingPointRange<Float>>(), StrokeSplitter.keptRanges(xs, ys, 11, listOf(sweep)))
    }

    @Test fun crumbsShorterThanTheMinimumAreDropped() {
        // Two cuts 2 pt apart leave a 1 pt crumb between them (radius 0.5): dropped with a 1.5 pt minimum.
        val cuts = listOf(dot(40f, 0f, 0.5f), dot(41.5f + 0.5f, 0f, 0.5f))
        val all = StrokeSplitter.keptRanges(xs, ys, 11, cuts)!!
        assertEquals(3, all.size)
        val tidy = StrokeSplitter.keptRanges(xs, ys, 11, cuts, minPieceLength = 1.5f)!!
        assertEquals(2, tidy.size)
    }

    @Test fun eraserEndingOnTheStrokeShortensIt() {
        val kept = StrokeSplitter.keptRanges(xs, ys, 11, listOf(dot(100f, 0f, 5f)))!!
        assertEquals(1, kept.size)
        assertEquals(95f, StrokeSplitter.lerpAt(xs, kept[0].endInclusive), 1e-3f)
    }

    @Test fun dotStrokeIsErasedWhenTouched() {
        val x = floatArrayOf(10f)
        val y = floatArrayOf(10f)
        assertEquals(emptyList<ClosedFloatingPointRange<Float>>(), StrokeSplitter.keptRanges(x, y, 1, listOf(dot(12f, 10f, 3f))))
        assertNull(StrokeSplitter.keptRanges(x, y, 1, listOf(dot(20f, 10f, 3f))))
    }

    @Test fun cursiveWordThroughWhichTheEraserPassesSplitsAtEveryCrossing() {
        // A looping "eee"-like curve crossing x = 50 several times; a vertical sweep there cuts each crossing.
        val n = 200
        val cx = FloatArray(n) { 20f + it * 0.3f + 8f * cos(it * 0.25f) }
        val cy = FloatArray(n) { 8f * sin(it * 0.25f) }
        val crossings = (0 until n - 1).count { (cx[it] - 50f) * (cx[it + 1] - 50f) < 0 }
        val kept = StrokeSplitter.keptRanges(cx, cy, n, listOf(EraserCapsule(50f, -30f, 50f, 30f, 0.2f)))!!
        assertTrue(crossings >= 3)
        assertEquals(crossings + 1, kept.size)
        // No kept piece reaches into the eraser.
        for (piece in kept) {
            for (u in listOf(piece.start, piece.endInclusive)) {
                assertTrue(kotlin.math.abs(StrokeSplitter.lerpAt(cx, u) - 50f) >= 0.2f - 1e-3f)
            }
        }
    }
}
