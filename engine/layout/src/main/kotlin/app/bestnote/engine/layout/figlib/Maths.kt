package app.bestnote.engine.layout.figlib

import app.bestnote.core.model.Vec2
import app.bestnote.engine.layout.FigureMath
import app.bestnote.engine.layout.figlib.Pen.Companion.angleOf
import app.bestnote.engine.layout.figlib.Pen.Companion.dir
import app.bestnote.engine.layout.figlib.Pen.Companion.mid
import app.bestnote.engine.layout.figlib.Pen.Companion.unit
import app.bestnote.engine.layout.figlib.Pen.Role
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** FIG-LIB maths: function plots, number lines, geometry constructions, triangles, Venn diagrams, 3D solids, probability trees. */
internal object Maths {
    val all: List<FigElement> by lazy {
        listOf(
            FigElement("plot", Subject.Maths, "x|y=[min,max]; f=[expr, label]…; pts=[x1,y1,…]; pt=[label,x,y]…; drop; asy=[x|y, v]; shade=[a,b(,between)]; grid; xl|yl=[label]", listOf("graph", "axes", "function", "functiongraph", "coordinate", "grid", "cartesian", "chart"), ::plot),
            FigElement("numberline", Subject.Maths, "x=[min,max]; pt=[x, open|closed, label]; int=[a, b|inf, \"[)\"]", listOf("number line", "line", "interval", "realline"), ::numberLine),
            FigElement("geo", Subject.Maths, "pt=[A,x,y]…; seg|dash=[A,B,label]; poly=[A,B,C…]; line|ray=[A,B]; circ=[O, r|P, label]; arc=[O,r,deg,deg]; ang=[A,B,C,label]; right|eq|par=[A,B(,C)]; axes", listOf("geometry", "construction", "polygon", "circle", "circles", "shape", "shapes", "quadrilateral", "coordinategeometry"), ::geo),
            FigElement("triangle", Subject.Maths, "v=[A,B,C]; type=right|iso|equi|any; right=[V]; angles=[A°,B°]; side=[P,Q,label]; ang|h=[V,label]", listOf("tri", "righttriangle", "trigonometry"), ::triangle),
            FigElement("venn", Subject.Maths, "sets=[A,B,C]; U=[label]; shade=[set expr: A∩B, A-B, A']; in=[A|AB|out, text]", listOf("venndiagram", "sets", "settheory"), Venn::draw),
            FigElement("solid", Subject.Maths, "type=cube|cuboid|prism|cylinder|cone|sphere|pyramid; a|b|c|r|h|l=[label]", listOf("solids", "3d", "cube", "cuboid", "cylinder", "cone", "sphere", "pyramid", "prism", "box3d"), Solids::draw),
            FigElement("ptree", Subject.Maths, "b=[parent (\"\" root, or A/B), prob, outcome]…", listOf("probabilitytree", "treediagram", "probability"), ::ptree),
        )
    }

    private fun niceStep(range: Float, target: Int = 6): Float {
        val raw = range / target
        val p = 10f.pow(floor(log10(raw)))
        val m = raw / p
        return p * when {
            m < 1.5f -> 1f
            m < 3f -> 2f
            m < 7f -> 5f
            else -> 10f
        }
    }

    private fun fmt(v: Float): String = if (abs(v - Math.round(v)) < 1e-4f) Math.round(v).toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

    private fun plot(p: Params, pen: Pen): Boolean {
        val fs = p.all("f", "fn", "y", "func").mapNotNull { r ->
            val e = Params.textOf(r.getOrNull(0)) ?: return@mapNotNull null
            if (Params.numOf(e) != null && r.size >= 2 && Params.numOf(r[1]) != null) return@mapNotNull null // y=[min,max]
            FigureMath.parse(e)?.let { it to Params.textOf(r.getOrNull(1)) }
        }.take(4)
        val ptsRows = p.all("pts", "points", "data").map { r ->
            val label = r.firstOrNull()?.takeIf { Params.numOf(it) == null }?.let(Params::textOf)
            val nums = r.mapNotNull(Params::numOf)
            label to nums.chunked(2).filter { it.size == 2 }.map { Vec2(it[0], it[1]) }
        }.filter { it.second.size >= 2 }.take(4)
        val marks = p.all("pt", "point").mapNotNull { r ->
            val label = r.firstOrNull()?.takeIf { Params.numOf(it) == null }?.let(Params::textOf)
            val nums = r.mapNotNull(Params::numOf)
            if (nums.size < 2) null else Triple(label, nums[0], nums[1])
        }.take(10)
        if (fs.isEmpty() && ptsRows.isEmpty() && marks.isEmpty() && !p.has("x", "y")) return false
        val xr = p.first("x", "xr", "xrange")?.mapNotNull(Params::numOf)?.takeIf { it.size >= 2 && abs(it[1] - it[0]) > 1e-4f }
        val allX = ptsRows.flatMap { r -> r.second.map { it.x } } + marks.map { it.second }
        var xMin = xr?.minOrNull() ?: (allX.minOrNull()?.let { min(it, 0f) } ?: -5f)
        var xMax = xr?.maxOrNull() ?: (allX.maxOrNull()?.let { max(it, 0f) } ?: 5f)
        if (xMax - xMin < 1e-3f) { xMin -= 1f; xMax += 1f }
        val samples = fs.map { (f, _) -> (0..200).map { i -> val x = xMin + (xMax - xMin) * i / 200; Vec2(x, f(x.toDouble()).toFloat()) } }
        val yr = p.first("yr", "yrange")?.mapNotNull(Params::numOf) ?: p.all("y").firstOrNull { r -> r.size >= 2 && r.all { Params.numOf(it) != null } }?.mapNotNull(Params::numOf)
        val ys = samples.flatten().map { it.y }.filter { it.isFinite() }.sorted()
        val dataY = ptsRows.flatMap { r -> r.second.map { it.y } } + marks.map { it.third }
        var yMin: Float
        var yMax: Float
        if (yr != null && yr.size >= 2 && abs(yr[1] - yr[0]) > 1e-4f) {
            yMin = min(yr[0], yr[1]); yMax = max(yr[0], yr[1])
        } else {
            // Robust range: a curve's asymptote must not flatten the rest of it.
            val lo = (ys.getOrNull((ys.size * 0.04f).toInt()) ?: 0f)
            val hi = (ys.getOrNull((ys.size * 0.96f).toInt().coerceAtMost(ys.size - 1)) ?: 1f)
            yMin = min(min(lo, dataY.minOrNull() ?: lo), 0f)
            yMax = max(max(hi, dataY.maxOrNull() ?: hi), 0f)
            val pad = (yMax - yMin) * 0.08f
            yMin -= if (yMin < 0f) pad else 0f
            yMax += pad
        }
        if (yMax - yMin < 1e-3f) { yMin -= 1f; yMax += 1f }
        val w = 200f
        val h = if (p.flag("eq", "equal")) (w * (yMax - yMin) / (xMax - xMin)).coerceIn(80f, 260f) else 140f
        fun px(x: Float) = (x - xMin) / (xMax - xMin) * w
        fun py(y: Float) = (y - yMin) / (yMax - yMin) * h
        fun m(x: Float, y: Float) = Vec2(px(x), py(y))
        val ox = px(0f.coerceIn(xMin, xMax))
        val oy = py(0f.coerceIn(yMin, yMax))
        val sx = niceStep(xMax - xMin)
        val sy = niceStep(yMax - yMin)
        if (p.flag("grid")) {
            var gx = ceil(xMin / sx) * sx
            while (gx <= xMax + 1e-4f) { pen.shade(m(gx, yMin), m(gx, yMax)); gx += sx }
            var gy = ceil(yMin / sy) * sy
            while (gy <= yMax + 1e-4f) { pen.shade(m(xMin, gy), m(xMax, gy)); gy += sy }
        }
        pen.arrow(Vec2(-6f, oy), Vec2(w + 10f, oy), Role.Ink)
        pen.arrow(Vec2(ox, -6f), Vec2(ox, h + 10f), Role.Ink)
        pen.label(p.str("xl", "xlabel") ?: "x", Vec2(w + 10f, oy), Vec2(0.3f, -1f), gap = 2f)
        pen.label(p.str("yl", "ylabel") ?: "y", Vec2(ox, h + 10f), Vec2(1f, 0.2f), gap = 2f)
        if (p.flag("ticks", default = true)) {
            val small = Pen.FONT * 0.72f
            var tx = ceil(xMin / sx) * sx
            while (tx <= xMax + 1e-4f) {
                if (abs(tx) > sx * 0.01f) {
                    pen.line(Vec2(px(tx), oy - 2f), Vec2(px(tx), oy + 2f))
                    pen.label(fmt(tx), Vec2(px(tx), oy - 2f), Vec2(0f, -1f), gap = 1.5f, size = small, tick = true)
                }
                tx += sx
            }
            var ty = ceil(yMin / sy) * sy
            while (ty <= yMax + 1e-4f) {
                if (abs(ty) > sy * 0.01f) {
                    pen.line(Vec2(ox - 2f, py(ty)), Vec2(ox + 2f, py(ty)))
                    pen.label(fmt(ty), Vec2(ox - 2f, py(ty)), Vec2(-1f, 0f), gap = 1.5f, size = small, tick = true)
                }
                ty += sy
            }
            if (xMin <= 0f && yMin <= 0f) pen.label("0", Vec2(ox, oy), Vec2(-0.7f, -0.7f), gap = 1.5f, size = small)
        }
        for (r in p.all("asy", "asymptote")) {
            val axis = r.getOrNull(0)?.toString()?.lowercase()
            val v = r.mapNotNull(Params::numOf).firstOrNull() ?: continue
            if (axis == "y" && v in yMin..yMax) pen.line(m(xMin, v), m(xMax, v), Role.Note, dashed = true)
            else if (v in xMin..xMax) pen.line(m(v, yMin), m(v, yMax), Role.Note, dashed = true)
        }
        val visible = samples.map { s -> runs(s, yMin, yMax).map { run -> run.map { m(it.x, it.y) } } }
        for ((i, rs) in visible.withIndex()) {
            for (run in rs) pen.poly(run, role = Role.Vector)
            val label = fs[i].second ?: continue
            val last = rs.lastOrNull()?.lastOrNull() ?: continue
            pen.label(label, last, Vec2(1f, 0.6f), gap = 2f, role = Role.Vector)
        }
        for ((label, pts) in ptsRows) {
            val mp = pts.sortedBy { it.x }.map { m(it.x, it.y) }
            pen.poly(mp, role = Role.Vector)
            label?.let { pen.label(it, mp.last(), Vec2(1f, 0.5f), role = Role.Vector) }
        }
        p.first("shade", "area", "fill")?.let { r ->
            val nums = r.mapNotNull(Params::numOf)
            val a = (nums.getOrNull(0) ?: xMin).coerceIn(xMin, xMax)
            val b = (nums.getOrNull(1) ?: xMax).coerceIn(xMin, xMax)
            val between = r.any { it.toString().lowercase().startsWith("betw") } && fs.size >= 2
            val f1 = fs.firstOrNull()?.first
            if (f1 != null && b > a) {
                fun curve(f: (Double) -> Double) = (0..60).map { i -> val x = a + (b - a) * i / 60; Vec2(x, f(x.toDouble()).toFloat().coerceIn(yMin, yMax)) }
                val top = curve(f1)
                val poly = if (between) top + curve(fs[1].first).reversed() else listOf(Vec2(a, 0f.coerceIn(yMin, yMax))) + top + Vec2(b, 0f.coerceIn(yMin, yMax))
                pen.hatch(poly.map { m(it.x, it.y) }, spacing = 4.5f, angle = 45f)
            }
        }
        val drop = p.flag("drop", "dashed", "guides")
        for ((label, x, y) in marks) {
            if (x !in xMin..xMax || y !in yMin..yMax) continue
            val c = m(x, y)
            pen.dot(c, 1.8f, Role.Accent)
            if (drop) {
                pen.line(c, Vec2(c.x, oy), Role.Note, dashed = true)
                pen.line(c, Vec2(ox, c.y), Role.Note, dashed = true)
            }
            pen.label(label ?: "(${fmt(x)},${fmt(y)})", c, Vec2(0.7f, 0.7f), gap = 2f)
        }
        return true
    }

    /** A sampled curve split where it leaves the range or jumps (an asymptote). */
    private fun runs(s: List<Vec2>, lo: Float, hi: Float): List<List<Vec2>> {
        val out = ArrayList<List<Vec2>>()
        var run = ArrayList<Vec2>()
        val span = hi - lo
        var prev: Vec2? = null
        for (q in s) {
            val okp = q.y.isFinite() && q.y >= lo - span * 0.02f && q.y <= hi + span * 0.02f
            val jump = prev != null && abs(q.y - prev.y) > span * 0.6f
            if (!okp || jump) {
                if (run.size >= 2) out += run
                run = ArrayList()
            }
            if (okp) run += Vec2(q.x, q.y.coerceIn(lo, hi))
            prev = q
        }
        if (run.size >= 2) out += run
        return out
    }

    private fun numberLine(p: Params, pen: Pen): Boolean {
        val pts = p.all("pt", "point").mapNotNull { r ->
            val x = Params.numOf(r.getOrNull(0)) ?: return@mapNotNull null
            val open = r.getOrNull(1)?.toString()?.lowercase()?.startsWith("o") == true
            Triple(x, open, r.drop(2).firstOrNull()?.let(Params::textOf) ?: r.getOrNull(1)?.takeIf { it.toString().lowercase().let { s -> !s.startsWith("o") && !s.startsWith("c") && !s.startsWith("f") } }?.let(Params::textOf))
        }
        val ints = p.all("int", "interval", "range").mapNotNull { r ->
            val a = r.getOrNull(0)?.let { v -> Params.numOf(v) ?: if (v.toString().contains("inf") || v.toString().contains("∞")) Float.NEGATIVE_INFINITY else null } ?: return@mapNotNull null
            val b = r.getOrNull(1)?.let { v -> Params.numOf(v) ?: if (v.toString().contains("inf") || v.toString().contains("∞")) Float.POSITIVE_INFINITY else null } ?: return@mapNotNull null
            val br = r.getOrNull(2)?.toString() ?: "[]"
            Triple(min(a, b), max(a, b), br)
        }
        val xr = p.first("x", "range")?.mapNotNull(Params::numOf)?.takeIf { it.size >= 2 }
        val known = pts.map { it.first } + ints.flatMap { listOf(it.first, it.second) }.filter { it.isFinite() }
        var lo = xr?.min() ?: ((known.minOrNull() ?: 0f) - 2f)
        var hi = xr?.max() ?: ((known.maxOrNull() ?: 5f) + 2f)
        if (hi - lo < 1f) { lo -= 1f; hi += 1f }
        val w = 220f
        fun px(x: Float) = (x.coerceIn(lo, hi) - lo) / (hi - lo) * w
        pen.arrow(Vec2(-8f, 0f), Vec2(w + 10f, 0f), Role.Ink)
        pen.arrow(Vec2(w + 2f, 0f), Vec2(-12f, 0f), Role.Ink)
        val step = if (hi - lo <= 20f) 1f else niceStep(hi - lo, 10)
        var t = ceil(lo / step) * step
        val small = Pen.FONT * 0.75f
        while (t <= hi + 1e-4f) {
            pen.line(Vec2(px(t), -3f), Vec2(px(t), 3f))
            pen.label(fmt(t), Vec2(px(t), -3f), Vec2(0f, -1f), gap = 1.5f, size = small, tick = true)
            t += step
        }
        for ((i, iv) in ints.withIndex()) {
            val y = 7f + i * 7f
            val a = if (iv.first.isFinite()) px(iv.first) else -10f
            val b = if (iv.second.isFinite()) px(iv.second) else w + 8f
            if (iv.second.isInfinite()) pen.arrow(Vec2(a, y), Vec2(b, y), Role.Accent) else if (iv.first.isInfinite()) pen.arrow(Vec2(b, y), Vec2(a, y), Role.Accent) else pen.line(Vec2(a, y), Vec2(b, y), Role.Accent)
            if (iv.first.isFinite()) endpoint(pen, Vec2(a, y), open = iv.third.firstOrNull() == '(')
            if (iv.second.isFinite()) endpoint(pen, Vec2(b, y), open = iv.third.lastOrNull() == ')')
        }
        for ((x, open, label) in pts) {
            endpoint(pen, Vec2(px(x), 0f), open)
            label?.let { pen.label(it, Vec2(px(x), 3f), Vec2(0f, 1f), gap = 3f + ints.size * 7f) }
        }
        return true
    }

    private fun endpoint(pen: Pen, c: Vec2, open: Boolean) {
        if (open) pen.circle(c, 2.4f, Role.Accent) else pen.dot(c, 2.4f, Role.Accent)
    }

    /** A geometry construction from named points and operations on them (shared by [triangle]). */
    internal fun drawGeo(points0: Map<String, Vec2>, p: Params, pen: Pen): Boolean {
        if (points0.isEmpty()) return false
        val xs = points0.values.map { it.x }
        val ys = points0.values.map { it.y }
        val span = max(max(xs.max() - xs.min(), ys.max() - ys.min()), 1e-3f)
        val k = 170f / span
        val pts = points0.mapValues { (_, v) -> v * k }
        val centroid = Vec2(pts.values.map { it.x }.average().toFloat(), pts.values.map { it.y }.average().toFloat())
        fun at(v: Any?): Vec2? = Params.textOf(v)?.let { pts[it] }
        val used = HashSet<String>()
        fun use(vararg v: Any?) = v.forEach { Params.textOf(it)?.let(used::add) }
        if (p.flag("axes")) {
            val lo = Vec2(min(0f, pts.values.minOf { it.x }) - 20f, min(0f, pts.values.minOf { it.y }) - 20f)
            val hi = Vec2(max(0f, pts.values.maxOf { it.x }) + 20f, max(0f, pts.values.maxOf { it.y }) + 20f)
            pen.arrow(Vec2(lo.x, 0f), Vec2(hi.x, 0f), Role.Ink)
            pen.arrow(Vec2(0f, lo.y), Vec2(0f, hi.y), Role.Ink)
            pen.label("x", Vec2(hi.x, 0f), Vec2(0.3f, -1f))
            pen.label("y", Vec2(0f, hi.y), Vec2(1f, 0.3f))
        }
        fun sideLabel(a: Vec2, b: Vec2, text: String?) {
            text ?: return
            val m = mid(a, b)
            var n = unit(b - a).rot90()
            if (n.dot(m - centroid) < 0f) n = n * -1f
            pen.label(text, m, n, gap = 2f)
        }
        for (r in p.all("poly", "polygon")) {
            val v = r.mapNotNull { at(it) }
            if (v.size >= 3) { pen.poly(v, closed = true); r.forEach { use(it) } }
        }
        for (r in p.all("seg", "segment", "side")) {
            val a = at(r.getOrNull(0)) ?: continue
            val b = at(r.getOrNull(1)) ?: continue
            pen.line(a, b)
            use(r[0], r[1])
            sideLabel(a, b, r.getOrNull(2)?.let(Params::textOf))
        }
        for (r in p.all("dash", "dashed")) {
            val a = at(r.getOrNull(0)) ?: continue
            val b = at(r.getOrNull(1)) ?: continue
            pen.line(a, b, dashed = true)
            use(r[0], r[1])
            sideLabel(a, b, r.getOrNull(2)?.let(Params::textOf))
        }
        for (r in p.all("line")) {
            val a = at(r.getOrNull(0)) ?: continue
            val b = at(r.getOrNull(1)) ?: continue
            val t = unit(b - a)
            pen.line(a - t * 25f, b + t * 25f)
            use(r[0], r[1])
            r.getOrNull(2)?.let(Params::textOf)?.let { pen.label(it, b + t * 25f, t) }
        }
        for (r in p.all("ray")) {
            val a = at(r.getOrNull(0)) ?: continue
            val b = at(r.getOrNull(1)) ?: continue
            pen.arrow(a, b + unit(b - a) * 30f, Role.Ink)
            use(r[0], r[1])
        }
        for (r in p.all("vec", "vector", "arrow")) {
            val a = at(r.getOrNull(0)) ?: continue
            val b = at(r.getOrNull(1)) ?: continue
            pen.arrow(a, b)
            use(r[0], r[1])
            sideLabel(a, b, r.getOrNull(2)?.let(Params::textOf))
        }
        for (r in p.all("circ", "circle")) {
            val c = at(r.getOrNull(0)) ?: continue
            use(r[0])
            val through = at(r.getOrNull(1))
            val rad = through?.dist(c) ?: Params.numOf(r.getOrNull(1))?.let { it * k } ?: continue
            if (rad < 1f) continue
            pen.circle(c, rad)
            pen.dot(c, 1.5f)
            r.getOrNull(2)?.let(Params::textOf)?.let { t ->
                val e = c + dir(35f) * rad
                pen.line(c, e, dashed = through == null)
                pen.label(t, mid(c, e), dir(125f), gap = 1.5f)
            }
        }
        for (r in p.all("arc")) {
            val c = at(r.getOrNull(0)) ?: continue
            val n = r.drop(1).mapNotNull(Params::numOf)
            if (n.size < 3) continue
            pen.arc(c, n[0] * k, n[1], n[2])
        }
        for (r in p.all("ang", "angle")) {
            val a = at(r.getOrNull(0)) ?: continue
            val b = at(r.getOrNull(1)) ?: continue
            val c = at(r.getOrNull(2)) ?: continue
            val label = r.getOrNull(3)?.let(Params::textOf)
            val arcs = Params.numOf(r.getOrNull(4))?.toInt() ?: 1
            val r0 = (min(a.dist(b), c.dist(b)) * 0.28f).coerceIn(9f, 20f)
            pen.angle(b, angleOf(a - b), angleOf(c - b), r = r0, label = label, arcs = arcs)
        }
        for (r in p.all("right", "perp", "rightangle")) {
            val a = at(r.getOrNull(0)) ?: continue
            val b = at(r.getOrNull(1)) ?: continue
            val c = at(r.getOrNull(2)) ?: continue
            pen.rightAngle(b, angleOf(a - b), angleOf(c - b), size = 7f)
        }
        for (r in p.all("eq", "equal", "tick", "ticks")) {
            val a = at(r.getOrNull(0)) ?: continue
            val b = at(r.getOrNull(1)) ?: continue
            pen.ticks(a, b, Params.numOf(r.getOrNull(2))?.toInt() ?: 1)
        }
        for (r in p.all("par", "parallel")) {
            val a = at(r.getOrNull(0)) ?: continue
            val b = at(r.getOrNull(1)) ?: continue
            pen.parallelMark(a, b, Params.numOf(r.getOrNull(2))?.toInt() ?: 1)
        }
        // Every named point is labelled away from the figure's middle; points on nothing get a dot.
        for ((name, v) in pts) {
            if (name !in used) pen.dot(v, 1.8f)
            val d = unit(v - centroid).takeIf { it.length() > 0.5f } ?: Vec2(-0.7f, 0.7f)
            if (!name.startsWith("_")) pen.label(name, v, d, gap = 2f)
        }
        return true
    }

    private fun geo(p: Params, pen: Pen): Boolean {
        val pts = LinkedHashMap<String, Vec2>()
        for (r in p.all("pt", "point", "p")) {
            val name = Params.textOf(r.getOrNull(0)) ?: continue
            val x = Params.numOf(r.getOrNull(1)) ?: continue
            val y = Params.numOf(r.getOrNull(2)) ?: continue
            if (pts.size < 26) pts[name] = Vec2(x, y)
        }
        return drawGeo(pts, p, pen)
    }

    private fun triangle(p: Params, pen: Pen): Boolean {
        val names = (p.first("v", "vertices", "names")?.mapNotNull(Params::textOf) ?: emptyList()).let { if (it.size >= 3) it.take(3) else listOf("A", "B", "C") }
        val (na, nb, nc) = names
        val type = p.choice("type", options = listOf("right", "iso", "isosceles", "equi", "equilateral", "any", "scalene"), default = if (p.has("right")) "right" else "any")
        val angles = p.first("angles")?.mapNotNull(Params::numOf)
        val rightAt = p.str("right")?.takeIf { it in names } ?: nc
        val pts: Map<String, Vec2> = when {
            angles != null && angles.size >= 2 && angles[0] + angles[1] < 178f -> {
                val a = Math.toRadians(angles[0].coerceIn(1f, 178f).toDouble())
                val b = Math.toRadians(angles[1].coerceIn(1f, 178f).toDouble())
                // C from the base AB = 1 and the base angles.
                val t = (kotlin.math.tan(b) / (kotlin.math.tan(a) + kotlin.math.tan(b))).toFloat()
                val c = Vec2(t, (t * kotlin.math.tan(a)).toFloat())
                mapOf(na to Vec2(0f, 0f), nb to Vec2(1f, 0f), nc to c)
            }
            type.startsWith("equi") -> mapOf(na to Vec2(0f, 0f), nb to Vec2(1f, 0f), nc to Vec2(0.5f, sqrt(3f) / 2))
            type.startsWith("iso") -> mapOf(na to Vec2(0f, 0f), nb to Vec2(1f, 0f), nc to Vec2(0.5f, 0.85f))
            type == "right" -> {
                val others = names.filter { it != rightAt }
                mapOf(rightAt to Vec2(0f, 0f), others[0] to Vec2(0f, 0.75f), others[1] to Vec2(1.15f, 0f))
            }
            else -> mapOf(na to Vec2(0f, 0f), nb to Vec2(1f, 0f), nc to Vec2(0.32f, 0.72f))
        }
        val rows = ArrayList<List<Any>>()
        rows += listOf("poly", na, nb, nc)
        if (type == "right") {
            val others = names.filter { it != rightAt }
            rows += listOf("right", others[0], rightAt, others[1])
        }
        for (r in p.all("side", "seg")) if (r.size >= 3) rows += listOf<Any>("seg") + r
        for (k in listOf(na, nb, nc)) p.str(k.lowercase())?.takeIf { k.lowercase() != k }?.let { label ->
            val (u, v) = names.filter { it != k }
            rows += listOf("seg", u, v, label)
        }
        for (r in p.all("ang", "angle")) {
            val v = Params.textOf(r.getOrNull(0)) ?: continue
            if (v !in names) continue
            val (u, w) = names.filter { it != v }
            rows += listOf("ang", u, v, w, Params.textOf(r.getOrNull(1)) ?: continue)
        }
        val all = HashMap(pts)
        for (r in p.all("h", "height", "altitude")) {
            val v = Params.textOf(r.getOrNull(0))?.takeIf { it in names } ?: nc
            val (u, w) = names.filter { it != v }
            val a = pts.getValue(u)
            val b = pts.getValue(w)
            val c = pts.getValue(v)
            val t = ((c - a).dot(b - a) / (b - a).dot(b - a))
            val foot = lerp(a, b, t)
            all["_H"] = foot
            rows += listOf("dash", v, "_H", Params.textOf(r.getOrNull(1)) ?: "h")
            rows += listOf("right", u, "_H", v)
        }
        for (r in p.all("eq", "ticks")) rows += listOf<Any>("eq") + r
        return drawGeo(all, Params(rows), pen)
    }

    private fun ptree(p: Params, pen: Pen): Boolean {
        class Node(val name: String, val prob: String?, val parent: Node?) {
            val kids = ArrayList<Node>()
            val depth: Int = (parent?.depth ?: -1) + 1
            var y = 0f
            val path: String = if (parent == null || parent.parent == null && parent.name.isEmpty()) name else parent.path + "/" + name
        }
        val root = Node("", null, null)
        val nodes = arrayListOf(root)
        for (r in p.all("b", "branch", "edge")) {
            if (r.size < 2) continue
            // ["", p, outcome] loses its empty parent on the way: two values are a branch from the root.
            val v = if (r.size == 2) listOf<Any>("") + r else r
            val parentKey = Params.textOf(v.getOrNull(0)).orEmpty().trim().removePrefix("root").trim('/')
            val prob = Params.textOf(v.getOrNull(1))
            val outcome = Params.textOf(v.getOrNull(2)) ?: continue
            val parent = if (parentKey.isEmpty()) root else nodes.firstOrNull { it.path == parentKey } ?: nodes.filter { it.name == parentKey.substringAfterLast('/') }.minByOrNull { it.depth } ?: continue
            if (parent.depth >= 4 || nodes.size > 40) continue
            val n = Node(outcome, prob, parent)
            parent.kids += n
            nodes += n
        }
        if (root.kids.isEmpty()) return false
        var slot = 0f
        fun place(n: Node) {
            if (n.kids.isEmpty()) { n.y = -slot * 26f; slot++; return }
            n.kids.forEach(::place)
            n.y = (n.kids.first().y + n.kids.last().y) / 2
        }
        place(root)
        val dx = 80f
        fun draw(n: Node) {
            val (w, _) = Pen.extent(n.name)
            val from = Vec2(n.depth * dx + (if (n === root) 0f else w / 2 + 3f), n.y)
            if (n === root) pen.dot(from, 1.8f)
            for (k in n.kids) {
                val (kw, _) = Pen.extent(k.name)
                val to = Vec2(k.depth * dx - kw / 2 - 3f, k.y)
                pen.line(from, to)
                pen.label(k.name, Vec2(k.depth * dx, k.y), inside = true)
                k.prob?.let { pr ->
                    val m = mid(from, to)
                    val nrm = unit(to - from).rot90()
                    pen.label(pr, m, if (nrm.y >= 0f) nrm else nrm * -1f, gap = 1.5f, size = Pen.FONT * 0.85f)
                }
                draw(k)
            }
        }
        draw(root)
        return true
    }
}
