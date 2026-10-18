package app.bestnote.engine.ink

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import app.bestnote.core.model.LineDash
import java.util.WeakHashMap

/**
 * Draws finished strokes in [InkLayers] order: highlighters first (one multiply-blended layer per
 * color, so overlaps keep one tone and text below never darkens), then all other ink on top.
 *
 * A highlighter stroke in progress is drawn inside its color's layer too ([draw] with `wet`, or
 * [drawHighlightGroup]), so it sits under the ink from its first frame. Start such a stroke with a
 * [BrushCatalog.opaque] brush; it is composited at the real color's alpha.
 *
 * Dashed and dotted strokes ([StrokeDashes], INK-07) are drawn through their [DashClip], so every caller (page
 * tiles, previews, selection) shows the dashes. The clip of a finished stroke is built once and cached.
 */
class InkRenderer(private val renderer: CanvasStrokeRenderer = CanvasStrokeRenderer.create(InkTextures)) {
    /** Highlighter stroke -> same stroke with an opaque color, drawn inside its group's layer. */
    private val opaqueCopies = WeakHashMap<Stroke, Stroke>()
    private val layerPaint = Paint().apply { blendMode = BlendMode.MULTIPLY }
    private val bounds = RectF()
    private val strokeBounds = RectF()
    private val dashClip = DashClip()
    private val dashPaths = WeakHashMap<Stroke, Pair<LineDash, Path>>()
    private val wetInputs = MutableStrokeInputBatch()
    private val wetPath = Path()

    /**
     * @param wet a highlighter stroke in progress, started with an opaque color; [wetColorArgb] is
     *   its real (translucent) color, which picks its layer and the layer's alpha.
     */
    fun draw(canvas: Canvas, strokes: List<Stroke>, transform: Matrix = IDENTITY, wet: InProgressStroke? = null, wetColorArgb: Int = 0) {
        val layers = layers(strokes, if (wet != null) wetColorArgb else null)
        layers.highlights.forEachIndexed { i, group ->
            drawHighlightGroup(canvas, group, transform, if (i == layers.wetGroup) wet else null, null)
        }
        drawInk(canvas, layers.ink, transform)
    }

    /** The paint order of [strokes] (plus a wet highlighter of [wetColorArgb], if any). */
    fun layers(strokes: List<Stroke>, wetColorArgb: Int? = null): InkLayers<Stroke> =
        InkLayers.of(strokes, { BrushCatalog.isHighlighter(it.brush) }, { it.brush.colorIntArgb }, wetColorArgb)

    /**
     * One highlight color: its strokes (and [wet]) drawn opaque into a layer that is multiplied
     * onto the page at the group's alpha. [layerBounds] null = the strokes' own bounds.
     */
    fun drawHighlightGroup(canvas: Canvas, group: InkLayers.HighlightGroup<Stroke>, transform: Matrix, wet: InProgressStroke?, layerBounds: RectF?) {
        if (layerBounds != null) bounds.set(layerBounds) else if (!unionBounds(group.items, transform) && wet == null) return
        if (layerBounds == null && wet != null) bounds.set(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
        layerPaint.alpha = group.alpha
        val save = canvas.saveLayer(bounds, layerPaint)
        for (stroke in group.items) renderer.draw(canvas, opaque(stroke, group.opaqueColorArgb), transform)
        if (wet != null) renderer.draw(canvas, wet, transform)
        canvas.restoreToCount(save)
    }

    fun drawInk(canvas: Canvas, ink: List<Stroke>, transform: Matrix) {
        for (stroke in ink) {
            val dash = StrokeDashes.of(stroke)
            if (dash == LineDash.Solid) {
                renderer.draw(canvas, stroke, transform)
                continue
            }
            val clip = dashPaths[stroke]?.takeIf { it.first == dash }?.second
                ?: dashClip.of(stroke.inputs, dash, stroke.brush.size, DashPattern.maxWidthScale(BrushCatalog.familyOf(stroke.brush)))
                    ?.also { dashPaths[stroke] = dash to it }
            val save = canvas.save()
            if (clip != null) canvas.clipPath(clip)
            renderer.draw(canvas, stroke, transform)
            canvas.restoreToCount(save)
        }
    }

    /**
     * A pen stroke in progress drawn with [dash] (INK-07). Its clip is rebuilt from its inputs every call: one
     * stroke's samples, never the page's. Canvas coordinates are the stroke's, as for [drawInk].
     */
    fun drawWet(canvas: Canvas, wet: InProgressStroke, transform: Matrix, dash: LineDash) {
        val brush = wet.brush ?: return
        val clip = if (dash == LineDash.Solid) {
            null
        } else {
            wet.populateInputs(wetInputs)
            dashClip.of(wetInputs, dash, brush.size, DashPattern.maxWidthScale(BrushCatalog.familyOf(brush)), wetPath)
        }
        val save = canvas.save()
        if (clip != null) canvas.clipPath(clip)
        renderer.draw(canvas, wet, transform)
        canvas.restoreToCount(save)
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
