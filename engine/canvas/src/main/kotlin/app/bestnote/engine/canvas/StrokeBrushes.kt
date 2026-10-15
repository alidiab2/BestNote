package app.bestnote.engine.canvas

import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import app.bestnote.engine.ink.BrushCatalog
import java.util.WeakHashMap

/**
 * Remembers the exact brush each stroke was started with. The brush is captured at pen-down and
 * travels with the in-progress stroke [I] until it finishes as stroke [S], so a later tool switch,
 * the side-button eraser or late/palm events can never relabel it. Pure Kotlin (unit-tested).
 */
class StrokeBrushes<I : Any, S : Any> {
    private val inProgress = HashMap<I, InkBrush>()

    /** Finished strokes compare by identity; weak so undone-and-dropped strokes can be collected. */
    private val finished = WeakHashMap<S, InkBrush>()

    /** Pen-down: [brush] is what this stroke will be stored with. */
    fun begin(id: I, brush: InkBrush) {
        inProgress[id] = brush
    }

    fun cancel(id: I) {
        inProgress.remove(id)
    }

    /** The in-progress stroke [id] became [stroke]. */
    fun finish(id: I, stroke: S) {
        inProgress.remove(id)?.let { finished[stroke] = it }
    }

    fun brushOf(stroke: S): InkBrush? = finished[stroke]

    companion object {
        /** The full brush for a stroke starting now with these canvas settings, or null if [tool] doesn't ink. */
        fun capture(tool: Tool, penStyle: PenStyle, colorArgb: Int, size: Float): InkBrush? {
            val spec = BrushCatalog.spec(tool, penStyle) ?: return null
            return InkBrush(tool, penStyle, colorArgb, size, spec.family.key)
        }
    }
}
