package app.bestnote.core.model

/** The active editing tool in the toolbar. */
enum class Tool { Pen, Pencil, Highlighter, Eraser, Lasso;

    /** True for tools that lay down ink (as opposed to erasing or selecting). */
    val inks: Boolean get() = this == Pen || this == Pencil || this == Highlighter
}

/** Nib style of the [Tool.Pen]. */
enum class PenStyle { Fountain, Ball, Brush }

/**
 * One digitizer sample in view/page coordinates.
 *
 * @property pressure normalized 0..1 (fingers and mice usually report a constant).
 * @property tiltRad 0 = perpendicular to the screen, PI/2 = flat; 0 when unsupported.
 * @property orientationRad direction the stylus points, -PI..PI; 0 when unsupported.
 */
data class StylusSample(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tiltRad: Float,
    val orientationRad: Float,
    val timeMillis: Long,
)
