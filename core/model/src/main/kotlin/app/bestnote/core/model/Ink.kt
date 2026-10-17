package app.bestnote.core.model

/**
 * How a stored stroke is drawn. Mirrors what the canvas passes to the brush catalog
 * (tool, pen style, colour, size), so a stroke can be re-created without Android types.
 *
 * @property colorArgb packed 0xAARRGGBB.
 * @property size base brush size in page points.
 * @property family versioned brush-family key the stroke was drawn with (e.g. `fountain.v1`), so
 *   strokes keep rendering identically after brush tuning; null for strokes stored without one.
 * @property group id shared by the strokes (and elements) grouped together on the page, or null (CANVAS-14).
 * @property fillArgb packed 0xAARRGGBB fill of the shape a closed stroke outlines (INK-11; alpha is the fill's opacity); null for none.
 * @property shape what the stroke is when it is a clean shape, and what its ends are glued to (CANVAS-17); null for plain ink.
 */
data class InkBrush(
    val tool: Tool,
    val penStyle: PenStyle = PenStyle.Fountain,
    val colorArgb: Int = 0xFF1F1F1F.toInt(),
    val size: Float,
    val family: String? = null,
    val audio: AudioSync? = null,
    val fillArgb: Int? = null,
    val group: String? = null,
    val shape: ShapeInfo? = null,
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

    /** The recording moment this stroke was written at, if any. */
    val audio: AudioSync? get() = brush.audio
}
