package app.bestnote.engine.mathtype

/** TeX's spacing class of an atom (The TeXbook, chapter 17): decides the space between neighbours. */
enum class AtomKind { Ord, Op, Bin, Rel, Open, Close, Punct, Inner }

/** Alphabet of letters and digits (`\mathbf`, `\mathbb`…). [Default] writes Latin and lowercase Greek letters in italic. */
enum class Alphabet { Default, Upright, Italic, Bold, BoldItalic, Script, Fraktur, DoubleStruck }

/** Where something went wrong in the source: characters [start] until [end] (exclusive). */
data class LatexError(val start: Int, val end: Int, val message: String)

/** The parsed formula. Every node is drawn; problems are reported in [LatexError]s, never thrown. */
sealed interface MathNode {
    /** One symbol (usually one code point, e.g. "x", "∑", "≤"). */
    data class Sym(val text: String, val kind: AtomKind, val alphabet: Alphabet = Alphabet.Default) : MathNode

    /** A braced group or argument: a list of atoms laid out as one Ord atom ([kind] for `\mathrel{…}` and friends). */
    data class Row(val items: List<MathNode>, val kind: AtomKind = AtomKind.Ord) : MathNode

    /** Upright words (`\text{…}`); characters the math font lacks (Arabic, Hebrew…) use the device font. */
    data class Text(val text: String, val bold: Boolean = false, val italic: Boolean = false) : MathNode

    /** `\frac`, `\binom`: [rule] false for a stack without a bar; [style] forces display or text size; delimiters around it. */
    data class Frac(
        val num: Row,
        val den: Row,
        val rule: Boolean = true,
        val style: FracStyle = FracStyle.Auto,
        val left: String = "",
        val right: String = "",
    ) : MathNode

    enum class FracStyle { Auto, Display, Text }

    data class Radical(val body: Row, val index: Row? = null) : MathNode

    data class Scripts(val base: MathNode?, val sup: Row?, val sub: Row?) : MathNode

    /**
     * A large operator (∑, ∫) when [name] is null, or an operator name (lim, sin) written upright.
     * [limits]: null = TeX's default (limits above and below in display style for ∑ and lim, never for ∫ and sin).
     */
    data class BigOp(val symbol: String, val name: Boolean, val limitsByDefault: Boolean, val limits: Boolean? = null) : MathNode

    /** `\left( … \middle| … \right)`: [parts] separated by [middles]; "" is the empty delimiter `.`. */
    data class Fenced(val left: String, val parts: List<Row>, val middles: List<String>, val right: String) : MathNode

    /** `\big(`, `\Bigg]`…: a delimiter of fixed [size] (1..4). */
    data class BigDelim(val delim: String, val size: Int, val kind: AtomKind) : MathNode

    /** An accent over [body]; [stretchy] accents (widehat, overrightarrow) grow with it. */
    data class Accent(val accent: Int, val body: Row, val stretchy: Boolean) : MathNode

    /** `\overline` ([over] true) and `\underline`. */
    data class Line(val body: Row, val over: Boolean) : MathNode

    /** `\overbrace{…}^{label}` / `\underbrace{…}_{label}`. */
    data class Brace(val body: Row, val over: Boolean, val label: Row?) : MathNode

    /** `\overset`, `\underset`, `\stackrel`: [base] with small [over] / [under]. */
    data class Stack(val base: Row, val over: Row?, val under: Row?, val kind: AtomKind?) : MathNode

    /** Environments (matrix, cases, aligned…) and a formula of several lines. */
    data class Matrix(val rows: List<List<Row>>, val kind: MatrixKind, val left: String = "", val right: String = "", val columns: String = "") : MathNode

    enum class MatrixKind { Matrix, SmallMatrix, Cases, Array, Aligned, Gathered }

    /** Explicit space in mu (18 mu = 1 em). */
    data class Space(val mu: Float) : MathNode

    /** `\displaystyle` and friends: [body] is the rest of the group. */
    data class Styled(val level: Int, val body: Row) : MathNode

    data class Boxed(val body: Row) : MathNode

    /** `\cancel` (slash), `\bcancel` (back slash), `\xcancel` (both), `\cancelto{to}{body}`: a strike line over [body]. */
    data class Cancel(val body: Row, val slash: Boolean, val back: Boolean, val to: Row? = null) : MathNode

    /** Takes the room of [body] without drawing it. */
    data class Phantom(val body: Row, val width: Boolean = true, val height: Boolean = true) : MathNode

    /** Something that could not be read, drawn as its source text in the error colour. */
    data class Error(val source: String) : MathNode
}

/** A parse: the formula and what was wrong with the source. */
class ParsedLatex(val root: MathNode, val errors: List<LatexError>)
