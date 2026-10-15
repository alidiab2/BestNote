package app.bestnote.engine.canvas.select

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** An axis-aligned box in page points. */
data class Box(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
    val centerX: Float get() = (minX + maxX) / 2
    val centerY: Float get() = (minY + maxY) / 2

    fun intersects(o: Box) = minX <= o.maxX && o.minX <= maxX && minY <= o.maxY && o.minY <= maxY
    fun contains(x: Float, y: Float) = x in minX..maxX && y in minY..maxY
    fun union(o: Box) = Box(min(minX, o.minX), min(minY, o.minY), max(maxX, o.maxX), max(maxY, o.maxY))
    fun inflate(d: Float) = Box(minX - d, minY - d, maxX + d, maxY + d)

    companion object {
        fun of(xs: FloatArray, ys: FloatArray, count: Int = xs.size): Box? {
            if (count <= 0) return null
            var x0 = xs[0]
            var y0 = ys[0]
            var x1 = xs[0]
            var y1 = ys[0]
            for (i in 1 until count) {
                x0 = min(x0, xs[i]); y0 = min(y0, ys[i]); x1 = max(x1, xs[i]); y1 = max(y1, ys[i])
            }
            return Box(x0, y0, x1, y1)
        }

        fun union(boxes: Iterable<Box>): Box? = boxes.reduceOrNull { a, b -> a.union(b) }
    }
}

/**
 * A uniform grid over page points (CANVAS-08): each item is filed under every cell its box
 * overlaps, so a query only looks at items near the query box instead of the whole page.
 * Pure Kotlin. Build once per page content version; queries are O(cells + hits).
 */
class SpatialGrid<T : Any>(private val cellSize: Float = DEFAULT_CELL) {
    init {
        require(cellSize > 0f) { "cellSize must be > 0" }
    }

    private val cells = HashMap<Long, MutableList<Int>>()
    private val items = ArrayList<T>()
    private val boxes = ArrayList<Box>()

    /** Items too big for the grid (e.g. dragged far off the page): every query checks them. */
    private val oversized = ArrayList<Int>()

    val size: Int get() = items.size

    fun insert(item: T, box: Box) {
        val index = items.size
        items += item
        boxes += box
        if (!forCells(box) { key -> cells.getOrPut(key) { ArrayList(2) } += index }) oversized += index
    }

    /** Items whose box intersects [box], each once, in insertion (z) order. */
    fun query(box: Box): List<T> {
        val hits = java.util.BitSet()
        val check = { i: Int -> if (boxes[i].intersects(box)) hits.set(i) }
        oversized.forEach(check)
        if (!forCells(box) { key -> cells[key]?.forEach(check) }) boxes.indices.forEach(check)
        val out = ArrayList<T>(hits.cardinality())
        var i = hits.nextSetBit(0)
        while (i >= 0) {
            out += items[i]
            i = hits.nextSetBit(i + 1)
        }
        return out
    }

    /** Calls [action] for every cell [box] overlaps. @return false (and does nothing) when that is too many cells. */
    private inline fun forCells(box: Box, action: (Long) -> Unit): Boolean {
        val cx0 = cell(box.minX)
        val cy0 = cell(box.minY)
        val cx1 = cell(box.maxX)
        val cy1 = cell(box.maxY)
        if ((cx1.toLong() - cx0 + 1) * (cy1.toLong() - cy0 + 1) > MAX_CELLS_PER_BOX) return false
        for (cy in cy0..cy1) for (cx in cx0..cx1) action(key(cx, cy))
        return true
    }

    private fun cell(v: Float): Int = floor(v / cellSize).toInt()

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    companion object {
        /** About a word of handwriting. */
        const val DEFAULT_CELL = 64f
        private const val MAX_CELLS_PER_BOX = 4096L
    }
}
