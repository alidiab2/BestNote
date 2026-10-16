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

    /** Axes with labels ([x], [y], words or LaTeX) and an optional polyline through [points] (in data units). */
    fun axes(x: String, y: String, points: List<Pair<Float, Float>> = emptyList()): VectorDiagram {
        val w = 200f
        val h = 140f
        val o = Vec2(24f, h - 22f)
        val shapes = arrayListOf<VectorShape>(
            VectorShape.Arrow(o, Vec2(w - 10f, o.y)),
            VectorShape.Arrow(o, Vec2(o.x, 8f)),
            VectorShape.Label(Vec2(w - 30f, o.y + 16f), x, latex = true),
            VectorShape.Label(Vec2(2f, 16f), y, latex = true),
        )
        if (points.size >= 2) {
            val maxX = points.maxOf { it.first }.takeIf { it > 0f } ?: 1f
            val maxY = points.maxOf { it.second }.takeIf { it > 0f } ?: 1f
            val minY = minOf(0f, points.minOf { it.second })
            val sx = (w - 50f) / maxX
            val sy = (o.y - 20f) / (maxY - minY)
            shapes += VectorShape.Polyline(points.map { (px, py) -> Vec2(o.x + px.coerceAtLeast(0f) * sx, o.y - (py - minY) * sy) })
        }
        return VectorDiagram(RectPt(0f, 0f, w, h), shapes)
    }
}
