package app.bestnote.core.model

/**
 * How a stored stroke is drawn. Mirrors what the canvas passes to the brush catalog
 * (tool, pen style, colour, size), so a stroke can be re-created without Android types.
 *
 * @property colorArgb packed 0xAARRGGBB.
 * @property size base brush size in page points.
 */
data class InkBrush(
    val tool: Tool,
    val penStyle: PenStyle = PenStyle.Fountain,
    val colorArgb: Int = 0xFF1F1F1F.toInt(),
    val size: Float,
) {
    init {
        require(tool.inks) { "$tool does not lay down ink" }
    }
}

/** One finished stroke: its brush and every digitizer sample, in input order. */
data class InkStroke(val brush: InkBrush, val samples: List<StylusSample>) {
    init {
        require(samples.isNotEmpty()) { "A stroke has at least one sample" }
    }
}
