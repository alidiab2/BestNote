package app.bestnote.engine.layout.figlib

/**
 * FIG-LIB layered layout for node–edge drawings (flowcharts, food chains): each node's rank is its
 * longest path from a source (edges that close a cycle are ignored for ranking), nodes in a rank are
 * ordered by the average place of their neighbours (a few sweeps, fewer crossings), then spaced by
 * their widths. Returns each node's centre as (along the rank, rank index).
 */
internal object Layered {
    class Node(val id: String, val width: Float)

    fun layout(nodes: List<Node>, edges: List<Pair<String, String>>, gap: Float): Map<String, Pair<Float, Int>> {
        val ids = nodes.map { it.id }
        val out = ids.associateWith { ArrayList<String>() }
        val inn = ids.associateWith { ArrayList<String>() }
        // Drop back edges (found by DFS in the given order) so ranking terminates.
        val state = HashMap<String, Int>()
        val forward = ArrayList<Pair<String, String>>()
        val adj = ids.associateWith { id -> edges.filter { it.first == id && it.second in out }.map { it.second } }
        fun dfs(u: String) {
            state[u] = 1
            for (v in adj.getValue(u)) when (state[v]) {
                null -> { forward += u to v; dfs(v) }
                2 -> forward += u to v
                else -> Unit // back edge
            }
            state[u] = 2
        }
        for (id in ids) if (state[id] == null) dfs(id)
        for ((a, b) in forward) { out.getValue(a) += b; inn.getValue(b) += a }
        val rank = HashMap<String, Int>()
        fun rankOf(v: String, depth: Int = 0): Int = rank[v] ?: (if (depth > ids.size) 0 else (inn.getValue(v).maxOfOrNull { rankOf(it, depth + 1) + 1 } ?: 0)).also { rank[v] = it }
        ids.forEach { rankOf(it) }
        val ranks = ids.groupBy { rank.getValue(it) }.toSortedMap().values.map { it.toMutableList() }
        val pos = HashMap<String, Float>()
        fun place() = ranks.forEach { r -> r.forEachIndexed { i, id -> pos[id] = i.toFloat() } }
        place()
        repeat(4) { sweep ->
            val order = if (sweep % 2 == 0) ranks.indices.drop(1) else ranks.indices.reversed().drop(1)
            for (ri in order) {
                val r = ranks[ri]
                val nb = { id: String -> if (sweep % 2 == 0) inn.getValue(id) else out.getValue(id) }
                r.sortBy { id -> nb(id).mapNotNull { pos[it] }.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: pos.getValue(id) }
                r.forEachIndexed { i, id -> pos[id] = i.toFloat() }
            }
        }
        val width = nodes.associate { it.id to it.width }
        val result = HashMap<String, Pair<Float, Int>>()
        for ((ri, r) in ranks.withIndex()) {
            val total = r.sumOf { width.getValue(it).toDouble() }.toFloat() + gap * (r.size - 1)
            var x = -total / 2
            for (id in r) {
                val w = width.getValue(id)
                result[id] = (x + w / 2) to ri
                x += w + gap
            }
        }
        return result
    }
}
