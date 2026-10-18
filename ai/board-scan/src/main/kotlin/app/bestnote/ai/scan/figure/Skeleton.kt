package app.bestnote.ai.scan.figure

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A point in crop pixels. */
data class XY(val x: Float, val y: Float) {
    operator fun plus(o: XY) = XY(x + o.x, y + o.y)
    operator fun minus(o: XY) = XY(x - o.x, y - o.y)
    operator fun times(k: Float) = XY(x * k, y * k)
    fun dist(o: XY) = hypot(x - o.x, y - o.y)
    fun len() = hypot(x, y)
    fun mid(o: XY) = XY((x + o.x) / 2, (y + o.y) / 2)
    fun unit(): XY = len().let { if (it < 1e-6f) XY(0f, 0f) else XY(x / it, y / it) }
}

/**
 * Centre lines of an [InkMask]: Zhang–Suen thinning (the textbook algorithm, own implementation), then
 * the one-pixel skeleton is walked into paths between ends and junctions, short spurs are pruned and
 * paths that run straight through a junction are joined, so a line crossed by another stays one line.
 */
object Skeleton {
    fun thin(mask: InkMask): BooleanArray {
        val w = mask.width
        val h = mask.height
        val img = mask.ink.copyOf()
        val remove = ArrayList<Int>()
        var changed = true
        while (changed) {
            changed = false
            for (pass in 0..1) {
                remove.clear()
                for (y in 1 until h - 1) for (x in 1 until w - 1) {
                    val i = y * w + x
                    if (!img[i]) continue
                    val p2 = img[i - w]; val p3 = img[i - w + 1]; val p4 = img[i + 1]; val p5 = img[i + w + 1]
                    val p6 = img[i + w]; val p7 = img[i + w - 1]; val p8 = img[i - 1]; val p9 = img[i - w - 1]
                    val n = listOf(p2, p3, p4, p5, p6, p7, p8, p9)
                    val b = n.count { it }
                    if (b < 2 || b > 6) continue
                    var a = 0
                    for (k in 0 until 8) if (!n[k] && n[(k + 1) % 8]) a++
                    if (a != 1) continue
                    if (pass == 0) {
                        if (p2 && p4 && p6) continue
                        if (p4 && p6 && p8) continue
                    } else {
                        if (p2 && p4 && p8) continue
                        if (p2 && p6 && p8) continue
                    }
                    remove += i
                }
                if (remove.isNotEmpty()) changed = true
                for (i in remove) img[i] = false
            }
        }
        // Border pixels were never thinned: drop them (crops are padded, nothing real lives there).
        for (x in 0 until w) { img[x] = false; img[(h - 1) * w + x] = false }
        for (y in 0 until h) { img[y * w] = false; img[y * w + w - 1] = false }
        return img
    }

    /** A walked skeleton path; [a]/[b] are node ids at its ends (-1: a closed loop). */
    class Path(val points: MutableList<XY>, var a: Int, var b: Int) {
        val length: Float get() = points.zipWithNext().sumOf { (p, q) -> p.dist(q).toDouble() }.toFloat()
    }

    /** A junction or end: [degree] paths meet at [at]. */
    class Node(val at: XY, var degree: Int = 0)

    class Graph(val nodes: List<Node>, val paths: List<Path>)

    private val DX = intArrayOf(0, 1, 0, -1, 1, 1, -1, -1)
    private val DY = intArrayOf(-1, 0, 1, 0, -1, 1, 1, -1)

    /** Walks [skel] ([w] x [h]) into paths. */
    fun trace(skel: BooleanArray, w: Int, h: Int): Graph {
        fun on(x: Int, y: Int) = x in 0 until w && y in 0 until h && skel[y * w + x]
        fun degree(i: Int): Int {
            val x = i % w
            val y = i / w
            var d = 0
            for (k in 0 until 8) if (on(x + DX[k], y + DY[k])) d++
            return d
        }
        // Node pixels (ends and junctions); adjacent junction pixels form one node.
        val nodeOf = IntArray(skel.size) { -1 }
        val nodes = ArrayList<Node>()
        for (i in skel.indices) {
            if (!skel[i] || nodeOf[i] >= 0) continue
            val d = degree(i)
            if (d == 2) continue
            val id = nodes.size
            val cluster = ArrayList<Int>()
            val stack = ArrayDeque(listOf(i))
            nodeOf[i] = id
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                cluster += p
                if (degree(p) < 3) continue
                val px = p % w
                val py = p / w
                for (k in 0 until 8) {
                    val x = px + DX[k]
                    val y = py + DY[k]
                    if (!on(x, y)) continue
                    val q = y * w + x
                    if (nodeOf[q] < 0 && degree(q) >= 3) {
                        nodeOf[q] = id
                        stack += q
                    }
                }
            }
            nodes += Node(XY(cluster.map { it % w }.average().toFloat(), cluster.map { it / w }.average().toFloat()))
        }
        val visited = BooleanArray(skel.size)
        val paths = ArrayList<Path>()
        fun walk(start: Int, first: Int, startNode: Int) {
            val pts = arrayListOf(XY((start % w).toFloat(), (start / w).toFloat()))
            var prev = start
            var cur = first
            while (true) {
                pts += XY((cur % w).toFloat(), (cur / w).toFloat())
                val n = nodeOf[cur]
                if (n >= 0) {
                    paths += Path(pts, startNode, n)
                    return
                }
                visited[cur] = true
                val cx = cur % w
                val cy = cur / w
                var next = -1
                // 4-neighbours first, then diagonals: no corner cutting.
                for (k in 0 until 8) {
                    val x = cx + DX[k]
                    val y = cy + DY[k]
                    if (!on(x, y)) continue
                    val q = y * w + x
                    if (q == prev || visited[q]) continue
                    if (nodeOf[q] >= 0 && nodeOf[q] == startNode && pts.size <= 2) continue
                    next = q
                    break
                }
                if (next < 0) {
                    paths += Path(pts, startNode, -1)
                    return
                }
                prev = cur
                cur = next
            }
        }
        for (i in skel.indices) {
            val n = nodeOf[i]
            if (n < 0) continue
            val x = i % w
            val y = i / w
            for (k in 0 until 8) {
                val nx = x + DX[k]
                val ny = y + DY[k]
                if (!on(nx, ny)) continue
                val q = ny * w + nx
                if (nodeOf[q] >= 0 || visited[q]) continue
                walk(i, q, n)
            }
        }
        // Loops with no node (a circle, a closed triangle drawn in one go).
        for (i in skel.indices) {
            if (!skel[i] || visited[i] || nodeOf[i] >= 0) continue
            val pts = ArrayList<XY>()
            var cur = i
            var prev = -1
            while (true) {
                visited[cur] = true
                pts += XY((cur % w).toFloat(), (cur / w).toFloat())
                val cx = cur % w
                val cy = cur / w
                var next = -1
                for (k in 0 until 8) {
                    val x = cx + DX[k]
                    val y = cy + DY[k]
                    if (!on(x, y)) continue
                    val q = y * w + x
                    if (q != prev && !visited[q]) { next = q; break }
                }
                if (next < 0) break
                prev = cur
                cur = next
            }
            if (pts.size >= 3) {
                pts += pts[0]
                paths += Path(pts, -1, -1)
            }
        }
        // Paths start/end on their nodes' centres.
        for (p in paths) {
            if (p.a >= 0) p.points[0] = nodes[p.a].at
            if (p.b >= 0) p.points[p.points.lastIndex] = nodes[p.b].at
        }
        recount(nodes, paths)
        return Graph(nodes, paths)
    }

    private fun recount(nodes: List<Node>, paths: List<Path>) {
        for (n in nodes) n.degree = 0
        for (p in paths) {
            if (p.a >= 0) nodes[p.a].degree++
            if (p.b >= 0) nodes[p.b].degree++
        }
    }

    /**
     * Removes spurs (a short dead-end branch off a junction, a thinning artefact of thick or blobby
     * strokes) shorter than [spur], then joins, at every junction, the pairs of paths that continue
     * each other within [maxTurnDeg].
     */
    fun clean(g: Graph, spur: Float, maxTurnDeg: Float = 32f): List<Path> {
        val paths = g.paths.toMutableList()
        repeat(2) {
            recount(g.nodes, paths)
            paths.removeAll { p ->
                val endA = p.a >= 0 && g.nodes[p.a].degree == 1
                val endB = p.b >= 0 && g.nodes[p.b].degree == 1
                val junction = (p.a >= 0 && g.nodes[p.a].degree >= 3) || (p.b >= 0 && g.nodes[p.b].degree >= 3)
                (endA xor endB) && junction && p.length < spur
            }
        }
        recount(g.nodes, paths)
        val cosLimit = kotlin.math.cos(Math.toRadians(180.0 - maxTurnDeg)).toFloat()
        var merged = true
        while (merged) {
            merged = false
            for (n in g.nodes.indices) {
                val ends = paths.flatMap { p ->
                    buildList {
                        if (p.a == n) add(p to true)
                        if (p.b == n && p.a != n) add(p to false)
                    }
                }
                if (ends.size < 2) continue
                var best: Triple<Int, Int, Float>? = null
                for (i in ends.indices) for (j in i + 1 until ends.size) {
                    if (ends[i].first === ends[j].first) continue
                    val c = dot(outDir(ends[i].first, ends[i].second), outDir(ends[j].first, ends[j].second))
                    if (c < cosLimit && (best == null || c < best.third)) best = Triple(i, j, c)
                }
                val (i, j) = best ?: continue
                val (p, pAtStart) = ends[i]
                val (q, qAtStart) = ends[j]
                // p ... n ... q
                val left = if (pAtStart) p.points.reversed() else p.points
                val right = if (qAtStart) q.points else q.points.reversed()
                val newA = if (pAtStart) p.b else p.a
                val newB = if (qAtStart) q.b else q.a
                paths.remove(p)
                paths.remove(q)
                paths += Path((left + right.drop(1)).toMutableList(), newA, newB)
                recount(g.nodes, paths)
                merged = true
                break
            }
        }
        return paths
    }

    /** Direction leaving the node along [p], from its first few pixels. */
    private fun outDir(p: Path, atStart: Boolean): XY {
        val pts = if (atStart) p.points else p.points.asReversed()
        val k = min(pts.size - 1, max(3, min(10, pts.size / 3)))
        return (pts[k] - pts[0]).unit()
    }

    private fun dot(a: XY, b: XY) = a.x * b.x + a.y * b.y

    /** Mean thickness of the ink in pixels (area over centre-line length). */
    fun strokeWidth(mask: InkMask, skel: BooleanArray): Float {
        val len = skel.count { it }
        return if (len == 0) 1f else (mask.count.toFloat() / len).coerceIn(1f, 40f)
    }
}
