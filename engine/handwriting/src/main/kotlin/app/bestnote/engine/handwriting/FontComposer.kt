package app.bestnote.engine.handwriting

import app.bestnote.core.model.EquationStyle
import app.bestnote.core.model.CalloutTone
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.PageBlock
import app.bestnote.core.model.StylusSample
import app.bestnote.core.model.Tool
import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.BlockMeasurer
import app.bestnote.engine.layout.Decoration
import app.bestnote.engine.layout.FlowItem
import app.bestnote.engine.layout.HandFont
import app.bestnote.engine.layout.NotebookPalette
import app.bestnote.engine.layout.NoteRole
import app.bestnote.engine.layout.RoleStyle
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.StyleTemplate
import app.bestnote.engine.layout.WritingScript
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Text metrics of the bundled handwriting fonts (Android: Paint; tests: java.awt). Sizes in points. */
interface TextMeasure {
    fun width(text: String, font: HandFont, sizePt: Float): Float

    /** Ascent above the baseline, as a fraction of the size. */
    fun ascent(font: HandFont): Float

    /** Descent below the baseline, as a fraction of the size. */
    fun descent(font: HandFont): Float

    /**
     * The real ink of [text] at [sizePt], in points: above (`[0]`) and below (`[1]`) the baseline, and how
     * far it sticks out left of the pen's start (`[2]`) and right of start + [width] (`[3]`), e.g. a
     * hand's swash on a first or last letter. Null when unknown (the layout then estimates the height
     * from [ascent] and [descent] and assumes no overhang).
     */
    fun ink(text: String, font: HandFont, sizePt: Float): FloatArray? = null
}

/**
 * One piece of typed handwriting: [text] written in [font] at [sizePt], its box starting at [left]
 * and [width] wide, sitting on [baseline]. [rtl] text reads right to left inside its box.
 */
data class TypeRun(
    val text: String,
    val left: Float,
    val baseline: Float,
    val width: Float,
    val sizePt: Float,
    val color: Int,
    val font: HandFont,
    val rtl: Boolean,
    val block: String,
    /** The row of [block] that wrote it (layout QA: ink of different rows must never touch); -1 = unknown. */
    val part: Int = -1,
)

/** A picture on the page (AI-12: a board drawing that could not be redrawn): notebook image [asset], its box in page points. */
data class TypeImage(val asset: String, val left: Float, val top: Float, val width: Float, val height: Float, val srcWidth: Int, val srcHeight: Int, val block: String)

/** A page of typed notes: text runs, plus pen strokes for boxes, underlines, arrows, fraction bars and drawings, typeset [equations] and [images]. */
class TypesetPage(
    val runs: List<TypeRun>,
    val ink: List<InkStroke>,
    val equations: List<EquationRun> = emptyList(),
    val images: List<TypeImage> = emptyList(),
    /** Per [ink] stroke: the element that drew it (null: highlighter marks, unknown) and its row (-1 unknown). For layout QA. */
    val inkOwners: List<Pair<String?, Int>> = emptyList(),
)

/** A typeset formula ([latex]) whose ink box sits on [baseline] from [left], [ascent] above and [descent] below it; [block] and its row [part] wrote it (layout QA). */
data class EquationRun(
    val latex: String,
    val left: Float,
    val baseline: Float,
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val sizePt: Float,
    val color: Int,
    val boxed: Boolean = false,
    val block: String = "",
    val part: Int = -1,
) {
    /** The page element for this formula: a typeset [app.bestnote.core.model.EquationElement] whose frame is the formula plus its margin. */
    fun toElement(id: String): app.bestnote.core.model.EquationElement {
        val m = sizePt * app.bestnote.core.model.EquationElement.MARGIN_EM
        val h = ascent + descent
        return app.bestnote.core.model.EquationElement(
            id = id, cx = left + width / 2, cy = baseline - ascent + h / 2, width = (width + 2 * m).coerceAtLeast(sizePt), height = (h + 2 * m).coerceAtLeast(sizePt),
            latex = latex, sizePt = sizePt.coerceIn(app.bestnote.core.model.EquationElement.MIN_SIZE_PT, app.bestnote.core.model.EquationElement.MAX_SIZE_PT),
            colorArgb = color,
        )
    }
}

class TypesetPages(val pages: List<TypesetPage>) {
    val elementCount: Int get() = pages.sumOf { it.runs.size + it.ink.size }
}

/**
 * Smart Board output written with a handwriting FONT (the default; [PageComposer] draws synthetic
 * strokes instead). Same ruled-line model: every line of text sits on a ruled line inside the
 * column, the same rows drive [measurer] (for [app.bestnote.engine.layout.RuledFlow]) and the
 * writing. Every role gets its colour, size and decoration from the [StyleTemplate].
 *
 * Bidi: each line is split into same-direction runs (Arabic right to left; Latin, numbers and maths
 * left to right) laid in reading order from the line's start; inline `\( … \)` maths is laid out as
 * real maths (fractions, scripts, roots) made of small text runs and pen strokes.
 */
class FontComposer(
    internal val paper: RuledPaper,
    internal val style: StyleTemplate,
    internal val measure: TextMeasure,
    /** The page's direction (margin side, maths alignment); each text block also follows its own first letter. */
    internal val pageRtl: Boolean = false,
    internal val penColor: Int = INK,
    internal val seed: Long = 20_261_016L,
) {
    internal val gap = paper.lineGap

    /** Direction of the block being laid out: its own first strong letter, else the page's. */
    internal var rtl = pageRtl

    /** Script for words the page adds when a block has none of its own (maths-only proofs). */
    internal val pageScript = if (pageRtl) WritingScript.Arabic else WritingScript.Latin

    /** Script of the block being laid out, for the words a student adds ("Definition:", "Solution:"). */
    internal var script = WritingScript.Latin

    internal fun textOf(b: PageBlock): String = when (b) {
        is PageBlock.Heading -> b.text
        is PageBlock.Paragraph -> b.text
        is PageBlock.Bullets -> b.items.joinToString(" ")
        is PageBlock.Callout -> (b.title ?: "") + " " + b.body
        is PageBlock.MarginNote -> b.text
        is PageBlock.Proof -> b.steps.joinToString(" ") { (it.text ?: "") + " " + (it.justification ?: "") }
        is PageBlock.Math, is PageBlock.Diagram -> ""
    }

    /**
     * A block's direction: right to left when it has Hebrew or Arabic letters and either most of its
     * letters are, or the page is (a Hebrew sentence that starts with an English term stays a Hebrew
     * line); left to right when it has none; the page's direction when it has no letters (maths).
     */
    internal fun directionOf(b: PageBlock): Boolean {
        val text = textOf(b)
        if (text.none(Char::isLetter)) return pageRtl
        if (!WritingScript.hasRtl(text)) return false
        return pageRtl || WritingScript.mostlyRtl(text)
    }
    internal val body = gap * style.bodyScale
    internal val inlineTex = ArrayList<String>()

    internal fun hand(page: Int) = Hand(Rand(seed * 31 + page), xh = gap * 0.3f, unitsPerMm = PageComposer.UNITS_PER_MM, jitter = 0.35f)

    /** Collects one page's output. */
    internal inner class Sheet(page: Int) {
        val runs = ArrayList<TypeRun>()
        val equations = ArrayList<EquationRun>()
        internal val h = hand(page)
        val ink = InkBuilder(h, PenChoice(colorArgb = penColor))
        val shapes = ShapeWriter(h, ink)
        val marks = ArrayList<InkStroke>() // highlighter strokes, drawn under the text
        val images = ArrayList<TypeImage>()
        var block: String = ""

        /** The row being drawn (see [TypeRun.part]). */
        var part: Int = -1
            set(v) {
                field = v
                ink.part = v
            }

        fun text(t: String, left: Float, baseline: Float, width: Float, size: Float, color: Int, font: HandFont, rtl: Boolean) {
            if (t.isNotBlank()) runs += TypeRun(t, left, baseline, width, size * scaleOf(font), color, font, rtl, block, part)
        }

        fun highlight(x0: Float, x1: Float, baseline: Float, size: Float) {
            val y = baseline - 0.28f * size
            val pts = listOf(StylusSample(min(x0, x1) - 2f, y, 1f, 0f, 0f, 0L), StylusSample(max(x0, x1) + 2f, y, 1f, 0f, 0f, 120L))
            marks += InkStroke(InkBrush(Tool.Highlighter, colorArgb = style.highlight.toInt(), size = size * 0.75f), pts)
        }
    }

    /**
     * One row of writing: whole ruled [lines]. [blank] rows are air (dropped at a column's top in the
     * packed layout); a [glue] row is never separated from the row after it (a pen box drawn by a
     * later row around it).
     */
    internal class Row(val lines: Int, val blank: Boolean = false, val glue: Boolean = false, val draw: Sheet.(left: Float, right: Float, baseline: Float) -> Unit)

    internal fun blankRow() = Row(1, blank = true) { _, _, _ -> }

    /**
     * The real ink of [row] written on a baseline at 0 in a column [width] wide: text above (`[0]`) and
     * below (`[1]`) the baseline, pen strokes above (`[2]`) and below (`[3]`); NaN where there is none.
     * Measured by drawing the row on a scratch sheet, so it is exactly what [compose] writes.
     */
    internal fun extentOf(row: Row, width: Float): FloatArray {
        val probe = Sheet(0)
        probe.block = PROBE
        probe.ink.block = PROBE
        val (r0, s0) = rtl to script
        row.draw(probe, 0f, width, 0f)
        rtl = r0
        script = s0
        val out = floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN)
        for (r in probe.runs) {
            val e = measure.ink(r.text, r.font, r.sizePt) ?: floatArrayOf(measure.ascent(r.font) * r.sizePt * 0.78f, measure.descent(r.font) * r.sizePt * 0.6f)
            out[0] = maxOf(out[0], e[0] - r.baseline)
            out[1] = maxOf(out[1], r.baseline + e[1])
        }
        // Typeset maths (MATH-02): its box metrics are its ink.
        for (q in probe.equations) {
            out[0] = maxOf(out[0], q.ascent - q.baseline)
            out[1] = maxOf(out[1], q.baseline + q.descent)
        }
        probe.ink.boundsOf(PROBE)?.let { b ->
            out[2] = -b[1] + PEN_HALF
            out[3] = b[3] + PEN_HALF
        }
        return out
    }

    private fun maxOf(a: Float, b: Float) = if (a.isNaN()) b else max(a, b)

    // ------------------------------------------------------------------ public

    fun linesFor(block: PageBlock, width: Float): Int = rows(block, width).sumOf { it.lines }.coerceAtLeast(1)

    fun measurer(blockOf: (String) -> PageBlock?): BlockMeasurer = BlockMeasurer { item: FlowItem, width: Float ->
        val block = blockOf(item.key) ?: return@BlockMeasurer gap
        linesFor(block, width) * gap
    }

    fun compose(placed: List<Pair<Int, PageBlock>>): TypesetPages {
        val pageCount = (placed.maxOfOrNull { it.first } ?: -1) + 1
        return TypesetPages(
            (0 until pageCount).map { page ->
                val sheet = Sheet(page + 1)
                val blocks = placed.filter { it.first == page }.map { it.second }
                val byId = blocks.associateBy { it.id }
                for (b in blocks) {
                    sheet.block = b.id.value
                    var baseline = b.frame.top
                    val blockRows = rows(b, b.frame.width)
                    for (row in blockRows) {
                        baseline += row.lines * gap
                        row.draw(sheet, b.frame.left, b.frame.right, baseline)
                    }
                    if (b is PageBlock.MarginNote && style.marginArrows) b.anchor?.let(byId::get)?.let { marginArrow(sheet, b, it) }
                }
                TypesetPage(sheet.runs, sheet.marks + sheet.ink.build().map { it.stroke }, sheet.equations)
            },
        )
    }

    // ------------------------------------------------------------------ roles

    internal fun colorOf(r: RoleStyle): Int = r.color?.toInt() ?: penColor

    internal fun sizeOf(r: RoleStyle) = body * r.scale

    internal fun usable(width: Float) = width * 0.97f

    internal fun rows(b: PageBlock, width: Float): List<Row> {
        rtl = directionOf(b)
        script = WritingScript.dominant(textOf(b)).takeIf { textOf(b).any(Char::isLetter) } ?: if (pageRtl) pageScript else WritingScript.Latin
        val role = style.role(NoteRole.of(b))
        return when (b) {
            is PageBlock.Heading -> textRows(b.text, width, sizeOf(role), colorOf(role), decoration = role.decoration)
            is PageBlock.Paragraph -> textRows(b.text, width, body, colorOf(role))
            is PageBlock.Bullets -> b.items.withIndex().flatMap { (i, item) ->
                bulletRows(item, if (b.ordered) "${i + 1}." else "•", width, colorOf(role))
            }
            is PageBlock.Callout -> calloutRows(b, role, width)
            is PageBlock.Math -> mathRows(b.latex, width, colorOf(role), boxed = role.decoration == Decoration.Box || b.boxed && NoteRole.of(b) == NoteRole.Result)
            is PageBlock.Proof -> proofRows(b, width)
            is PageBlock.MarginNote -> textRows(b.text, width, sizeOf(role), colorOf(role))
            is PageBlock.Diagram -> diagramRows(b, width)
        }
    }

    /** The font for a run by its script: Arabic → the Arabic hand, Hebrew → the Hebrew hand, everything else → the Latin hand. */
    internal fun fontFor(text: String): HandFont = when (WritingScript.dominant(text)) {
        WritingScript.Arabic -> style.arabicFont
        WritingScript.Hebrew -> style.hebrewFont
        else -> style.latinFont
    }

    internal fun isRtlText(text: String) = WritingScript.mostlyRtl(text)

    /**
     * The device font (Hebrew) is much larger than the handwriting fonts at the same point size, so it is
     * written smaller to match their letter height.
     */
    internal fun scaleOf(font: HandFont) = if (font == HandFont.System) SYSTEM_SCALE else 1f

    /** Width of [text] in [font] as it will be written (the font's size scale applied). */
    internal fun widthOf(text: String, font: HandFont, size: Float) = measure.width(text, font, size * scaleOf(font))

    /** Lines a row of text at [size] needs: its ascent must fit under the line above. */
    internal fun linesForSize(size: Float, font: HandFont = style.latinFont): Int {
        val ascent = measure.ascent(font)
        var extent = min(ascent, MAX_INK_ASCENT) * size * 0.82f
        // A hand with tall marks and deep tails (Aref Ruqaa) needs its whole height between lines.
        if (ascent > TALL_HAND) extent = max(extent, TALL_EXTENT * size - 0.05f * gap)
        return max(1, ceil(extent / gap - 0.02f).toInt())
    }

    // ------------------------------------------------------------------ text lines

    /** A word (or an inline maths token) and its width at a size. */
    internal fun wordWidth(word: String, size: Float): Float =
        InlineMath.segments(word).sumOf { (token, part) ->
            (if (token != null) mathBox(token, size).width else if (part.isEmpty()) 0f else scriptWidth(part, size)).toDouble()
        }.toFloat()

    /** Width of plain text written run by run in each script's own hand. */
    private fun scriptWidth(part: String, size: Float): Float {
        var w = 0f
        var start = 0
        var cur: WritingScript? = null
        for ((i, c) in part.withIndex()) {
            val sc = WritingScript.of(c) ?: continue
            if (cur != null && sc != cur) {
                w += widthOf(part.substring(start, i), fontFor(part.substring(start, i)), size)
                start = i
            }
            cur = sc
        }
        return w + widthOf(part.substring(start), fontFor(part.substring(start)), size)
    }

    internal fun space(size: Float) = widthOf(" ", style.latinFont, size).coerceAtLeast(size * 0.22f)

    /**
     * Greedy line breaking, each candidate line measured as [writeLine] writes it (bidi pieces, each
     * script in its own hand, maths boxes), so a line never comes out wider than [width].
     */
    internal fun wrap(text: String, width: Float, size: Float, firstIndent: Float = 0f): List<String> {
        val lines = ArrayList<String>()
        val cur = ArrayList<String>()
        val narrowest = width - max(firstIndent, 0f)
        for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }.flatMap { breakWord(it, size, narrowest) }) {
            val limit = if (lines.isEmpty()) width - firstIndent else width
            if (cur.isNotEmpty() && lineWidth((cur + word).joinToString(" "), size) > limit) {
                lines += cur.joinToString(" ")
                cur.clear()
            }
            cur += word
        }
        if (cur.isNotEmpty()) lines += cur.joinToString(" ")
        return lines
    }

    /** Width of an encoded line as [writeLine] writes it, with the ink sticking out of its ends. */
    internal fun lineWidth(line: String, size: Float): Float {
        val clean = line.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        if (clean.isEmpty()) return 0f
        val pieces = LineBidi.visual(clean, rtl)
        val (lo, ro) = lineOverhang(pieces, size)
        return lo + ro + pieces.sumOf { p -> (if (p.token != null) mathBox(p.token, size).width else widthOf(p.text, fontOf(p), size)).toDouble() }.toFloat()
    }

    private fun mirrored(t: String) = String(CharArray(t.length) { i -> MIRROR[t[i]] ?: t[i] })

    /** How far the ink of a line's leftmost and rightmost text pieces sticks out of their advance (points, ≥ 0). */
    private fun lineOverhang(pieces: List<LineBidi.Piece>, size: Float): Pair<Float, Float> {
        // A right-to-left run draws brackets mirrored: measure the glyphs it really writes. Maths: its
        // first or last letter in the maths hand (an italic f swashes past its box).
        fun ink(p: LineBidi.Piece?, first: Boolean): FloatArray? {
            if (p == null) return null
            val token = p.token
            val e = if (token != null) {
                val letters = inlineTex.getOrElse(token) { "" }.replace(TEX_COMMAND, "").filter { it.isLetterOrDigit() }
                val c = (if (first) letters.firstOrNull() else letters.lastOrNull()) ?: return null
                measure.ink(c.toString(), mathFont, size)
            } else {
                if (p.text.isBlank()) return null
                measure.ink(if (p.rtl) mirrored(p.text) else p.text, fontOf(p), size * scaleOf(fontOf(p)))
            }
            return e?.takeIf { it.size >= 4 }
        }
        val lo = ink(pieces.firstOrNull(), first = true)?.get(2)?.coerceAtLeast(0f) ?: 0f
        val ro = ink(pieces.lastOrNull(), first = false)?.get(3)?.coerceAtLeast(0f) ?: 0f
        return lo to ro
    }

    /**
     * A word wider than a whole line (a long word in a narrow column or table cell at a big font scale)
     * in pieces that fit, a Latin piece ending in a hyphen; a word with maths is never cut, its maths is written narrower.
     */
    internal fun breakWord(word: String, size: Float, limit: Float): List<String> {
        if (limit <= 0f || word.length < 2 || wordWidth(word, size) <= limit) return listOf(word)
        if (InlineMath.hasToken(word)) {
            // Maths can't be cut: its tokens get narrower (see [mathBox]) so the word fits.
            val k = limit / wordWidth(word, size)
            for ((token, _) in InlineMath.segments(word)) if (token != null) tokenCap[token] = mathBox(token, size).width * k * 0.98f
            return listOf(word)
        }
        val out = ArrayList<String>()
        var start = 0
        while (start < word.length) {
            var end = start + 1
            fun piece(e: Int) = word.substring(start, e) + if (e < word.length && word[e - 1].isLatinLetter()) "-" else ""
            while (end < word.length && wordWidth(piece(end + 1), size) <= limit) end++
            out += piece(end)
            start = end
        }
        return out
    }

    private fun Char.isLatinLetter() = this in 'a'..'z' || this in 'A'..'Z'

    internal fun textRows(
        content: String,
        width: Float,
        size: Float,
        color: Int,
        indent: Float = 0f,
        firstIndent: Float = 0f,
        decoration: Decoration = Decoration.None,
    ): List<Row> {
        val encoded = InlineMath.encode(content, inlineTex)
        // A boxed line's pen box sits around the writing: the writing moves in so the box stays in the column.
        val inset = if (decoration == Decoration.Box) BOX_SIDE * size + PEN_HALF else 0f
        val lines = wrap(encoded, usable(width) - indent - 2 * inset, size, firstIndent)
        val n = linesForSize(size, fontFor(content))
        return lines.flatMapIndexed { i, line ->
            // A tall inline atom (e^{x^2}, a subscript under a superscript) gets the lines it needs.
            val (above, below) = inlineExtent(line, size)
            val row = Row(max(n, above)) { left, right, baseline ->
                val off = indent + inset + if (i == 0) firstIndent else 0f
                val (x0, x1) = writeLine(this, line, left, right, off, baseline, size, color)
                decorate(this, decoration, x0, x1, baseline, size, color)
            }
            if (below) listOf(row, blankRow()) else listOf(row)
        }
    }

    /**
     * Lines an encoded text line needs for its inline maths (ascent under the line above), and whether
     * its descent would reach the next line's writing (then a blank line follows), so nothing overlaps.
     */
    internal fun inlineExtent(line: String, size: Float): Pair<Int, Boolean> {
        if (!InlineMath.hasToken(line)) return 1 to false
        var asc = 0f
        var desc = 0f
        for (word in line.split(' ')) for ((token, _) in InlineMath.segments(word)) {
            if (token == null) continue
            val b = mathBox(token, size)
            asc = max(asc, b.ascent)
            desc = max(desc, b.descent)
        }
        return max(1, ceil((asc + 0.12f * gap) / gap - 0.02f).toInt()) to (desc > INLINE_DESCENT * gap)
    }

    internal fun decorate(sheet: Sheet, d: Decoration, x0: Float, x1: Float, baseline: Float, size: Float, color: Int) {
        val lo = min(x0, x1)
        val hi = max(x0, x1)
        when (d) {
            Decoration.None -> Unit
            Decoration.Underline -> sheet.shapes.underline(lo, hi, baseline + 0.22f * size, color)
            Decoration.DoubleUnderline -> {
                sheet.shapes.underline(lo, hi, baseline + 0.2f * size, color)
                sheet.shapes.underline(lo, hi, baseline + 0.34f * size, color)
            }
            Decoration.Highlight -> sheet.highlight(lo, hi, baseline, size)
            Decoration.Box -> sheet.shapes.box(lo - BOX_SIDE * size, baseline - 0.85f * size, hi + BOX_SIDE * size, baseline + 0.3f * size, color)
        }
    }

    /**
     * Writes one line from the column's start ([left] for LTR, [right] for RTL) plus [indent].
     * @return the x range the writing covers.
     */
    internal fun writeLine(sheet: Sheet, line: String, left: Float, right: Float, indent: Float, baseline: Float, size: Float, color: Int): Pair<Float, Float> {
        val clean = line.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        if (clean.isEmpty()) return left to left
        // Unicode bidi: pieces in visual order, each one direction and one script (its own font).
        val pieces = LineBidi.visual(clean, rtl)
        val widths = pieces.map { p -> if (p.token != null) mathBox(p.token, size).width else widthOf(p.text, fontOf(p), size) }
        val total = widths.sum()
        // A swash sticking out of the line's first letter stays inside the column (the line start moves in).
        val (lo, ro) = lineOverhang(pieces, size)
        val start = if (rtl) right - indent - ro else left + indent + lo
        var x = if (rtl) start - total else start
        val x0 = x
        for ((i, p) in pieces.withIndex()) {
            val w = widths[i]
            if (p.token != null) mathBox(p.token, size).draw(sheet, x, baseline, color)
            else sheet.text(p.text, x, baseline, w, size, color, fontOf(p), p.rtl)
            x += w
        }
        return x0 to x
    }

    /** The hand for a bidi piece: its own script's, or the line's for punctuation and spaces. */
    private fun fontOf(p: LineBidi.Piece): HandFont = when (p.script) {
        WritingScript.Arabic -> style.arabicFont
        WritingScript.Hebrew -> style.hebrewFont
        else -> style.latinFont
    }

    internal fun pieceWidth(piece: String, size: Float) = if (InlineMath.hasToken(piece)) wordWidth(piece, size) else widthOf(piece, fontFor(piece), size)

    internal fun writePiece(sheet: Sheet, piece: String, left: Float, baseline: Float, width: Float, size: Float, color: Int, rtl: Boolean) {
        if (!InlineMath.hasToken(piece)) {
            sheet.text(piece, left, baseline, width, size, color, fontFor(piece), rtl || isRtlText(piece))
            return
        }
        var x = left
        for ((token, part) in InlineMath.segments(piece)) {
            if (token != null) {
                val box = mathBox(token, size)
                box.draw(sheet, x, baseline, color)
                x += box.width
            } else if (part.isNotEmpty()) {
                val w = widthOf(part, fontFor(part), size)
                sheet.text(part, x, baseline, w, size, color, fontFor(part), isRtlText(part))
                x += w
            }
        }
    }

    internal fun bulletRows(item: String, marker: String, width: Float, color: Int): List<Row> {
        val indent = widthOf("$marker ", fontFor(marker), body) + 0.3f * body
        val rows = textRows(item, width, body, color, indent = indent)
        if (rows.isEmpty()) return rows
        val first = rows[0]
        return listOf(
            Row(first.lines) { left, right, baseline ->
                val mw = widthOf(marker, fontFor(marker), body)
                val x = if (rtl) right - 0.15f * body - mw else left + 0.15f * body
                text(marker, x, baseline, mw, body, color, fontFor(marker), false)
                first.draw(this, left, right, baseline)
            },
        ) + rows.drop(1)
    }

    // ------------------------------------------------------------------ callouts, proofs

    internal fun label(tone: CalloutTone): String = when (script) {
        WritingScript.Hebrew -> when (tone) {
            CalloutTone.Definition -> "הגדרה"
            CalloutTone.Theorem -> "משפט"
            CalloutTone.KeyResult -> "תוצאה"
            CalloutTone.Warning -> "זהירות"
            CalloutTone.Example -> "דוגמה"
            CalloutTone.Summary -> "סיכום"
            CalloutTone.Info -> "הערה"
        }
        WritingScript.Arabic -> when (tone) {
            CalloutTone.Definition -> "تعريف"
            CalloutTone.Theorem -> "مبرهنة"
            CalloutTone.KeyResult -> "نتيجة"
            CalloutTone.Warning -> "تنبيه"
            CalloutTone.Example -> "مثال"
            CalloutTone.Summary -> "خلاصة"
            CalloutTone.Info -> "ملاحظة"
        }
        else -> when (tone) {
            CalloutTone.Definition -> "Definition"
            CalloutTone.Theorem -> "Theorem"
            CalloutTone.KeyResult -> "Result"
            CalloutTone.Warning -> "Careful"
            CalloutTone.Example -> "Example"
            CalloutTone.Summary -> "Summary"
            CalloutTone.Info -> "Note"
        }
    }

    internal fun solutionWord() = when (script) {
        WritingScript.Hebrew -> "פתרון"
        WritingScript.Arabic -> "الحل"
        else -> "Solution"
    }

    internal fun calloutRows(b: PageBlock.Callout, role: RoleStyle, width: Float): List<Row> {
        val color = colorOf(role)
        val text = (b.title ?: label(b.tone)).trimEnd(':', '：') + ":"
        val labelW = wordWidth(InlineMath.encode(text, inlineTex), body).let { widthOf(text, fontFor(text), body).coerceAtLeast(it) }
        val bodyIndent = labelW + space(body) * 1.2f
        val fitsAfter = bodyIndent < usable(width) * 0.45f
        val bodyRows = textRows(b.body, width, body, color, firstIndent = if (fitsAfter) bodyIndent else 0f)
        val labelRow = Row(max(1, bodyRows.firstOrNull()?.lines ?: 1)) { left, right, baseline ->
            val (x0, x1) = writeLine(this, text, left, right, 0f, baseline, body, color)
            decorate(this, role.decoration, x0, x1, baseline, body, color)
            if (fitsAfter && bodyRows.isNotEmpty()) bodyRows[0].draw(this, left, right, baseline)
        }
        // A label too long to share its line goes on lines of its own, wrapped like any text.
        return if (fitsAfter) listOf(labelRow) + bodyRows.drop(1) else textRows(text, width, body, color, decoration = role.decoration) + bodyRows
    }

    internal fun proofRows(b: PageBlock.Proof, width: Float): List<Row> {
        val head = style.role(NoteRole.Subheading)
        val stepRole = style.role(NoteRole.Step)
        val note = style.role(NoteRole.Margin)
        val out = ArrayList<Row>()
        val title = (b.title ?: solutionWord()).trimEnd(':') + ":"
        out += textRows(title, width, body, colorOf(head), decoration = Decoration.Underline)
        val ink = colorOf(stepRole)
        val indent = 0.8f * body
        for (s in b.steps) {
            val why = s.justification?.let { "($it)" }
            val whySize = body * 0.85f
            val whyW = why?.let { widthOf(it, fontFor(it), whySize) } ?: 0f
            val math = s.latex?.let { mathRows(it, width, ink, boxed = false, indent = indent) }.orEmpty()
            val prose = s.text?.let { textRows(it, width, body, ink, indent = indent) }.orEmpty()
            val formula = math.lastOrNull { !it.blank }
            val inline = why != null && prose.isEmpty() && math.count { !it.blank } == 1 && s.latex?.let { tex ->
                indent + displayWidth(tex) + 2f * body + whyW <= usable(width)
            } == true
            if (inline && formula != null) {
                out += math.map { row ->
                    if (row !== formula) row else Row(row.lines) { left, right, baseline ->
                        row.draw(this, left, right, baseline)
                        val x = if (rtl) left else right - whyW
                        text(why!!, x, baseline, whyW, whySize, colorOf(note), fontFor(why), isRtlText(why))
                    }
                }
            } else {
                out += math + prose
                why?.let { out += textRows(it, width, whySize, colorOf(note), indent = indent * 3) }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ display maths

    /** Display maths size relative to the body text (the packed layout writes maths more compactly). */
    internal var displayScale = DISPLAY_SCALE

    /** Lines an inline formula may reach up into (2 on fine grid lines: a fraction in a sentence stays readable). */
    internal var inlineLines = 1

    /** Indent of display maths lines from the column's start. */
    internal var mathIndent = 1.2f * gap

    internal fun displaySize() = body * displayScale

    internal fun displayWidth(tex: String) = MathParser.parse(tex).maxOfOrNull { layout(it, displaySize()).width } ?: 0f

    /** MATH-02: formulas drawn as real typeset maths (equation elements) instead of handwriting. */
    private val typesetter by lazy { app.bestnote.engine.mathtype.MathTypesetter(app.bestnote.engine.mathtype.MathFont.stixTwo) }

    /** One box per line of [latex], in the template's equation style, shrunk to fit [avail]. */
    internal fun displayBoxes(latex: String, avail: Float, size: Float): List<MBox> {
        if (style.equationStyle == EquationStyle.Typeset) {
            return MathParser.rowsOf(latex).flatMap { raw ->
                val row = if ("\\begin" in raw) raw else raw.replace("&", " ")
                fitTypeset(row, size, avail).map { (piece, sz) ->
                    val tex = typesetter.typeset(piece, sz, display = true)
                    MBox(tex.width, tex.ascent, tex.descent) { x, baseline, color ->
                        equations += EquationRun(piece, x, baseline, tex.width, tex.ascent, tex.descent, sz, color, block = block, part = part)
                    }
                }
            }
        }
        return MathParser.parse(latex).flatMap { nodes -> fitLine(nodes, size, avail) }
    }

    /**
     * A typeset display row in [avail] points, as [fitLine] does for handwritten maths: whole when it fits
     * or a little smaller is enough, else cut before its top-level relations (then binary + and −), all
     * pieces at one size. Returns (LaTeX, size) per line.
     */
    private fun fitTypeset(row: String, size: Float, avail: Float): List<Pair<String, Float>> {
        fun w(t: String, sz: Float) = typesetter.typeset(t, sz, display = true).width
        val whole = w(row, size)
        if (whole <= avail || whole <= 0f || avail <= 0f) return listOf(row to size)
        // (An environment, cases or a matrix, is never cut: it only shrinks.)
        if (avail / whole >= MIN_SHRINK || "\\begin" in row) return listOf(row to size * min(1f, max(MIN_FIT, avail / whole)))
        val small = size * MIN_SHRINK
        fun pack(parts: List<String>): List<String> {
            val out = ArrayList<String>()
            var cur = ""
            for (p in parts) cur = if (cur.isEmpty()) p else if (w(cur + p, small) <= avail) cur + p else { out += cur; p }
            if (cur.isNotEmpty()) out += cur
            return out
        }
        var pieces = pack(texCut(row, "=<>").ifEmpty { listOf(row) })
        if (pieces.any { w(it, small) > avail }) pieces = pieces.flatMap { p -> if (w(p, small) <= avail) listOf(p) else pack(texCut(p, "+-")) }
        if (pieces.size < 2) return listOf(row to size * max(MIN_FIT, avail / whole))
        val widest = pieces.maxOf { w(it, size) }
        val sz = size * min(1f, max(MIN_FIT, avail / widest))
        return pieces.map { it to sz }
    }

    /** [tex] cut before every top-level (outside braces and \left…\right) character of [ops]; a sign right after an operator or bracket is not cut. */
    private fun texCut(tex: String, ops: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var from = 0
        var i = 0
        while (i < tex.length) {
            val c = tex[i]
            when {
                tex.startsWith("\\left", i) -> { depth++; i += 5; continue }
                tex.startsWith("\\right", i) -> { depth--; i += 6; continue }
                c == '\\' -> { i += 2; continue }
                c == '{' -> depth++
                c == '}' -> depth--
                depth == 0 && c in ops && i > from -> {
                    val prev = tex.substring(from, i).trimEnd().lastOrNull()
                    if (prev != null && prev !in "=<>+-({[,") {
                        out += tex.substring(from, i)
                        from = i
                    }
                }
            }
            i++
        }
        out += tex.substring(from)
        return out.filter { it.isNotBlank() }
    }

    internal fun mathRows(latex: String, width: Float, color: Int, boxed: Boolean, indent: Float = mathIndent, scale: Float = 1f): List<Row> {
        val pad = if (boxed) 0.45f * gap else 0f
        val avail = usable(width) - indent - 2 * pad
        val size = displaySize() * scale
        val boxes = displayBoxes(latex, avail, size)
        val rows = boxes.mapIndexed { i, box ->
            // Inside a box the rows are glued (nothing spaces them later): a broken formula's next line clears the one above.
            val prevDown = if (boxed && i > 0) boxes[i - 1].descent + 0.06f * gap else 0f
            val lines = max(1, ceil((box.ascent + 0.12f * gap + prevDown) / gap - 0.02f).toInt())
            Row(lines) { left, right, baseline ->
                val x = if (rtl) right - indent - pad - box.width else left + indent + pad
                box.draw(this, x, baseline, color)
            }
        }
        if (rows.isEmpty()) return rows
        if (!boxed) return rows + hangRows(boxes.last().descent)
        val widest = boxes.maxOf { it.width }
        // The box's top clears the first line's REAL ink (a tall letter reaches above the maths metrics).
        val firstUp = extentOf(rows.first(), width).let { e -> listOf(e[0], e[2]).filter { !it.isNaN() }.maxOrNull() } ?: 0f
        val firstTop = max(boxes.first().ascent + 0.25f * gap, firstUp + 0.06f * gap + PEN_HALF)
        val lastDescent = boxes.last().descent
        val total = rows.sumOf { it.lines }
        val below = if (max(lastDescent, 0.1f * gap) + 0.22f * gap > 0.3f * gap) listOf(blankRow()) else emptyList()
        // The box is drawn by the last row around the rows above it: they stay together.
        return listOf(Row(1, blank = true, glue = true) { _, _, _ -> }) + rows.dropLast(1).map { r -> Row(r.lines, r.blank, glue = true, r.draw) } + Row(rows.last().lines) { left, right, baseline ->
            rows.last().draw(this, left, right, baseline)
            val x0 = if (rtl) right - indent - widest - 2 * pad else left + indent
            val top = baseline - (total - rows.first().lines) * gap - firstTop
            val bottom = baseline + max(lastDescent, 0.1f * gap) + 0.2f * gap
            shapes.box(x0, top, x0 + widest + 2 * pad, bottom, color)
        } + below
    }

    /**
     * One display line of maths at [size] in [avail] points: as it is when it fits, a little smaller
     * (down to [MIN_SHRINK]) when that is enough, else broken before its top-level relations (=, <, ⇒…;
     * then before binary + and −, then between any atoms) into lines that each start with their operator, all at one size
     * (never below [MIN_FIT]), so a long formula never runs into the next column or off the page.
     */
    internal fun fitLine(nodes: List<MathParser.Node>, size: Float, avail: Float): List<MBox> {
        val whole = layout(nodes, size)
        if (whole.width <= avail || whole.width <= 0f || avail <= 0f) return listOf(whole)
        if (avail / whole.width >= MIN_SHRINK) return listOf(layout(nodes, size * avail / whole.width))
        val small = size * MIN_SHRINK
        var pieces = pack(cut(nodes) { k -> isRelation(nodes[k]) }, small, avail)
        if (pieces.any { layout(it, small).width > avail }) {
            pieces = pieces.flatMap { p -> if (layout(p, small).width <= avail) listOf(p) else pack(cut(p) { k -> isBinary(p, k) }, small, avail) }
        }
        // Still too wide (no operator to break at): between any two atoms.
        if (pieces.any { layout(it, small).width > avail }) {
            pieces = pieces.flatMap { p -> if (layout(p, small).width <= avail) listOf(p) else pack(cut(p) { true }, small, avail) }
        }
        if (pieces.size < 2) return listOf(layout(nodes, size * max(MIN_FIT, avail / whole.width)))
        val widest = pieces.maxOf { layout(it, size).width }
        val s = size * min(1f, max(MIN_FIT, avail / widest))
        return pieces.map { layout(it, s) }
    }

    private fun isRelation(n: MathParser.Node) = n is MathParser.Text && n.s.length == 1 && n.s[0] in "=<>≤≥≈≠⇒→⇔≡"

    /** A + or − between two terms (not a sign: "= −1", "(−x"). */
    private fun isBinary(nodes: List<MathParser.Node>, k: Int): Boolean {
        val n = nodes[k]
        if (k == 0 || n !is MathParser.Text || n.s !in setOf("+", "-", "−", "±")) return false
        val prev = nodes[k - 1]
        return !(prev is MathParser.Text && (isOp(prev) || prev.s == "(" || prev.s == "[" || prev.s == ","))
    }

    /** [nodes] cut before every index [at] accepts (never at 0). */
    private fun cut(nodes: List<MathParser.Node>, at: (Int) -> Boolean): List<List<MathParser.Node>> {
        val out = ArrayList<List<MathParser.Node>>()
        var from = 0
        for (k in 1 until nodes.size) if (at(k)) {
            out += nodes.subList(from, k)
            from = k
        }
        out += nodes.subList(from, nodes.size)
        return out
    }

    /** Neighbouring [parts] joined while they fit [avail] at [size]. */
    private fun pack(parts: List<List<MathParser.Node>>, size: Float, avail: Float): List<List<MathParser.Node>> {
        val out = ArrayList<List<MathParser.Node>>()
        var cur: List<MathParser.Node> = emptyList()
        for (p in parts) {
            cur = if (cur.isEmpty()) p else if (layout(cur + p, size).width <= avail) cur + p else { out += cur; p }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** Blank lines under a formula whose writing hangs below its line (a fraction, limits under lim). */
    internal fun hangRows(descent: Float): List<Row> {
        val n = if (descent > HANG * gap) ceil((descent - HANG * gap) / gap).toInt().coerceAtLeast(1) else 0
        return List(n) { blankRow() }
    }

    // ------------------------------------------------------------------ maths layout (font)

    /** A laid-out piece of maths: its size and how to draw it from a pen position on a baseline. */
    internal class MBox(val width: Float, val ascent: Float, val descent: Float, val draw: Sheet.(x: Float, baseline: Float, color: Int) -> Unit)

    /** Widest an inline maths token may be written (token → points): set when it would not fit its line. */
    private val tokenCap = HashMap<Int, Float>()

    internal fun mathBox(token: Int, size: Float): MBox {
        val nodes = MathParser.parse(inlineTex.getOrElse(token) { "" }).flatten()
        val b = layout(nodes, size)
        // Inline maths may not hit the lines above and below: shrink tall fractions.
        val tall = minOf(1f, (inlineLines - 0.15f) * gap / b.ascent.coerceAtLeast(0.01f), (0.45f + 0.5f * (inlineLines - 1)) * gap / b.descent.coerceAtLeast(0.01f))
        // Nor be wider than its line (a long formula alone in a table cell): it is written smaller.
        val wide = tokenCap[token]?.let { min(1f, it / b.width.coerceAtLeast(0.01f)) } ?: 1f
        val fit = min(max(0.6f, tall), max(MIN_INLINE_FIT, wide))
        return if (fit < 0.98f) layout(nodes, size * fit) else b
    }

    internal val mathFont get() = style.latinFont

    /** A sub/superscript's size: [factor] of its base, but never smaller than [MIN_SCRIPT_PT] (a script of a script on a dense page). */
    internal fun scriptSize(size: Float, factor: Float) = max(size * factor, min(size, MIN_SCRIPT_PT))

    internal fun isOp(n: MathParser.Node) = n is MathParser.Text && n.s.length == 1 && n.s[0] in "=+−-×·<>≤≥≈≠⇒→±÷"

    internal fun layout(nodes: List<MathParser.Node>, size: Float): MBox {
        // Consecutive plain symbols become one text run ("x + y = 2"), with air around operators.
        val parts = ArrayList<MBox>()
        val run = StringBuilder()
        fun flush() {
            if (run.isEmpty()) return
            parts += textBox(run.toString(), size)
            run.clear()
        }
        for ((k, n) in nodes.withIndex()) {
            when {
                n is MathParser.Text && (n.s == "|" || n.s == "‖") -> {
                    // |x+2|: bars are pen lines (the handwriting font draws "|" as a slash).
                    flush()
                    parts += bar(n.s.length.let { if (n.s == "‖") 2 else 1 }, measure.ascent(mathFont) * size * 0.72f, 0.18f * size, size)
                }
                n is MathParser.Text -> {
                    // A sign ("= −1", "(−x", a leading minus) gets no air; a binary operator does.
                    val prev = nodes.getOrNull(k - 1)
                    val sign = (n.s == "-" || n.s == "+" || n.s == "−") && (prev == null || (prev is MathParser.Text && (isOp(prev) || prev.s == "(" || prev.s == "[" || prev.s == ",")))
                    val op = isOp(n) && !sign
                    if (op && run.isNotEmpty() && run.last() != ' ') run.append(' ')
                    run.append(n.s.replace('-', '−'))
                    if (op && k < nodes.lastIndex) run.append(' ')
                }
                n is MathParser.Space -> when {
                    run.isEmpty() -> parts += MBox(n.em * size * 0.28f, 0f, 0f) { _, _, _ -> }
                    run.last() != ' ' -> run.append(' ')
                }
                n is MathParser.Group -> {
                    flush()
                    parts += layout(n.body, size)
                }
                else -> {
                    flush()
                    parts += node(n, size)
                }
            }
        }
        flush()
        val w = parts.sumOf { it.width.toDouble() }.toFloat()
        val a = max(parts.maxOfOrNull { it.ascent } ?: 0f, measure.ascent(mathFont) * size * 0.7f)
        val d = parts.maxOfOrNull { it.descent } ?: 0f
        return MBox(w, a, d) { x, baseline, color ->
            var cx = x
            for (p in parts) {
                p.draw(this, cx, baseline, color)
                cx += p.width
            }
        }
    }

    /** lim, Σ, max…: the limits written under (and over) the operator, the way it is written by hand. */
    internal fun underLimits(n: MathParser.Scripts, size: Float): MBox {
        val base = node(n.base!!, size)
        val ss = scriptSize(size, 0.6f)
        val sub = layout(n.sub!!, ss)
        val sup = n.sup?.let { layout(it, ss) }
        val w = maxOf(base.width, sub.width, sup?.width ?: 0f) + 0.15f * size
        val gapY = 0.08f * size
        val a = base.ascent + (sup?.let { gapY + it.ascent + it.descent } ?: 0f)
        val d = max(base.descent, 0.1f * size) + gapY + sub.ascent + sub.descent
        return MBox(w, a, d) { x, baseline, color ->
            base.draw(this, x + (w - base.width) / 2, baseline, color)
            sub.draw(this, x + (w - sub.width) / 2, baseline + max(base.descent, 0.1f * size) + gapY + sub.ascent, color)
            sup?.draw(this, x + (w - sup.width) / 2, baseline - base.ascent - gapY - sup.descent, color)
        }
    }

    /** [count] vertical pen bars from [ascent] above the baseline to [descent] below it. */
    internal fun bar(count: Int, ascent: Float, descent: Float, size: Float): MBox {
        val step = 0.14f * size
        val w = 0.3f * size + (count - 1) * step
        return MBox(w, ascent, descent) { x, baseline, color ->
            for (k in 0 until count) {
                val bx = x + 0.15f * size + k * step
                shapes.line(P(bx, baseline - ascent), P(bx, baseline + descent), color, gap = Gap.Letter, ruler = false)
            }
        }
    }

    /**
     * A delimiter [d] ("(", "[", "{", "|", "‖", their closing forms) as tall as ascent+descent; tall ones
     * are pen strokes, short round and square brackets the font's own.
     */
    internal fun delimiter(d: String, ascent: Float, descent: Float, size: Float): MBox {
        if (d.isEmpty()) return MBox(0f, 0f, 0f) { _, _, _ -> }
        if (d == "|" || d == "‖") return bar(if (d == "‖") 2 else 1, ascent, descent, size)
        val h = ascent + descent
        if (h < 1.25f * size && d in listOf("(", ")", "[", "]")) return textBox(d, size)
        val w = when (d) {
            "{", "}" -> 0.42f * size
            else -> 0.36f * size
        }
        val open = d in listOf("(", "[", "{", "⟨", "⌊", "⌈")
        return MBox(w + 0.08f * size, ascent, descent) { x, baseline, color ->
            val top = baseline - ascent
            val bottom = baseline + descent
            val mid = (top + bottom) / 2
            // Points drawn for an opening delimiter, mirrored for a closing one.
            fun px(u: Float) = if (open) x + 0.04f * size + u * w else x + 0.04f * size + (1 - u) * w
            val pts = when (d) {
                "(", ")" -> (0..8).map { k ->
                    val t = k / 8f
                    P(px(0.85f - 0.75f * kotlin.math.sin(Math.PI * t).toFloat()), top + t * (bottom - top))
                }
                "[", "]", "⌊", "⌋", "⌈", "⌉" -> listOf(P(px(0.8f), top), P(px(0.15f), top), P(px(0.15f), bottom), P(px(0.8f), bottom))
                "⟨", "⟩" -> listOf(P(px(0.85f), top), P(px(0.15f), mid), P(px(0.85f), bottom))
                else -> listOf( // { }
                    P(px(0.95f), top), P(px(0.55f), top + 0.06f * h), P(px(0.5f), mid - 0.1f * h), P(px(0.05f), mid),
                    P(px(0.5f), mid + 0.1f * h), P(px(0.55f), bottom - 0.06f * h), P(px(0.95f), bottom),
                )
            }
            shapes.polyline(pts, closed = false, color = color, ruler = false)
        }
    }

    /** Rows of cells (cases, aligned, matrices): columns aligned, centred on the maths axis, with the delimiters. */
    internal fun matrix(n: MathParser.Matrix, size: Float): MBox {
        val cells = n.rows.map { r -> r.map { layout(it, size) } }
        val cols = cells.maxOf { it.size }
        val colW = (0 until cols).map { c -> cells.maxOf { r -> r.getOrNull(c)?.width ?: 0f } }
        val colGap = if (n.alignLeft) 0.25f * size else 0.8f * size
        val rowGap = 0.22f * size
        val heights = cells.map { r -> (r.maxOfOrNull { it.ascent } ?: 0f) to (r.maxOfOrNull { it.descent } ?: 0f) }
        val total = heights.sumOf { (a, d) -> (a + d).toDouble() }.toFloat() + rowGap * (cells.size - 1)
        val axis = 0.3f * size
        val ascent = axis + total / 2
        val descent = total / 2 - axis
        val innerW = colW.sum() + colGap * (cols - 1)
        val l = delimiter(n.left, ascent, descent, size)
        val r = delimiter(n.right, ascent, descent, size)
        val pad = 0.12f * size
        return MBox(l.width + pad + innerW + pad + r.width, ascent, max(descent, 0f)) { x, baseline, color ->
            l.draw(this, x, baseline, color)
            var y = baseline - ascent
            for ((ri, row) in cells.withIndex()) {
                val (a, d) = heights[ri]
                y += a
                var cx = x + l.width + pad
                for ((ci, cell) in row.withIndex()) {
                    val off = if (n.alignLeft) 0f else (colW[ci] - cell.width) / 2
                    cell.draw(this, cx + off, y, color)
                    cx += colW[ci] + colGap
                }
                y += d + rowGap
            }
            r.draw(this, x + l.width + pad + innerW + pad, baseline, color)
        }
    }

    internal fun textBox(t: String, size: Float): MBox {
        val text = t.trimEnd()
        val font = fontFor(text)
        val w = widthOf(text, font, size) + if (t.endsWith(' ')) space(size) else 0f
        val tw = widthOf(text, font, size)
        return MBox(w, measure.ascent(font) * size * 0.78f, measure.descent(font) * size * 0.6f) { x, baseline, color ->
            text(text, x, baseline, tw, size, color, font, false)
        }
    }

    internal fun node(n: MathParser.Node, size: Float): MBox = when (n) {
        is MathParser.Text -> textBox(n.s, size)
        is MathParser.Space -> MBox(n.em * size * 0.28f, 0f, 0f) { _, _, _ -> }
        is MathParser.Group -> layout(n.body, size)
        is MathParser.Deco -> {
            val b = layout(n.body, size)
            val unit = 0.3f * size
            val e = DecoGeometry.extra(n.kind, unit)
            val label = n.label?.takeIf { n.kind == MathParser.DecoKind.UnderBrace || n.kind == MathParser.DecoKind.OverBrace }?.let { layout(it, size * 0.62f) }
            val over = n.kind == MathParser.DecoKind.OverBrace
            val labelH = label?.let { it.ascent + it.descent } ?: 0f
            val w = max(b.width + e.side, label?.width ?: 0f)
            val tail = n.label?.takeIf { n.kind == MathParser.DecoKind.CancelTo }?.let { layout(it, size * 0.62f) }
            MBox(w + (tail?.width ?: 0f), b.ascent + e.up + if (over) labelH else 0f, b.descent + e.down + if (!over) labelH else 0f) { x, baseline, color ->
                val bx = x + (w - b.width) / 2
                b.draw(this, bx, baseline, color)
                DecoGeometry.draw(shapes, n.kind, bx, baseline, b.width, b.ascent, b.descent, unit, color, e)
                label?.let { l ->
                    val ly = if (over) baseline - b.ascent - e.up - l.descent else baseline + b.descent + e.down + l.ascent
                    l.draw(this, x + (w - l.width) / 2, ly, color)
                }
                tail?.draw(this, x + w, baseline - b.ascent, color)
            }
        }
        is MathParser.Frac -> {
            val s = max(size * 0.55f, size * 0.8f)
            val num = layout(n.num, s)
            val den = layout(n.den, s)
            val axis = 0.3f * size
            val pad = 0.12f * size
            val w = max(num.width, den.width) + 0.3f * size
            MBox(w, axis + pad + num.descent + num.ascent, -axis + pad + den.ascent + den.descent) { x, baseline, color ->
                val ay = baseline - axis
                num.draw(this, x + (w - num.width) / 2, ay - pad - num.descent, color)
                shapes.line(P(x + 0.06f * size, ay), P(x + w - 0.06f * size, ay), color, gap = Gap.Letter, ruler = false)
                den.draw(this, x + (w - den.width) / 2, ay + pad + den.ascent, color)
            }
        }
        is MathParser.Scripts -> if ((n.base as? MathParser.Text)?.s in UNDER_LIMITS && n.sub != null) underLimits(n, size) else {
            val base = n.base?.let { node(it, size) } ?: MBox(0f, 0f, 0f) { _, _, _ -> }
            val ss = scriptSize(size, 0.62f)
            val sup = n.sup?.let { layout(it, ss) }
            val sub = n.sub?.let { layout(it, ss) }
            val raise = 0.42f * size
            val drop = 0.22f * size
            val w = base.width + max(sup?.width ?: 0f, sub?.width ?: 0f) + 0.04f * size
            val a = max(base.ascent, sup?.let { raise + it.ascent } ?: 0f)
            val d = max(base.descent, sub?.let { drop + it.descent } ?: 0f)
            MBox(w, a, d) { x, baseline, color ->
                base.draw(this, x, baseline, color)
                val sx = x + base.width + 0.03f * size
                sup?.draw(this, sx, baseline - raise, color)
                sub?.draw(this, sx, baseline + drop, color)
            }
        }
        is MathParser.Sqrt -> {
            val b = layout(n.body, size)
            val sign = 0.55f * size
            val top = b.ascent + 0.12f * size
            MBox(b.width + sign + 0.1f * size, top + 0.05f * size, max(b.descent, 0.1f * size)) { x, baseline, color ->
                val pts = listOf(
                    P(x, baseline - 0.35f * size),
                    P(x + 0.15f * sign, baseline - 0.42f * size),
                    P(x + 0.45f * sign, baseline + 0.08f * size),
                    P(x + 0.9f * sign, baseline - top),
                    P(x + sign + b.width + 0.08f * size, baseline - top),
                )
                shapes.polyline(pts, closed = false, color = color, ruler = false)
                b.draw(this, x + sign + 0.04f * size, baseline, color)
            }
        }
        is MathParser.Delim -> {
            val b = layout(n.body, size)
            val a = max(b.ascent, measure.ascent(mathFont) * size * 0.72f) + 0.05f * size
            val d = max(b.descent, 0.18f * size) + 0.05f * size
            val l = delimiter(n.left, a, d, size)
            val r = delimiter(n.right, a, d, size)
            MBox(l.width + b.width + r.width, a, d) { x, baseline, color ->
                l.draw(this, x, baseline, color)
                b.draw(this, x + l.width, baseline, color)
                r.draw(this, x + l.width + b.width, baseline, color)
            }
        }
        is MathParser.Matrix -> matrix(n, size)
    }

    // ------------------------------------------------------------------ diagrams, margin arrows

    internal fun diagramRows(b: PageBlock.Diagram, width: Float): List<Row> {
        val vb = b.diagram.viewBox
        if (vb.isEmpty) return emptyList()
        val scale = min(usable(width) * 0.8f / vb.width, gap * PageComposer.MAX_DIAGRAM_LINES / vb.height)
        val w = vb.width * scale
        val h = vb.height * scale
        val lines = max(2, ceil((h + 0.8f * gap) / gap).toInt())
        return listOf(
            Row(lines) { left, right, baseline ->
                val top = baseline - lines * gap + 0.5f * gap
                val ox = if (rtl) right - 1.2f * gap - w else left + 1.2f * gap
                fun map(x: Float, y: Float) = P(ox + (x - vb.left) * scale, top + (y - vb.top) * scale)
                for (shape in b.diagram.shapes) drawShape(this, shape, ::map)
            },
        )
    }

    internal fun drawShape(sheet: Sheet, s: VectorShape, map: (Float, Float) -> P) {
        val c = s.style.colorArgb.let { if (it == NotebookPalette.INK || it == 0xFF1F1F1FL) penColor else it.toInt() }
        val shapes = sheet.shapes
        when (s) {
            is VectorShape.Line -> if (s.style.dashed) shapes.dashed(map(s.from.x, s.from.y), map(s.to.x, s.to.y), c) else shapes.line(map(s.from.x, s.from.y), map(s.to.x, s.to.y), c)
            is VectorShape.Arrow -> shapes.arrow(map(s.from.x, s.from.y), map(s.to.x, s.to.y), c)
            is VectorShape.Rect -> {
                val r = s.rect
                val cx = (r.left + r.right) / 2
                val cy = (r.top + r.bottom) / 2
                val rad = Math.toRadians(s.rotationDeg.toDouble())
                val cos = kotlin.math.cos(rad).toFloat()
                val sin = kotlin.math.sin(rad).toFloat()
                val corners = listOf(r.left to r.top, r.right to r.top, r.right to r.bottom, r.left to r.bottom).map { (x, y) ->
                    map(cx + (x - cx) * cos - (y - cy) * sin, cy + (x - cx) * sin + (y - cy) * cos)
                }
                shapes.polyline(corners, closed = true, color = c)
            }
            is VectorShape.Ellipse -> {
                val a = map(s.bounds.left, s.bounds.top)
                val z = map(s.bounds.right, s.bounds.bottom)
                shapes.ellipse((a.x + z.x) / 2, (a.y + z.y) / 2, (z.x - a.x) / 2, (z.y - a.y) / 2, c)
            }
            is VectorShape.Polyline -> shapes.polyline(s.points.map { map(it.x, it.y) }, s.closed, c)
            is VectorShape.Path -> shapes.path(s.d, map, c)
            is VectorShape.Label -> {
                val at = map(s.at.x, s.at.y)
                val size = body * 0.8f
                if (s.latex) {
                    MathParser.parse(s.text).firstOrNull()?.let { layout(it, size).draw(sheet, at.x, at.y, c) }
                } else {
                    val w = widthOf(s.text, fontFor(s.text), size)
                    sheet.text(s.text, at.x, at.y, w, size, c, fontFor(s.text), isRtlText(s.text))
                }
            }
        }
    }

    /**
     * A short green arrow from the margin note to the FIRST line of the block it annotates (the note
     * sits level with that line), ending just past where that line's writing stops — never into the
     * middle of a long derivation.
     */
    internal fun marginArrow(sheet: Sheet, note: PageBlock.MarginNote, anchor: PageBlock) {
        val n = boundsOf(sheet, note.id.value) ?: return
        val a = boundsOf(sheet, anchor.id.value) ?: return
        // The anchor's first written line: runs within a line gap of its top.
        val anchorRuns = sheet.runs.filter { it.block == anchor.id.value }
        val firstBaseline = anchorRuns.minOfOrNull { it.baseline }
        val firstLine = anchorRuns.filter { firstBaseline != null && it.baseline - firstBaseline < 0.6f * gap }
        val noteRuns = sheet.runs.filter { it.block == note.id.value }
        val noteBaseline = noteRuns.minOfOrNull { it.baseline } ?: (note.frame.top + gap)
        val y = firstBaseline?.let { it - 0.3f * gap } ?: (a[1] + 0.5f * gap)
        val fromY = noteBaseline - 0.3f * gap
        val color = colorOf(style.role(NoteRole.Margin))
        if (!pageRtl) {
            val end = firstLine.maxOfOrNull { it.left + it.width } ?: a[2]
            val from = P(n[0] - 0.25f * gap, fromY)
            val to = P(min(end + 0.3f * gap, from.x - gap), y)
            if (from.x - to.x > 0.6f * gap) sheet.shapes.curvedArrow(from, to, color, bend = -0.08f)
        } else {
            val start = firstLine.minOfOrNull { it.left } ?: a[0]
            val from = P(n[2] + 0.25f * gap, fromY)
            val to = P(max(start - 0.3f * gap, from.x + gap), y)
            if (to.x - from.x > 0.6f * gap) sheet.shapes.curvedArrow(from, to, color, bend = 0.08f)
        }
    }

    /** Bounds (left, top, right, bottom) of what is written for [block] so far: text runs and pen strokes. */
    internal fun boundsOf(sheet: Sheet, block: String): FloatArray? {
        val runs = sheet.runs.filter { it.block == block }
        val inkB = sheet.ink.boundsOf(block)
        if (runs.isEmpty() && inkB == null) return null
        var l = inkB?.get(0) ?: Float.MAX_VALUE
        var t = inkB?.get(1) ?: Float.MAX_VALUE
        var r = inkB?.get(2) ?: -Float.MAX_VALUE
        var b = inkB?.get(3) ?: -Float.MAX_VALUE
        for (run in runs) {
            l = min(l, run.left)
            r = max(r, run.left + run.width)
            t = min(t, run.baseline - measure.ascent(run.font) * run.sizePt * 0.7f)
            b = max(b, run.baseline)
        }
        return floatArrayOf(l, t, r, b)
    }

    companion object {
        const val INK = 0xFF1E2433.toInt()
        internal const val DISPLAY_SCALE = 1.1f
        internal const val HANG = 0.3f

        /** A display formula may shrink this far to stay on one line before it is broken… */
        internal const val MIN_SHRINK = 0.8f

        /** …and its broken lines this far (a single huge atom: a matrix). */
        internal const val MIN_FIT = 0.55f

        /** An inline formula wider than its whole line may shrink this far (a narrow Cornell column at a big scale). */
        internal const val MIN_INLINE_FIT = 0.4f
        internal const val PROBE = "\u0000probe"

        /** Half a written stroke's width plus the hand's wobble (points): a stroke is drawn again, a little differently, on the page. */
        internal const val PEN_HALF = 2.0f

        /** The smallest script size (points) still readable on a tablet at arm's length. */
        internal const val MIN_SCRIPT_PT = 4.5f

        private val TEX_COMMAND = Regex("""\\[a-zA-Z]+""")

        private val MIRROR = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{', '<' to '>', '>' to '<')

        /** A boxed line's box reaches this far (sizes) past its writing on each side. */
        internal const val BOX_SIDE = 0.25f
        internal const val SYSTEM_SCALE = 0.86f
        internal const val INLINE_DESCENT = 0.55f

        /**
         * Letters reach at most this far above the baseline (fraction of the size), whatever the font's
         * ascent says: Aref Ruqaa's ascent leaves room for stacked marks no line of notes uses, and
         * counting it put every Arabic line on two lines of the paper.
         */
        internal const val MAX_INK_ASCENT = 1.05f

        /** Fonts whose ascent is above this are "tall hands"; their lines need [TALL_EXTENT] × size. */
        internal const val TALL_HAND = 1.1f
        internal const val TALL_EXTENT = 1.12f

        /** Operators whose limits go under them. */
        internal val UNDER_LIMITS = setOf("lim", "max", "min", "sup", "inf", "Σ", "Π", "limsup", "liminf")
    }
}
