package app.bestnote.feature.editor

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.StylusSample
import app.bestnote.engine.ink.BrushCatalog

/** Maps between androidx.ink [Stroke]s on the canvas and the stored [InkStroke] model. */
internal object InkConversion {
    /** Every input sample of [stroke], bit-exact, drawn with [brush]. O(samples of this one stroke). */
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
        val b = model.brush
        val brush = requireNotNull(BrushCatalog.brush(b.tool, b.penStyle, b.colorArgb, b.size)) { "${b.tool} has no brush" }
        val batch = MutableStrokeInputBatch()
        for (s in model.samples) {
            batch.add(InputToolType.STYLUS, s.x, s.y, s.timeMillis, StrokeInput.NO_STROKE_UNIT_LENGTH, s.pressure, s.tiltRad, s.orientationRad)
        }
        return Stroke(brush, batch)
    }
}
