package app.bestnote.engine.canvas

/** What the eraser removes (CANVAS-01, CANVAS-02). */
enum class EraserMode {
    /** Whole strokes the eraser touches. */
    Stroke,

    /** Only the ink under the eraser: strokes are split along its path. */
    Pixel,

    /** Whole highlighter strokes the eraser touches; pen and pencil ink stays. */
    HighlighterOnly,
}
