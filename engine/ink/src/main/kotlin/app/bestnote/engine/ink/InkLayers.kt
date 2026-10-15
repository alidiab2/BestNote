package app.bestnote.engine.ink

/**
 * Paint order for a page's finished strokes (INK-03): all highlighter strokes first, grouped by
 * color, then every other stroke in its original order. Each highlight group is drawn opaque into
 * its own layer that is composited once at [HighlightGroup.alpha], so overlapping highlights of
 * the same color keep one tone instead of darkening, and ink always sits crisp on top.
 *
 * Pure and generic so the ordering rules are unit-testable without androidx.ink's native code.
 */
data class InkLayers<T>(val highlights: List<HighlightGroup<T>>, val ink: List<T>) {
    data class HighlightGroup<T>(
        /** Opaque ARGB the group's strokes are drawn with inside the layer. */
        val opaqueColorArgb: Int,
        /** 0..255 alpha the layer is composited with. */
        val alpha: Int,
        val items: List<T>,
    )

    companion object {
        fun <T> of(items: List<T>, isHighlighter: (T) -> Boolean, colorArgb: (T) -> Int): InkLayers<T> {
            val groups = LinkedHashMap<Int, MutableList<T>>()
            val alphas = HashMap<Int, Int>()
            val ink = ArrayList<T>(items.size)
            for (item in items) {
                if (!isHighlighter(item)) {
                    ink += item
                    continue
                }
                val argb = colorArgb(item)
                val opaque = argb or OPAQUE
                groups.getOrPut(opaque) { ArrayList() } += item
                // Strokes of one color share a layer; the most opaque one sets the layer's tone.
                alphas[opaque] = maxOf(alphas[opaque] ?: 0, argb ushr 24)
            }
            return InkLayers(groups.map { (color, list) -> HighlightGroup(color, alphas.getValue(color), list) }, ink)
        }

        private const val OPAQUE = 0xFF000000.toInt()
    }
}
