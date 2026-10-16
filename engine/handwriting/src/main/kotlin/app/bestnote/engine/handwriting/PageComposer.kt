package app.bestnote.engine.handwriting

import app.bestnote.core.model.CalloutTone
import app.bestnote.core.model.InkSemantic
import app.bestnote.core.model.PageBlock
import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.BlockMeasurer
import app.bestnote.engine.layout.FlowItem
import app.bestnote.engine.layout.NotebookPalette
import app.bestnote.engine.layout.RuledPaper
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * How the notes are handwritten.
 *
 * @property seed the same seed always writes the same pages.
 * @property jitter how much the hand varies (0 = machine-perfect, 1 = everyday handwriting).
 */
data class HandwritingStyle(
    val seed: Long = 20_261_015L,
    val pen: PenChoice = PenChoice(),
    val script: Script = Script.Cursive,
    val jitter: Float = 0.7f,
    val slantDeg: Float = 4f,
)

/** Handwritten pages: per page, the strokes in writing order with their natural timing. */
class HandwrittenPages(val pages: List<List<WrittenStroke>>) {
    /** Natural writing time of page [i] in ms (the review screen plays it much faster). */
    fun durationMs(i: Int): Long = pages[i].maxOfOrNull { it.endMs } ?: 0L
    val strokeCount: Int get() = pages.sumOf { it.size }
}

/**
 * AI-05/AI-06: writes laid-out [PageBlock]s as real ink on ruled paper, the way a careful student
 * fills a notebook page: every line of writing sits on a ruled line inside the text column, titles
 * are hand-underlined, key formulas get a pen-drawn box, diagrams are drawn along a ruler and
 * margin notes are written in green in the side column with an arrow to what they comment on.
 *
 * The same row model drives [measurer] (AI-06: how many ruled lines a block needs at a width) and
 * the writing, so the layout and the ink always agree. Pure JVM and deterministic per seed.
 */
class PageComposer(
    private val paper: RuledPaper,
    private val style: HandwritingStyle = HandwritingStyle(),
    private val rtl: Boolean = false,
) {
    private val gap = paper.lineGap

    /** x-height of normal writing: about a third of the line gap, like real notebook writing. */
    private val xh = gap * X_HEIGHT

    /** Body text scale: joined script has tall capitals and loops, so it is written a little smaller. */
    private val body = if (style.script == Script.Cursive) 0.9f else 1f

    /** Inline formulas met so far; text carries tokens pointing here ([InlineMath.encode]). */
    private val inlineTex = ArrayList<String>()

    private val metricsHand = hand(0)
    private val metrics = Pen(metricsHand, InkBuilder(metricsHand, style.pen))

    private fun hand(page: Int) = Hand(
        Rand(style.seed * 31 + page),
        xh = xh,
        unitsPerMm = UNITS_PER_MM,
        slantDeg = style.slantDeg,
        jitter = style.jitter,
    )

    /** The writers for one page, sharing one hand and one ink stream. */
    private inner class Pen(val hand: Hand, val ink: InkBuilder) {
        val text = TextWriter(hand, ink)
        val shapes = ShapeWriter(hand, ink)
        val math = MathWriter(hand, text, shapes)

        init {
            text.inline = object : InlineRenderer {
                override fun measure(token: Int, scale: Float): Float {
                    val nodes = nodes(token)
                    return math.measure(nodes, inlineScale(nodes, scale)).width
                }

                override fun write(token: Int, x: Float, baseline: Float, color: Int, scale: Float, gap: Gap): Float {
                    val nodes = nodes(token)
                    return math.write(nodes, x, baseline, color, inlineScale(nodes, scale), gap)
                }
            }
        }

        private fun nodes(token: Int) = MathParser.parse(inlineTex.getOrElse(token) { "" }).flatten()

        /** Inline maths is written at the text's size, smaller only when a fraction would hit the next line. */
        private fun inlineScale(nodes: List<MathParser.Node>, scale: Float): Float {
            val size = scale * INLINE_SIZE
            val m = math.measure(nodes, size)
            val fit = minOf(1f, INLINE_ASCENT * gap / m.ascent.coerceAtLeast(0.01f), INLINE_DESCENT * gap / m.descent.coerceAtLeast(0.01f))
            return size * fit.coerceAtLeast(0.6f)
        }
    }

    /** One ruled-line row of a block (or several, for tall content); [draw] writes it on its last baseline. */
    private class Row(val lines: Int, val blank: Boolean = false, val draw: Pen.(left: Float, right: Float, baseline: Float) -> Unit)

    /** An empty ruled line (air above a box, room under a hanging fraction). */
    private fun blankRow() = Row(1, blank = true) { _, _, _ -> }

    /** Ruled lines [block] needs in a column [width] wide. */
    fun linesFor(block: PageBlock, width: Float): Int = rows(block, width).sumOf { it.lines }.coerceAtLeast(1)

    /** AI-06: a [BlockMeasurer] for [RuledFlow] that measures real handwriting. [blockOf] resolves a flow key. */
    fun measurer(blockOf: (String) -> PageBlock?): BlockMeasurer = BlockMeasurer { item: FlowItem, width: Float ->
        val block = blockOf(item.key) ?: return@BlockMeasurer gap
        linesFor(block, width) * gap
    }

    /** Writes [placed] blocks (page index to block with its frame from [RuledFlow]), in order. */
    fun compose(placed: List<Pair<Int, PageBlock>>): HandwrittenPages {
        val pageCount = (placed.maxOfOrNull { it.first } ?: -1) + 1
        val out = ArrayList<List<WrittenStroke>>(pageCount)
        for (page in 0 until pageCount) {
            val h = hand(page + 1)
            val pen = Pen(h, InkBuilder(h, style.pen))
            val blocks = placed.filter { it.first == page }.map { it.second }
            val byId = blocks.associateBy { it.id }
            for (b in blocks) {
                pen.ink.block = b.id.value
                var baseline = b.frame.top
                for (row in rows(b, b.frame.width)) {
                    baseline += row.lines * gap
                    row.draw(pen, b.frame.left, b.frame.right, baseline)
                }
                if (b is PageBlock.MarginNote) b.anchor?.let(byId::get)?.let { anchor -> marginArrow(pen, b, anchor) }
            }
            out += pen.ink.build()
        }
        return HandwrittenPages(out)
    }

    // ------------------------------------------------------------------ rows per block

    private fun colorOf(s: InkSemantic): Int = if (s == InkSemantic.Regular) style.pen.colorArgb else NotebookPalette.ink(s).toInt()

    /**
     * One pen per role, whatever colour the board used: titles and headings in the emphasis pen,
     * margin notes in the annotation (green) pen, boxed formulas in the essential (red) pen, body
     * text in the user's pen. A role's own semantic wins only when it is stronger than regular.
     */
    private fun roleColor(b: PageBlock, role: InkSemantic): Int = colorOf(if (b.semantic == InkSemantic.Regular) role else b.semantic)

    /** Usable width: a little narrower than the column, since a hand never spaces letters exactly. */
    private fun usable(width: Float) = width * 0.95f

    private fun rows(b: PageBlock, width: Float): List<Row> = when (b) {
        is PageBlock.Heading -> {
            val scale = when (b.level) { 1 -> 1.3f; 2 -> 1.12f; else -> 1f }
            textRows(b.text, width, scale, roleColor(b, InkSemantic.Emphasis), Script.Print, underline = b.level <= 2)
        }
        is PageBlock.Paragraph -> textRows(b.text, width, body, colorOf(b.semantic), style.script)
        is PageBlock.Bullets -> b.items.withIndex().flatMap { (i, item) -> bulletRows(item, if (b.ordered) "${i + 1}." else "•", width, colorOf(b.semantic)) }
        is PageBlock.Callout -> calloutRows(b, width)
        is PageBlock.Math -> mathRows(b.latex, width, if (b.boxed) roleColor(b, InkSemantic.Essential) else colorOf(b.semantic), boxed = b.boxed)
        is PageBlock.Proof -> proofRows(b, width)
        is PageBlock.MarginNote -> textRows(b.text, width, 0.85f * body, roleColor(b, InkSemantic.Annotation), style.script)
        is PageBlock.Diagram -> diagramRows(b, width)
    }

    private fun startX(left: Float, right: Float, indent: Float = 0f) = if (rtl) right - indent else left + indent

    private fun textRows(
        content: String,
        width: Float,
        scale: Float,
        color: Int,
        script: Script,
        underline: Boolean = false,
        indent: Float = 0f,
        firstIndent: Float = 0f,
    ): List<Row> {
        val lines = metrics.text.wrap(InlineMath.encode(content, inlineTex), usable(width) - indent, scale, script, firstIndent)
        return lines.mapIndexed { i, line ->
            Row(1) { left, right, baseline ->
                val x0 = startX(left, right, indent + if (i == 0) firstIndent else 0f)
                val end = text.writeLine(line, x0, baseline, color, scale, script, rtl, if (i == 0 && firstIndent == 0f) Gap.Line else Gap.Word)
                if (underline) shapes.underline(min(x0, end), max(x0, end), baseline + 0.2f * gap, color)
            }
        }
    }

    private fun bulletRows(item: String, marker: String, width: Float, color: Int): List<Row> {
        val indent = 1.7f * xh + metrics.text.measureWord(marker, 1f, Script.Print)
        val rows = textRows(item, width, body, color, style.script, indent = indent)
        if (rows.isEmpty()) return rows
        val first = rows[0]
        return listOf(
            Row(1) { left, right, baseline ->
                val mw = text.measureWord(marker, 1f, Script.Print)
                val x = if (rtl) right - 0.4f * xh - mw else left + 0.4f * xh
                text.writeWord(Baseline(hand, x, baseline, steady = true), marker, color, 1f, Script.Print, Gap.Line)
                first.draw(this, left, right, baseline)
            },
        ) + rows.drop(1)
    }

    private fun calloutRows(b: PageBlock.Callout, width: Float): List<Row> {
        val label = (b.title ?: toneLabel(b.tone)).trimEnd(':') + ":"
        val accent = colorOf(if (b.semantic == InkSemantic.Regular) toneSemantic(b.tone) else b.semantic)
        val labelW = metrics.text.measure(label, 1f, Script.Print)
        val bodyIndent = labelW + metrics.text.spaceWidth() * 1.3f
        val fitsAfter = bodyIndent < usable(width) * 0.45f
        val body = textRows(b.body, width, this.body, colorOf(b.semantic), style.script, firstIndent = if (fitsAfter) bodyIndent else 0f)
        val labelRow = Row(1) { left, right, baseline ->
            val x0 = startX(left, right)
            val end = text.writeLine(label, x0, baseline, accent, 1f, Script.Print, rtl, Gap.Line)
            shapes.underline(min(x0, end), max(x0, end), baseline + 0.2f * gap, accent)
            if (fitsAfter && body.isNotEmpty()) body[0].draw(this, left, right, baseline)
        }
        return if (fitsAfter) listOf(labelRow) + body.drop(1) else listOf(labelRow) + body
    }

    private fun toneLabel(t: CalloutTone) = when (t) {
        CalloutTone.Definition -> "Def."
        CalloutTone.Theorem -> "Theorem"
        CalloutTone.KeyResult -> "Key"
        CalloutTone.Warning -> "Careful"
        CalloutTone.Example -> "Example"
        CalloutTone.Summary -> "Summary"
        CalloutTone.Info -> "Note"
    }

    private fun toneSemantic(t: CalloutTone) = when (t) {
        CalloutTone.KeyResult, CalloutTone.Warning -> InkSemantic.Essential
        CalloutTone.Example -> InkSemantic.Annotation
        else -> InkSemantic.Emphasis
    }

    /** Lines a math row needs: its height above the baseline must fit under the line above. */
    private fun mathLines(m: Metrics) = max(1, ceil((m.ascent + 0.15f * gap) / gap - 0.02f).toInt())

    private fun mathRows(latex: String, width: Float, color: Int, boxed: Boolean, indent: Float = 1.2f * gap): List<Row> {
        val parsed = MathParser.parse(latex)
        val avail = usable(width) - indent - (if (boxed) 1.2f * gap else 0f)
        val sized = parsed.map { nodes ->
            val m = metrics.math.measure(nodes, MATH_SCALE)
            val scale = if (m.width > avail && m.width > 0f) max(0.5f, MATH_SCALE * avail / m.width) else MATH_SCALE
            Triple(nodes, scale, metrics.math.measure(nodes, scale))
        }
        val rows = sized.map { (nodes, scale, m) ->
            Row(mathLines(m)) { left, right, baseline ->
                val pad = if (boxed) 0.5f * gap else 0f
                val x = if (rtl) right - indent - pad - m.width else left + indent + pad
                math.write(nodes, x, baseline, color, scale)
            }
        }
        // A fraction or subscript that hangs below the line would touch the next line's capitals: skip a line.
        if (!boxed) return if (rows.isNotEmpty() && sized.last().third.descent > HANG * gap) rows + blankRow() else rows
        if (rows.isEmpty()) return rows
        val widest = sized.maxOf { it.third.width }
        val firstAscent = sized.first().third.ascent
        val lastDescent = sized.last().third.descent
        val total = rows.sumOf { it.lines }
        // One extra line above gives the box room (and one below when subscripts hang low);
        // the box is drawn after the formula, like a student does.
        val below = if (max(lastDescent, 0.1f * gap) + 0.22f * gap > 0.3f * gap) listOf(blankRow()) else emptyList()
        return listOf(blankRow()) + rows.dropLast(1) + Row(rows.last().lines) { left, right, baseline ->
            rows.last().draw(this, left, right, baseline)
            val pad = 0.5f * gap
            val x0 = if (rtl) right - indent - widest - 2 * pad else left + indent
            val top = baseline - (total - 1) * gap - firstAscent - 0.3f * gap
            val bottom = baseline + max(lastDescent, 0.1f * gap) + 0.22f * gap
            shapes.box(x0, top, x0 + widest + 2 * pad, bottom, NotebookPalette.ESSENTIAL.toInt())
        } + below
    }

    private fun proofRows(b: PageBlock.Proof, width: Float): List<Row> {
        val accent = colorOf(InkSemantic.Emphasis)
        val out = ArrayList<Row>()
        out += textRows((b.title ?: "Proof").trimEnd(':') + ":", width, 1f, accent, Script.Print, underline = true)
        val ink = colorOf(b.semantic)
        val note = colorOf(InkSemantic.Annotation)
        for (s in b.steps) {
            val why = s.justification?.let { "($it)" }
            val whyScale = 0.85f * body
            val whyWidth = why?.let { metrics.text.measure(InlineMath.encode(it, inlineTex), whyScale, style.script) } ?: 0f
            val math = s.latex?.let { mathRows(it, width, ink, boxed = false, indent = 1.6f * xh) }.orEmpty()
            val prose = s.text?.let { textRows(it, width, body, ink, style.script, indent = 1.6f * xh) }.orEmpty()
            val steps = math + prose
            // The reason goes at the end of its line, like "(parts)" written to the right of a step,
            // when the step is a single formula row with room for it; else on its own line.
            val lastFormula = math.lastOrNull { !it.blank }
            val inline = why != null && prose.isEmpty() && math.count { !it.blank } == 1 && s.latex?.let { tex ->
                val widest = MathParser.parse(tex).maxOfOrNull { metrics.math.measure(it, MATH_SCALE).width } ?: 0f
                1.6f * xh + widest + 2.5f * xh + whyWidth <= usable(width)
            } == true
            if (inline && lastFormula != null) {
                out += math.map { row ->
                    if (row !== lastFormula) row else Row(row.lines) { left, right, baseline ->
                        row.draw(this, left, right, baseline)
                        val x = if (rtl) left + whyWidth else right - whyWidth
                        text.writeLine(InlineMath.encode(why!!, inlineTex), x, baseline, note, whyScale, style.script, rtl, Gap.Word)
                    }
                }
            } else {
                out += steps
                why?.let { out += textRows(it, width, whyScale, note, style.script, indent = 4f * xh) }
            }
        }
        return out
    }

    private fun diagramRows(b: PageBlock.Diagram, width: Float): List<Row> {
        val vb = b.diagram.viewBox
        if (vb.isEmpty) return emptyList()
        val maxW = usable(width) * 0.8f
        val maxH = gap * MAX_DIAGRAM_LINES
        val scale = min(maxW / vb.width, maxH / vb.height)
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

    private fun drawShape(pen: Pen, s: VectorShape, map: (Float, Float) -> P) {
        val c = s.style.colorArgb.let { if (it == NotebookPalette.INK || it == 0xFF1F1F1FL) style.pen.colorArgb else it.toInt() }
        when (s) {
            is VectorShape.Line -> if (s.style.dashed) pen.shapes.dashed(map(s.from.x, s.from.y), map(s.to.x, s.to.y), c) else pen.shapes.line(map(s.from.x, s.from.y), map(s.to.x, s.to.y), c)
            is VectorShape.Arrow -> pen.shapes.arrow(map(s.from.x, s.from.y), map(s.to.x, s.to.y), c)
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
                pen.shapes.polyline(corners, closed = true, color = c)
            }
            is VectorShape.Ellipse -> {
                val a = map(s.bounds.left, s.bounds.top)
                val z = map(s.bounds.right, s.bounds.bottom)
                pen.shapes.ellipse((a.x + z.x) / 2, (a.y + z.y) / 2, (z.x - a.x) / 2, (z.y - a.y) / 2, c)
            }
            is VectorShape.Polyline -> pen.shapes.polyline(s.points.map { map(it.x, it.y) }, s.closed, c)
            is VectorShape.Path -> pen.shapes.path(s.d, map, c)
            is VectorShape.Label -> {
                val at = map(s.at.x, s.at.y)
                if (s.latex) {
                    MathParser.parse(s.text).firstOrNull()?.let { pen.math.write(it, at.x, at.y, c, LABEL_SCALE, Gap.Shape) }
                } else {
                    pen.text.writeLine(s.text, at.x, at.y, c, LABEL_SCALE, Script.Print, rtl = false, firstGap = Gap.Shape, steady = true)
                }
            }
        }
    }

    /** A curved green arrow from the margin note back to the end of the writing it comments on. */
    private fun marginArrow(pen: Pen, note: PageBlock.MarginNote, anchor: PageBlock) {
        val inkBounds = pen.ink.boundsOf(anchor.id.value) ?: return
        val noteBounds = pen.ink.boundsOf(note.id.value) ?: return
        val y = (inkBounds[1] + inkBounds[3]) / 2
        val color = roleColor(note, InkSemantic.Annotation)
        if (!rtl) {
            val from = P(noteBounds[0] - 0.25f * gap, note.frame.top + 0.55f * gap)
            val to = P(min(inkBounds[2] + 0.3f * gap, from.x - gap), y)
            if (from.x - to.x > 0.6f * gap) pen.shapes.curvedArrow(from, to, color, bend = -0.1f)
        } else {
            val from = P(noteBounds[2] + 0.25f * gap, note.frame.top + 0.55f * gap)
            val to = P(max(inkBounds[0] - 0.3f * gap, from.x + gap), y)
            if (to.x - from.x > 0.6f * gap) pen.shapes.curvedArrow(from, to, color, bend = 0.1f)
        }
    }

    companion object {
        /** Page units are points. */
        const val UNITS_PER_MM = 72f / 25.4f
        const val X_HEIGHT = 0.3f
        const val MAX_DIAGRAM_LINES = 9f
        const val LABEL_SCALE = 0.85f
        const val MATH_SCALE = 1.3f

        /** Inline maths may rise this many line gaps above the baseline and drop this many below it. */
        const val INLINE_ASCENT = 0.8f

        /** Print maths beside joined script looks small at the same x-height; written a bit larger, like display maths. */
        const val INLINE_SIZE = 1.2f
        const val INLINE_DESCENT = 0.42f

        /** Display maths hanging further below the baseline (in line gaps) gets a blank line after it. */
        const val HANG = 0.3f
    }
}
