package app.bestnote.engine.canvas.select

import androidx.ink.brush.Brush
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.PageElement
import java.util.WeakHashMap
import kotlin.math.atan2

/** A stroke's centerline in page points and its inked bounds; strokes are immutable, so this never goes stale. */
class StrokeOutline(val xs: FloatArray, val ys: FloatArray, val box: Box)

/** Centerlines of strokes, computed once per stroke (weakly cached). Main thread only. */
class StrokeOutlines {
    private val cache = WeakHashMap<Stroke, StrokeOutline>()
    private val scratch = StrokeInput()

    fun of(stroke: Stroke): StrokeOutline = cache.getOrPut(stroke) {
        val inputs = stroke.inputs
        val n = inputs.size
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            inputs.populate(i, scratch)
            xs[i] = scratch.x
            ys[i] = scratch.y
        }
        val box = (Box.of(xs, ys) ?: Box(0f, 0f, 0f, 0f)).inflate(stroke.brush.size / 2)
        StrokeOutline(xs, ys, box)
    }

    fun boxOf(strokes: Collection<Stroke>): Box? = Box.union(strokes.map { of(it).box })
}

/** New strokes made from existing ones: moved, resized, rotated, recolored, thickened. */
object StrokeEdits {
    private const val OPAQUE_RGB = 0x00FFFFFF

    /** [stroke] mapped through [m]: every input moves, the brush size scales with [Affine.lengthScale]. */
    fun transformed(stroke: Stroke, m: Affine): Stroke {
        val src = stroke.inputs
        val batch = MutableStrokeInputBatch()
        val s = StrokeInput()
        val turn = atan2(m.b, m.a)
        for (i in 0 until src.size) {
            src.populate(i, s)
            batch.add(
                s.toolType,
                m.mapX(s.x, s.y),
                m.mapY(s.x, s.y),
                s.elapsedTimeMillis,
                s.strokeUnitLengthCm,
                s.pressure,
                s.tiltRadians,
                if (s.hasOrientation) s.orientationRadians + turn else StrokeInput.NO_ORIENTATION,
            )
        }
        val k = m.lengthScale
        val brush = if (k == 1f) stroke.brush else resized(stroke.brush, k)
        return Stroke(brush, batch)
    }

    /** [stroke] with its color's RGB replaced by [colorArgb]'s (its own alpha, e.g. a highlighter's, stays). */
    fun recolored(stroke: Stroke, colorArgb: Int): Stroke {
        val brush = stroke.brush
        return stroke.copy(Brush.createWithColorIntArgb(brush.family, withRgb(brush.colorIntArgb, colorArgb), brush.size, brush.epsilon))
    }

    /** [stroke] with its brush [factor] times as wide. */
    fun thickened(stroke: Stroke, factor: Float): Stroke = Stroke(resized(stroke.brush, factor), stroke.inputs)

    /** The stored brush that goes with [recolored]. */
    fun recolored(brush: InkBrush, colorArgb: Int): InkBrush = brush.copy(colorArgb = withRgb(brush.colorArgb, colorArgb))

    fun resized(brush: InkBrush, factor: Float): InkBrush = brush.copy(size = brush.size * factor)

    private fun resized(brush: Brush, factor: Float): Brush =
        Brush.createWithColorIntArgb(brush.family, brush.colorIntArgb, brush.size * factor, brush.epsilon)

    /** [base]'s alpha with [rgb]'s color. */
    fun withRgb(base: Int, rgb: Int): Int = (base and OPAQUE_RGB.inv()) or (rgb and OPAQUE_RGB)
}

/**
 * The in-app ink clipboard (CANVAS-11): process-wide, so ink copied on one page pastes on any
 * page of any notebook. Strokes are immutable page-point values, so holding them is safe.
 */
object InkClipboard {
    class Item(val stroke: Stroke, val brush: InkBrush?)

    var items: List<Item> = emptyList()
        private set

    /** Bounds of the copied ink, in the page points it was copied at. */
    var box: Box? = null
        private set

    /** Copied text boxes and pictures (TEXT-01, MEDIA-01), in page points. */
    var elements: List<PageElement> = emptyList()
        private set

    val hasContent: Boolean get() = items.isNotEmpty() || elements.isNotEmpty()

    fun set(items: List<Item>, box: Box?, elements: List<PageElement> = emptyList()) {
        this.items = items
        this.box = box
        this.elements = elements
    }

    fun clear() = set(emptyList(), null)
}
