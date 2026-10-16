package app.bestnote.engine.canvas

import java.util.WeakHashMap

/**
 * INK-11: the fill colour of each closed stroke (packed 0xAARRGGBB, alpha = opacity), keyed by stroke
 * identity and weak, so replaced strokes are collected. Readable from any thread: tile workers look
 * strokes up while painting. [StrokeBrushes] keeps it in step whenever a stroke is copied, moved,
 * pasted or restyled, and the stored [app.bestnote.core.model.InkBrush.fillArgb] is its saved form.
 */
object StrokeFills {
    private val fills = WeakHashMap<Any, Int>()

    @Volatile
    private var any = false

    /** The fill of [stroke], or null for none. */
    fun of(stroke: Any): Int? = if (!any) null else synchronized(fills) { fills[stroke] }

    /** Gives [stroke] the fill [argb], or removes it for null. */
    fun set(stroke: Any, argb: Int?) {
        synchronized(fills) {
            if (argb == null) {
                fills.remove(stroke)
            } else {
                fills[stroke] = argb
                any = true
            }
        }
    }

    /** [copy] looks like [original]: same fill (none stays none). */
    fun copy(copy: Any, original: Any) {
        if (!any) return
        set(copy, of(original))
    }
}
