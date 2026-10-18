package app.bestnote.core.model

/**
 * A typeset equation on the page (MATH-01): [latex] typeset at [sizePt] points per em in [colorArgb]. The
 * frame is the typeset formula plus a small margin; the formula is drawn scaled to fit it, so resizing the
 * frame (which also scales [sizePt]) keeps the two in step. Rotation and locking work like any element.
 */
data class EquationElement(
    override val id: String,
    override val cx: Float,
    override val cy: Float,
    override val width: Float,
    override val height: Float,
    val latex: String,
    val sizePt: Float = DEFAULT_SIZE_PT,
    val colorArgb: Int = DEFAULT_COLOR,
    override val rotation: Float = 0f,
    override val locked: Boolean = false,
    override val group: String? = null,
    /** True for a displayed formula (large operators, limits above and below); false for an inline one. */
    val display: Boolean = true,
    /** How the formula is drawn: real typeset maths, or a handwriting-like look. */
    val style: EquationStyle = EquationStyle.Typeset,
) : PageElement {
    init {
        require(width > 0f && height > 0f) { "Equation frame must be positive" }
        require(sizePt > 0f) { "Equation size must be positive" }
    }

    override fun placed(cx: Float, cy: Float, width: Float, height: Float, rotation: Float) =
        copy(cx = cx, cy = cy, width = width, height = height, rotation = rotation)

    override fun withLocked(locked: Boolean) = copy(locked = locked)
    override fun withGroup(group: String?) = copy(group = group)
    override fun withId(id: String) = copy(id = id)

    companion object {
        const val DEFAULT_SIZE_PT = 24f
        const val MIN_SIZE_PT = 6f
        const val MAX_SIZE_PT = 240f
        const val DEFAULT_COLOR = 0xFF1F1F1F.toInt()

        /** Margin around the typeset formula inside the frame, in em. */
        const val MARGIN_EM = 0.25f
    }
}

/** How equations are drawn: [Handwritten] strokes like a hand, or [Typeset] TeX-quality maths (STIX Two Math). */
enum class EquationStyle { Handwritten, Typeset }
