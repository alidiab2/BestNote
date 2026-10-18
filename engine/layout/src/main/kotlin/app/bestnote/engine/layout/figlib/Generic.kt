package app.bestnote.engine.layout.figlib

import app.bestnote.core.model.Vec2
import app.bestnote.engine.layout.figlib.Pen.Companion.mid
import app.bestnote.engine.layout.figlib.Pen.Companion.unit
import app.bestnote.engine.layout.figlib.Pen.Role

/**
 * FIG-LIB generic: a [sketch] of basic marks placed where the board has them (0–100 across the
 * drawing's box, y down like the photo): boxes, circles, lines, arrows, braces, callouts, labels,
 * hatched surfaces, dimension arrows, smooth curves. For anything the library has no kind for.
 */
internal object Generic {
    val all: List<FigElement> by lazy {
        listOf(
            FigElement(
                "sketch", Subject.Generic,
                "0-100 box coords, y down: box=[x,y,w,h,text]; circ=[x,y,r,text]; line|dash|arrow|darrow=[x1,y1,x2,y2,label]; poly|curve=[x,y,…]; text|dot=[x,y,text]; brace=[x1,y1,x2,y2,label]; callout=[x,y,tx,ty,text]; hatch=[x1,y1,x2,y2]",
                listOf("generic", "drawing", "diagram", "arrows", "boxes", "labels", "braces", "callouts", "primitives", "free"),
                ::sketch,
            ),
        )
    }

    /** Box units → drawing units (maths coordinates, y up). */
    private const val S = 2.2f

    private fun pt(x: Float, y: Float) = Vec2(x * S, -y * S)

    private fun sketch(p: Params, pen: Pen): Boolean {
        var drew = false
        for (row in p.rows) {
            if (row.isEmpty()) continue
            val key = row[0].toString().lowercase().filter(Char::isLetter)
            val v = row.drop(1)
            val n = v.mapNotNull(Params::numOf).map { it.coerceIn(-50f, 150f) }
            val text = v.lastOrNull()?.takeIf { Params.numOf(it) == null }?.let(Params::textOf)
            fun p2(i: Int) = pt(n[i], n[i + 1])
            when (key) {
                "box", "rect", "rectangle" -> if (n.size >= 4) {
                    val c = pt(n[0] + n[2] / 2, n[1] + n[3] / 2)
                    pen.rect(c, kotlin.math.abs(n[2]) * S, kotlin.math.abs(n[3]) * S)
                    pen.label(text, c, inside = true)
                    drew = true
                }
                "circ", "circle" -> if (n.size >= 3) {
                    pen.circle(p2(0), n[2] * S)
                    pen.label(text, p2(0), inside = true)
                    drew = true
                }
                "ell", "ellipse", "oval" -> if (n.size >= 4) {
                    pen.ellipse(p2(0), n[2] * S, n[3] * S)
                    pen.label(text, p2(0), inside = true)
                    drew = true
                }
                "line", "seg", "dash", "dashed", "arrow", "vec", "darrow", "double", "dim", "dimension" -> if (n.size >= 4) {
                    val a = p2(0)
                    val b = p2(2)
                    when (key) {
                        "line", "seg" -> pen.line(a, b)
                        "dash", "dashed" -> pen.line(a, b, dashed = true)
                        "arrow", "vec" -> pen.arrow(a, b)
                        "darrow", "double" -> pen.doubleArrow(a, b)
                        else -> pen.dimension(a, b, null, offset = 0f)
                    }
                    if (text != null) {
                        if (key.startsWith("arrow") || key == "vec") pen.label(text, b, unit(b - a), role = Role.Vector)
                        else pen.label(text, mid(a, b), unit(b - a).rot90().let { if (it.y < 0f) it * -1f else it }, gap = 2f)
                    }
                    drew = true
                }
                "poly", "polygon", "polyline", "curve", "path" -> if (n.size >= 4) {
                    val pts = n.chunked(2).filter { it.size == 2 }.map { pt(it[0], it[1]) }
                    val closed = key == "polygon"
                    pen.poly(if (key == "curve" || key == "path") smooth(pts) else pts, closed = closed)
                    drew = true
                }
                "text", "label", "t" -> if (n.size >= 2 && text != null) {
                    pen.label(text, p2(0), inside = true)
                    drew = true
                }
                "dot", "point" -> if (n.size >= 2) {
                    pen.dot(p2(0))
                    pen.label(text, p2(0), null, gap = 2f)
                    drew = true
                }
                "brace" -> if (n.size >= 4) {
                    pen.brace(p2(0), p2(2), text)
                    drew = true
                }
                "callout", "leader" -> if (n.size >= 4 && text != null) {
                    val a = p2(0)
                    val b = p2(2)
                    pen.dot(a, 1.2f)
                    pen.line(a, b - unit(b - a) * 4f)
                    pen.label(text, b, unit(b - a), gap = 0f)
                    drew = true
                }
                "hatch", "ground", "wall", "surface" -> if (n.size >= 4) {
                    pen.surface(p2(0), p2(2), side = -1)
                    drew = true
                }
            }
        }
        return drew
    }

    /** A Catmull–Rom curve through [pts]. */
    internal fun smooth(pts: List<Vec2>): List<Vec2> {
        if (pts.size < 3) return pts
        val out = ArrayList<Vec2>()
        for (i in 0 until pts.size - 1) {
            val p0 = pts[maxOf(0, i - 1)]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts[minOf(pts.size - 1, i + 2)]
            for (k in 0 until 8) {
                val t = k / 8f
                val t2 = t * t
                val t3 = t2 * t
                fun c(a: Float, b: Float, c: Float, d: Float) = 0.5f * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
                out += Vec2(c(p0.x, p1.x, p2.x, p3.x), c(p0.y, p1.y, p2.y, p3.y))
            }
        }
        out += pts.last()
        return out
    }
}
