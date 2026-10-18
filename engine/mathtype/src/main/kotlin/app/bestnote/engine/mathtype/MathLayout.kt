package app.bestnote.engine.mathtype

/** How a part of a formula is drawn: normally, in the error colour, or as a faint empty-slot placeholder. */
enum class Tone { Normal, Error, Placeholder }

/**
 * One drawing instruction of a typeset formula, in points. y grows downward; the formula's baseline is
 * y = 0 and its left edge x = 0 (so ascent is above, at negative y).
 */
sealed interface MathOp {
    val tone: Tone

    /** [glyph]'s outline with its origin at ([x], [y]), scaled by [scale] points per font unit (font y is up). */
    class GlyphOp(val glyph: Glyph, val x: Float, val y: Float, val scale: Float, override val tone: Tone) : MathOp

    /** A filled rectangle (fraction bars, radical overbars, box frames). */
    class RuleOp(val x: Float, val top: Float, val width: Float, val height: Float, override val tone: Tone) : MathOp

    /** A stroked line from ([x1], [y1]) to ([x2], [y2]) with [thickness] (cancel strikes). */
    class LineOp(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val thickness: Float, override val tone: Tone) : MathOp

    /** A stroked polyline ([points] = x0, y0, x1, y1…) with round joins: handwritten ink. */
    class PolyOp(val points: FloatArray, val thickness: Float, override val tone: Tone) : MathOp

    /** Words the math font cannot draw (Arabic, Hebrew…): the device font draws them, baseline at [y]. */
    class TextOp(val text: String, val x: Float, val y: Float, val sizePt: Float, val bold: Boolean, val italic: Boolean, override val tone: Tone, val rtl: Boolean = false) : MathOp
}

/** A typeset formula: [width] by [ascent] above and [descent] below the baseline, in points. */
class MathLayout(val width: Float, val ascent: Float, val descent: Float, val ops: List<MathOp>, val errors: List<LatexError>) {
    val height: Float get() = ascent + descent

    val hasErrors: Boolean get() = errors.isNotEmpty()
}

/** Measures words drawn with the device font (see [MathOp.TextOp]); the Android side passes the real one. */
fun interface TextMeasurer {
    /** Width, ascent and descent (all positive, points) of [text] at [sizePt]. */
    fun measure(text: String, sizePt: Float, bold: Boolean, italic: Boolean): FloatArray

    companion object {
        /** An estimate for tests and pure-JVM callers. */
        @Suppress("MagicNumber")
        val Approximate = TextMeasurer { text, size, _, _ -> floatArrayOf(text.codePointCount(0, text.length) * size * 0.55f, size * 0.72f, size * 0.24f) }
    }
}
