package app.bestnote.engine.handwriting

import app.bestnote.engine.handwriting.MathParser.DecoKind

/** Room and pen strokes of the marks drawn on maths ([MathParser.Deco]): shared by the stroke hand and the font writer. */
internal object DecoGeometry {
    /** Extra room around the body: [side] added to the width, [up] above it, [down] below it. */
    class Extra(val side: Float, val up: Float, val down: Float)

    /** [unit] is one x-height at the current scale. */
    fun extra(kind: DecoKind, unit: Float): Extra = when (kind) {
        DecoKind.Cancel, DecoKind.BCancel, DecoKind.XCancel -> Extra(0.2f * unit, 0.1f * unit, 0.1f * unit)
        DecoKind.CancelTo -> Extra(0.2f * unit, 0.1f * unit, 0.1f * unit)
        DecoKind.Under -> Extra(0f, 0f, 0.3f * unit)
        DecoKind.Over -> Extra(0f, 0.3f * unit, 0f)
        DecoKind.UnderBrace -> Extra(0f, 0f, 0.7f * unit)
        DecoKind.OverBrace -> Extra(0f, 0.7f * unit, 0f)
        DecoKind.Box -> Extra(0.6f * unit, 0.3f * unit, 0.3f * unit)
        DecoKind.Vec -> Extra(0f, 0.5f * unit, 0f)
        DecoKind.Hat -> Extra(0f, 0.5f * unit, 0f)
    }

    /** Draws the mark for a body of [w] x ([asc] above, [desc] below [baseline]) whose left edge is [x]. */
    fun draw(s: ShapeWriter, kind: DecoKind, x: Float, baseline: Float, w: Float, asc: Float, desc: Float, unit: Float, color: Int, e: Extra) {
        val top = baseline - asc
        val bottom = baseline + desc
        fun line(a: P, b: P) = s.line(a, b, color, gap = Gap.Letter, ruler = false)
        val o = 0.1f * unit
        when (kind) {
            DecoKind.Cancel, DecoKind.CancelTo -> line(P(x - o, bottom + o), P(x + w + o, top - o))
            DecoKind.BCancel -> line(P(x - o, top - o), P(x + w + o, bottom + o))
            DecoKind.XCancel -> {
                line(P(x - o, bottom + o), P(x + w + o, top - o))
                line(P(x - o, top - o), P(x + w + o, bottom + o))
            }
            DecoKind.Under -> line(P(x, bottom + 0.15f * unit), P(x + w, bottom + 0.15f * unit))
            DecoKind.Over -> line(P(x, top - 0.15f * unit), P(x + w, top - 0.15f * unit))
            DecoKind.Box -> s.box(x - 0.3f * unit, top - 0.3f * unit, x + w + 0.3f * unit, bottom + 0.3f * unit, color)
            DecoKind.UnderBrace, DecoKind.OverBrace -> {
                val under = kind == DecoKind.UnderBrace
                val sign = if (under) 1f else -1f
                val y0 = (if (under) bottom else top) + sign * 0.15f * unit // brace ends (towards the body)
                val y1 = y0 + sign * 0.2f * unit
                val y2 = y0 + sign * 0.45f * unit // the tip
                val mid = x + w / 2
                val r = 0.25f * unit
                s.polyline(
                    listOf(P(x, y0), P(x + r * 0.5f, y1), P(mid - r, y1), P(mid, y2), P(mid + r, y1), P(x + w - r * 0.5f, y1), P(x + w, y0)),
                    closed = false, color = color, ruler = false,
                )
            }
            DecoKind.Vec -> {
                val y = top - 0.25f * unit
                line(P(x, y), P(x + w, y))
                s.polyline(listOf(P(x + w - 0.25f * unit, y - 0.18f * unit), P(x + w, y), P(x + w - 0.25f * unit, y + 0.18f * unit)), closed = false, color = color, ruler = false)
            }
            DecoKind.Hat -> {
                val y = top - 0.12f * unit
                val mid = x + w / 2
                s.polyline(listOf(P(mid - 0.25f * unit, y), P(mid, y - 0.3f * unit), P(mid + 0.25f * unit, y)), closed = false, color = color, ruler = false)
            }
        }
    }
}

/** A formula written by the stroke hand: [strokes] are polylines (x0, y0, x1, y1…) in points, baseline of the first row at y = 0. */
class HandMathInk(val width: Float, val ascent: Float, val descent: Float, val strokes: List<FloatArray>, val thickness: Float)

/** Handwritten maths for equation elements: the same hand as the Smart Board's stroke writer, deterministic per formula. */
object HandMath {
    private const val ROW_GAP = 1.25f

    /** Null when the hand cannot write [latex] (a symbol it has no strokes for). */
    fun write(latex: String, sizePt: Float): HandMathInk? {
        if (!StrokeHand.canWriteLatex(latex)) return null
        val hand = Hand(Rand(latex.hashCode().toLong()), xh = sizePt * 0.42f, unitsPerMm = PageComposer.UNITS_PER_MM, jitter = 0.35f)
        val ink = InkBuilder(hand, PenChoice())
        val text = TextWriter(hand, ink)
        val math = MathWriter(hand, text, ShapeWriter(hand, ink))
        val rows = MathParser.parse(latex)
        if (rows.isEmpty()) return HandMathInk(0f, sizePt * 0.7f, 0f, emptyList(), 1f)
        val metrics = rows.map { math.measure(it, 1f) }
        var width = 0f
        var y = 0f
        var ascent = 0f
        rows.forEachIndexed { k, nodes ->
            val m = metrics[k]
            if (k == 0) { ascent = m.ascent } else { y += metrics[k - 1].descent + ROW_GAP * hand.xh + m.ascent }
            math.write(nodes, 0f, y, 0, 1f)
            width = maxOf(width, m.width)
        }
        val strokes = ink.build().map { w -> w.stroke.samples.flatMap { listOf(it.x, it.y) }.toFloatArray() }
        return HandMathInk(width, ascent, y + metrics.last().descent - 0f, strokes, hand.xh * 0.16f)
    }
}
