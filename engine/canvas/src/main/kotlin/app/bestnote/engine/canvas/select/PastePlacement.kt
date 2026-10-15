package app.bestnote.engine.canvas.select

/** Where pasted ink goes (CANVAS-11). Pure Kotlin. */
object PastePlacement {
    /**
     * The move that centers content with bounds [box] on ([x], [y]), where the pen last touched,
     * then nudges it back onto a [pageWidth] x [pageHeight] page as far as it fits (content larger
     * than the page stays centered on the point along that axis).
     */
    fun offset(box: Box, x: Float, y: Float, pageWidth: Float, pageHeight: Float): Pair<Float, Float> =
        axis(box.minX, box.maxX, x, pageWidth) to axis(box.minY, box.maxY, y, pageHeight)

    private fun axis(min: Float, max: Float, at: Float, extent: Float): Float {
        val size = max - min
        var start = at - size / 2
        if (size <= extent) start = start.coerceIn(0f, extent - size)
        return start - min
    }

    /** How far a duplicate lands from its original, down and to the right, in page points. */
    const val DUPLICATE_OFFSET = 16f
}
