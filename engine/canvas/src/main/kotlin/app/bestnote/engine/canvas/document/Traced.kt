package app.bestnote.engine.canvas.document

import android.os.Trace

/**
 * Runs [block] inside a systrace/perfetto section named [name]. While [SectionClock.enabled] (debug
 * frame-time probes only) it also adds the section's wall time to [SectionClock].
 */
internal inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(name)
    val clock = SectionClock.enabled
    val start = if (clock) System.nanoTime() else 0L
    try {
        return block()
    } finally {
        if (clock) SectionClock.add(name, System.nanoTime() - start)
        Trace.endSection()
    }
}

/** Main-thread time per [traced] section while a debug probe runs (CANVAS-05 frame-time checks). */
object SectionClock {
    @Volatile
    var enabled = false

    private class Stat {
        var count = 0
        var total = 0L
        var max = 0L
        var over8 = 0
    }

    private val stats = HashMap<String, Stat>()

    internal fun add(name: String, nanos: Long) {
        synchronized(stats) {
            val s = stats.getOrPut(name) { Stat() }
            s.count++
            s.total += nanos
            if (nanos > s.max) s.max = nanos
            if (nanos > 8_000_000L) s.over8++
        }
    }

    fun reset() = synchronized(stats) { stats.clear() }

    /** One line per section: count, total, max and calls over 8 ms, in ms. */
    fun summary(): String = synchronized(stats) {
        stats.entries.sortedByDescending { it.value.total }.joinToString(" ") { (k, s) ->
            "$k(n=${s.count} sum=${"%.1f".format(s.total / 1e6)} max=${"%.1f".format(s.max / 1e6)} >8=${s.over8})"
        }
    }
}
