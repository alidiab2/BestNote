package app.bestnote.engine.ink

import app.bestnote.core.model.LineDash
import java.util.WeakHashMap

/**
 * INK-07: the line dash of each dashed or dotted stroke, keyed by stroke identity and weak, so replaced strokes
 * are collected (a solid stroke has no entry). [InkRenderer] reads it to clip every stroke it draws, so the
 * canvas, tiles, previews and selection all show dashes. Readable from any thread (tile workers paint off the
 * main thread). The canvas keeps it in step whenever a stroke is copied, moved, pasted or restyled; the stored
 * [app.bestnote.core.model.InkBrush.dash] is its saved form.
 */
object StrokeDashes {
    private val dashes = WeakHashMap<Any, LineDash>()

    @Volatile
    private var any = false

    /** The dash of [stroke] ([LineDash.Solid] when it has none). */
    fun of(stroke: Any): LineDash = if (!any) LineDash.Solid else synchronized(dashes) { dashes[stroke] } ?: LineDash.Solid

    fun set(stroke: Any, dash: LineDash) {
        synchronized(dashes) {
            if (dash == LineDash.Solid) {
                dashes.remove(stroke)
            } else {
                dashes[stroke] = dash
                any = true
            }
        }
    }

    /** [copy] looks like [original]: same dash. */
    fun copy(copy: Any, original: Any) {
        if (!any) return
        set(copy, of(original))
    }
}
