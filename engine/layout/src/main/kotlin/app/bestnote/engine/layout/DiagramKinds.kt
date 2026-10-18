package app.bestnote.engine.layout

import app.bestnote.core.model.RectPt
import app.bestnote.core.model.StrokeStyle
import app.bestnote.core.model.Vec2
import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Drawings the AI asks for by DATA instead of coordinates, drawn here with a ruler: the model only
 * says which forces act or which nodes connect, the app guarantees straight arrows that start on the
 * body, evenly spaced boxes and labelled axes. Fewer tokens, and always neat.
 */
object DiagramKinds {
    /** A force on the body: [label] (LaTeX such as "mg", "F_N") pointing at [angleDeg] (0 = right, 90 = up). */
    data class Force(val label: String, val angleDeg: Float)

    /** Free-body diagram: a box (optionally on a slope of [slopeDeg]) with labelled force arrows from its centre. */
    fun freeBody(forces: List<Force>, slopeDeg: Float = 0f): VectorDiagram? {
        if (forces.isEmpty()) return null
        val w = 200f
        val h = 160f
        val c = Vec2(w / 2, h / 2)
        val half = 18f
        val shapes = ArrayList<VectorShape>()
        val ground = if (slopeDeg == 0f) {
            VectorShape.Line(Vec2(20f, c.y + half), Vec2(w - 20f, c.y + half))
        } else {
            val r = Math.toRadians(slopeDeg.toDouble())
            val dx = (cos(r) * 90).toFloat()
            val dy = (sin(r) * 90).toFloat()
            VectorShape.Line(Vec2(c.x - dx, c.y + half + dy), Vec2(c.x + dx, c.y + half - dy))
        }
        shapes += ground
        shapes += VectorShape.Rect(RectPt(c.x - half, c.y - half, c.x + half, c.y + half), rotationDeg = -slopeDeg)
        for (f in forces) {
            val r = Math.toRadians(f.angleDeg.toDouble())
            val ux = cos(r).toFloat()
            val uy = -sin(r).toFloat()
            val len = 58f
            val to = Vec2(c.x + ux * len, c.y + uy * len)
            shapes += VectorShape.Arrow(c, to, style = StrokeStyle(colorArgb = NotebookPalette.ink(app.bestnote.core.model.InkSemantic.Essential)))
            val lx = to.x + ux * 10f + (if (ux < -0.3f) -10f else 2f)
            val ly = to.y + uy * 10f + (if (uy > 0.3f) 8f else 0f)
            shapes += VectorShape.Label(Vec2(lx.coerceIn(2f, w - 14f), ly.coerceIn(10f, h - 2f)), f.label, latex = true)
        }
        return VectorDiagram(RectPt(0f, 0f, w, h), shapes)
    }

    /** One arrow of a flowchart: node [from] → node [to], with an optional [label] ("yes", "כן"). */
    data class Edge(val from: Int, val to: Int, val label: String? = null)

    /**
     * Decision tree / flowchart: nodes in levels (breadth first from node 0), each level a row of
     * evenly spaced boxes, arrows from the bottom of a box to the top of the next.
     */
    fun flow(nodes: List<String>, edges: List<Edge>): VectorDiagram? {
        if (nodes.isEmpty()) return null
        val level = IntArray(nodes.size) { -1 }
        level[0] = 0
        val queue = ArrayDeque(listOf(0))
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            for (e in edges) if (e.from == n && e.to in nodes.indices && level[e.to] < 0) {
                level[e.to] = level[n] + 1
                queue += e.to
            }
        }
        var extra = (level.maxOrNull() ?: 0) + 1
        for (i in level.indices) if (level[i] < 0) level[i] = extra++
        val rows = level.indices.groupBy { level[it] }.toSortedMap().values.toList()
        val boxW = 70f
        val boxH = 24f
        val gapY = 30f
        val widest = rows.maxOf { it.size }
        val w = max(200f, widest * (boxW + 16f) + 16f)
        val h = rows.size * (boxH + gapY) + 8f
        val boxes = HashMap<Int, RectPt>()
        val shapes = ArrayList<VectorShape>()
        rows.forEachIndexed { r, ids ->
            val step = w / ids.size
            ids.forEachIndexed { k, id ->
                val cx = step * (k + 0.5f)
                val top = 4f + r * (boxH + gapY)
                val box = RectPt(cx - boxW / 2, top, cx + boxW / 2, top + boxH)
                boxes[id] = box
                shapes += VectorShape.Rect(box)
                shapes += VectorShape.Label(Vec2(box.left + 4f, box.bottom - 7f), nodes[id], latex = false)
            }
        }
        for (e in edges) {
            val a = boxes[e.from] ?: continue
            val b = boxes[e.to] ?: continue
            val from = Vec2((a.left + a.right) / 2, a.bottom)
            val to = Vec2((b.left + b.right) / 2, b.top)
            shapes += VectorShape.Arrow(from, to)
            e.label?.takeIf { it.isNotBlank() }?.let { shapes += VectorShape.Label(Vec2((from.x + to.x) / 2 + 3f, (from.y + to.y) / 2 + 4f), it, latex = false) }
        }
        return VectorDiagram(RectPt(0f, 0f, w, h), shapes)
    }

    /**
     * Axes with labels ([x], [y], words or LaTeX) and an optional polyline through [points] (data units),
     * drawn as a real graph (AI-12a): the data's range (negative values too), the points' values as ticks
     * on both axes with dashed guides, and the area under the line hatched when the line starts and ends
     * on the x axis (an F–Δx trapezoid: the work is that area).
     */
    fun axes(x: String, y: String, points: List<Pair<Float, Float>> = emptyList()): VectorDiagram {
        val w = 220f
        val h = 150f
        val left = 30f
        val bottom = h - 24f
        val top = 14f
        val right = w - 16f
        val pts = points.filter { it.first.isFinite() && it.second.isFinite() }
        if (pts.size < 2) {
            val o = Vec2(left, bottom)
            return VectorDiagram(
                RectPt(0f, 0f, w, h),
                listOf(
                    VectorShape.Arrow(o, Vec2(w - 10f, o.y)),
                    VectorShape.Arrow(o, Vec2(o.x, 8f)),
                    VectorShape.Label(Vec2(w - 22f, o.y + 16f), x, latex = true),
                    VectorShape.Label(Vec2(4f, 14f), y, latex = true),
                ),
            )
        }
        val minX = minOf(0f, pts.minOf { it.first })
        val maxX = pts.maxOf { it.first }.let { if (it - minX < 1e-6f) minX + 1f else it }
        val minY = minOf(0f, pts.minOf { it.second })
        val maxY = pts.maxOf { it.second }.let { if (it - minY < 1e-6f) minY + 1f else it }
        fun px(v: Float) = left + (v - minX) / (maxX - minX) * (right - left - 10f)
        fun py(v: Float) = bottom - (v - minY) / (maxY - minY) * (bottom - top - 8f)
        val ox = px(0f)
        val oy = py(0f)
        val shapes = arrayListOf<VectorShape>(
            VectorShape.Arrow(Vec2(left - 6f, oy), Vec2(w - 6f, oy)),
            VectorShape.Arrow(Vec2(ox, bottom + 6f), Vec2(ox, 4f)),
            VectorShape.Label(Vec2(w - 22f, oy + 16f), x, latex = true),
            VectorShape.Label(Vec2(ox + 5f, 12f), y, latex = true),
        )
        val line = pts.map { Vec2(px(it.first), py(it.second)) }
        // Hatch the area between the line and the x axis (vertical strokes, light).
        val closed = pts.first().second == 0f && pts.last().second == 0f && pts.size >= 3
        if (closed) {
            val hatch = StrokeStyle(colorArgb = NotebookPalette.ink(app.bestnote.core.model.InkSemantic.Emphasis), width = 0.6f)
            val x0 = line.first().x
            val x1 = line.last().x
            var hx = x0 + HATCH
            while (hx < x1 - 1f) {
                val yAt = yOn(line, hx)
                if (yAt != null && oy - yAt > 3f) shapes += VectorShape.Line(Vec2(hx, oy), Vec2(hx, yAt + 1f), hatch)
                hx += HATCH
            }
        }
        shapes += VectorShape.Polyline(line, style = StrokeStyle(colorArgb = NotebookPalette.ink(app.bestnote.core.model.InkSemantic.Essential)))
        // Ticks: the points' values on each axis (at most six each), with dashed guides to the point.
        val guide = StrokeStyle(width = 0.6f, dashed = true)
        val xTicks = pts.map { it.first }.filter { it != 0f }.distinct().take(MAX_TICKS)
        val yTicks = pts.map { it.second }.filter { it != 0f }.distinct().take(MAX_TICKS)
        for (v in xTicks) {
            val tx = px(v)
            shapes += VectorShape.Line(Vec2(tx, oy - 2f), Vec2(tx, oy + 2f))
            shapes += VectorShape.Label(Vec2(tx - 3f * number(v).length, oy + 13f), number(v), latex = true)
        }
        for (v in yTicks) {
            val ty = py(v)
            shapes += VectorShape.Line(Vec2(ox - 2f, ty), Vec2(ox + 2f, ty))
            shapes += VectorShape.Label(Vec2((ox - 6f - 6f * number(v).length).coerceAtLeast(0f), ty + 4f), number(v), latex = true)
            val first = line.firstOrNull { kotlin.math.abs(it.y - ty) < 0.5f && it.x > ox + 3f }
            if (first != null) shapes += VectorShape.Line(Vec2(ox, ty), first, guide)
        }
        for (p in line) if (p.y < oy - 3f) shapes += VectorShape.Line(Vec2(p.x, oy), p, guide)
        return VectorDiagram(RectPt(0f, 0f, w, h), shapes)
    }

    /** The y of the polyline [line] at [x], or null outside it. */
    private fun yOn(line: List<Vec2>, x: Float): Float? {
        for ((a, b) in line.zipWithNext()) {
            val lo = minOf(a.x, b.x)
            val hi = maxOf(a.x, b.x)
            if (x < lo || x > hi || hi - lo < 1e-6f) continue
            return a.y + (b.y - a.y) * (x - a.x) / (b.x - a.x)
        }
        return null
    }

    /** 5 → "5", 2.5 → "2.5". */
    private fun number(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')

    private const val HATCH = 8f
    private const val MAX_TICKS = 6
}
