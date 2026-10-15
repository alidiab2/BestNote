package app.bestnote.engine.ink

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import java.util.WeakHashMap

/**
 * Draws finished strokes in [InkLayers] order: highlighters first (one multiply-blended layer per
 * color, so overlaps keep one tone and text below never darkens), then all other ink on top.
 */
class InkRenderer(private val renderer: CanvasStrokeRenderer = CanvasStrokeRenderer.create()) {
    /** Highlighter stroke -> same stroke with an opaque color, drawn inside its group's layer. */
    private val opaqueCopies = WeakHashMap<Stroke, Stroke>()
    private val layerPaint = Paint().apply { blendMode = BlendMode.MULTIPLY }
    private val bounds = RectF()
    private val strokeBounds = RectF()

    fun draw(canvas: Canvas, strokes: List<Stroke>, transform: Matrix = IDENTITY) {
        val layers = InkLayers.of(strokes, { BrushCatalog.isHighlighter(it.brush) }, { it.brush.colorIntArgb })
        for (group in layers.highlights) {
            if (!unionBounds(group.items, transform)) continue
            layerPaint.alpha = group.alpha
            val save = canvas.saveLayer(bounds, layerPaint)
            for (stroke in group.items) renderer.draw(canvas, opaque(stroke, group.opaqueColorArgb), transform)
            canvas.restoreToCount(save)
        }
        for (stroke in layers.ink) renderer.draw(canvas, stroke, transform)
    }

    private fun opaque(stroke: Stroke, color: Int): Stroke {
        if (stroke.brush.colorIntArgb == color) return stroke
        return opaqueCopies.getOrPut(stroke) { stroke.copy(stroke.brush.copyWithColorIntArgb(color)) }
    }

    /**
     * Bounds of [items] into [bounds], in stroke (= canvas-local) coordinates, because saveLayer takes
     * local coordinates; [transform] only tells the stroke renderer the device scale. False if none has geometry.
     */
    private fun unionBounds(items: List<Stroke>, transform: Matrix): Boolean {
        bounds.setEmpty()
        for (stroke in items) {
            val box = stroke.shape.computeBoundingBox() ?: continue
            strokeBounds.set(box.xMin, box.yMin, box.xMax, box.yMax)
            bounds.union(strokeBounds)
        }
        if (bounds.isEmpty) return false
        // One device pixel of slack for anti-aliasing, expressed in local units.
        val pad = 1f / transform.mapRadius(1f).coerceAtLeast(MIN_SCALE)
        bounds.inset(-pad, -pad)
        return true
    }

    private companion object {
        val IDENTITY = Matrix()
        const val MIN_SCALE = 0.01f
    }
}
