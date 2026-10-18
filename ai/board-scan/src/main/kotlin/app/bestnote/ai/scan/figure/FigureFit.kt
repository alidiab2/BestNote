package app.bestnote.ai.scan.figure

import app.bestnote.core.model.InkSemantic
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A clean drawing primitive fitted to the teacher's strokes, in crop pixels. */
sealed interface Prim {
    val sem: InkSemantic

    /** A straight line; [head] = an arrowhead at [b], [tail] = one at [a] too (a dimension arrow); [dashed] = drawn as dashes; [hatch] = a ground/wall hatch mark. */
    data class Seg(val a: XY, val b: XY, override val sem: InkSemantic, val dashed: Boolean = false, val head: Boolean = false, val tail: Boolean = false, val hatch: Boolean = false) : Prim {
        val length: Float get() = a.dist(b)
        val angle: Float get() = atan2(b.y - a.y, b.x - a.x)
    }

    /** A circular arc from [start] sweeping [sweep] radians (y down, so positive = clockwise on screen); a full circle when |sweep| ≈ 2π. */
    data class Arc(val c: XY, val r: Float, val start: Float, val sweep: Float, override val sem: InkSemantic, val mark: Boolean = false) : Prim {
        val full: Boolean get() = abs(sweep) > 2 * PI.toFloat() * 0.92f
        fun points(step: Float = 0.08f): List<XY> {
            val n = max(4, (abs(sweep) / step).toInt())
            return (0..n).map { i -> val t = start + sweep * i / n; XY(c.x + r * cos(t), c.y + r * sin(t)) }
        }
    }

    /** A coiled spring from [a] to [b]: [loops] loops of [radius] (y down). */
    data class Coil(val a: XY, val b: XY, val loops: Int, val radius: Float, override val sem: InkSemantic, val loopWidth: Float = radius) : Prim {
        /** The spring as one smooth looping line (a prolate cycloid along a→b). */
        fun points(): List<XY> {
            val d = b - a
            val len = d.len()
            if (len < 1e-3f) return listOf(a, b)
            val u = d * (1f / len)
            val n = XY(-u.y, u.x)
            val pitch = len / loops
            val steps = loops * 24
            return (0..steps).map { i ->
                val t = i.toFloat() / steps * loops * 2 * PI.toFloat()
                // Moving forward while turning a circle of [radius]: each turn draws one loop.
                val along = pitch * t / (2 * PI.toFloat()) - 0.85f * loopWidth * sin(t)
                val across = radius * (1 - cos(t)) - radius
                a + u * along + n * across
            }
        }
    }

    /** A block (a mass drawn as a box), maybe rotated onto a slope: four [corners] in order. */
    data class Block(val corners: List<XY>, override val sem: InkSemantic) : Prim

    /** A free curve (a function graph, a letter nobody labelled), already smoothed. */
    data class Curve(val points: List<XY>, override val sem: InkSemantic, val closed: Boolean = false) : Prim

    /** Writing the AI did not read but the drawing shows plainly (a tick's "0"), typeset at [at]. */
    data class Mark(val at: XY, val text: String, override val sem: InkSemantic) : Prim
}

/**
 * AI-12: strokes → primitives → a neat drawing. Each centre-line path becomes a line, a polyline with
 * crisp corners, a circular arc or a smooth curve; arrowheads (two short barbs at a line's end) become
 * arrows; rows of short collinear dashes become one dashed line; strokes that are the written labels
 * the AI read are removed (the labels are typeset instead). Then a ruler and set square: near-horizontal
 * and near-vertical lines are squared, nearly parallel or perpendicular lines are made exactly so, and
 * line ends that meet are welded at their true intersection.
 */
class FigureFit(
    /** Typical stroke thickness in pixels. */
    private val sw: Float,
    /** Diagonal of the figure crop in pixels: tolerances scale with it. */
    private val diag: Float,
) {
    private val eps = max(1.6f, max(0.8f * sw, 0.007f * diag))

    /** The last [fit] before squaring and welding: what is checked against the photo (squaring is on purpose). */
    var unsquared: List<Prim> = emptyList()
        private set

    /** Where each label of the last [fit] goes (an angle's label moves beside the arc it names). */
    var labelPlaces: List<XY> = emptyList()
        private set

    /** Paths (with their colour intent) → primitives. [labels]: what the AI read and where (crop pixels). */
    fun fit(paths: List<Pair<List<XY>, InkSemantic>>, labels: List<Pair<XY, String>>, words: List<FloatArray?> = emptyList()): List<Prim> {
        labelPlaces = labels.map { it.first }
        var prims = paths.flatMap { (pts, sem) -> fitPath(pts, sem) }
        // A block with its mass written in it first: the spring pushing it must not swallow its sides.
        prims = blocks(prims, labels, named = true)
        prims = coils(prims)
        // A block on a slope, before its corners are taken for arrowheads; then shafts drawn as two lines.
        prims = slopeBlocks(prims)
        prims = rods(prims)
        // Short arrows (a velocity beside a dashed path) first, so their shafts are not taken for dashes.
        prims = arrows(prims, minShaft = 0.03f * diag, twoBarbs = true)
        // Blocks before dashes: a block's side is not a dash of the path beside it.
        prims = blocks(prims, labels)
        prims = dashes(prims)
        // Lines split where others cross them are whole again before arrowheads are looked for.
        prims = merged(prims)
        prims = hatches(prims)
        if (DEBUG) println("before arrows (sw=$sw): " + prims.filterIsInstance<Prim.Seg>().joinToString { "[%.0f,%.0f→%.0f,%.0f]".format(it.a.x, it.a.y, it.b.x, it.b.y) })
        prims = arrows(prims)
        prims = labelled(prims, labels)
        prims = angleMarks(prims)
        prims = angleArcs(prims, labels)
        prims = withoutTwins(prims)
        prims = withoutLabels(prims, labels, words)
        prims = merged(prims)
        unsquared = prims
        prims = squared(prims)
        prims = welded(prims)
        prims = withoutScraps(prims, labels)
        return prims
    }

    // ------------------------------------------------------------------ one path

    internal fun fitPath(raw: List<XY>, sem: InkSemantic): List<Prim> {
        if (raw.size < 2) return emptyList()
        val pts = smooth(raw)
        val length = pts.zipWithNext().sumOf { (p, q) -> p.dist(q).toDouble() }.toFloat()
        if (length < 1.5f * sw) return emptyList()
        val closed = raw.first().dist(raw.last()) < max(2f, 1.5f * sw) && length > 6 * sw
        val corners = rdp(pts, eps)
        if (corners.size == 2 && !closed) return listOf(line(pts, sem))
        // A hand-drawn straight line bows a little: still a line when it stays close to its chord.
        if (!closed) {
            val l = line(pts, sem)
            if (pts.maxOf { lineDist(it, l) } < max(1.5f * eps, 0.05f * l.length)) return listOf(l)
        }
        val turns = (1 until corners.size - 1).map { turn(corners[it - 1], corners[it], corners[it + 1]) }
        circle(pts)?.let { (c, r, rms) ->
            val sweep = sweepOf(pts, c)
            val straightish = corners.size <= 2
            // An arc bends evenly: three sides of a box (sharp corners) are not one.
            val sharp = turns.any { it > SHARP_DEG }
            if (!straightish && !sharp && rms < max(1.0f, 0.07f * r) && r < 0.5f * diag && abs(sweep) > Math.toRadians(MIN_ARC_DEG).toFloat()) {
                val start = atan2(pts[0].y - c.y, pts[0].x - c.x)
                return listOf(Prim.Arc(c, r, start, if (closed) 2 * PI.toFloat() * kotlin.math.sign(sweep) else sweep, sem))
            }
        }
        // A real curve bends steadily and visibly; a straight stroke with a wobble is fitted as lines.
        val bow = pts.maxOf { segDist(it, pts.first(), pts.last()) }
        if (corners.size >= 4 && turns.all { it < CURVE_TURN_DEG } && turns.sum() > CURVE_TOTAL_DEG && bow > 4 * eps) {
            return listOf(Prim.Curve(chaikin(rdp(pts, eps * 0.5f), 2), sem, closed))
        }
        // A polyline with sharp corners: each run between corners is a fitted line, corners at their intersections.
        val idx = rdpIndices(pts, eps)
        val all = idx.zipWithNext().map { (i, j) -> line(pts.subList(i, j + 1), sem) }
        // A thick stroke's corner leaves a stub between two sides: the sides meet directly instead.
        val lines = if (all.size > 1) all.filter { it.length >= 2f * sw }.ifEmpty { all } else all
        val out = lines.toMutableList()
        for (k in 0 until out.size - 1) {
            val p = out[k]
            val q = out[k + 1]
            val x = intersect(p.a, p.b, q.a, q.b)?.takeIf { it.dist(p.b) < 3 * eps } ?: p.b
            out[k] = p.copy(b = x)
            out[k + 1] = q.copy(a = x)
        }
        if (closed && out.size >= 3) {
            val p = out.last()
            val q = out.first()
            val x = intersect(p.a, p.b, q.a, q.b)?.takeIf { it.dist(q.a) < 3 * eps } ?: q.a
            out[out.lastIndex] = p.copy(b = x)
            out[0] = q.copy(a = x)
        }
        return out
    }

    /** Total-least-squares line through [pts], from the projection of the first point to that of the last. */
    private fun line(pts: List<XY>, sem: InkSemantic): Prim.Seg {
        val n = pts.size
        val mx = pts.sumOf { it.x.toDouble() } / n
        val my = pts.sumOf { it.y.toDouble() } / n
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (p in pts) {
            val dx = p.x - mx
            val dy = p.y - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val theta = 0.5 * atan2(2 * sxy, sxx - syy)
        val d = XY(cos(theta).toFloat(), sin(theta).toFloat())
        val m = XY(mx.toFloat(), my.toFloat())
        fun proj(p: XY): XY { val t = (p.x - m.x) * d.x + (p.y - m.y) * d.y; return m + d * t }
        return Prim.Seg(proj(pts.first()), proj(pts.last()), sem)
    }

    // ------------------------------------------------------------------ arrows, dashes, labels

    /** Two (or one) short barbs leaving a line's end backwards at 12–70° make that end an arrowhead (both ends: a dimension arrow). */
    internal fun arrows(prims: List<Prim>, minShaft: Float = 0.07f * diag, twoBarbs: Boolean = false): List<Prim> {
        val segs = prims.filterIsInstance<Prim.Seg>()
        val used = HashSet<Prim>()
        val tol = max(4f, 3.5f * sw)
        // Barbs may come out as short lines, or as tiny arcs/curves when the head was drawn round.
        val candidates: List<Triple<Prim, XY, XY>> = prims.mapNotNull { p ->
            when (p) {
                is Prim.Seg -> if (p.hatch || p.head) null else Triple(p, p.a, p.b)
                is Prim.Arc -> if (p.full || p.r > 0.08f * diag) null else p.points().let { Triple(p, it.first(), it.last()) }
                is Prim.Curve -> if (p.closed) null else Triple(p, p.points.first(), p.points.last())
                is Prim.Coil, is Prim.Block, is Prim.Mark -> null
            }
        }
        val shafts = segs.filter { it.length >= minShaft && !it.hatch && !it.dashed && !(twoBarbs && it.head) }.sortedByDescending { it.length }
        val result = HashMap<Prim.Seg, Prim.Seg>()
        val isShaft = HashSet<Prim.Seg>()
        for (s in shafts) {
            if (s in used) continue
            val ends = BooleanArray(2)
            val tips = arrayOf<XY?>(null, null)
            for ((k, tipAtB) in listOf(true, false).withIndex()) {
                val tip = if (tipAtB) s.b else s.a
                val back = ((if (tipAtB) s.a else s.b) - tip).unit()
                val start = if (tipAtB) s.a else s.b
                val barbs = candidates.filter { (b, e0, e1) ->
                    b !== s && b !in used && b !in isShaft && e0.dist(e1) < 0.45f * s.length && e0.dist(e1) < 0.16f * diag && e0.dist(e1) > max(2f, 0.5f * sw) &&
                        (e0.dist(tip) < tol || e1.dist(tip) < tol) && run {
                        // Angles from where the barb meets the head (the real tip), back along the shaft.
                        val near = if (e0.dist(tip) < e1.dist(tip)) e0 else e1
                        val far = if (near === e0) e1 else e0
                        val ang = Math.toDegrees(acos(dot((far - near).unit(), (start - near).unit()).coerceIn(-1f, 1f).toDouble()))
                        ang in 12.0..75.0
                    }
                }.take(2)
                if (barbs.isEmpty() || (twoBarbs && barbs.size < 2)) continue
                // One barb alone could be a line that only touches the end: it must be short.
                if (barbs.size == 1 && barbs[0].let { it.second.dist(it.third) } > 0.25f * s.length) continue
                used += barbs.map { it.first }
                ends[k] = true
                // The head's point is where the barbs meet: the shaft reaches it.
                val meet = barbs.map { (_, e0, e1) -> if (e0.dist(tip) < e1.dist(tip)) e0 else e1 }
                tips[k] = XY(meet.map { it.x }.average().toFloat(), meet.map { it.y }.average().toFloat())
            }
            if (!ends[0] && !ends[1]) continue
            isShaft += s
            val b = tips[0] ?: s.b
            val a = tips[1] ?: s.a
            result[s] = when {
                ends[0] -> s.copy(a = a, b = b, head = true, tail = ends[1])
                else -> Prim.Seg(b, a, s.sem, s.dashed, head = true)
            }
        }
        return prims.mapNotNull { p ->
            when {
                p is Prim.Seg && p in result -> result.getValue(p)
                p in used -> null
                else -> p
            }
        }
    }

    /**
     * Ground or wall hatching: three or more short parallel strokes, slanted to a long line and touching
     * it on one side, stay as they are drawn (not arrowheads, dashes or a spring).
     */
    internal fun hatches(prims: List<Prim>): List<Prim> {
        val segs = prims.filterIsInstance<Prim.Seg>()
        val short = segs.filter { !it.head && !it.dashed && it.length < 0.08f * diag && it.length > sw }
        val marks = HashSet<Prim.Seg>()
        for (base in segs) {
            if (base.dashed || base.length < 0.2f * diag) continue
            val near = short.filter { s ->
                val slant = angleDiff(s.angle, base.angle)
                slant in 25f..80f && min(segDist(s.a, base.a, base.b), segDist(s.b, base.a, base.b)) < max(2.5f * sw, 0.35f * s.length)
            }
            // Parallel to each other: grouped by direction.
            val group = near.filter { s -> near.count { angleDiff(it.angle, s.angle) < 15f } >= 3 }
            if (group.size >= 3) marks += group
        }
        if (marks.isEmpty()) return prims
        return prims.map { if (it is Prim.Seg && it in marks) it.copy(hatch = true) else it }
    }

    /**
     * A spring: a cluster of curved pieces (arcs, curves, short strokes) that together form a long thin
     * band with much more ink than its length (loop after loop) → one [Prim.Coil] along the band.
     */
    internal fun coils(prims: List<Prim>): List<Prim> {
        fun pointsOf(p: Prim): List<XY>? = when (p) {
            is Prim.Arc -> if (p.r < 0.12f * diag) p.points() else null
            is Prim.Curve -> p.points
            is Prim.Seg -> if (!p.head && p.length < 0.1f * diag) listOf(p.a, p.b) else null
            is Prim.Coil, is Prim.Block, is Prim.Mark -> null
        }
        val pieces = prims.mapNotNull { p -> pointsOf(p)?.let { p to it } }
        if (pieces.size < MIN_COIL_PIECES) return prims
        val gap = max(3f * sw, 0.015f * diag)
        val boxes = pieces.map { (_, pts) -> floatArrayOf(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y }) }
        val parent = IntArray(pieces.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
        for (i in pieces.indices) for (j in i + 1 until pieces.size) {
            val a = boxes[i]
            val b = boxes[j]
            if (a[0] - gap <= b[2] && b[0] - gap <= a[2] && a[1] - gap <= b[3] && b[1] - gap <= a[3]) parent[find(i)] = find(j)
        }
        val gone = HashSet<Prim>()
        val coils = ArrayList<Prim.Coil>()
        for (group0 in pieces.indices.groupBy(::find).values) {
            if (group0.size < MIN_COIL_PIECES) continue
            // A straight piece reaching out of the band (a tick under the spring, "0" below it) is not a loop.
            val band = group0.flatMap { pieces[it].second }
            val axis0 = line(band.sortedBy { it.x * 10_000 + it.y }, InkSemantic.Regular)
            val offs0 = band.map { lineDist(it, axis0) * if (cross(axis0, it) < 0) -1f else 1f }.sorted()
            val lo0 = offs0[(offs0.size * 0.05f).toInt()] - 2 * sw
            val hi0 = offs0[((offs0.size - 1) * 0.95f).toInt()] + 2 * sw
            // A straight stroke across the whole band at its end (the block it pushes, the wall) is not a loop either.
            val dir0 = (axis0.b - axis0.a).unit()
            val ts0 = band.map { dot(it - axis0.a, dir0) }
            val t0 = ts0.min()
            val t1 = ts0.max()
            val group = group0.filter { i ->
                val p = pieces[i].first
                if (p !is Prim.Seg) return@filter true
                val t = dot(p.a.mid(p.b) - axis0.a, dir0)
                val end = t < t0 + 0.08f * (t1 - t0) || t > t1 - 0.08f * (t1 - t0)
                pieces[i].second.all { q -> (lineDist(q, axis0) * if (cross(axis0, q) < 0) -1f else 1f) in lo0..hi0 } &&
                    !(end && angleDiff(p.angle, axis0.angle) > 60f && p.length > 0.7f * (hi0 - lo0 - 4 * sw))
            }
            if (group.size < MIN_COIL_PIECES) continue
            val pts = group.flatMap { pieces[it].second }
            val axis = line(pts.sortedBy { it.x * 10_000 + it.y }, InkSemantic.Regular)
            val dir = (axis.b - axis.a).unit()
            val ts = pts.map { (it.x - axis.a.x) * dir.x + (it.y - axis.a.y) * dir.y }
            val offs = pts.map { (it.x - axis.a.x) * dir.y - (it.y - axis.a.y) * dir.x }
            val length = ts.max() - ts.min()
            // The band without stray points (a loop's tail, a stroke joined to the floor).
            val sorted = offs.sorted()
            val thick = sorted[((sorted.size - 1) * 0.95f).toInt()] - sorted[(sorted.size * 0.05f).toInt()]
            val ink = group.sumOf { pieces[it].second.zipWithNext().sumOf { (p, q) -> p.dist(q).toDouble() } }.toFloat()
            if (length < 3.5f * thick || thick < 2.5f * sw || ink < 2.2f * length) continue
            val mid = (sorted[((sorted.size - 1) * 0.95f).toInt()] + sorted[(sorted.size * 0.05f).toInt()]) / 2
            val normal = XY(dir.y, -dir.x)
            val a = axis.a + dir * ts.min() + normal * mid
            val b = axis.a + dir * ts.max() + normal * mid
            val r = thick / 2
            val loops = (length / (thick * 0.75f)).toInt().coerceIn(3, 60)
            gone += group.map { pieces[it].first }
            // Loops as the teacher drew them: as tall as the band, a little wider than the pitch.
            val pitch = length / loops
            val w = (pitch * 0.62f).coerceAtMost(r)
            coils += Prim.Coil(a + dir * w * 0.85f, b - dir * w * 0.85f, loops, r, majority(group.map { pieces[it].first.sem }), w)
        }
        return if (coils.isEmpty()) prims else prims.filter { it !in gone } + coils
    }

    /** Pieces of one straight line (split where other lines cross it) → one line. */
    internal fun merged(prims: List<Prim>): List<Prim> {
        val segs = prims.filterIsInstance<Prim.Seg>().filter { !it.dashed }.toMutableList()
        val others = prims.filter { it !is Prim.Seg || it.dashed }
        val off = max(3f, 2f * sw)
        val reach = max(3f * sw, 0.03f * diag)
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in segs.indices) for (j in segs.indices) {
                if (i == j) continue
                val p = segs[i]
                val q = segs[j]
                if (p.length < q.length || q.head || q.tail) continue
                if (angleDiff(p.angle, q.angle) > MERGE_DEG) continue
                if (lineDist(q.a, p) > off || lineDist(q.b, p) > off) continue
                val d = (p.b - p.a).unit()
                fun t(x: XY) = (x.x - p.a.x) * d.x + (x.y - p.a.y) * d.y
                val lo = minOf(t(q.a), t(q.b))
                val hi = maxOf(t(q.a), t(q.b))
                if (lo > p.length + reach || hi < -reach) continue
                // An arrow keeps its tip: it only grows at its tail.
                if (p.head && hi > p.length + off) continue
                if (p.tail && lo < -off) continue
                val start = minOf(0f, lo)
                val end = maxOf(p.length, hi)
                segs[i] = p.copy(a = p.a + d * start, b = p.a + d * end)
                segs.removeAt(j)
                changed = true
                break@loop
            }
        }
        return others + segs
    }

    /** Three or more short collinear segments with regular gaps → one dashed line. */
    internal fun dashes(prims: List<Prim>): List<Prim> {
        val short = prims.filterIsInstance<Prim.Seg>().filter { !it.head && it.length < 0.09f * diag && it.length > 0.8f * sw }
        if (short.size < 3) return prims
        val parent = IntArray(short.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
        for (i in short.indices) for (j in i + 1 until short.size) {
            val p = short[i]
            val q = short[j]
            if (angleDiff(p.angle, q.angle) > 14f) continue
            if (lineDist(q.a, p) > max(2.5f, 1.6f * sw) || lineDist(q.b, p) > max(2.5f, 1.6f * sw)) continue
            val gap = minOf(p.a.dist(q.a), p.a.dist(q.b), p.b.dist(q.a), p.b.dist(q.b))
            // Dashes have real gaps between them (pieces of one solid line touch).
            // Short dashes far apart are dashes too (a construction line drawn quickly), when well in line.
            if (gap > max(2.8f * max(p.length, q.length) + sw, 0.07f * diag) || gap < max(2f, 0.6f * sw)) continue
            parent[find(i)] = find(j)
        }
        val groups = short.indices.groupBy(::find).values.filter { it.size >= 3 }
        if (groups.isEmpty()) return prims
        val gone = HashSet<Prim.Seg>()
        val dashed = groups.map { g ->
            val members = g.map { short[it] }
            gone += members
            val pts = members.flatMap { listOf(it.a, it.b) }
            val l = line(pts.sortedBy { it.x * 10_000 + it.y }, majority(members.map { it.sem }))
            // Extreme projections along the fitted direction.
            val d = (l.b - l.a).unit()
            val ts = pts.map { (it.x - l.a.x) * d.x + (it.y - l.a.y) * d.y }
            Prim.Seg(l.a + d * ts.min(), l.a + d * ts.max(), l.sem, dashed = true)
        }
        return prims.filter { it !in gone } + dashed
    }

    /**
     * A block: two parallel sides and enough of the other two that the box's outline is mostly ink
     * (a letter written inside may touch it). It must hold a label ("m") or touch a long line (a block
     * on a slope, on the ground), so square letters of a word stay letters. Its sides become one [Prim.Block],
     * squared to the axes or to the slope it sits on.
     */
    internal fun blocks(prims: List<Prim>, labels: List<Pair<XY, String>>, named: Boolean = false): List<Prim> {
        val all = prims.filterIsInstance<Prim.Seg>().filter { !it.dashed && !it.hatch }
        val sides = all.filter { !it.head && it.length >= 1.5f * sw && it.length < 0.3f * diag }
        val long = all.filter { it.length >= 0.2f * diag }
        val tol = max(3f, 2.5f * sw)
        val gone = HashSet<Prim.Seg>()
        val blocks = ArrayList<Prim.Block>()
        val pairs = sides.indices.flatMap { i -> (i + 1 until sides.size).map { i to it } }
            .filter { (i, j) -> angleDiff(sides[i].angle, sides[j].angle) < 12f }
            .sortedByDescending { (i, j) -> lineDist(sides[j].a.mid(sides[j].b), sides[i]) }
        for ((i, j) in pairs) {
            val p = sides[i]
            val q = sides[j]
            if (p in gone || q in gone) continue
            var d = (if (p.length >= q.length) p.b - p.a else q.b - q.a).unit()
            // Squared to the page or to the line it rests on.
            val ang = atan2(d.y, d.x)
            val ref = (listOf(0f, PI.toFloat() / 2) + long.map { it.angle }).firstOrNull { abs(angleBetween(ang, it)) < Math.toRadians(8.0).toFloat() }
            // A box at a random tilt is writing, not a block.
            if (ref == null) continue
            val rot = ang - angleBetween(ang, ref)
            d = XY(cos(rot), sin(rot))
            val n = XY(-d.y, d.x)
            val o = p.a
            fun t(x: XY) = dot(x - o, d)
            fun u(x: XY) = dot(x - o, n)
            val op = (u(p.a) + u(p.b)) / 2
            val oq = (u(q.a) + u(q.b)) / 2
            val sep = abs(oq - op)
            if (sep < 3 * sw || sep > 0.25f * diag) continue
            val overlap = min(max(t(p.a), t(p.b)), max(t(q.a), t(q.b))) - max(min(t(p.a), t(p.b)), min(t(q.a), t(q.b)))
            if (overlap < 0.5f * min(p.length, q.length)) continue
            // The ends: the perpendicular sides if there are any, else the parallel sides' extent.
            val t0 = min(min(t(p.a), t(p.b)), min(t(q.a), t(q.b))) - 0.5f * sep
            val t1 = max(max(t(p.a), t(p.b)), max(t(q.a), t(q.b))) + 0.5f * sep
            val across = sides.filter { s -> s !== p && s !== q && abs(angleBetween(s.angle, atan2(n.y, n.x))) < Math.toRadians(20.0).toFloat() &&
                listOf(s.a, s.b).all { e -> u(e) in (min(op, oq) - tol)..(max(op, oq) + tol) && t(e) in t0..t1 } }
            val ts = listOf(t(p.a), t(p.b), t(q.a), t(q.b)) + across.flatMap { listOf(t(it.a), t(it.b)) }
            val lo = ts.min()
            val hi = ts.max()
            if (hi - lo < 3 * sw || min(hi - lo, sep) < 0.04f * diag || (hi - lo) / sep !in 0.25f..4f) continue
            // Two real sides, not a side and a scrap of a letter or an arrowhead.
            if (min(p.length, q.length) < 0.4f * (hi - lo)) continue
            var corners = listOf(o + d * lo + n * op, o + d * hi + n * op, o + d * hi + n * oq, o + d * lo + n * oq)
            // Most of the outline is its own ink: at least three sides drawn (the fourth may be the line it rests on).
            val longest = max(hi - lo, sep)
            val own = all.filter { it.length < 1.6f * longest }
            val perimeter = corners.indices.flatMap { k -> sample(corners[k], corners[(k + 1) % 4], sw) }
            val drawn = corners.indices.map { k ->
                val pts = sample(corners[k], corners[(k + 1) % 4], sw)
                pts.count { x -> own.any { segDist(x, it.a, it.b) < tol } }.toFloat() / pts.size
            }
            if (drawn.count { it >= 0.6f } < 3 || drawn.average() < 0.7) continue
            // A side the teacher did not draw is the line the block stands on: it ends there.
            val open = drawn.indexOfFirst { it < 0.6f }
            if (open >= 0) {
                val c0 = corners[open]
                val c1 = corners[(open + 1) % 4]
                val back0 = corners[(open + 3) % 4]
                val back1 = corners[(open + 2) % 4]
                val depth = back0.dist(c0)
                val floor = long.filter { angleDiff(it.angle, atan2(c1.y - c0.y, c1.x - c0.x)) < 8f && segDist(c0.mid(c1), it.a, it.b) < 0.5f * depth }
                    .minByOrNull { segDist(c0.mid(c1), it.a, it.b) }
                if (floor != null) {
                    val n0 = intersect(back0, c0, floor.a, floor.b)
                    val n1 = intersect(back1, c1, floor.a, floor.b)
                    if (n0 != null && n1 != null) corners = listOf(n0, n1, back1, back0).let { r -> List(4) { r[(it - open + 4) % 4] } }
                }
            }
            // A mass's name ("m", "2m", "M_1") inside, not a value written beside it.
            fun mass(text: String) = '=' !in text && visibleChars(text) <= 3
            val held = labels.filter { (at, _) -> t(at) in lo..hi && u(at) in min(op, oq)..max(op, oq) }
            // Around a value or a word ("0.4 (m)"), the "box" is that writing.
            if (held.any { !mass(it.second) }) continue
            val inside = held.isNotEmpty()
            // Resting on a line: one whole side on it.
            val touching = long.any { l -> corners.indices.any { k -> segDist(corners[k], l.a, l.b) < 2 * tol && segDist(corners[(k + 1) % 4], l.a, l.b) < 2 * tol } }
            if (!inside && (named || !touching)) continue
            // Before the springs are found, only a box as squat as a mass is drawn (not a band of loops under a dimension arrow).
            if (named && (hi - lo) / sep !in 0.4f..2.5f) continue
            gone += sides.filter { s -> listOf(s.a, s.b, s.a.mid(s.b)).all { e -> perimeter.any { it.dist(e) < tol } } }
            blocks += Prim.Block(corners, majority(listOf(p.sem, q.sem)))
        }
        return if (blocks.isEmpty()) prims else prims.filter { it !in gone } + blocks
    }

    /** [p] inside the convex quad [q] (or within [margin] of it). */
    private fun inside(p: XY, q: List<XY>, margin: Float): Boolean {
        val signs = q.indices.map { k -> val a = q[k]; val b = q[(k + 1) % q.size]; (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x) }
        return signs.all { it >= 0 } || signs.all { it <= 0 } || q.indices.any { k -> segDist(p, q[k], q[(k + 1) % q.size]) < margin }
    }

    /** Which side of [s]'s line [p] is on (the sign of the cross product). */
    private fun cross(s: Prim.Seg, p: XY): Float = (s.b.x - s.a.x) * (p.y - s.a.y) - (s.b.y - s.a.y) * (p.x - s.a.x)

    private fun sample(a: XY, b: XY, step: Float): List<XY> {
        val n = max(2, (a.dist(b) / max(1f, step)).toInt())
        return (0 until n).map { a + (b - a) * (it.toFloat() / n) }
    }

    /**
     * Leftovers of writing the labels did not cover (a letter's pieces): small clusters of short strokes
     * that touch nothing else in the drawing go.
     */
    internal fun withoutScraps(prims: List<Prim>, labels: List<Pair<XY, String>> = emptyList()): List<Prim> {
        val reach = max(3f, 2f * sw)
        // A board divider in the crop: a lone straight upright or level line, touching nothing, named by no label.
        val lone = prims.filter { p ->
            p is Prim.Seg && !p.dashed && !p.hatch && !p.head && (angleDiff(p.angle, 0f) < 3f || angleDiff(p.angle, PI.toFloat() / 2) < 3f) &&
                labels.none { (at, _) -> segDist(at, p.a, p.b) < 0.12f * diag } &&
                prims.none { o -> o !== p && segDistTo(o, p) < 3 * reach }
        }.toSet()
        val small = prims.filter { p -> p !in lone && ((p is Prim.Seg && !p.head && !p.dashed && !p.hatch && p.length < 0.06f * diag) || (p is Prim.Curve && extent(p.points) < 0.06f * diag)) }
        if (small.isEmpty()) return prims.filter { it !in lone }
        fun pts(p: Prim): List<XY> = when (p) {
            is Prim.Seg -> sample(p.a, p.b, sw) + p.b
            is Prim.Curve -> p.points
            is Prim.Arc -> p.points()
            is Prim.Coil -> p.points()
            is Prim.Block -> p.corners.indices.flatMap { sample(p.corners[it], p.corners[(it + 1) % 4], sw) }
            is Prim.Mark -> listOf(p.at)
        }
        val parent = IntArray(small.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
        val sp = small.map(::pts)
        for (i in small.indices) for (j in i + 1 until small.size) {
            if (sp[i].any { a -> sp[j].any { it.dist(a) < reach } }) parent[find(i)] = find(j)
        }
        val big = prims.filter { it !in small }.map(::pts)
        val gone = HashSet<Prim>()
        val zeros = ArrayList<Prim.Mark>()
        for (group in small.indices.groupBy(::find).values) {
            val g = group.flatMap { sp[it] }
            if (extent(g) > 0.08f * diag) continue
            if (big.any { b -> b.any { x -> g.any { it.dist(x) < reach } } }) continue
            gone += group.map { small[it] }
            zero(g, labels)?.let { zeros += Prim.Mark(it, "0", majority(group.map { small[it].sem })) }
        }
        return prims.filter { it !in gone && it !in lone } + zeros
    }

    /**
     * A written "0" nobody labelled (the zero under a spring's rest position): a small upright loop, ink
     * all round an empty middle, letter-sized. Its centre, or null.
     */
    private fun zero(g: List<XY>, labels: List<Pair<XY, String>>): XY? {
        val x0 = g.minOf { it.x }; val x1 = g.maxOf { it.x }; val y0 = g.minOf { it.y }; val y1 = g.maxOf { it.y }
        val w = x1 - x0
        val h = y1 - y0
        val c = XY((x0 + x1) / 2, (y0 + y1) / 2)
        if (h < 3 * sw || h > 0.06f * diag || h / max(w, 1f) !in 1.1f..3f) return null
        if (labels.any { it.first.dist(c) < 2 * h }) return null
        // Ink near each side of its box, none in the middle.
        val sides = listOf(g.any { it.x < x0 + 0.25f * w }, g.any { it.x > x1 - 0.25f * w }, g.any { it.y < y0 + 0.2f * h }, g.any { it.y > y1 - 0.2f * h })
        val hollow = g.none { abs(it.x - c.x) < 0.12f * w && abs(it.y - c.y) < 0.15f * h }
        return if (sides.all { it } && hollow) c else null
    }

    /** Smallest distance from [o]'s points to the line [s]. */
    private fun segDistTo(o: Prim, s: Prim.Seg): Float = when (o) {
        is Prim.Seg -> minOf(segDist(o.a, s.a, s.b), segDist(o.b, s.a, s.b), segDist(s.a, o.a, o.b), segDist(s.b, o.a, o.b))
        is Prim.Arc -> o.points().minOf { segDist(it, s.a, s.b) }
        is Prim.Coil -> o.points().minOf { segDist(it, s.a, s.b) }
        is Prim.Curve -> o.points.minOf { segDist(it, s.a, s.b) }
        is Prim.Block -> o.corners.minOf { segDist(it, s.a, s.b) }
        is Prim.Mark -> segDist(o.at, s.a, s.b)
    }

    private fun extent(pts: List<XY>): Float = max(pts.maxOf { it.x } - pts.minOf { it.x }, pts.maxOf { it.y } - pts.minOf { it.y })

    /**
     * The labels say what the lines are (the AI's reading is the hint, the geometry stays the photo's):
     * a line ending at a short name ("N", "F", "f_k", "mg", "y") is a force or axis arrow pointing at
     * it; the line nearest a length written beside its middle ("0.4 (m)", "5 (m)") is a dimension arrow,
     * heads at both ends (an angle or a value such as "37°" or "V=10" is not a length).
     */
    internal fun labelled(prims: List<Prim>, labels: List<Pair<XY, String>>): List<Prim> {
        if (labels.isEmpty()) return prims
        val out = prims.toMutableList()
        val lengths = HashSet<Int>()
        for ((at, text) in labels) {
            if (!isLength(text)) continue
            // An arrow beside it before a plain line (the block's side under the label is no dimension).
            val best = out.indices.filter { i ->
                val p = out[i]
                if (p !is Prim.Seg || p.dashed || p.hatch || p.length < 0.06f * diag) return@filter false
                val d = (p.b - p.a).unit()
                val t = (at.x - p.a.x) * d.x + (at.y - p.a.y) * d.y
                t in 0.15f * p.length..0.85f * p.length && lineDist(at, p) < 0.12f * diag
            }.minByOrNull { i -> (out[i] as Prim.Seg).let { (if (it.head) 0f else diag) + lineDist(at, it) } } ?: -1
            // Heads lost to the photo (auto-enhance thins small barbs) are restored by the written length.
            if (best >= 0) {
                out[best] = (out[best] as Prim.Seg).let { if (it.head) it.copy(tail = true) else it.copy(head = true, tail = true) }
                lengths += best
            }
        }
        // Each name claims the one line end nearest to it, just past that end.
        val near = 0.1f * diag
        for ((at, text) in labels) {
            if (!isName(text)) continue
            // Already named by an arrow pointing at it (a force drawn as a rod beside the slope it is pulled along).
            val named = out.any { it is Prim.Seg && it.head && !it.tail && it.b.dist(at) < near }
            var best: Triple<Int, Boolean, Float>? = null
            for ((i, p) in out.withIndex()) {
                if (p !is Prim.Seg || p.dashed || p.length < 0.12f * diag) continue
                for (atB in listOf(true, false)) {
                    val end = if (atB) p.b else p.a
                    // A corner of the drawing (the slope meeting the upright) is not a force's end.
                    if (out.any { o -> o !== p && o is Prim.Seg && !o.dashed && o.length >= 0.12f * diag && angleDiff(o.angle, p.angle) > 20f && min(o.a.dist(end), o.b.dist(end)) < 3 * sw }) continue
                    val other = if (atB) p.a else p.b
                    val d = (end - other).unit()
                    val past = (at.x - end.x) * d.x + (at.y - end.y) * d.y
                    val dist = at.dist(end)
                    if (dist < near && past > -0.15f * p.length && (best == null || dist < best.third)) best = Triple(i, atB, dist)
                }
            }
            val (i, atB) = best ?: continue
            val p = out[i] as Prim.Seg
            // Its head found on the block it is pulled from (the block's corner looked like barbs): it points at its name.
            if (p.head && !p.tail && !atB && out.any { it is Prim.Block && inside(p.b, it.corners, max(3 * sw, 0.04f * diag)) }) {
                out[i] = Prim.Seg(p.b, p.a, p.sem, head = true)
                continue
            }
            if (named && !(p.head && p.tail)) continue
            // A force has one head, at its name (the other "head" was forces crossing where it starts).
            if (p.head && p.tail && !lengths.contains(i)) {
                out[i] = if (atB) p.copy(tail = false) else Prim.Seg(p.b, p.a, p.sem, head = true)
                continue
            }
            if (p.head || p.tail) continue
            out[i] = if (atB) p.copy(head = true) else Prim.Seg(p.b, p.a, p.sem, head = true)
        }
        return out
    }

    /**
     * Angle arcs: only the ones the teacher drew. Each angle label ("37°", "α=37°") keeps the drawn arc
     * nearest to it that spans about the angle written; drawn arcs no label claims go when the figure has
     * angle labels at all (they are pieces of writing). No arc is made up from a label: the AI's places are
     * loose, and an arc at the wrong corner is worse than none.
     */
    internal fun angleArcs(prims: List<Prim>, labels: List<Pair<XY, String>>): List<Prim> {
        val angles = labels.indices.filter { isAngle(labels[it].second) }
        if (angles.isEmpty()) return prims
        fun dir(a: Prim.Arc) = XY(cos(a.start + a.sweep / 2), sin(a.start + a.sweep / 2))
        fun mid(a: Prim.Arc) = a.c + dir(a) * a.r
        // The written angle, when there is one: the arc must span about that much.
        fun fits(text: String, sweep: Float): Boolean {
            val deg = DEGREES.find(text)?.value?.toFloatOrNull()?.takeIf { it in 5f..175f }
            val s = abs(Math.toDegrees(sweep.toDouble())).toFloat()
            return if (deg != null) abs(s - deg) < 20f else s in 15f..80f
        }
        val arcs = prims.filterIsInstance<Prim.Arc>().filter { it.mark && it.r >= 4 * sw }
        // Closest pairs first, each label and each arc once: the AI's places are loose, so the arc decides.
        val pairs = angles.flatMap { i -> arcs.filter { fits(labels[i].second, it.sweep) }.map { a -> Triple(i, a, mid(a).dist(labels[i].first)) } }
            .filter { it.third < 0.3f * diag }.sortedBy { it.third }
        val keep = HashSet<Prim.Arc>()
        val done = HashSet<Int>()
        val places = labelPlaces.toMutableList()
        for ((i, a, _) in pairs) {
            if (i in done || a in keep) continue
            keep += a
            done += i
            // The label just outside the middle of its arc, inside the angle.
            places[i] = a.c + dir(a) * (a.r + 0.045f * diag)
        }
        labelPlaces = places
        return prims.filter { it !is Prim.Arc || !it.mark || it in keep }
    }

    /**
     * Angle marks: a small arc between two lines that meet becomes an exact arc centred on their
     * meeting point, from one line to the other (kept even when a label such as "37°" sits on it).
     * A mark across a right angle is the teacher's square: a small square corner.
     */
    internal fun angleMarks(prims: List<Prim>): List<Prim> {
        // A dashed line (a normal, a construction line) is one side of an angle as often as a solid one.
        val segs = prims.filterIsInstance<Prim.Seg>().filter { it.length > 0.08f * diag }
        val tol = max(4f, 2.5f * sw)
        fun pts(p: Prim): List<XY>? = when (p) {
            is Prim.Arc -> if (p.full || p.r > 0.2f * diag) null else p.points()
            is Prim.Curve -> if (p.closed) null else p.points
            // A small arc flat enough to fit as a line is still an angle mark when it spans two lines.
            is Prim.Seg -> if (p.head || p.dashed || p.hatch || p.length > 0.12f * diag) null else listOf(p.a, p.b)
            else -> null
        }
        fun mark(p: Prim, pts: List<XY>): Prim? {
            val e0 = pts.first()
            val e1 = pts.last()
            val s0 = segs.filter { it !== p && segDist(e0, it.a, it.b) < tol }
            val s1 = segs.filter { it !== p && segDist(e1, it.a, it.b) < tol }
            for (u in s0) for (v in s1) {
                if (u === v || angleDiff(u.angle, v.angle) < 10f) continue
                val vtx = intersect(u.a, u.b, v.a, v.b) ?: continue
                val r = (e0.dist(vtx) + e1.dist(vtx)) / 2
                if (r < 2 * sw || r > 0.25f * diag || abs(e0.dist(vtx) - e1.dist(vtx)) > 0.45f * r) continue
                // Round about the vertex all the way: not a letter that happens to touch both lines.
                if (pts.any { abs(it.dist(vtx) - r) > 0.35f * r }) continue
                val a0 = atan2(e0.y - vtx.y, e0.x - vtx.x)
                var sweep = atan2(e1.y - vtx.y, e1.x - vtx.x) - a0
                while (sweep > PI) sweep -= 2 * PI.toFloat()
                while (sweep < -PI) sweep += 2 * PI.toFloat()
                // At a corner (one line ends there), not where a line crosses another.
                val corner = listOf(u.a, u.b, v.a, v.b).any { it.dist(vtx) < 3 * sw }
                if (angleDiff(u.angle, v.angle) > 82f && corner) {
                    val k = 0.75f * r
                    fun along(s: Prim.Seg, e: XY): XY = (s.b - s.a).unit().let { if (dot(it, e - vtx) >= 0) it else it * -1f }
                    val du = along(u, e0) * k
                    val dv = along(v, e1) * k
                    return Prim.Curve(listOf(vtx + du, vtx + du + dv, vtx + dv), p.sem)
                }
                return Prim.Arc(vtx, r, a0, sweep, p.sem, mark = true)
            }
            return null
        }
        val pieces = prims.mapNotNull { p -> pts(p)?.let { p to it } }
        val out = prims.toMutableList()
        val used = HashSet<Prim>()
        for ((p, q) in pieces) mark(p, q)?.let { out[out.indexOf(p)] = it; used += p }
        // An arc cut in two where a letter touches it ("α" against its arc): two pieces end to end.
        val join = 2.5f * sw
        for ((p, q) in pieces) for ((p2, q2) in pieces) {
            if (p === p2 || p in used || p2 in used) continue
            val chain = when {
                q.last().dist(q2.first()) < join -> q + q2
                q.last().dist(q2.last()) < join -> q + q2.reversed()
                else -> continue
            }
            val m = mark(p, chain) ?: continue
            out[out.indexOf(p)] = m
            out.remove(p2)
            used += p
            used += p2
        }
        return out
    }

    /**
     * A shaft drawn as two close parallel lines (a thick force arrow, a line gone over twice) → one line
     * between them, keeping any head; and an arrow found twice → once.
     */
    internal fun rods(prims: List<Prim>): List<Prim> {
        val segs = prims.filterIsInstance<Prim.Seg>().filter { !it.dashed && !it.hatch && it.length >= 0.06f * diag }.sortedByDescending { it.length }
        val gap = max(4f * sw, 0.025f * diag)
        val gone = HashSet<Prim.Seg>()
        val swap = HashMap<Prim.Seg, Prim.Seg>()
        for (p in segs) {
            if (p in gone || p in swap) continue
            for (q in segs) {
                if (q === p || q in gone || q in swap || q.length > p.length || p.length > 1.7f * q.length) continue
                if (angleDiff(p.angle, q.angle) > 6f) continue
                val off = lineDist(q.a.mid(q.b), p)
                if (off < 0.5f * sw || off > gap) continue
                val d = (p.b - p.a).unit()
                fun t(x: XY) = dot(x - p.a, d)
                val overlap = min(p.length, max(t(q.a), t(q.b))) - max(0f, min(t(q.a), t(q.b)))
                if (overlap < 0.6f * q.length) continue
                // Halfway between the two lines, over both.
                val n = XY(-d.y, d.x)
                val shift = n * (dot(q.a.mid(q.b) - p.a, n) / 2)
                val lo = min(0f, min(t(q.a), t(q.b)))
                val hi = max(p.length, max(t(q.a), t(q.b)))
                swap[p] = p.copy(a = p.a + d * lo + shift, b = p.a + d * hi + shift, head = p.head || q.head, tail = p.tail || q.tail)
                gone += q
                break
            }
        }
        if (gone.isEmpty()) return prims
        return prims.filter { it !in gone }.map { swap[it] ?: it }
    }

    /** Two arrows on (almost) the same line, the same way: one arrow found twice. The longer stays. */
    internal fun withoutTwins(prims: List<Prim>): List<Prim> {
        val arrows = prims.filterIsInstance<Prim.Seg>().filter { it.head }.sortedByDescending { it.length }
        val gap = max(4f * sw, 0.03f * diag)
        val gone = HashSet<Prim.Seg>()
        for (p in arrows) for (q in arrows) {
            if (q === p || q in gone || p in gone || q.length > p.length) continue
            if (angleDiff(p.angle, q.angle) > 8f || dot((p.b - p.a).unit(), (q.b - q.a).unit()) < 0) continue
            if (segDist(q.a, p.a, p.b) > gap || segDist(q.b, p.a, p.b) > gap) continue
            gone += q
        }
        return if (gone.isEmpty()) prims else prims.filter { it !in gone }
    }

    /**
     * A block resting on a slope (a rectangle turned to the slope, one side on it): searched for along
     * each slanted long line, as the biggest box whose three free sides all have strokes along them. Its
     * pieces (cut up by the forces drawn through it) give way to one [Prim.Block].
     */
    internal fun slopeBlocks(prims: List<Prim>): List<Prim> {
        val tol = max(2.5f, 1.2f * sw)
        val slopes = merged(prims).filterIsInstance<Prim.Seg>().filter {
            !it.dashed && !it.hatch && !it.head && it.length >= 0.25f * diag && angleDiff(it.angle, 0f) > 15f && angleDiff(it.angle, PI.toFloat() / 2) > 15f
        }
        if (slopes.isEmpty()) return prims
        val strokes = prims.filterIsInstance<Prim.Seg>().filter { !it.dashed && !it.hatch && it.length >= 2 * sw }
        // A side is drawn when strokes run along it (a hand-drawn side leans a little: more for a longer side).
        fun cover(a: XY, b: XY): Float {
            val pts = sample(a, b, max(1f, sw))
            val ang = atan2(b.y - a.y, b.x - a.x)
            val near = max(2.5f * sw, 0.08f * a.dist(b))
            val along = strokes.filter { angleDiff(it.angle, ang) < 20f }
            return pts.count { q -> along.any { segDist(q, it.a, it.b) <= near } }.toFloat() / pts.size
        }
        val blocks = ArrayList<Prim.Block>()
        val step = max(2f, sw)
        val sizes = generateSequence(0.03f * diag) { it + 0.01f * diag }.takeWhile { it <= 0.2f * diag }.toList()
        for (l in slopes) {
            val d = (l.b - l.a).unit()
            var best: List<XY>? = null
            var bestScore = 0f
            for (side in listOf(1f, -1f)) {
                val n = XY(-d.y, d.x) * side
                var t0 = 0f
                while (t0 < l.length) {
                    val p0 = l.a + d * t0
                    for (a in sizes) {
                        if (t0 + a > l.length) break
                        val p1 = l.a + d * (t0 + a)
                        // The upright sides first: most places fail there.
                        val left = cover(p0 + n * tol, p0 + n * sizes.first())
                        val right = cover(p1 + n * tol, p1 + n * sizes.first())
                        if (left < 0.8f || right < 0.8f) continue
                        for (b in sizes) {
                            if (a / b !in 0.6f..2.6f) continue
                            val c = listOf(cover(p1 + n * tol, p1 + n * b), cover(p1 + n * b, p0 + n * b), cover(p0 + n * b, p0 + n * tol))
                            if (c.min() < 0.7f) continue
                            // The biggest box that is all ink: a part of the block is a box too.
                            val score = c.average().toFloat() * (a + b)
                            if (score > bestScore) { bestScore = score; best = listOf(p0, p1, p1 + n * b, p0 + n * b) }
                        }
                    }
                    t0 += step
                }
            }
            val corners = best ?: continue
            blocks += Prim.Block(corners, l.sem)
        }
        if (blocks.isEmpty()) return prims
        // The block's own pieces: what lies (almost) all along its three drawn sides.
        val outline = blocks.flatMap { b -> (1..3).flatMap { k -> sample(b.corners[k], b.corners[(k + 1) % 4], sw) } }
        fun onOutline(p: Prim): Boolean {
            val pts = when (p) {
                is Prim.Seg -> if (p.length > 0.3f * diag) return false else sample(p.a, p.b, sw) + p.b
                is Prim.Arc -> p.points()
                is Prim.Curve -> p.points
                else -> return false
            }
            return pts.count { q -> outline.any { it.dist(q) < 2 * tol } } >= 0.7f * pts.size
        }
        return prims.filter { !onOutline(it) } + blocks
    }

    /** An angle's label: "37^\\circ", "α=37°", "\\theta". */
    private fun isAngle(t: String): Boolean = t.contains("circ") || t.contains('°') || ANGLE_NAMES.any { t.contains(it) }

    /** A length written beside a dimension arrow: "0.4\\,(m)", "5 (m)" (not an angle, a tick's "0", or a value such as "V=10"), or a height or distance's name ("h_B", "d"). */
    private fun isLength(t: String): Boolean {
        val bare = t.replace(" ", "").replace("{", "").replace("}", "")
        return LENGTH_NAME.matches(bare) || (t.any(Char::isDigit) && !isAngle(t) && '=' !in t && LENGTH_HINT.containsMatchIn(t.replace("\\,", "")))
    }

    /** A force or axis name ("N", "F", "f_k", "mg", "T_1", "y"); state names (A, B) and masses (m) are not. */
    private fun isName(t: String): Boolean = NAME.matches(t.replace(" ", "").replace("\\vec", "").replace("{", "").replace("}", ""))

    /**
     * Strokes that are a label the AI read (letters, digits, °) go: everything that fits inside the
     * label's estimated text box (from its length and a letter height that scales with the figure).
     * Arrows, dashed lines, coils and long lines always stay. Labels are typeset instead.
     */
    internal fun withoutLabels(prims: List<Prim>, labels: List<Pair<XY, String>>, words: List<FloatArray?> = emptyList()): List<Prim> {
        if (labels.isEmpty()) return prims
        val letter = (0.07f * diag).coerceIn(4f * sw, max(4f * sw, 0.11f * diag))
        val pad = 1.5f * sw
        val boxes = labels.mapIndexed { i, (c, text) ->
            // The written word the label was matched to, else a box from the label's length.
            words.getOrNull(i)?.let { w -> floatArrayOf(w[0] - pad, w[1] - pad, w[2] + pad, w[3] + pad) } ?: run {
                val hw = letter * 0.62f * visibleChars(text) / 2 + letter * 0.45f
                val hh = letter * 0.85f
                floatArrayOf(c.x - hw, c.y - hh, c.x + hw, c.y + hh)
            }
        }
        return prims.filter { p ->
            val pts = when (p) {
                is Prim.Seg -> { if (p.head || p.dashed || p.hatch || p.length > 0.16f * diag) return@filter true; listOf(p.a, p.b) }
                is Prim.Arc -> { if (p.mark || p.r > 0.08f * diag) return@filter true; p.points() }
                is Prim.Curve -> p.points
                is Prim.Coil, is Prim.Block, is Prim.Mark -> return@filter true
            }
            boxes.none { bx -> pts.all { it.x >= bx[0] && it.x <= bx[2] && it.y >= bx[1] && it.y <= bx[3] } }
        }
    }

    /** Characters a LaTeX label shows ("f_k" → 2, "37^\\circ" → 3). */
    private fun visibleChars(t: String): Int =
        t.replace(Regex("\\\\(circ|degree)"), "°").replace(Regex("\\\\[a-zA-Z]+"), "a").count { it.isLetterOrDigit() || it in "°=+-.()" }.coerceAtLeast(1)

    // ------------------------------------------------------------------ ruler and set square

    /** Squares near-horizontal/vertical lines, then makes nearly parallel or perpendicular lines exact (the longer line wins). */
    internal fun squared(prims: List<Prim>): List<Prim> {
        val segs = prims.filterIsInstance<Prim.Seg>().sortedByDescending { it.length }
        val fixed = LinkedHashMap<Prim.Seg, Prim.Seg>()
        val anchors = ArrayList<Float>() // angles (mod π) of lines already set
        for (s in segs) {
            var target: Float? = null
            val a = mod(s.angle)
            for (axis in listOf(0f, PI.toFloat() / 2, PI.toFloat())) if (abs(a - axis) < SNAP_AXIS) target = axis
            if (target == null && s.length > 0.05f * diag) {
                for (anchor in anchors) {
                    if (abs(angleBetween(a, anchor)) < SNAP_REL) { target = anchor; break }
                    val perp = mod(anchor + PI.toFloat() / 2)
                    if (abs(angleBetween(a, perp)) < SNAP_REL) { target = perp; break }
                }
            }
            val t = target?.let { mod(it) }
            val out = if (t == null) s else rotateTo(s, t)
            if (s.length > 0.05f * diag) anchors += t ?: a
            fixed[s] = out
        }
        val lines = fixed.values.filter { it.length > 0.2f * diag }.map { mod(it.angle) }
        return prims.map { p ->
            when (p) {
                is Prim.Seg -> fixed.getValue(p)
                // A block turns with the line it rests on, or squares to the page.
                is Prim.Block -> {
                    val a = mod(atan2(p.corners[1].y - p.corners[0].y, p.corners[1].x - p.corners[0].x))
                    val target = (listOf(0f, PI.toFloat() / 2) + lines + lines.map { mod(it + PI.toFloat() / 2) })
                        .minByOrNull { abs(angleBetween(a, it)) }?.takeIf { abs(angleBetween(a, it)) < Math.toRadians(8.0).toFloat() }
                    if (target == null) p else rotated(p, -angleBetween(a, target))
                }
                else -> p
            }
        }
    }

    private fun rotated(b: Prim.Block, by: Float): Prim.Block {
        val c = XY(b.corners.map { it.x }.average().toFloat(), b.corners.map { it.y }.average().toFloat())
        val cs = cos(by)
        val sn = sin(by)
        return b.copy(corners = b.corners.map { val v = it - c; c + XY(v.x * cs - v.y * sn, v.x * sn + v.y * cs) })
    }

    /** Line ends that nearly meet (end to end, or end to another line) are moved to the exact intersection. */
    internal fun welded(prims: List<Prim>): List<Prim> {
        val segs = prims.filterIsInstance<Prim.Seg>().toMutableList()
        val tol = max(4f, 2.8f * sw)
        for (i in segs.indices) for (end in 0..1) {
            val s = segs[i]
            val p = if (end == 0) s.a else s.b
            if (end == 1 && s.head) continue // arrow tips stay where the teacher put them
            var best: XY? = null
            var bestD = tol
            for (j in segs.indices) {
                if (j == i) continue
                val o = segs[j]
                if (angleDiff(s.angle, o.angle) < 8f) continue
                val x = intersect(s.a, s.b, o.a, o.b) ?: continue
                // The intersection must be near this end and on (or just past) the other line.
                val d = x.dist(p)
                if (d >= bestD) continue
                if (lineDistToSegment(x, o) > tol) continue
                best = x
                bestD = d
            }
            if (best != null) segs[i] = if (end == 0) s.copy(a = best) else s.copy(b = best)
        }
        val map = prims.filterIsInstance<Prim.Seg>().zip(segs).toMap()
        return prims.map { if (it is Prim.Seg) map.getValue(it) else it }
    }

    private fun rotateTo(s: Prim.Seg, angle: Float): Prim.Seg {
        val m = (s.a + s.b) * 0.5f
        var d = XY(cos(angle), sin(angle))
        if (dot(d, s.b - s.a) < 0) d = d * -1f
        val h = s.length / 2
        return s.copy(a = m - d * h, b = m + d * h)
    }

    // ------------------------------------------------------------------ geometry helpers

    private fun smooth(p: List<XY>): List<XY> {
        if (p.size < 5) return p
        return p.indices.map { i ->
            if (i == 0 || i == p.lastIndex) p[i] else (p[i - 1] + p[i] + p[i + 1]) * (1f / 3f)
        }
    }

    /** Kasa algebraic circle fit: centre, radius and RMS distance of the points from the circle. */
    private fun circle(p: List<XY>): Triple<XY, Float, Float>? {
        if (p.size < 6) return null
        val n = p.size.toDouble()
        val mx = p.sumOf { it.x.toDouble() } / n
        val my = p.sumOf { it.y.toDouble() } / n
        var suu = 0.0; var svv = 0.0; var suv = 0.0; var suuu = 0.0; var svvv = 0.0; var suvv = 0.0; var svuu = 0.0
        for (q in p) {
            val u = q.x - mx
            val v = q.y - my
            suu += u * u; svv += v * v; suv += u * v
            suuu += u * u * u; svvv += v * v * v; suvv += u * v * v; svuu += v * u * u
        }
        val det = suu * svv - suv * suv
        if (abs(det) < 1e-9) return null
        val b1 = 0.5 * (suuu + suvv)
        val b2 = 0.5 * (svvv + svuu)
        val uc = (b1 * svv - b2 * suv) / det
        val vc = (suu * b2 - suv * b1) / det
        val c = XY((uc + mx).toFloat(), (vc + my).toFloat())
        val r = sqrt(uc * uc + vc * vc + (suu + svv) / n).toFloat()
        if (!r.isFinite() || r <= 0f) return null
        val rms = sqrt(p.sumOf { val d = (it.dist(c) - r).toDouble(); d * d } / n).toFloat()
        return Triple(c, r, rms)
    }

    private fun sweepOf(p: List<XY>, c: XY): Float {
        var total = 0f
        var prev = atan2(p[0].y - c.y, p[0].x - c.x)
        for (i in 1 until p.size) {
            val a = atan2(p[i].y - c.y, p[i].x - c.x)
            var d = a - prev
            while (d > PI) d -= 2 * PI.toFloat()
            while (d < -PI) d += 2 * PI.toFloat()
            total += d
            prev = a
        }
        return total
    }

    companion object {
        private const val MIN_ARC_DEG = 35.0
        private const val SHARP_DEG = 55f
        /** A unit, a bracket or a decimal point: "0.4 (m)", "5cm"; a bare "0" or "2" is a tick's number. */
        private val LENGTH_HINT = Regex("""[a-zA-Z(.]""")
        private val LENGTH_NAME = Regex("""(h|H|d|L|l|\\Delta[xyhs])(_?[a-zA-Z0-9]{1,3})?""")
        private val DEGREES = Regex("""\d+(\.\d+)?""")
        private val ANGLE_NAMES = listOf("alpha", "beta", "theta", "gamma", "phi", "α", "β", "θ")
        private val NAME = Regex("""(N|F|W|T|R|f|mg|Mg|g|x|y|z|v|a)(_?[a-zA-Z0-9]{1,3})?|F_?[a-zA-Z]+""")
        private const val MIN_COIL_PIECES = 5
        private const val MERGE_DEG = 7f
        private val DEBUG: Boolean get() = System.getProperty("figure.debug") != null
        private const val CURVE_TURN_DEG = 28f
        private const val CURVE_TOTAL_DEG = 45f
        private val SNAP_AXIS = Math.toRadians(7.0).toFloat()
        private val SNAP_REL = Math.toRadians(5.0).toFloat()

        fun rdp(p: List<XY>, eps: Float): List<XY> = rdpIndices(p, eps).map { p[it] }

        /** Ramer–Douglas–Peucker: indices of the points kept. */
        fun rdpIndices(p: List<XY>, eps: Float): List<Int> {
            if (p.size < 3) return p.indices.toList()
            val keep = BooleanArray(p.size)
            keep[0] = true
            keep[p.lastIndex] = true
            val stack = ArrayDeque(listOf(0 to p.lastIndex))
            while (stack.isNotEmpty()) {
                val (i, j) = stack.removeLast()
                var best = -1
                var bestD = eps
                for (k in i + 1 until j) {
                    val d = segDist(p[k], p[i], p[j])
                    if (d > bestD) { bestD = d; best = k }
                }
                if (best >= 0) {
                    keep[best] = true
                    stack += i to best
                    stack += best to j
                }
            }
            return p.indices.filter { keep[it] }
        }

        fun chaikin(p: List<XY>, iterations: Int): List<XY> {
            var pts = p
            repeat(iterations) {
                if (pts.size < 3) return pts
                val out = ArrayList<XY>(pts.size * 2)
                out += pts.first()
                for (i in 0 until pts.size - 1) {
                    val a = pts[i]
                    val b = pts[i + 1]
                    out += a * 0.75f + b * 0.25f
                    out += a * 0.25f + b * 0.75f
                }
                out += pts.last()
                pts = out
            }
            return pts
        }

        fun segDist(p: XY, a: XY, b: XY): Float {
            val ab = b - a
            val l2 = ab.x * ab.x + ab.y * ab.y
            if (l2 < 1e-9f) return p.dist(a)
            val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / l2).coerceIn(0f, 1f)
            return p.dist(a + ab * t)
        }

        fun intersect(a: XY, b: XY, c: XY, d: XY): XY? {
            val r = b - a
            val s = d - c
            val den = r.x * s.y - r.y * s.x
            if (abs(den) < 1e-6f) return null
            val t = ((c.x - a.x) * s.y - (c.y - a.y) * s.x) / den
            return a + r * t
        }

        fun dot(a: XY, b: XY) = a.x * b.x + a.y * b.y

        /** Distance from [p] to the infinite line through [s]. */
        fun lineDist(p: XY, s: Prim.Seg): Float {
            val d = (s.b - s.a).unit()
            return abs((p.x - s.a.x) * d.y - (p.y - s.a.y) * d.x)
        }

        fun lineDistToSegment(p: XY, s: Prim.Seg) = segDist(p, s.a, s.b)

        /** Angle in [0, π). */
        fun mod(a: Float): Float {
            var x = a % PI.toFloat()
            if (x < 0) x += PI.toFloat()
            return x
        }

        /** Signed smallest difference between two line angles (mod π), in radians. */
        fun angleBetween(a: Float, b: Float): Float {
            var d = mod(a) - mod(b)
            if (d > PI / 2) d -= PI.toFloat()
            if (d < -PI / 2) d += PI.toFloat()
            return d
        }

        /** Difference between two line directions in degrees, ignoring direction (0..90). */
        fun angleDiff(a: Float, b: Float): Float = Math.toDegrees(abs(angleBetween(a, b)).toDouble()).toFloat()

        /** Turning angle at [b] in degrees (0 = straight on). */
        fun turn(a: XY, b: XY, c: XY): Float {
            val u = (b - a).unit()
            val v = (c - b).unit()
            return Math.toDegrees(acos(dot(u, v).coerceIn(-1f, 1f).toDouble())).toFloat()
        }

        fun majority(s: List<InkSemantic>): InkSemantic = s.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: InkSemantic.Regular
    }
}
