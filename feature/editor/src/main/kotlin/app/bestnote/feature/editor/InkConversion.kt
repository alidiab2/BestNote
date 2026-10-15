package app.bestnote.feature.editor

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.StylusSample
import app.bestnote.core.model.Tool
import app.bestnote.engine.ink.BrushCatalog
import app.bestnote.engine.ink.InkFamily

/** Maps between androidx.ink [Stroke]s on the canvas and the stored [InkStroke] model. */
internal object InkConversion {
    /** Every input sample of [stroke], bit-exact, stored with [brush]. O(samples of this one stroke). */
    fun toModel(stroke: Stroke, brush: InkBrush): InkStroke {
        val inputs = stroke.inputs
        val input = StrokeInput()
        val samples = ArrayList<StylusSample>(inputs.size)
        for (i in 0 until inputs.size) {
            inputs.populate(i, input)
            samples += StylusSample(input.x, input.y, input.pressure, input.tiltRadians, input.orientationRadians, input.elapsedTimeMillis)
        }
        return InkStroke(brush, samples)
    }

    /** Rebuilds a renderable stroke (computes its mesh, so call it off the main thread). */
    fun toInk(model: InkStroke): Stroke {
        val batch = MutableStrokeInputBatch()
        for (s in model.samples) {
            batch.add(InputToolType.STYLUS, s.x, s.y, s.timeMillis, StrokeInput.NO_STROKE_UNIT_LENGTH, s.pressure, s.tiltRad, s.orientationRad)
        }
        return Stroke(toInkBrush(model.brush), batch)
    }

    /** The stored brush as an androidx.ink [Brush]; the stored family key wins over the tool's current family. */
    fun toInkBrush(b: InkBrush): Brush {
        val spec = requireNotNull(BrushCatalog.spec(b.tool, b.penStyle)) { "${b.tool} has no brush" }
        val family = InkFamily.entries.firstOrNull { it.key == b.family } ?: spec.family
        return Brush.createWithColorIntArgb(
            BrushCatalog.family(family),
            BrushCatalog.applyOpacity(b.colorArgb, spec.opacity),
            b.size * spec.sizeScale,
            BrushCatalog.EPSILON,
        )
    }

    /**
     * Best-effort model brush for a stroke the canvas has no pen-down record of, read from the
     * stroke's own brush (its family is unique per tool and pen style). Opacity is undone approximately.
     */
    fun brushFromInk(brush: Brush): InkBrush {
        val family = BrushCatalog.familyOf(brush)
        val (tool, style) = SPECS.firstOrNull { (t, s) -> BrushCatalog.spec(t, s)?.family == family } ?: (Tool.Pen to PenStyle.Fountain)
        val spec = BrushCatalog.spec(tool, style)!!
        val alpha = ((brush.colorIntArgb ushr 24) / spec.opacity).toInt().coerceIn(0, 255)
        val color = (alpha shl 24) or (brush.colorIntArgb and 0x00FFFFFF)
        return InkBrush(tool, style, color, brush.size / spec.sizeScale, family?.key)
    }

    private val SPECS = Tool.entries.filter { it.inks }.flatMap { t -> PenStyle.entries.map { t to it } }
}
