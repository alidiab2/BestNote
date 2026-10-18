package app.bestnote.engine.handwriting

import app.bestnote.core.model.LineDash
import app.bestnote.core.model.ShapeInfo
import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.FigureRaster
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Most ruled lines a board drawing may take (a tall figure is scaled down to fit). */
internal const val MAX_FIGURE_LINES = 20f

/**
 * AI-12: a redrawn board figure as rows of the font writer. Every shape is ONE clean stroke (no hand
 * tremor) carrying CANVAS-17 shape metadata, so it selects, moves and edits like a shape the user
 * drew: lines, arrows (shaft and head in one stroke), polylines (arcs, springs, curves), circles;
 * dashed lines keep their dash. Labels are typeset centred where the teacher wrote them. The drawing
 * is [frac] of the column wide (at most [MAX_FIGURE_LINES] lines tall), centred in the column.
 */
internal fun FontComposer.figureRows(d: VectorDiagram, frac: Float, width: Float, recolour: (Long) -> Int, labelsPlaced: Boolean = false): List<FontComposer.Row> {
    val vb = d.viewBox
    if (vb.isEmpty) return emptyList()
    val scale = min(usable(width) * frac / vb.width, gap * MAX_FIGURE_LINES / vb.height)
    val w = vb.width * scale
    val h = vb.height * scale
    val lines = max(2, ceil((h + 0.6f * gap) / gap).toInt())
    return listOf(
        FontComposer.Row(lines) { left, right, baseline ->
            val top = baseline - lines * gap + 0.4f * gap
            val ox = left + (right - left - w) / 2
            fun map(x: Float, y: Float) = P(ox + (x - vb.left) * scale, top + (y - vb.top) * scale)
            val labelSize = body * LABEL_SCALE
            for (s in d.shapes) if (s !is VectorShape.Label) drawFigureShape(this, s, ::map, scale, recolour(s.style.colorArgb), labelSize)
            placeLabels(this, d, ::map, labelSize, recolour, left, right, labelsPlaced)
        },
    )
}

/**
 * FIG-LIB: the column share at which a library drawing's labels ([app.bestnote.engine.layout.figlib.Pen.FONT]
 * units) come out at the note's label size; small drawings are not shrunk below a readable share.
 */
internal fun FontComposer.libraryFrac(d: VectorDiagram, width: Float): Float =
    (d.viewBox.width * (body * LABEL_SCALE / app.bestnote.engine.layout.figlib.Pen.FONT) / usable(width)).coerceIn(LIBRARY_MIN_FRAC, 1f)

/** A board drawing kept as a cleaned picture (the flagged last resort), as wide as [frac] of the column. */
internal fun FontComposer.pictureRows(r: FigureRaster, frac: Float, width: Float): List<FontComposer.Row> {
    if (r.width <= 0 || r.height <= 0) return emptyList()
    val scale = min(usable(width) * frac / r.width, gap * MAX_FIGURE_LINES / r.height)
    val w = r.width * scale
    val h = r.height * scale
    val lines = max(2, ceil((h + 0.6f * gap) / gap).toInt())
    return listOf(
        FontComposer.Row(lines) { left, right, baseline ->
            val top = baseline - lines * gap + 0.4f * gap
            images += TypeImage(r.asset, left + (right - left - w) / 2, top, w, h, r.width, r.height, block)
        },
    )
}

/**
 * Labels where the teacher wrote them, but never on a line: each label's box is tested against the
 * drawing's strokes and the labels already placed, and moves to the nearest free spot around it.
 * [placed0]: the labels were placed by the figure library already and are written where they are.
 */
private fun FontComposer.placeLabels(sheet: FontComposer.Sheet, d: VectorDiagram, map: (Float, Float) -> P, size: Float, recolour: (Long) -> Int, left: Float, right: Float, placed0: Boolean = false) {
    val obstacles = ArrayList<P>()
    fun seg(a: P, b: P) {
        val n = max(1, (kotlin.math.hypot(b.x - a.x, b.y - a.y) / 0.8f).toInt())
        for (i in 0..n) obstacles += P(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n)
    }
    for (s in d.shapes) when (s) {
        is VectorShape.Line -> seg(map(s.from.x, s.from.y), map(s.to.x, s.to.y))
        is VectorShape.Arrow -> seg(map(s.from.x, s.from.y), map(s.to.x, s.to.y))
        is VectorShape.Polyline -> s.points.map { map(it.x, it.y) }.zipWithNext().forEach { (a, b) -> seg(a, b) }
        is VectorShape.Ellipse -> {
            val a = map(s.bounds.left, s.bounds.top)
            val z = map(s.bounds.right, s.bounds.bottom)
            for (i in 0 until 48) {
                val t = i * 2 * Math.PI / 48
                obstacles += P((a.x + z.x) / 2 + (z.x - a.x) / 2 * cos(t).toFloat(), (a.y + z.y) / 2 + (z.y - a.y) / 2 * sin(t).toFloat())
            }
        }
        else -> Unit
    }
    val placed = ArrayList<FloatArray>()
    for (s in d.shapes.filterIsInstance<VectorShape.Label>()) {
        val at = map(s.at.x, s.at.y)
        val maths = s.text.all { it.code < ASCII }
        val box = if (maths) MathParser.parse(s.text).firstOrNull()?.let { layout(it, size) } else null
        // A label with words ("\text{מצב} B") is written as text, without its LaTeX.
        val text = if (maths) s.text else plainLabel(s.text)
        val font = fontFor(text)
        val w = box?.width ?: widthOf(text, font, size)
        val h = box?.let { it.ascent + it.descent } ?: (size * 1.1f)
        val pad = 0.6f
        fun free(cx: Float, cy: Float): Boolean {
            val r = floatArrayOf(cx - w / 2 - pad, cy - h / 2 - pad, cx + w / 2 + pad, cy + h / 2 + pad)
            // Inside the column: never across its divider.
            if (r[0] < left || r[2] > right) return false
            if (obstacles.any { it.x in r[0]..r[2] && it.y in r[1]..r[3] }) return false
            return placed.none { o -> r[0] < o[2] && o[0] < r[2] && r[1] < o[3] && o[1] < r[3] }
        }
        var c = P(at.x.coerceIn(left + w / 2, max(left + w / 2, right - w / 2)), at.y)
        // FIG-LIB drawings come with their labels already placed beside their shapes (at this size).
        if (!placed0) search@ for (ring in 0..LABEL_RINGS) {
            val rad = ring * 0.5f * size
            val dirs = if (ring == 0) 1 else 12
            for (k in 0 until dirs) {
                val a = k * 2 * Math.PI / dirs
                val p = P(at.x + rad * cos(a).toFloat() * (w / h).coerceIn(1f, 3f), at.y + rad * sin(a).toFloat())
                if (free(p.x, p.y)) { c = p; break@search }
            }
        }
        placed += floatArrayOf(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
        val color = recolour(s.style.colorArgb)
        if (box != null) {
            box.draw(sheet, c.x - w / 2, c.y + (box.ascent - box.descent) / 2, color)
        } else {
            sheet.text(text, c.x - w / 2, c.y + 0.35f * size, w, size, color, font, isRtlText(text))
        }
    }
}

/** "\text{מצב } B" → "מצב B": LaTeX text commands and spacing dropped. */
internal fun plainLabel(tex: String): String =
    tex.replace(Regex("""\\(text|mathrm|textrm|mbox)\s*\{([^}]*)\}"""), "$2").replace(Regex("""\\[,;: ]"""), " ")
        .replace(Regex("""\\(?=[{}_^])"""), "").replace(Regex("""[{}]"""), "").replace(Regex("""\s+"""), " ").trim()

private var shapeCounter = 0L

private fun shapeId(): String = "sb" + java.lang.Long.toString(System.nanoTime() xor (++shapeCounter shl 20), 36)

private fun flat(vararg p: P): List<Float> = p.flatMap { listOf(it.x, it.y) }

private fun FontComposer.drawFigureShape(sheet: FontComposer.Sheet, s: VectorShape, map: (Float, Float) -> P, scale: Float, color: Int, labelSize: Float) {
    val ink = sheet.ink
    val dash = if (s.style.dashed) LineDash.Dashed else LineDash.Solid
    when (s) {
        is VectorShape.Line -> {
            val a = map(s.from.x, s.from.y)
            val b = map(s.to.x, s.to.y)
            ink.addShape(listOf(a, b), color, ShapeInfo(shapeId(), "Line", flat(a, b)), dash)
        }
        is VectorShape.Arrow -> {
            val a = map(s.from.x, s.from.y)
            val tip = map(s.to.x, s.to.y)
            val len = (s.headSize * scale).coerceIn(1.6f * PageComposer.UNITS_PER_MM, 4.5f * PageComposer.UNITS_PER_MM)
            val back = atan2(a.y - tip.y, a.x - tip.x)
            val b1 = P(tip.x + len * cos(back - HEAD_SPREAD), tip.y + len * sin(back - HEAD_SPREAD))
            val b2 = P(tip.x + len * cos(back + HEAD_SPREAD), tip.y + len * sin(back + HEAD_SPREAD))
            ink.addShape(listOf(a, tip, b1, tip, b2), color, ShapeInfo(shapeId(), "Arrow", flat(a, tip, b1, b2)), dash)
        }
        is VectorShape.Polyline -> {
            val pts = s.points.map { map(it.x, it.y) }
            val closed = if (s.closed) pts + pts.first() else pts
            ink.addShape(closed, color, ShapeInfo(shapeId(), if (s.closed) "Polygon" else "Polyline", flat(*pts.toTypedArray())), dash)
        }
        is VectorShape.Ellipse -> {
            val a = map(s.bounds.left, s.bounds.top)
            val z = map(s.bounds.right, s.bounds.bottom)
            val cx = (a.x + z.x) / 2
            val cy = (a.y + z.y) / 2
            val rx = (z.x - a.x) / 2
            val ry = (z.y - a.y) / 2
            val pts = (0..ELLIPSE_STEPS).map { i -> val t = i * 2 * Math.PI / ELLIPSE_STEPS; P(cx + rx * cos(t).toFloat(), cy + ry * sin(t).toFloat()) }
            val kind = if (kotlin.math.abs(rx - ry) < 0.05f * max(rx, ry)) "Circle" else "Ellipse"
            ink.addShape(pts, color, ShapeInfo(shapeId(), kind, listOf(cx, cy, cx + rx, cy, cx, cy + ry)), dash)
        }
        is VectorShape.Rect -> drawShape(sheet, s, map)
        is VectorShape.Path -> drawShape(sheet, s, map)
        is VectorShape.Label -> {
            // Centred on where the teacher wrote it.
            val at = map(s.at.x, s.at.y)
            val box = MathParser.parse(s.text).firstOrNull()?.let { layout(it, labelSize) }
            // Symbols, numbers and LaTeX are maths; words (Hebrew, Arabic) are handwriting.
            if (box != null && s.text.all { it.code < ASCII }) {
                box.draw(sheet, at.x - box.width / 2, at.y + (box.ascent - box.descent) / 2, color)
            } else {
                val font = fontFor(s.text)
                val w = widthOf(s.text, font, labelSize)
                sheet.text(s.text, at.x - w / 2, at.y + 0.35f * labelSize, w, labelSize, color, font, isRtlText(s.text))
            }
        }
    }
}

private const val HEAD_SPREAD = 0.45f
private const val ELLIPSE_STEPS = 72
private const val LABEL_SCALE = 0.85f
private const val LIBRARY_MIN_FRAC = 0.35f
private const val ASCII = 128
private const val LABEL_RINGS = 6
