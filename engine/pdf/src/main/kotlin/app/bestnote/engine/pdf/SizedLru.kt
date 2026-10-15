package app.bestnote.engine.pdf

/**
 * Least-recently-used map bounded by the total weight of its values (e.g. bitmap bytes) rather than
 * their count. Not thread-safe: callers synchronize. The most recently inserted entry is never
 * evicted, even if it alone exceeds [maxWeight], so a render that was just paid for can be drawn.
 */
internal class SizedLru<K : Any, V : Any>(private val maxWeight: Long, private val weigh: (V) -> Long) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    var weight: Long = 0
        private set

    val size: Int get() = map.size

    operator fun get(key: K): V? = map[key]

    /** The values without touching their recency. */
    fun values(): List<V> = map.values.toList()

    fun entries(): List<Map.Entry<K, V>> = map.entries.toList()

    fun put(key: K, value: V) {
        map.put(key, value)?.let { weight -= weigh(it) }
        weight += weigh(value)
        val it = map.entries.iterator()
        while (weight > maxWeight && map.size > 1 && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == key) continue
            weight -= weigh(eldest.value)
            it.remove()
        }
    }

    fun clear() {
        map.clear()
        weight = 0
    }
}
