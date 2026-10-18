package app.bestnote.engine.export

import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.InkStroke
import app.bestnote.engine.ink.BrushCatalog
import app.bestnote.engine.ink.InkFamily
import app.bestnote.engine.ink.PencilModel
import app.bestnote.engine.ink.WidthModel
import kotlin.math.hypot

/**
 * Turns a stored stroke into straight pieces with a width each, using the same width rules as the
 * live brushes ([WidthModel]: pressure, speed, tapered ends, bold dots, pencil tilt; [PencilModel] for graphite darkness). Pure Kotlin so it is unit
 * tested; [InkPainter] draws the pieces as vector paths.
 */
internal object StrokeGeometry {
    /**
     * A piece from (x0, y0) to (x1, y1), [width] wide in page points. A zero-length piece is a dot. [opacity] is
     * the graphite darkness of a pencil piece (pressure and tilt, [PencilModel]); 1 for every other pen.
     */
    class Segment(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val width: Float, val opacity: Float = 1f)

    fun familyOf(brush: InkBrush): InkFamily {
        val spec = requireNotNull(BrushCatalog.spec(brush.tool, brush.penStyle)) { "${brush.tool} has no brush" }
        return InkFamily.entries.firstOrNull { it.key == brush.family } ?: spec.family
    }

    /** Full brush width in page points. */
    fun baseWidth(brush: InkBrush): Float {
        val spec = requireNotNull(BrushCatalog.spec(brush.tool, brush.penStyle))
        return brush.size * spec.sizeScale
    }

    fun segments(stroke: InkStroke): List<Segment> {
        val s = stroke.samples
        val family = familyOf(stroke.brush)
        val base = baseWidth(stroke.brush).coerceAtLeast(MIN_WIDTH)
        if (s.size == 1) {
            return listOf(dot(family, base, s[0]))
        }
        val cum = FloatArray(s.size)
        for (i in 1 until s.size) cum[i] = cum[i - 1] + hypot(s[i].x - s[i - 1].x, s[i].y - s[i - 1].y)
        val total = cum.last()
        val mult = FloatArray(s.size) { i ->
            val dtSec = if (i == 0) 0f else (s[i].timeMillis - s[i - 1].timeMillis).coerceAtLeast(1L) / 1000f
            val speed = if (i == 0) 0f else (cum[i] - cum[i - 1]) / base / dtSec
            WidthModel.multiplier(family, s[i].pressure, speed, cum[i] / base, (total - cum[i]) / base, s[i].tiltRad)
        }
        if (total < 1e-3f) {
            return listOf(dot(family, base, s[0]))
        }
        val pencil = family.isPencil
        val alpha = FloatArray(s.size) { i -> if (pencil) PencilModel.opacity(s[i].pressure, s[i].tiltRad) else 1f }
        val out = ArrayList<Segment>(s.size - 1)
        for (i in 1 until s.size) {
            if (cum[i] == cum[i - 1]) continue
            out += Segment(s[i - 1].x, s[i - 1].y, s[i].x, s[i].y, (base * (mult[i - 1] + mult[i]) / 2f).coerceAtLeast(MIN_WIDTH), (alpha[i - 1] + alpha[i]) / 2f)
        }
        return out
    }

    private fun dot(family: InkFamily, base: Float, p: app.bestnote.core.model.StylusSample) = Segment(
        p.x, p.y, p.x, p.y, base * WidthModel.multiplier(family, p.pressure, 0f, 0f, 0f, p.tiltRad),
        // A pencil dot is pressed: dots are lifted to full darkness like they are to full width.
        if (family.isPencil) PencilModel.tiltOpacity(p.tiltRad) else 1f,
    )

    /**
     * Joins neighbouring pieces of similar width (and pencil darkness) into runs, so a stroke becomes a handful of paths
     * instead of one per sample. Each run is the list of its points and one width.
     */
    class Run(val points: FloatArray, val width: Float, val opacity: Float = 1f)

    fun runs(segments: List<Segment>, tolerance: Float = 0.06f, opacityTolerance: Float = 0.06f): List<Run> {
        val out = ArrayList<Run>()
        var i = 0
        while (i < segments.size) {
            val first = segments[i]
            val pts = ArrayList<Float>()
            pts += first.x0; pts += first.y0
            var sum = 0f
            var opacity = 0f
            var n = 0
            var j = i
            while (j < segments.size) {
                val seg = segments[j]
                if (n > 0 && kotlin.math.abs(seg.width - first.width) > first.width * tolerance) break
                if (n > 0 && kotlin.math.abs(seg.opacity - first.opacity) > opacityTolerance) break
                if (n > 0 && (seg.x0 != pts[pts.size - 2] || seg.y0 != pts[pts.size - 1])) break
                pts += seg.x1; pts += seg.y1
                sum += seg.width
                opacity += seg.opacity
                n++
                j++
            }
            out += Run(pts.toFloatArray(), sum / n, opacity / n)
            i = j
        }
        return out
    }

    private const val MIN_WIDTH = 0.2f
}
