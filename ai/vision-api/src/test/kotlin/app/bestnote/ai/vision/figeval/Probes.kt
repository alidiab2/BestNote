package app.bestnote.ai.vision.figeval

import app.bestnote.core.model.Vec2
import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.FigureMath
import app.bestnote.engine.layout.NotebookPalette
import app.bestnote.engine.layout.figlib.FigRender
import app.bestnote.engine.layout.figlib.FigSpec
import app.bestnote.engine.layout.figlib.Params
import app.bestnote.engine.layout.figlib.Pen
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * FIG-LIB renderer fidelity: checks a rendered drawing against its ground-truth parameters by
 * MEASURING the drawing (angles of the strokes, where arrows start, the plotted curve read back
 * through the axis ticks, the image a lens forms…), plus label rules every kind must keep (each
 * label drawn, none on a stroke, none on another label). Coordinates are the diagram's (y down).
 */
object Probes {
    class Check(val name: String, val ok: Boolean)

    private fun deg(v: Vec2) = Math.toDegrees(atan2(-v.y.toDouble(), v.x.toDouble())).toFloat()
    private fun diff(a: Float, b: Float): Float { val d = abs(((a - b) % 360f + 540f) % 360f - 180f); return d }
    private fun axisDiff(a: Float, b: Float) = min(diff(a, b), diff(a, b + 180f))
    private fun near(a: Vec2, b: Vec2) = hypot(a.x - b.x, a.y - b.y)

    private val R = FigRender::class

    fun run(spec: FigSpec, r: FigRender): List<Check> {
        val out = ArrayList<Check>()
        out += labelChecks(spec, r)
        out += runCatching { kind(spec, r) }.getOrElse { listOf(Check("${spec.kind}: probe crashed ${it.message}", false)) }
        return out
    }

    /** Text the drawing must show, from the ground truth (kind by kind). */
    fun expectedLabels(spec: FigSpec): Set<String> {
        val p = spec.params
        fun col(key: String, i: Int) = p.all(key).mapNotNull { it.getOrNull(i)?.let(Params::textOf) }
        val s = when (spec.kind) {
            "incline" -> col("blk", 0) + col("f", 0) + col("ang", 0) + col("h", 0)
            "fbd" -> col("f", 0) + col("body", 1) + col("ang", 0)
            "blocks" -> col("m", 0) + col("f", 0)
            "spring" -> col("k", 0) + col("m", 0)
            "pulley" -> col("m", 0)
            "pendulum" -> col("l", 0) + col("m", 0) + col("f", 0)
            "circuit" -> p.rows.filter { it.size >= 4 && it[0] != "I" }.mapNotNull { Params.textOf(it[1]) }.filter { it != "-" }
            "wave" -> col("lam", 0) + col("v", 0) + (if (p.str("type") == "transverse") col("a", 0) else emptyList())
            "vectors" -> col("v", 0) + col("sum", 0)
            "plot" -> col("f", 1) + col("pt", 0)
            "geo" -> col("pt", 0)
            "triangle" -> listOf("A", "B", "C") + col("side", 2) + col("ang", 1)
            "venn" -> (p.first("sets") ?: emptyList()).mapNotNull(Params::textOf) + col("in", 1)
            "solid" -> p.rows.filter { it.size == 2 && it[0] != "type" }.mapNotNull { Params.textOf(it[1]) }
            "ptree" -> col("b", 1) + col("b", 2)
            "sketch" -> p.rows.mapNotNull { r -> r.lastOrNull()?.takeIf { Params.numOf(it) == null && r[0] != "arrow" }?.let(Params::textOf) }
            else -> emptyList()
        }
        return s.toSet()
    }

    private fun labelChecks(spec: FigSpec, r: FigRender): List<Check> {
        val shown = r.diagram.shapes.filterIsInstance<VectorShape.Label>().map { it.text }.toSet()
        val missing = expectedLabels(spec) - shown
        val placed = r.labels
        var overlaps = 0
        for (i in placed.indices) for (j in i + 1 until placed.size) {
            val a = placed[i].second
            val b = placed[j].second
            val ix = min(a[0] + a[2], b[0] + b[2]) - max(a[0] - a[2], b[0] - b[2])
            val iy = min(a[1] + a[3], b[1] + b[3]) - max(a[1] - a[3], b[1] - b[3])
            if (ix > 0 && iy > 0 && ix * iy > 0.1f * min(4 * a[2] * a[3], 4 * b[2] * b[3])) overlaps++
        }
        // Stroke samples inside a free label's box (maths coordinates in placed; the diagram is y down).
        val samples = r.diagram.shapes.filter { it !is VectorShape.Label }.flatMap(Pen::samples)
        var onStroke = 0
        for ((_, b) in placed) {
            if (b[4] > 0f) continue
            val n = samples.count { abs(it.x - b[0]) < b[2] - 1f && abs(-it.y - b[1]) < b[3] - 1f }
            if (n > 2) onStroke++
        }
        return listOf(
            Check("${spec.kind}: every label drawn ${if (missing.isEmpty()) "" else missing}", missing.isEmpty()),
            Check("${spec.kind}: labels apart", overlaps == 0),
            Check("${spec.kind}: labels off the strokes ($onStroke on)", onStroke == 0),
        )
    }

    private fun arrows(r: FigRender) = r.diagram.shapes.filterIsInstance<VectorShape.Arrow>()
    private fun labels(r: FigRender) = r.diagram.shapes.filterIsInstance<VectorShape.Label>()
    private fun polys(r: FigRender) = r.diagram.shapes.filterIsInstance<VectorShape.Polyline>()

    /** The arrow a label names: the one whose tip is nearest the label. */
    private fun arrowOf(r: FigRender, text: String): VectorShape.Arrow? {
        val l = labels(r).firstOrNull { it.text == text } ?: return null
        return arrows(r).minByOrNull { near(it.to, l.at) }
    }

    private fun kind(spec: FigSpec, r: FigRender): List<Check> {
        val p = spec.params
        val k = spec.kind
        val c = ArrayList<Check>()
        fun check(name: String, ok: Boolean) { c += Check("$k: $name", ok) }
        when (k) {
            "incline" -> {
                val a = p.num("a", default = 30f)
                val tri = polys(r).first { it.closed && it.points.size == 3 }
                val sides = tri.points.indices.map { i -> tri.points[i] to tri.points[(i + 1) % 3] }
                val slope = sides.first { (u, v) -> axisDiff(deg(v - u), 0f) > 2f && axisDiff(deg(v - u), 90f) > 2f }
                val slopeDeg = deg(slope.second - slope.first)
                check("slope at the given angle", abs(axisDiff(slopeDeg, 0f) - a) < 0.5f)
                val corner = tri.points.first { it != slope.first && it != slope.second }
                check("right-angle mark in the right-angle corner", polys(r).any { !it.closed && it.points.size == 3 && it.points.all { q -> near(q, corner) < 14f } })
                val block = polys(r).first { it.closed && it.points.size == 4 }
                val centre = Vec2(block.points.map { it.x }.average().toFloat(), block.points.map { it.y }.average().toFloat())
                val forces = p.all("f").mapNotNull { it.getOrNull(0)?.let(Params::textOf) }.distinct()
                for (f in forces) {
                    val ar = arrowOf(r, f) ?: run { check("arrow for $f", false); null } ?: continue
                    check("$f starts at the block centre", near(ar.from, centre) < 0.6f)
                    val d = deg(ar.to - ar.from)
                    val row = p.all("f").first { Params.textOf(it[0]) == f }
                    when (val dir = row.getOrNull(1)) {
                        "n" -> check("N ⟂ slope", abs(axisDiff(d, slopeDeg) - 90f) < 1f)
                        "g" -> check("$f vertical", diff(d, -90f) < 1f)
                        "down", "up" -> check("$f ∥ slope", axisDiff(d, slopeDeg) < 1f)
                        "h" -> check("$f horizontal", axisDiff(d, 0f) < 1f)
                        else -> Params.numOf(dir)?.let { g -> check("$f at ${g}° to the slope", abs(axisDiff(d, slopeDeg) - g) < 1f) }
                    }
                }
                val ang = p.str("ang")
                check("one angle label", labels(r).count { it.text == ang } == 1)
                val low = slope.toList().minBy { if (corner.x > (slope.first.x + slope.second.x) / 2) it.x else -it.x }
                check("one arc at the angle", polys(r).count { pl -> !pl.closed && pl.points.size >= 6 && pl.points.map { q -> near(q, low) }.let { ds -> ds.max() - ds.min() < 0.5f } } == 1)
            }
            "fbd" -> for (row in p.all("f")) {
                val name = Params.textOf(row[0]) ?: continue
                val want = when (val d = row.getOrNull(1)) { "up" -> 90f; "down" -> -90f; "left" -> 180f; "right" -> 0f; else -> Params.numOf(d) ?: continue }
                val ar = arrowOf(r, name)
                check("$name direction", ar != null && diff(deg(ar.to - ar.from), want) < 1f)
            }
            "vectors" -> {
                val scales = ArrayList<Float>()
                val drawn = arrows(r).filter { it.style.colorArgb == NotebookPalette.EMPHASIS }
                for (row in p.all("v")) {
                    val name = Params.textOf(row[0])!!
                    val g = Vec2(Params.numOf(row[1])!!, -Params.numOf(row[2])!!)
                    val ar = drawn.firstOrNull { diff(deg(it.to - it.from), deg(g)) < 1f }
                    check("$name drawn in its direction", ar != null)
                    if (ar != null) scales += near(ar.to, ar.from) / g.length()
                }
                if (scales.size >= 2) check("one scale for all vectors", scales.max() / scales.min() < 1.02f)
            }
            "plot" -> plot(p, r, ::check)
            "lens", "mirror" -> optics(k, p, r, ::check)
            "refraction" -> {
                val n = p.first("n")!!
                val n1 = Params.numOf(n[0])!!
                val n2 = Params.numOf(n[1])!!
                val i = p.num("i", default = 40f)
                val s = n1 * sin(Math.toRadians(i.toDouble())) / n2
                if (s < 1) {
                    val want = Math.toDegrees(asin(s)).toFloat()
                    val ar = arrows(r).first { it.from.length() < 0.5f && it.to.y > 1f && !it.style.dashed }
                    check("Snell's law", abs(diff(deg(ar.to - ar.from), -90f) - want) < 0.5f)
                }
            }
            "pendulum" -> {
                val bob = r.diagram.shapes.filterIsInstance<VectorShape.Ellipse>().first()
                val bc = bob.bounds.center
                val string = r.diagram.shapes.filterIsInstance<VectorShape.Line>().first { near(it.to, bc) < 0.5f }
                check("string at the given angle", abs(diff(deg(string.to - string.from), -90f) - p.num("a", default = 30f)) < 0.5f)
            }
            "wave" -> {
                val type = p.str("type")
                val n = p.num("n", default = 2f)
                val curve = polys(r).filter { it.style.colorArgb == NotebookPalette.EMPHASIS && !it.style.dashed }.maxByOrNull { it.points.size }
                when (type) {
                    "longitudinal" -> check("compression lines", r.diagram.shapes.count { it is VectorShape.Line } > 8 * n)
                    else -> {
                        val pts = curve!!.points
                        val crests = (1 until pts.size - 1).count { pts[it].y < pts[it - 1].y && pts[it].y <= pts[it + 1].y }
                        val want = (0..10).count { (it + 0.25f) <= n + 1e-3f }
                        check("$want crests", crests == want)
                        if (type == "standing") check("mirror curve", polys(r).any { it.style.dashed && it.style.colorArgb == NotebookPalette.EMPHASIS })
                    }
                }
            }
            "numberline" -> {
                val ticks = labels(r).mapNotNull { l -> l.text.toFloatOrNull()?.let { it to l.at.x } }
                val (k0, b0) = fit(ticks)
                val iv = p.first("int")!!
                val a = Params.numOf(iv[0])!!
                val ends = r.diagram.shapes.filterIsInstance<VectorShape.Ellipse>().filter { it.style.colorArgb == NotebookPalette.ESSENTIAL }.map { it.bounds.center.x }
                check("interval starts at $a", ends.any { abs((it - b0) / k0 - a) < 0.05f })
            }
            "geo" -> {
                val pts = p.all("pt").associate { Params.textOf(it[0])!! to Vec2(Params.numOf(it[1])!!, Params.numOf(it[2])!!) }
                val poly = polys(r).first { it.closed }
                val names = p.first("poly")!!.mapNotNull(Params::textOf)
                val g = names.map { pts.getValue(it) }
                val k0 = near(poly.points[1], poly.points[0]) / near(g[1], g[0])
                val err = g.indices.maxOf { i -> near(Vec2((poly.points[i].x - poly.points[0].x) / k0, -(poly.points[i].y - poly.points[0].y) / k0), g[i] - g[0]) }
                val span = g.maxOf { it.length() }
                check("polygon matches its coordinates", err < 0.01f * span + 1e-3f)
            }
            "triangle" -> {
                val tri = polys(r).first { it.closed && it.points.size == 3 }
                val s = tri.points.indices.map { near(tri.points[it], tri.points[(it + 1) % 3]) }
                when (p.str("type")) {
                    "equi" -> check("equal sides", s.max() / s.min() < 1.01f)
                    "right" -> check("right-angle mark", polys(r).any { !it.closed && it.points.size == 3 })
                    "iso" -> check("two equal sides", s.indices.any { i -> abs(s[i] - s[(i + 1) % 3]) < 0.01f * s[i] })
                }
            }
            "venn" -> {
                val sets = p.first("sets")!!.mapNotNull(Params::textOf)
                val circles = r.diagram.shapes.filterIsInstance<VectorShape.Ellipse>().filter { it.bounds.width > 60f }
                check("${sets.size} circles", circles.size == sets.size)
                for (row in p.all("in")) {
                    val region = Params.textOf(row[0])!!
                    val want = sets.map { region != "out" && it in region }
                    // The same number may stand in two regions: one of them must be this one.
                    val ok = labels(r).filter { it.text == Params.textOf(row[1]) }.any { l -> circles.map { near(it.bounds.center, l.at) < it.bounds.width / 2 } == want }
                    check("'${row[1]}' in region $region", ok)
                }
            }
            "circuit" -> {
                val rs = p.rows.count { it.size >= 4 && it[0] == "R" }
                val zig = polys(r).count { pl -> !pl.closed && pl.points.size == 10 }
                check("$rs resistors", zig == rs)
                val meters = p.rows.count { it.size >= 4 && (it[0] == "A" || it[0] == "V") }
                check("$meters meters", labels(r).count { it.text == "A" || it.text == "V" } >= meters)
            }
            "spring" -> {
                val coil = polys(r).maxBy { it.points.size }
                val span = coil.points.maxOf { it.x } - coil.points.minOf { it.x }
                val want = when (p.str("state")) { "compressed" -> 54f; "stretched" -> 126f; else -> 90f }
                check("length for its state", abs(span - want) < 0.05f * want + 9f)
            }
            "field" -> {
                val q = p.all("q")
                if (q.isNotEmpty()) check("${q.size} charges", r.diagram.shapes.filterIsInstance<VectorShape.Ellipse>().size == q.size)
                p.numOrNull("uniform")?.let { a -> check("uniform field direction", arrows(r).filter { it.style.colorArgb == NotebookPalette.EMPHASIS }.all { diff(deg(it.to - it.from), a) < 1f }) }
            }
            "blocks" -> check("${p.all("m").size} blocks", polys(r).count { it.closed && it.points.size == 4 } == p.all("m").size)
            "ptree" -> check("${p.all("b").size} branches", r.diagram.shapes.count { it is VectorShape.Line } == p.all("b").size)
            "sketch" -> check("${p.all("box").size} boxes", polys(r).count { it.closed && it.points.size == 4 } == p.all("box").size)
            "solid" -> check("hidden edges dashed", r.diagram.shapes.any { it.style.dashed } || p.str("type") == "sphere")
            "pulley" -> check("${p.all("m").size} masses", polys(r).count { it.closed && it.points.size == 4 } == p.all("m").size.coerceAtMost(2))
        }
        return c
    }

    /** Least-squares value → position: position = k·value + b. */
    private fun fit(pairs: List<Pair<Float, Float>>): Pair<Float, Float> {
        val n = pairs.size
        val mx = pairs.sumOf { it.first.toDouble() } / n
        val my = pairs.sumOf { it.second.toDouble() } / n
        val k = pairs.sumOf { (it.first - mx) * (it.second - my) } / pairs.sumOf { (it.first - mx) * (it.first - mx) }
        return k.toFloat() to (my - k * mx).toFloat()
    }

    private fun plot(p: Params, r: FigRender, check: (String, Boolean) -> Unit) {
        val axisX = arrows(r).first { abs(it.from.y - it.to.y) < 1e-3f }
        val axisY = arrows(r).first { abs(it.from.x - it.to.x) < 1e-3f }
        val nums = labels(r).filter { it.text.toFloatOrNull() != null && it.text != "0" }
        // Each tick (a short stroke across an axis) with the number written nearest to it.
        val lines = r.diagram.shapes.filterIsInstance<VectorShape.Line>().filter { near(it.from, it.to) in 3.9f..4.1f }
        val xt = lines.filter { abs(it.from.x - it.to.x) < 1e-3f }.map { t -> nums.minBy { near(it.at, t.from) }.text.toFloat() to t.from.x }
        val yt = lines.filter { abs(it.from.y - it.to.y) < 1e-3f }.map { t -> nums.minBy { near(it.at, t.from) }.text.toFloat() to t.from.y }
        if (xt.size < 2 || yt.size < 2) return check("axis ticks readable", false)
        val (kx, bx) = fit(xt)
        val (ky, by) = fit(yt)
        val f = FigureMath.parse(p.all("f").first()[0].toString())!!
        val curve = polys(r).filter { it.style.colorArgb == NotebookPalette.EMPHASIS }
        val ys = yt.map { it.first }
        val range = ys.max() - ys.min()
        val errs = curve.flatMap { it.points }.map { q ->
            val x = (q.x - bx) / kx
            val y = (q.y - by) / ky
            abs(f(x.toDouble()).toFloat() - y)
        }.filter { it.isFinite() }.sorted()
        check("curve read back through the axes (median error ${errs.getOrNull(errs.size / 2)}, range $range, ticks $xt $yt)", errs.isNotEmpty() && errs[errs.size / 2] < 0.01f * range)
    }

    private fun optics(kind: String, p: Params, r: FigRender, check: (String, Boolean) -> Unit) {
        val type = p.str("type")
        val o = p.first("obj")!!
        val d = Params.numOf(o[0])!!
        val image = arrows(r).firstOrNull { it.style.colorArgb == NotebookPalette.ESSENTIAL } ?: return check("image drawn", false)
        if (type == "plane") {
            val obj = arrows(r).first { it.style.colorArgb == NotebookPalette.INK }
            return check("plane mirror image as far behind", abs(image.from.x + obj.from.x) < 0.5f)
        }
        // The focal points: the small dots on the axis (the lens or mirror is at x = 0).
        val dots = r.diagram.shapes.filterIsInstance<VectorShape.Ellipse>().filter { it.bounds.width < 4f && abs(it.bounds.center.y) < 0.1f }.map { abs(it.bounds.center.x) }
        val f = dots.min()
        val centre = 0f
        val fl = if (kind == "lens") (if (type == "concave") -1f else 1f) else (if (type == "convex") -1f else 1f)
        val di = 1f / (fl - 1f / d)
        val got = (image.from.x - centre) / f
        val want = if (kind == "lens") di else -di
        check("image where the ${kind} equation puts it", abs(got - want) < 0.02f * max(1f, abs(want)))
    }
}
