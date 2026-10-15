package app.bestnote.engine.canvas.document

import app.bestnote.engine.layout.document.DocumentLayout
import kotlin.math.hypot

/**
 * The pinch preview (CANVAS-05): while fingers pinch, the already-recorded pages are scaled by the
 * GPU instead of re-laid out, mapping an unscaled view point `v` to the screen at `scale * v + t`.
 * Pure Kotlin.
 */
data class PreviewTransform(val scale: Float = 1f, val tx: Float = 0f, val ty: Float = 0f) {
    val isIdentity: Boolean get() = scale == 1f && tx == 0f && ty == 0f

    /** Scales by [k] about screen point ([fx], [fy]), then moves by ([dx], [dy]). */
    fun scaledBy(k: Float, fx: Float, fy: Float, dx: Float = 0f, dy: Float = 0f) =
        PreviewTransform(scale * k, fx + k * (tx - fx) + dx, fy + k * (ty - fy) + dy)

    /** The unscaled view point shown at screen point ([sx], [sy]). */
    fun unmapX(sx: Float) = (sx - tx) / scale
    fun unmapY(sy: Float) = (sy - ty) / scale
}

/**
 * One step of a finger gesture: scale by [scale] about the fingers' previous centroid
 * ([focusX], [focusY]), then move by the centroid's move ([dx], [dy]). Applied in that order, the
 * content under the old centroid ends up under the new one.
 */
data class PinchStep(val scale: Float, val focusX: Float, val focusY: Float, val dx: Float, val dy: Float) {
    companion object {
        /**
         * Compares the fingers' positions [before] and [after] one input frame (pointer id -> x, y).
         * Only fingers present in both count, so a finger landing or lifting never jumps.
         * Null when no finger is in both.
         */
        fun of(before: Map<Int, Pair<Float, Float>>, after: Map<Int, Pair<Float, Float>>): PinchStep? {
            val ids = before.keys.filter { it in after }
            if (ids.isEmpty()) return null
            val bx = ids.map { before.getValue(it).first }.average().toFloat()
            val by = ids.map { before.getValue(it).second }.average().toFloat()
            val ax = ids.map { after.getValue(it).first }.average().toFloat()
            val ay = ids.map { after.getValue(it).second }.average().toFloat()
            var scale = 1f
            if (ids.size >= 2) {
                val spanBefore = ids.map { val (x, y) = before.getValue(it); hypot(x - bx, y - by) }.average().toFloat()
                val spanAfter = ids.map { val (x, y) = after.getValue(it); hypot(x - ax, y - ay) }.average().toFloat()
                if (spanBefore > MIN_SPAN && spanAfter > MIN_SPAN) scale = spanAfter / spanBefore
            }
            return PinchStep(scale, bx, by, ax - bx, ay - by)
        }

        /** Fingers closer together than this (px) give unreliable scale factors. */
        private const val MIN_SPAN = 8f
    }
}

/** Where a page-local point (in page points) is, for anchoring zoom changes. */
data class PageAnchor(val page: Int, val x: Float, val y: Float)

object ZoomMath {
    /** 25%..800% of fit-to-width. */
    const val MIN_ZOOM = 0.25f
    const val MAX_ZOOM = 8f

    fun clamp(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** The factor to apply to [current] (with a pending preview scale [preview]) so the result stays in limits. */
    fun clampFactor(current: Float, preview: Float, k: Float): Float {
        val now = current * preview
        return clamp(now * k) / now
    }

    /**
     * The page point under document pixel ([docX], [docY]): on the page it is in, or for gaps and
     * the desk on the nearest page above (coordinates may then lie outside the page).
     */
    fun anchorAt(layout: DocumentLayout, docX: Float, docY: Float): PageAnchor? {
        if (layout.pageCount == 0) return null
        var page = 0
        for (i in 0 until layout.pageCount) if (layout.pageRect(i).top <= docY) page = i else break
        val r = layout.pageRect(page)
        return PageAnchor(page, (docX - r.left) / layout.scale, (docY - r.top) / layout.scale)
    }

    /** Document pixel of [anchor] in [layout]. */
    fun docX(layout: DocumentLayout, anchor: PageAnchor) = layout.pageRect(anchor.page).left + anchor.x * layout.scale
    fun docY(layout: DocumentLayout, anchor: PageAnchor) = layout.pageRect(anchor.page).top + anchor.y * layout.scale

    /** View pixel -> page point for page [page] at scroll ([scrollX], [scrollY]): how pen input lands on the page. */
    fun viewToPage(layout: DocumentLayout, scrollX: Int, scrollY: Int, page: Int, x: Float, y: Float): Pair<Float, Float> {
        val r = layout.pageRect(page)
        return (x + scrollX - r.left) / layout.scale to (y + scrollY - r.top) / layout.scale
    }

    /** Page point -> view pixel; the inverse of [viewToPage]. */
    fun pageToView(layout: DocumentLayout, scrollX: Int, scrollY: Int, page: Int, px: Float, py: Float): Pair<Float, Float> {
        val r = layout.pageRect(page)
        return r.left - scrollX + px * layout.scale to r.top - scrollY + py * layout.scale
    }

    /**
     * Scroll offset for [next] that keeps the content shown at screen point ([sx], [sy]) there.
     * Before the change that point showed unscaled view point ([vx], [vy]) of [prev] at
     * ([scrollX], [scrollY]). Unclamped: the caller clamps to the new content size.
     */
    fun anchoredScroll(
        prev: DocumentLayout,
        scrollX: Int,
        scrollY: Int,
        vx: Float,
        vy: Float,
        next: DocumentLayout,
        sx: Float,
        sy: Float,
    ): Pair<Float, Float> {
        val anchor = anchorAt(prev, vx + scrollX, vy + scrollY) ?: return 0f to 0f
        return docX(next, anchor) - sx to docY(next, anchor) - sy
    }
}
