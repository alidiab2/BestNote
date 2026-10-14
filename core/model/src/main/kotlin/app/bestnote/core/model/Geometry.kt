package app.bestnote.core.model

/** Page coordinates are in points (1/72 in), origin top-left, y down. */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    fun length() = kotlin.math.hypot(x, y)
}

data class RectPt(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val isEmpty get() = width <= 0f || height <= 0f
    val center get() = Vec2((left + right) / 2f, (top + bottom) / 2f)

    fun intersects(o: RectPt) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun contains(p: Vec2) = p.x in left..right && p.y in top..bottom
    fun union(o: RectPt) = RectPt(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
    fun inset(d: Float) = RectPt(left + d, top + d, right - d, bottom - d)

    companion object {
        fun ofPoints(points: Iterable<Vec2>): RectPt? {
            val it = points.iterator()
            if (!it.hasNext()) return null
            val first = it.next()
            var l = first.x; var t = first.y; var r = first.x; var b = first.y
            for (p in it) { l = minOf(l, p.x); t = minOf(t, p.y); r = maxOf(r, p.x); b = maxOf(b, p.y) }
            return RectPt(l, t, r, b)
        }
    }
}

data class PageSize(val widthPt: Float, val heightPt: Float) {
    companion object {
        val A4 = PageSize(595f, 842f)
        val Letter = PageSize(612f, 792f)
    }
}

/** Page layouts that Smart Board reconstruction can flow notes into. */
enum class LayoutTemplate { Cornell, LectureSummary, CheatSheet, Minimalist }
