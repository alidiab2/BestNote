package app.bestnote.engine.canvas

import android.graphics.Canvas
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import app.bestnote.engine.ink.BrushCatalog
import app.bestnote.engine.ink.InkRenderer
import kotlin.math.PI
import kotlin.math.sin

/**
 * Live stroke previews for the pen tray and picker (DS-04): a short synthetic squiggle drawn with
 * the very same brush the canvas uses, so what the chrome shows is exactly what the pen writes.
 */
object InkPreview {
    private const val SAMPLES = 48
    private const val SAMPLE_MS = 8L
    private const val MIN_PRESSURE = 0.25f

    /**
     * A gentle S-curve spanning [width] x [height] px (inset by half the stroke width), with a
     * pressure swell in the middle. Null for tools that don't ink. Meshing is O(48 samples).
     */
    fun squiggle(tool: Tool, penStyle: PenStyle, colorArgb: Int, size: Float, width: Float, height: Float): Stroke? {
        val brush = BrushCatalog.brush(tool, penStyle, colorArgb, size) ?: return null
        val inset = (brush.size / 2f).coerceAtMost(minOf(width, height) / 3f)
        val left = inset
        val length = (width - 2 * inset).coerceAtLeast(1f)
        val midY = height / 2f
        val amp = ((height - 2 * inset) / 2f).coerceAtLeast(0f) * 0.6f
        val batch = MutableStrokeInputBatch()
        for (i in 0..SAMPLES) {
            val t = i / SAMPLES.toFloat()
            batch.add(
                type = InputToolType.STYLUS,
                x = left + t * length,
                y = midY - amp * sin(t * 2 * PI).toFloat(),
                elapsedTimeMillis = i * SAMPLE_MS,
                pressure = MIN_PRESSURE + (1f - MIN_PRESSURE) * sin(t * PI).toFloat(),
                tiltRadians = 0f,
                orientationRadians = 0f,
            )
        }
        return Stroke(brush, batch)
    }

    /** Draws [stroke] (highlighters multiply like on the page). */
    fun draw(canvas: Canvas, stroke: Stroke, renderer: InkRenderer) = renderer.draw(canvas, listOf(stroke))
}
