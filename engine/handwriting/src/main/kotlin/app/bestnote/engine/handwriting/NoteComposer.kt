package app.bestnote.engine.handwriting

import app.bestnote.core.model.BlockId
import app.bestnote.core.model.CalloutTone
import app.bestnote.core.model.PageBlock
import app.bestnote.core.model.RectPt
import app.bestnote.engine.layout.BlockMeasurer
import app.bestnote.engine.layout.Decoration
import app.bestnote.engine.layout.Density
import app.bestnote.engine.layout.PackedFlow
import app.bestnote.engine.layout.FlowItem
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteNormalizer
import app.bestnote.engine.layout.NotebookGlossary
import app.bestnote.engine.layout.NoteRole
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.StyleTemplate
import app.bestnote.engine.layout.WritingScript
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The element library: every [NoteElement] as a deterministic layout + render component in the
 * font writer. Styling comes ONLY from the [StyleTemplate] (role colours, fonts, sizes,
 * decorations); every line of writing sits on a ruled line of [paper], the same rows drive the
 * measurer (for [app.bestnote.engine.layout.RuledFlow]) and the writing. Right-to-left elements
 * start at the right edge; maths stays left to right inside them.
 */
class NoteComposer(
    paper: RuledPaper,
    private val style: StyleTemplate,
    measure: TextMeasure,
    private val pageRtl: Boolean = false,
    penColor: Int = FontComposer.INK,
) {
    private val fc = FontComposer(paper, style, measure, pageRtl, penColor)
    private val gap = paper.lineGap
    private val lineCount = paper.lineCount
    private val body get() = fc.body

    private fun row(lines: Int, d: FontComposer.Sheet.(Float, Float, Float) -> Unit) = FontComposer.Row(lines) { l, r, b -> d(l, r, b) }

    /**
     * A laid-out element: its flow [key], what it is and where it goes. [targetKey] is a margin remark's
     * anchor. [rows]: only these rows of the element (the packed layout splits a long element between
     * columns); null = all of it.
     */
    data class Placed(val key: String, val block: NoteBlock, val frame: RectPt, val targetKey: String? = null, val rows: IntRange? = null)

    /** Rows of [block] at [width] as the packed layout measures them. */
    fun rowShape(block: NoteBlock, width: Float): List<PackedFlow.Row> = rows(block, width).map { PackedFlow.Row(it.lines, it.blank, it.glue) }

    /**
     * Compact writing for the packed layout: smaller display maths and steps, maths closer to the
     * column's start; on [fine] lines (every grid line) inline formulas may take two lines.
     */
    fun compact(density: Density, fine: Boolean = false) {
        fc.displayScale = when (density) {
            Density.Airy -> FontComposer.DISPLAY_SCALE
            Density.Normal -> 1.0f
            Density.Dense -> 0.94f
        }
        fc.mathIndent = 0.5f * gap
        if (fine) fc.inlineLines = 2
        stepScale = if (density == Density.Dense) 0.9f else 0.95f
    }

    private var stepScale = 1f

    fun linesFor(block: NoteBlock, width: Float): Int = rows(block, width).sumOf { it.lines }.coerceAtLeast(1)

    fun measurer(blockOf: (String) -> NoteBlock?): BlockMeasurer = BlockMeasurer { item: FlowItem, width: Float ->
        val block = blockOf(item.key) ?: return@BlockMeasurer gap
        linesFor(block, width) * gap
    }

    fun compose(placed: List<Pair<Int, Placed>>, rules: List<PackedFlow.Rule> = emptyList()): TypesetPages {
        val pageCount = maxOf((placed.maxOfOrNull { it.first } ?: -1) + 1, (rules.maxOfOrNull { it.page } ?: -1) + 1)
        return TypesetPages(
            (0 until pageCount).map { page ->
                val sheet = fc.Sheet(page + 1)
                val items = placed.filter { it.first == page }.map { it.second }
                val keys = items.mapTo(HashSet()) { it.key }
                for (p in items) {
                    sheet.block = p.key
                    sheet.ink.block = p.key
                    var baseline = p.frame.top
                    val all = rows(p.block, p.frame.width)
                    for (r in p.rows?.let { range -> all.subList(range.first.coerceIn(0, all.size), (range.last + 1).coerceIn(0, all.size)) } ?: all) {
                        baseline += r.lines * gap
                        r.draw(sheet, p.frame.left, p.frame.right, baseline)
                    }
                    val target = p.targetKey
                    if (p.block.element == NoteElement.MarginRemark && style.marginArrows && target != null && target in keys) {
                        val note = PageBlock.MarginNote(BlockId(p.key), p.frame, p.block.text.orEmpty(), BlockId(target))
                        fc.marginArrow(sheet, note, PageBlock.Paragraph(BlockId(target), p.frame, ""))
                    }
                }
                sheet.block = RULES
                sheet.ink.block = RULES
                for (r in rules.filter { it.page == page }) sheet.shapes.dashed(P(r.x0, r.y0), P(r.x1, r.y1), RULE_COLOR)
                TypesetPage(sheet.runs, sheet.marks + sheet.ink.build().map { it.stroke }, sheet.equations, sheet.images)
            },
        )
    }

    // ------------------------------------------------------------------ dispatch

    /** The block's direction and script, set on the font writer for building AND for drawing its rows. */
    private fun setDirection(b: NoteBlock) {
        val words = InlineMath.flatten(b.words())
        fc.rtl = when {
            words.none(Char::isLetter) -> pageRtl
            !WritingScript.hasRtl(words) -> false
            else -> pageRtl || WritingScript.mostlyRtl(words)
        }
        fc.script = WritingScript.dominant(words).takeIf { words.any(Char::isLetter) } ?: if (pageRtl) WritingScript.Arabic else WritingScript.Latin
    }

    /** Rows whose drawing restores the direction they were built with (rows of nested elements are drawn later). */
    private fun pinned(rows: List<FontComposer.Row>): List<FontComposer.Row> {
        val rtl = fc.rtl
        val script = fc.script
        return rows.map { r ->
            FontComposer.Row(r.lines, r.blank, r.glue) { l, rt, b ->
                val (pr, ps) = fc.rtl to fc.script
                fc.rtl = rtl
                fc.script = script
                r.draw(this, l, rt, b)
                fc.rtl = pr
                fc.script = ps
            }
        }
    }

    internal fun rows(b: NoteBlock, width: Float): List<FontComposer.Row> {
        setDirection(b)
        val id = BlockId("x")
        val r = EMPTY
        val built: List<FontComposer.Row> = when (b.element) {
            NoteElement.Title -> fc.rows(PageBlock.Heading(id, r, 1, b.text ?: b.head.orEmpty()), width)
            NoteElement.Heading -> fc.rows(PageBlock.Heading(id, r, 2, b.text ?: b.head.orEmpty()), width)
            NoteElement.Paragraph -> fc.rows(PageBlock.Paragraph(id, r, b.text ?: b.head.orEmpty()), width)
            NoteElement.Bullets -> fc.rows(PageBlock.Bullets(id, r, b.items.ifEmpty { listOfNotNull(b.text) }), width)
            NoteElement.Definition -> callout(b, CalloutTone.Definition, width)
            NoteElement.Theorem -> callout(b, CalloutTone.Theorem, width)
            NoteElement.Note -> callout(b, CalloutTone.Info, width)
            NoteElement.Warning -> callout(b, CalloutTone.Warning, width)
            NoteElement.Example -> callout(b, CalloutTone.Example, width) + steps(b, width, numbered = false)
            NoteElement.Formula -> centredMath(b.math ?: b.text.orEmpty(), width, colorOf(NoteRole.Formula))
            NoteElement.KeyResult -> keyResult(b, width, number = null)
            NoteElement.NumberedFormula -> keyResult(b, width, number = b.number)
            NoteElement.StepSolution -> heading(b.head ?: fc.solutionWord(), width, NoteRole.Subheading) + steps(b, width, numbered = true)
            NoteElement.MarginRemark -> fc.textRows(b.text.orEmpty(), width, fc.sizeOf(fc.style.role(NoteRole.Margin)), colorOf(NoteRole.Margin))
            NoteElement.StarNote -> fc.textRows("★ " + b.text.orEmpty(), width, body, colorOf(NoteRole.Note))
            NoteElement.Summary -> summary(b, width)
            NoteElement.Comparison -> grid(b.rows, width, comparison = true)
            NoteElement.Table -> grid(b.rows, width, comparison = false)
            NoteElement.Glossary -> glossary(b, width)
            NoteElement.Diagram -> diagram(b, width)
            NoteElement.VariableLegend -> legend(b, width)
            NoteElement.Derivation -> derivation(b, width)
            NoteElement.WorkedExample -> workedExample(b, width)
            NoteElement.Columns -> columns(b, width)
        }
        return pinned(built)
    }

    private fun colorOf(role: NoteRole) = fc.colorOf(style.role(role))

    // ------------------------------------------------------------------ simple elements

    private fun heading(text: String, width: Float, role: NoteRole): List<FontComposer.Row> {
        val rs = style.role(role)
        return fc.textRows(text.trimEnd(':') + ":", width, body, fc.colorOf(rs), decoration = if (rs.decoration == Decoration.None) Decoration.Underline else rs.decoration)
    }

    private fun callout(b: NoteBlock, tone: CalloutTone, width: Float): List<FontComposer.Row> {
        val text = b.text ?: b.items.joinToString("\n").takeIf { it.isNotBlank() }
        val head = b.head
        val block = PageBlock.Callout(BlockId("x"), EMPTY, tone, if (text == null) null else head, text ?: head.orEmpty())
        return fc.rows(block, width)
    }

    /** A display formula centred in the column (the line maths moved out of a right-to-left sentence gets). */
    private fun centredMath(latex: String, width: Float, color: Int): List<FontComposer.Row> {
        val boxes = mathBoxes(latex, width)
        return boxes.map { box ->
            row(linesOf(box)) { l, r, b -> box.draw(this, l + (r - l - box.width) / 2, b, color) }
        } + (boxes.lastOrNull()?.let { fc.hangRows(it.descent) } ?: emptyList())
    }

    private fun mathBoxes(latex: String, width: Float, scale: Float = 1f): List<FontComposer.MBox> {
        val avail = fc.usable(width) - 1.2f * gap
        return fc.displayBoxes(latex, avail, fc.displaySize() * scale)
    }

    private fun linesOf(box: FontComposer.MBox) = max(1, ceil((box.ascent + 0.12f * gap) / gap - 0.02f).toInt())

    /** A boxed key formula; with [number], its circled number ① sits at the line's far end, where later steps can refer to it. */
    private fun keyResult(b: NoteBlock, width: Float, number: Int?): List<FontComposer.Row> {
        val color = colorOf(NoteRole.Result)
        val label = b.head?.let { heading(it, width, NoteRole.Result) }.orEmpty()
        val math = fc.mathRows(b.math ?: b.text.orEmpty(), width, color, boxed = true)
        if (number == null) return label + math
        val tag = NoteNormalizer.circled(number)
        val target = math.indexOfLast { !it.blank }.takeIf { it >= 0 } ?: return label + math
        return label + math.mapIndexed { i, r ->
            if (i != target) r else FontComposer.Row(r.lines) { l, rt, base ->
                r.draw(this, l, rt, base)
                val w = fc.widthOf(tag, fc.fontFor(tag), body)
                val x = if (fc.rtl) l + 0.2f * gap else rt - w - 0.2f * gap
                text(tag, x, base, w, body, color, fc.fontFor(tag), false)
            }
        }
    }

    /** Steps: numbered ("1.", "2.") for a solution, plain for an example; each with its short reason. */
    private fun steps(b: NoteBlock, width: Float, numbered: Boolean): List<FontComposer.Row> {
        val ink = colorOf(NoteRole.Step)
        val whyColor = colorOf(NoteRole.Margin)
        val out = ArrayList<FontComposer.Row>()
        for ((i, s) in b.steps.withIndex()) {
            val marker = if (numbered) "${i + 1}." else "•"
            val markerW = fc.widthOf("$marker ", fc.style.latinFont, body) + 0.3f * body
            val why = s.why?.takeIf { it.isNotBlank() }?.let { "($it)" }
            val whySize = body * 0.85f
            val whyW = why?.let { fc.widthOf(InlineMath.flatten(it), fc.fontFor(it), whySize) } ?: 0f
            val prose = s.text?.takeIf { it.isNotBlank() }?.let { fc.bulletRows(it, marker, width, ink) }.orEmpty()
            val math = s.math?.takeIf { it.isNotBlank() }?.let { fc.mathRows(it, width, ink, boxed = false, indent = markerW, scale = stepScale) }.orEmpty()
            val mathRows = if (prose.isEmpty() && math.isNotEmpty()) {
                // The number goes on the formula's own line.
                math.mapIndexed { k, r ->
                    if (k != 0) r else FontComposer.Row(r.lines, r.blank, r.glue) { l, rt, base ->
                        r.draw(this, l, rt, base)
                        val mw = fc.widthOf(marker, fc.style.latinFont, body)
                        val x = if (fc.rtl) rt - 0.15f * body - mw else l + 0.15f * body
                        text(marker, x, base, mw, body, ink, fc.style.latinFont, false)
                    }
                }
            } else {
                math
            }
            val lines = prose + mathRows
            val last = lines.indexOfLast { !it.blank }
            val fits = why != null && last >= 0 && !InlineMath.hasToken(InlineMath.encode(why, fc.inlineTex)) && lineWidth(s, width, markerW) + whyW + 1.5f * body <= fc.usable(width)
            if (fits) {
                out += lines.mapIndexed { k, r ->
                    if (k != last) r else FontComposer.Row(r.lines, r.blank, r.glue) { l, rt, base ->
                        r.draw(this, l, rt, base)
                        // The reason follows the step's own writing, a little apart (not at the column's far end).
                        fc.writeLine(this, InlineMath.encode(why!!, fc.inlineTex), l, rt, min(fc.usable(rt - l) - whyW, lineWidth(s, width, markerW) + 1.2f * body), base, whySize, whyColor)
                    }
                }
            } else {
                out += lines
                why?.let { out += fc.textRows(it, width, whySize, whyColor, indent = markerW * 2) }
            }
        }
        return out
    }

    /** Rough width of a step's own writing (for deciding whether its reason fits on the same line). */
    private fun lineWidth(s: app.bestnote.engine.layout.NoteStep, width: Float, indent: Float): Float {
        val m = s.math?.let { fc.displayWidth(it) } ?: 0f
        val t = s.text?.let { fc.wordWidth(InlineMath.encode(it, fc.inlineTex), body) } ?: 0f
        return min(fc.usable(width), indent + max(m, t))
    }

    /** A summary: its label, then the points, inside a pen-drawn box. */
    private fun summary(b: NoteBlock, width: Float): List<FontComposer.Row> {
        val color = colorOf(NoteRole.Note)
        val pad = 0.5f * gap
        val inner = width - 2 * pad
        val content = ArrayList<FontComposer.Row>()
        content += heading(b.head ?: fc.label(CalloutTone.Summary), inner, NoteRole.Note)
        b.text?.let { content += fc.textRows(it, inner, body, fc.penColor) }
        for (item in b.items) content += fc.bulletRows(item, "•", inner, fc.penColor)
        return boxed(content, pad, color)
    }

    /** [content] (laid out [pad] narrower) inside a box from its first line's top to just below its last line. */
    private fun boxed(content: List<FontComposer.Row>, pad: Float, color: Int): List<FontComposer.Row> {
        if (content.isEmpty()) return content
        val total = content.sumOf { it.lines }
        return content.dropLast(1).map { r -> FontComposer.Row(r.lines, r.blank, glue = true) { l, rt, base -> r.draw(this, l + pad, rt - pad, base) } } +
            FontComposer.Row(content.last().lines) { l, rt, base ->
                content.last().draw(this, l + pad, rt - pad, base)
                shapes.box(l + 0.15f * gap, base - total * gap + 0.25f * gap, rt - 0.15f * gap, base + 0.32f * gap, color)
            } + fc.blankRow()
    }

    // ------------------------------------------------------------------ grids: table, comparison, glossary, legend

    /**
     * A table (pen grid, header row in the heading colour) or a comparison (two columns, header row
     * underlined, a dashed divider). Columns run from the start side: the first column is on the
     * right in right-to-left notes.
     */
    private fun grid(rows: List<List<String>>, width: Float, comparison: Boolean): List<FontComposer.Row> {
        if (rows.isEmpty()) return emptyList()
        val cols = rows.maxOf { it.size }.coerceIn(1, MAX_COLUMNS)
        val usable = fc.usable(width)
        val cellW = usable / cols
        val pad = 0.3f * body
        val headColor = colorOf(NoteRole.Heading)
        val lineColor = if (comparison) colorOf(NoteRole.Heading) else fc.penColor
        val rtl = fc.rtl
        val out = ArrayList<FontComposer.Row>()
        for ((ri, cells) in rows.withIndex()) {
            val header = ri == 0
            val size = body * if (header) 1f else CELL_SCALE
            val wrapped = (0 until cols).map { c -> fc.wrap(InlineMath.encode(cellText(cells.getOrElse(c) { "" }), fc.inlineTex), cellW - 2 * pad, size) }
            // Lines each wrapped line needs (a fraction in a cell takes two; a tall script needs its own
            // height), then the row's height. The first lines of all cells share one baseline.
            fun up(line: String) = max(fc.inlineExtent(line, size).first, fc.linesForSize(size, fc.fontFor(InlineMath.stripTokens(line))))
            fun down(line: String) = if (fc.inlineExtent(line, size).second) 1 else 0
            val firstUp = wrapped.maxOf { cell -> cell.firstOrNull()?.let(::up) ?: 1 }
            val heights = wrapped.map { cell -> cell.withIndex().sumOf { (k, line) -> (if (k == 0) firstUp else up(line)) + down(line) } }
            val lines = max(1, heights.max())
            out += row(lines) { l, r, base ->
                val top = base - lines * gap + 0.3f * gap
                val bottom = base + 0.3f * gap
                for (c in 0 until cols) {
                    val x0 = if (rtl) l + usable - (c + 1) * cellW else l + c * cellW
                    var y = base - lines * gap
                    for ((k, line) in wrapped[c].withIndex()) {
                        y += (if (k == 0) firstUp else up(line)) * gap
                        val prev = fc.rtl
                        fc.rtl = WritingScript.mostlyRtl(InlineMath.stripTokens(line))
                        fc.writeLine(this, line, x0 + pad, x0 + cellW - pad, 0f, y, size, if (header) headColor else fc.penColor)
                        fc.rtl = prev
                        y += down(line) * gap
                    }
                }
                val left = l
                val right = l + usable
                if (!comparison) {
                    if (header) shapes.line(P(left, top), P(right, top), lineColor)
                    shapes.line(P(left, bottom), P(right, bottom), lineColor)
                    for (c in 0..cols) shapes.line(P(left + c * cellW, top), P(left + c * cellW, bottom), lineColor)
                } else {
                    if (header) shapes.underline(left, right, bottom - 0.05f * gap, lineColor)
                    for (c in 1 until cols) shapes.dashed(P(left + c * cellW, top), P(left + c * cellW, bottom), lineColor)
                }
            }
        }
        return out + fc.blankRow()
    }

    /**
     * A table cell as prose: a cell that is bare LaTeX (models write `\frac{a}{x^2}` in cells without
     * `\( … \)`) is maths, so it is laid out as maths instead of showing its source.
     */
    internal fun cellText(cell: String): String {
        val t = cell.trim()
        if (t.isEmpty() || t.contains("\\(") || t.contains('$')) return cell
        // In a Hebrew/Arabic cell, each bare LaTeX word is maths on its own.
        if (WritingScript.hasRtl(t)) return t.split(' ').joinToString(" ") { w -> if (!WritingScript.hasRtl(w) && BARE_TEX.containsMatchIn(w)) "\\($w\\)" else w }
        return if (BARE_TEX.containsMatchIn(t)) "\\($t\\)" else cell
    }

    /** The page's terms, "term — translation", each language in its own direction, in two aligned columns. */
    private fun glossary(b: NoteBlock, width: Float): List<FontComposer.Row> {
        if (b.terms.isEmpty()) return emptyList()
        val title = b.head ?: when (fc.script) {
            WritingScript.Hebrew -> "מילון מונחים"
            WritingScript.Arabic -> "مسرد المصطلحات"
            else -> "Glossary"
        }
        val color = colorOf(NoteRole.Definition)
        val usable = fc.usable(width)
        val half = usable / 2
        val size = body * CELL_SCALE
        val rtl = fc.rtl
        // No second language (both sides the same word): one column of terms.
        if (b.terms.none { it.translation.isNotBlank() && NotebookGlossary.key(it.translation) != NotebookGlossary.key(it.term) }) {
            return heading(title, width, NoteRole.Heading) + b.terms.flatMap { t -> fc.bulletRows(t.term, "•", width, color) } + fc.blankRow()
        }
        val termLines = fc.linesForSize(size, style.arabicFont).takeIf { b.terms.any { WritingScript.hasRtl(it.term + it.translation) } } ?: fc.linesForSize(size)
        return heading(title, width, NoteRole.Heading) + b.terms.flatMap { t ->
            // Each side wraps inside its half; the pair takes the lines of its longer side.
            val term = fc.wrap(InlineMath.encode(t.term, fc.inlineTex), half - 0.4f * gap, size)
            val translation = fc.wrap(InlineMath.encode(t.translation, fc.inlineTex), half - 0.4f * gap, size)
            (0 until max(term.size, translation.size)).map { k ->
                row(termLines) { l, _, base ->
                    val first = if (rtl) l + half else l
                    val second = if (rtl) l else l + half
                    for ((x0, line, c) in listOf(Triple(first, term.getOrNull(k), color), Triple(second, translation.getOrNull(k), fc.penColor))) {
                        if (line == null) continue
                        val prev = fc.rtl
                        fc.rtl = WritingScript.mostlyRtl(InlineMath.stripTokens(line))
                        fc.writeLine(this, line, x0, x0 + half - 0.3f * gap, 0f, base, size, c)
                        fc.rtl = prev
                    }
                    val mid = l + half
                    shapes.dashed(P(mid - 0.15f * gap, base - 0.6f * gap), P(mid - 0.15f * gap, base + 0.2f * gap), color)
                }
            }
        } + fc.blankRow()
    }

    /** "symbol — meaning (unit)" lines: the symbols in their own aligned column, then the words, then the unit. */
    private fun legend(b: NoteBlock, width: Float): List<FontComposer.Row> {
        if (b.rows.isEmpty()) return emptyList()
        val size = body * CELL_SCALE
        val symbols = b.rows.map { fc.layout(MathParser.parse(it.getOrElse(0) { "" }).flatten(), size) }
        val symW = (symbols.maxOfOrNull { it.width } ?: 0f) + 0.6f * body
        val color = colorOf(NoteRole.Formula)
        return b.rows.mapIndexed { i, cells ->
            val unit = cells.getOrNull(2)?.let(::bareUnit)?.takeIf { it.isNotBlank() }?.let { " \\(($it)\\)" }.orEmpty()
            val words = "— " + cells.getOrElse(1) { "" } + unit
            val encoded = InlineMath.encode(words, fc.inlineTex)
            val wrapped = fc.wrap(encoded, fc.usable(width) - symW - gap, size)
            val box = symbols[i]
            wrapped.mapIndexed { k, line ->
                row(max(fc.linesForSize(size, fc.fontFor(InlineMath.stripTokens(line))), if (k == 0) linesOf(box) else 1)) { l, r, base ->
                    if (k == 0) {
                        val x = if (fc.rtl) r - 0.6f * gap - box.width else l + 0.6f * gap
                        box.draw(this, x, base, color)
                    }
                    fc.writeLine(this, line, l, r, symW + 0.6f * gap, base, size, fc.penColor)
                }
            }
        }.flatten() + fc.blankRow()
    }

    // ------------------------------------------------------------------ derivation, worked example

    /**
     * A chain of equations aligned at "=": the left sides end at one x, the right sides start after
     * one "="; the last line (the result) is boxed and, when numbered, gets its circled number.
     */
    private fun derivation(b: NoteBlock, width: Float): List<FontComposer.Row> {
        val eqs = b.steps.mapNotNull { it.math ?: it.text }.filter { it.isNotBlank() }
        if (eqs.isEmpty()) return emptyList()
        val size = fc.displaySize()
        val parts = eqs.map { splitAtEquals(it) }
        val lhs = parts.map { fc.layout(MathParser.parse(it.first).flatten(), size) }
        val rhs = parts.map { p -> p.second?.let { fc.layout(MathParser.parse(it).flatten(), size) } }
        val eqW = fc.widthOf(" = ", fc.style.latinFont, size)
        val lhsW = lhs.maxOf { it.width }
        val total = lhsW + eqW + (rhs.maxOfOrNull { it?.width ?: 0f } ?: 0f)
        val avail = fc.usable(width) - 2.4f * gap
        val scale = if (total > avail) max(0.6f, avail / total) else 1f
        if (scale < 1f) return derivationStacked(b, eqs, width)
        val step = colorOf(NoteRole.Step)
        val result = colorOf(NoteRole.Result)
        val indent = 1.2f * gap
        val out = ArrayList<FontComposer.Row>()
        for (i in eqs.indices) {
            val last = i == eqs.lastIndex
            val l = lhs[i]
            val r = rhs[i]
            val lines = max(1, ceil((max(l.ascent, r?.ascent ?: 0f) + (if (last) 0.4f else 0.12f) * gap) / gap - 0.02f).toInt())
            if (last) out += fc.blankRow().takeIf { lines == 1 && i > 0 }?.let { listOf(it) }.orEmpty()
            out += row(lines) { left, right, base ->
                // Right to left: the circled number goes right of the box, so the box moves left to leave it room.
                val tagRoom = b.number?.takeIf { fc.rtl }?.let { n -> NoteNormalizer.circled(n) }?.let { tag -> fc.widthOf(tag, fc.fontFor(tag), body) + 0.9f * gap } ?: 0f
                val x0 = if (fc.rtl) right - indent - total - tagRoom else left + indent
                val color = if (last) result else step
                l.draw(this, x0 + lhsW - l.width, base, color)
                if (r != null) {
                    text("=", x0 + lhsW + eqW * 0.3f, base, eqW * 0.4f, size, color, fc.style.latinFont, false)
                    r.draw(this, x0 + lhsW + eqW, base, color)
                }
                if (last) {
                    val w = lhsW + eqW + (r?.width ?: 0f)
                    val top = base - max(l.ascent, r?.ascent ?: 0f) - 0.25f * gap
                    val bottom = base + max(max(l.descent, r?.descent ?: 0f), 0.1f * gap) + 0.2f * gap
                    val bx0 = x0 + lhsW - l.width - 0.3f * gap
                    shapes.box(bx0, top, x0 + w + 0.3f * gap, bottom, result)
                    b.number?.let { n ->
                        val tag = NoteNormalizer.circled(n)
                        val tw = fc.widthOf(tag, fc.fontFor(tag), body)
                        val tx = x0 + w + 0.7f * gap
                        text(tag, tx, base, tw, body, result, fc.fontFor(tag), false)
                    }
                }
            }
        }
        return out + fc.blankRow()
    }

    /** A derivation too wide for the column: each equation on its own (shrunk) line, the last one boxed. */
    private fun derivationStacked(b: NoteBlock, eqs: List<String>, width: Float): List<FontComposer.Row> {
        val step = colorOf(NoteRole.Step)
        return eqs.dropLast(1).flatMap { fc.mathRows(it, width, step, boxed = false) } +
            keyResult(NoteBlock(NoteElement.KeyResult, math = eqs.last()), width, b.number)
    }

    /** "a = b" → ("a", "b") at the first "=" outside braces; no "=" → (whole, null). */
    internal fun splitAtEquals(tex: String): Pair<String, String?> {
        var depth = 0
        for ((i, c) in tex.withIndex()) {
            when (c) {
                '{' -> depth++
                '}' -> depth--
                '=' -> if (depth == 0 && tex.getOrNull(i - 1) != '\\') return tex.substring(0, i).trim() to tex.substring(i + 1).trim()
            }
        }
        return tex.trim() to null
    }

    /**
     * Problem → "Given" (each value with its unit) → sub-questions labelled in the notes' alphabet
     * (א ב ג, أ ب ج, a b c), each solved formula → substitution → boxed result with its unit, with a
     * dashed rule between sub-questions.
     */
    private fun workedExample(b: NoteBlock, width: Float): List<FontComposer.Row> {
        val ex = colorOf(NoteRole.Example)
        val ink = colorOf(NoteRole.Step)
        val result = colorOf(NoteRole.Result)
        val out = ArrayList<FontComposer.Row>()
        val title = (b.head ?: fc.label(CalloutTone.Example)).trimEnd(':') + ":"
        val label = fc.textRows(title, width, body, ex, decoration = style.role(NoteRole.Example).decoration)
        out += label
        b.text?.let { out += fc.textRows(it, width, body, fc.penColor) }
        if (b.rows.isNotEmpty()) {
            val given = b.rows.joinToString("   ") { r ->
                val unit = r.getOrNull(2)?.let(::bareUnit)?.takeIf { it.isNotBlank() }?.let { "\\,($it)" }.orEmpty()
                "\\(" + r.getOrElse(0) { "" } + " = " + r.getOrElse(1) { "" } + unit + "\\)"
            }
            out += fc.textRows(givenWord() + ": " + given, width, body * CELL_SCALE, ink)
        }
        val letters = partLetters()
        val indent = fc.widthOf("ב. ", fc.style.latinFont, body) + 0.4f * body
        for ((i, p) in b.parts.withIndex()) {
            if (i > 0) out += row(1) { l, r, base -> shapes.dashed(P(l + 0.5f * gap, base - 0.45f * gap), P(r - 0.5f * gap, base - 0.45f * gap), ex) }
            val marker = letters.getOrElse(i) { "${i + 1}" } + "."
            out += fc.bulletRows(p.question ?: "", marker, width, fc.penColor).ifEmpty {
                listOf(row(1) { l, r, base -> text(marker, if (fc.rtl) r - fc.widthOf(marker, fc.style.latinFont, body) else l, base, fc.widthOf(marker, fc.style.latinFont, body), body, ex, fc.style.latinFont, false) })
            }
            for (s in p.steps) out += fc.mathRows(s, width, ink, boxed = false, indent = indent * 1.5f, scale = stepScale)
            p.result?.let { out += fc.mathRows(it, width, result, boxed = true, indent = indent * 1.5f) }
        }
        return out
    }

    private fun givenWord() = when (fc.script) {
        WritingScript.Hebrew -> "נתון"
        WritingScript.Arabic -> "المعطيات"
        else -> "Given"
    }

    private fun partLetters(): List<String> = when (fc.script) {
        WritingScript.Hebrew -> listOf("א", "ב", "ג", "ד", "ה", "ו", "ז", "ח")
        WritingScript.Arabic -> listOf("أ", "ب", "ج", "د", "هـ", "و", "ز", "ح")
        else -> listOf("a", "b", "c", "d", "e", "f", "g", "h")
    }

    // ------------------------------------------------------------------ diagrams, columns

    private fun diagram(b: NoteBlock, width: Float): List<FontComposer.Row> {
        b.figure?.let { f -> if (f.raster != null || b.diagram != null) return figure(b, f, width) }
        val d = b.diagram ?: return b.head?.let { fc.textRows(it, width, body, fc.penColor) }.orEmpty()
        val caption = b.head?.let { fc.textRows(it, width, body * CELL_SCALE, colorOf(NoteRole.Note)) }.orEmpty()
        return caption + fc.rows(PageBlock.Diagram(BlockId("x"), EMPTY, d), width)
    }

    /**
     * AI-12: a board drawing, redrawn as clean shapes (lines, arrows, arcs, springs, dashed lines) with
     * typeset labels, in the template's colours: dark ink → the pen, red → the result colour, green →
     * the margin colour, blue/purple → the heading colour. Sized from how much of the board it took.
     */
    private fun figure(b: NoteBlock, f: app.bestnote.engine.layout.BoardFigure, width: Float): List<FontComposer.Row> {
        // AI-12 (owner): no text that is not on the board. A board drawing's caption is the AI's description
        // of it (kept in the document, not written on the page); what is written on the drawing is in its labels.
        val share = f.box?.width ?: 0.4f
        val frac = (share * FIGURE_GROWTH).coerceIn(FIGURE_MIN, 1f)
        val recolour: (Long) -> Int = { argb ->
            when (argb or 0xFF000000L) {
                app.bestnote.engine.layout.NotebookPalette.ESSENTIAL -> colorOf(NoteRole.Result)
                app.bestnote.engine.layout.NotebookPalette.ANNOTATION -> colorOf(NoteRole.Margin)
                app.bestnote.engine.layout.NotebookPalette.EMPHASIS -> colorOf(NoteRole.Heading)
                else -> fc.penColor
            }
        }
        val rows = f.raster?.let { fc.pictureRows(it, frac, width) } ?: b.diagram?.let { fc.figureRows(it, frac, width, recolour) }.orEmpty()
        return rows
    }

    /**
     * Two columns side by side with a dashed divider and a dashed rule under them (the section
     * separator). The first column is on the start side: the right in right-to-left notes. Columns
     * taller than a page are written one under the other instead, so nothing runs off the page.
     */
    private fun columns(b: NoteBlock, width: Float): List<FontComposer.Row> {
        val cols = b.columns.filter { it.isNotEmpty() }.take(2)
        if (cols.isEmpty()) return emptyList()
        val rtl = fc.rtl
        val divider = colorOf(NoteRole.Margin)
        if (cols.size == 1) return cols[0].flatMap { inColumn(it, width) } + rule(divider)
        val gutter = 0.8f * gap
        val colW = (width - gutter) / 2
        val built = cols.map { c -> c.flatMap { inColumn(it, colW) } }
        val heights = built.map { rs -> rs.sumOf { it.lines } }
        val lines = heights.max()
        if (lines > lineCount - 2) return built[0] + rule(divider) + built[1] + rule(divider)
        return listOf(
            row(lines) { l, r, base ->
                val top = base - lines * gap
                for ((ci, rs) in built.withIndex()) {
                    val first = ci == 0
                    val x0 = if (first != rtl) l else l + colW + gutter
                    var y = top
                    for (rr in rs) {
                        y += rr.lines * gap
                        rr.draw(this, x0, x0 + colW, y)
                    }
                }
                val mid = l + colW + gutter / 2
                shapes.dashed(P(mid, top + 0.3f * gap), P(mid, base + 0.2f * gap), divider)
            },
        ) + rule(divider)
    }

    private fun inColumn(b: NoteBlock, width: Float): List<FontComposer.Row> {
        // No margin column inside a column: a side remark becomes a ★ note in place.
        val inner = if (b.element == NoteElement.MarginRemark) b.copy(element = NoteElement.StarNote) else b
        return rows(inner, width)
    }

    /** A dashed horizontal rule across the column, between sections. */
    private fun rule(color: Int) = listOf(row(1) { l, r, base -> shapes.dashed(P(l + 0.3f * gap, base - 0.5f * gap), P(r - 0.3f * gap, base - 0.5f * gap), color) })

    private companion object {
        const val FIGURE_GROWTH = 3f
        const val FIGURE_MIN = 0.7f
        val EMPTY = RectPt(0f, 0f, 0f, 0f)

        /** Block key of the packed layout's dashed dividers. */
        const val RULES = "rules"
        const val RULE_COLOR = 0xFF7D8696.toInt()
        const val HANG = 0.3f
        const val CELL_SCALE = 0.9f
        const val MAX_COLUMNS = 6
        val BARE_TEX = Regex("""\\[a-zA-Z]+|[\^_]\{?\w""")
    }
}

/** A unit as the AI wrote it ("(m)", "[N]", "\\((m)\\)", "m") → bare ("m"): the page adds its own brackets once. */
internal fun bareUnit(u: String): String {
    var t = u.trim().removePrefix("\\(").removeSuffix("\\)").trim().removeSurrounding("$").trim()
    while (t.length >= 2 && ((t.first() == '(' && t.last() == ')') || (t.first() == '[' && t.last() == ']')) && t.count { it == t.first() } == 1) t = t.substring(1, t.length - 1).trim()
    return t
}
