package app.bestnote.engine.layout.document

/**
 * Page recycling for CANVAS-07: keeps one slot (a view, a bitmap, …) bound to each page in the
 * visible range plus [overscan] pages on either side, and reuses slots that scroll out of range.
 * The number of live slots depends only on how many pages fit on screen, never on the page count,
 * so a 300-page notebook uses the same memory as a 3-page one.
 *
 * Not thread-safe; call from the UI thread.
 */
class PageWindow<T : Any>(
    private val overscan: Int = 1,
    private val create: () -> T,
    private val bind: (slot: T, page: Int) -> Unit,
    private val unbind: (slot: T) -> Unit,
) {
    init {
        require(overscan >= 0) { "overscan must be >= 0" }
    }

    private val bound = LinkedHashMap<Int, T>()
    private val pool = ArrayDeque<T>()

    /** Slots ever created: bounded by the largest window seen. */
    var created: Int = 0
        private set

    /** Pages bound right now, in no particular order. */
    val boundPages: Set<Int> get() = bound.keys

    fun slotFor(page: Int): T? = bound[page]

    /** Pages that should be bound when [visible] pages are on screen out of [pageCount]. */
    fun target(visible: IntRange, pageCount: Int): IntRange {
        if (visible.isEmpty() || pageCount <= 0) return IntRange.EMPTY
        return (visible.first - overscan).coerceAtLeast(0)..(visible.last + overscan).coerceAtMost(pageCount - 1)
    }

    /** Binds exactly the pages in [target(visible, pageCount)], recycling everything else. */
    fun update(visible: IntRange, pageCount: Int) {
        val want = target(visible, pageCount)
        val it = bound.entries.iterator()
        while (it.hasNext()) {
            val (page, slot) = it.next()
            if (page !in want) {
                unbind(slot)
                pool.addLast(slot)
                it.remove()
            }
        }
        for (page in want) {
            if (page in bound) continue
            val slot = pool.removeFirstOrNull() ?: create().also { created++ }
            bind(slot, page)
            bound[page] = slot
        }
    }

    /** Re-binds every bound slot (page sizes or templates changed). */
    fun rebindAll() {
        for ((page, slot) in bound) bind(slot, page)
    }

    /** Unbinds everything; slots stay pooled for reuse. */
    fun clear() {
        for (slot in bound.values) {
            unbind(slot)
            pool.addLast(slot)
        }
        bound.clear()
    }
}
