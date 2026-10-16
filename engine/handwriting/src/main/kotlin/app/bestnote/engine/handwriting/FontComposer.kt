package app.bestnote.engine.handwriting

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
)

/** A page of typed notes: text runs, plus pen strokes for boxes, underlines, arrows, fraction bars and drawings. */
class TypesetPage(val runs: List<TypeRun>, val ink: List<InkStroke>)

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
    private val paper: RuledPaper,
    private val style: StyleTemplate,
    private val measure: TextMeasure,
    /** The page's direction (margin side, maths alignment); each text block also follows its own first letter. */
    private val pageRtl: Boolean = false,
    private val penColor: Int = INK,
    private val seed: Long = 20_261_016L,
) {
    private val gap = paper.lineGap

    /** Direction of the block being laid out: its own first strong letter, else the page's. */
    private var rtl = pageRtl

    /** Script for words the page adds when a block has none of its own (maths-only proofs). */
    private val pageScript = if (pageRtl) WritingScript.Arabic else WritingScript.Latin

    /** Script of the block being laid out, for the words a student adds ("Definition:", "Solution:"). */
    private var script = WritingScript.Latin

    private fun textOf(b: PageBlock): String = when (b) {
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
    private fun directionOf(b: PageBlock): Boolean {
        val text = textOf(b)
        if (text.none(Char::isLetter)) return pageRtl
        if (!WritingScript.hasRtl(text)) return false
        return pageRtl || WritingScript.mostlyRtl(text)
    }
    private val body = gap * style.bodyScale
    private val inlineTex = ArrayList<String>()

    private fun hand(page: Int) = Hand(Rand(seed * 31 + page), xh = gap * 0.3f, unitsPerMm = PageComposer.UNITS_PER_MM, jitter = 0.35f)

    /** Collects one page's output. */
    private inner class Sheet(page: Int) {
        val runs = ArrayList<TypeRun>()
        private val h = hand(page)
        val ink = InkBuilder(h, PenChoice(colorArgb = penColor))
        val shapes = ShapeWriter(h, ink)
        val marks = ArrayList<InkStroke>() // highlighter strokes, drawn under the text
        var block: String = ""

        fun text(t: String, left: Float, baseline: Float, width: Float, size: Float, color: Int, font: HandFont, rtl: Boolean) {
            if (t.isNotBlank()) runs += TypeRun(t, left, baseline, width, size * scaleOf(font), color, font, rtl, block)
        }

        fun highlight(x0: Float, x1: Float, baseline: Float, size: Float) {
            val y = baseline - 0.28f * size
            val pts = listOf(StylusSample(min(x0, x1) - 2f, y, 1f, 0f, 0f, 0L), StylusSample(max(x0, x1) + 2f, y, 1f, 0f, 0f, 120L))
            marks += InkStroke(InkBrush(Tool.Highlighter, colorArgb = style.highlight.toInt(), size = size * 0.75f), pts)
        }
    }

    private class Row(val lines: Int, val blank: Boolean = false, val draw: Sheet.(left: Float, right: Float, baseline: Float) -> Unit)

    private fun blankRow() = Row(1, blank = true) { _, _, _ -> }

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
                TypesetPage(sheet.runs, sheet.marks + sheet.ink.build().map { it.stroke })
            },
        )
    }

    // ------------------------------------------------------------------ roles

    private fun colorOf(r: RoleStyle): Int = r.color?.toInt() ?: penColor

    private fun sizeOf(r: RoleStyle) = body * r.scale

    private fun usable(width: Float) = width * 0.97f

    private fun rows(b: PageBlock, width: Float): List<Row> {
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
    private fun fontFor(text: String): HandFont = when (WritingScript.dominant(text)) {
        WritingScript.Arabic -> style.arabicFont
        WritingScript.Hebrew -> style.hebrewFont
        else -> style.latinFont
    }

    private fun isRtlText(text: String) = WritingScript.mostlyRtl(text)

    /**
     * The device font (Hebrew) is much larger than the handwriting fonts at the same point size, so it is
     * written smaller to match their letter height.
     */
    private fun scaleOf(font: HandFont) = if (font == HandFont.System) SYSTEM_SCALE else 1f

    /** Width of [text] in [font] as it will be written (the font's size scale applied). */
    private fun widthOf(text: String, font: HandFont, size: Float) = measure.width(text, font, size * scaleOf(font))

    /** Lines a row of text at [size] needs: its ascent must fit under the line above. */
    private fun linesForSize(size: Float, font: HandFont = style.latinFont) =
        max(1, ceil((measure.ascent(font) * size * 0.82f) / gap - 0.02f).toInt())

    // ------------------------------------------------------------------ text lines

    /** A word (or an inline maths token) and its width at a size. */
    private fun wordWidth(word: String, size: Float): Float =
        InlineMath.segments(word).sumOf { (token, part) ->
            (if (token != null) mathBox(token, size).width else if (part.isEmpty()) 0f else widthOf(part, fontFor(part), size)).toDouble()
        }.toFloat()

    private fun space(size: Float) = widthOf(" ", style.latinFont, size).coerceAtLeast(size * 0.22f)

    private fun wrap(text: String, width: Float, size: Float, firstIndent: Float = 0f): List<String> {
        val lines = ArrayList<String>()
        val cur = ArrayList<String>()
        var curW = 0f
        for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val w = wordWidth(word, size)
            val limit = if (lines.isEmpty()) width - firstIndent else width
            if (cur.isNotEmpty() && curW + space(size) + w > limit) {
                lines += cur.joinToString(" ")
                cur.clear()
                curW = 0f
            }
            if (cur.isNotEmpty()) curW += space(size)
            cur += word
            curW += w
        }
        if (cur.isNotEmpty()) lines += cur.joinToString(" ")
        return lines
    }

    private fun textRows(
        content: String,
        width: Float,
        size: Float,
        color: Int,
        indent: Float = 0f,
        firstIndent: Float = 0f,
        decoration: Decoration = Decoration.None,
    ): List<Row> {
        val encoded = InlineMath.encode(content, inlineTex)
        val lines = wrap(encoded, usable(width) - indent, size, firstIndent)
        val n = linesForSize(size, fontFor(content))
        return lines.mapIndexed { i, line ->
            Row(n) { left, right, baseline ->
                val off = indent + if (i == 0) firstIndent else 0f
                val (x0, x1) = writeLine(this, line, left, right, off, baseline, size, color)
                decorate(this, decoration, x0, x1, baseline, size, color)
            }
        }
    }

    private fun decorate(sheet: Sheet, d: Decoration, x0: Float, x1: Float, baseline: Float, size: Float, color: Int) {
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
            Decoration.Box -> sheet.shapes.box(lo - 0.25f * size, baseline - 0.85f * size, hi + 0.25f * size, baseline + 0.3f * size, color)
        }
    }

    /**
     * Writes one line from the column's start ([left] for LTR, [right] for RTL) plus [indent].
     * @return the x range the writing covers.
     */
    private fun writeLine(sheet: Sheet, line: String, left: Float, right: Float, indent: Float, baseline: Float, size: Float, color: Int): Pair<Float, Float> {
        val words = line.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return left to left
        val sp = space(size)
        val start = if (rtl) right - indent else left + indent
        var x = start
        var first = true
        for (run in Bidi.runs(words, rtl)) {
            // Pieces of the run: plain words joined into one text run; each maths token on its own.
            val pieces = ArrayList<String>()
            val plain = ArrayList<String>()
            for (w in run.words) {
                if (InlineMath.hasToken(w)) {
                    if (plain.isNotEmpty()) { pieces += plain.joinToString(" "); plain.clear() }
                    pieces += w
                } else {
                    plain += w
                }
            }
            if (plain.isNotEmpty()) pieces += plain.joinToString(" ")
            val widths = pieces.map { pieceWidth(it, size) }
            val total = widths.sum() + sp * (pieces.size - 1)
            if (!first) x += if (rtl) -sp else sp
            first = false
            val runLeft = if (rtl) x - total else x
            // Inside the run, pieces go in the run's own direction.
            var cx = if (run.rtl) runLeft + total else runLeft
            for ((i, piece) in pieces.withIndex()) {
                val w = widths[i]
                val pl = if (run.rtl) cx - w else cx
                writePiece(sheet, piece, pl, baseline, w, size, color, run.rtl)
                cx = if (run.rtl) cx - w - sp else cx + w + sp
            }
            x = if (rtl) runLeft else runLeft + total
        }
        return if (rtl) x to start else start to x
    }

    private fun pieceWidth(piece: String, size: Float) = if (InlineMath.hasToken(piece)) wordWidth(piece, size) else widthOf(piece, fontFor(piece), size)

    private fun writePiece(sheet: Sheet, piece: String, left: Float, baseline: Float, width: Float, size: Float, color: Int, rtl: Boolean) {
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

    private fun bulletRows(item: String, marker: String, width: Float, color: Int): List<Row> {
        val indent = widthOf("$marker ", style.latinFont, body) + 0.3f * body
        val rows = textRows(item, width, body, color, indent = indent)
        if (rows.isEmpty()) return rows
        val first = rows[0]
        return listOf(
            Row(first.lines) { left, right, baseline ->
                val mw = widthOf(marker, style.latinFont, body)
                val x = if (rtl) right - 0.15f * body - mw else left + 0.15f * body
                text(marker, x, baseline, mw, body, color, style.latinFont, false)
                first.draw(this, left, right, baseline)
            },
        ) + rows.drop(1)
    }

    // ------------------------------------------------------------------ callouts, proofs

    private fun label(tone: CalloutTone): String = when (script) {
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

    private fun solutionWord() = when (script) {
        WritingScript.Hebrew -> "פתרון"
        WritingScript.Arabic -> "الحل"
        else -> "Solution"
    }

    private fun calloutRows(b: PageBlock.Callout, role: RoleStyle, width: Float): List<Row> {
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
        return if (fitsAfter) listOf(labelRow) + bodyRows.drop(1) else listOf(labelRow) + bodyRows
    }

    private fun proofRows(b: PageBlock.Proof, width: Float): List<Row> {
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

    private fun displaySize() = body * DISPLAY_SCALE

    private fun displayWidth(tex: String) = MathParser.parse(tex).maxOfOrNull { layout(it, displaySize()).width } ?: 0f

    private fun mathRows(latex: String, width: Float, color: Int, boxed: Boolean, indent: Float = 1.2f * gap): List<Row> {
        val parsed = MathParser.parse(latex)
        val pad = if (boxed) 0.45f * gap else 0f
        val avail = usable(width) - indent - 2 * pad
        val boxes = parsed.map { nodes ->
            val b = layout(nodes, displaySize())
            if (b.width > avail && b.width > 0f) layout(nodes, displaySize() * max(0.55f, avail / b.width)) else b
        }
        val rows = boxes.map { box ->
            val lines = max(1, ceil((box.ascent + 0.12f * gap) / gap - 0.02f).toInt())
            Row(lines) { left, right, baseline ->
                val x = if (rtl) right - indent - pad - box.width else left + indent + pad
                box.draw(this, x, baseline, color)
            }
        }
        if (rows.isEmpty()) return rows
        val hang = boxes.last().descent > HANG * gap
        if (!boxed) return if (hang) rows + blankRow() else rows
        val widest = boxes.maxOf { it.width }
        val firstAscent = boxes.first().ascent
        val lastDescent = boxes.last().descent
        val total = rows.sumOf { it.lines }
        val below = if (max(lastDescent, 0.1f * gap) + 0.22f * gap > 0.3f * gap) listOf(blankRow()) else emptyList()
        return listOf(blankRow()) + rows.dropLast(1) + Row(rows.last().lines) { left, right, baseline ->
            rows.last().draw(this, left, right, baseline)
            val x0 = if (rtl) right - indent - widest - 2 * pad else left + indent
            val top = baseline - (total - 1) * gap - firstAscent - 0.25f * gap
            val bottom = baseline + max(lastDescent, 0.1f * gap) + 0.2f * gap
            shapes.box(x0, top, x0 + widest + 2 * pad, bottom, color)
        } + below
    }

    // ------------------------------------------------------------------ maths layout (font)

    /** A laid-out piece of maths: its size and how to draw it from a pen position on a baseline. */
    private class MBox(val width: Float, val ascent: Float, val descent: Float, val draw: Sheet.(x: Float, baseline: Float, color: Int) -> Unit)

    private fun mathBox(token: Int, size: Float): MBox {
        val nodes = MathParser.parse(inlineTex.getOrElse(token) { "" }).flatten()
        val b = layout(nodes, size)
        // Inline maths may not hit the lines above and below: shrink tall fractions.
        val fit = minOf(1f, 0.85f * gap / b.ascent.coerceAtLeast(0.01f), 0.45f * gap / b.descent.coerceAtLeast(0.01f))
        return if (fit < 0.98f) layout(nodes, size * max(0.6f, fit)) else b
    }

    private val mathFont get() = style.latinFont

    private fun isOp(n: MathParser.Node) = n is MathParser.Text && n.s.length == 1 && n.s[0] in "=+−-×·<>≤≥≈≠⇒→±÷"

    private fun layout(nodes: List<MathParser.Node>, size: Float): MBox {
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
                n is MathParser.Text -> {
                    if (isOp(n) && run.isNotEmpty()) run.append(' ')
                    run.append(n.s.replace('-', '−'))
                    if (isOp(n) && k < nodes.lastIndex) run.append(' ')
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

    private fun textBox(t: String, size: Float): MBox {
        val text = t.trimEnd()
        val font = fontFor(text)
        val w = widthOf(text, font, size) + if (t.endsWith(' ')) space(size) else 0f
        val tw = widthOf(text, font, size)
        return MBox(w, measure.ascent(font) * size * 0.78f, measure.descent(font) * size * 0.6f) { x, baseline, color ->
            text(text, x, baseline, tw, size, color, font, false)
        }
    }

    private fun node(n: MathParser.Node, size: Float): MBox = when (n) {
        is MathParser.Text -> textBox(n.s, size)
        is MathParser.Space -> MBox(n.em * size * 0.28f, 0f, 0f) { _, _, _ -> }
        is MathParser.Group -> layout(n.body, size)
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
        is MathParser.Scripts -> {
            val base = n.base?.let { node(it, size) } ?: MBox(0f, 0f, 0f) { _, _, _ -> }
            val ss = size * 0.62f
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
    }

    // ------------------------------------------------------------------ diagrams, margin arrows

    private fun diagramRows(b: PageBlock.Diagram, width: Float): List<Row> {
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

    private fun drawShape(sheet: Sheet, s: VectorShape, map: (Float, Float) -> P) {
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

    private fun marginArrow(sheet: Sheet, note: PageBlock.MarginNote, anchor: PageBlock) {
        val a = boundsOf(sheet, anchor.id.value) ?: return
        val n = boundsOf(sheet, note.id.value) ?: return
        val y = (a[1] + a[3]) / 2
        val color = colorOf(style.role(NoteRole.Margin))
        if (!pageRtl) {
            val from = P(n[0] - 0.25f * gap, note.frame.top + 0.6f * gap)
            val to = P(min(a[2] + 0.3f * gap, from.x - gap), y)
            if (from.x - to.x > 0.6f * gap) sheet.shapes.curvedArrow(from, to, color, bend = -0.1f)
        } else {
            val from = P(n[2] + 0.25f * gap, note.frame.top + 0.6f * gap)
            val to = P(max(a[0] - 0.3f * gap, from.x + gap), y)
            if (to.x - from.x > 0.6f * gap) sheet.shapes.curvedArrow(from, to, color, bend = 0.1f)
        }
    }

    /** Bounds (left, top, right, bottom) of what is written for [block] so far: text runs and pen strokes. */
    private fun boundsOf(sheet: Sheet, block: String): FloatArray? {
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
        private const val DISPLAY_SCALE = 1.1f
        private const val HANG = 0.3f
        private const val SYSTEM_SCALE = 0.78f
    }
}
