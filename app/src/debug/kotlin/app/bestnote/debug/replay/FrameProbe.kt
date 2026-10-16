package app.bestnote.debug.replay

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import app.bestnote.engine.canvas.document.SectionClock

/**
 * Debug-only frame-time logger (CANVAS-05 smooth zoom): while running, records per frame
 *  - the Choreographer interval between consecutive vsync callbacks (a stall shows as one long gap),
 *  - FrameMetrics' main-thread share (input + animation + layout + draw) and total duration.
 * [summary] reports counts of frames over 16.6 ms and 50 ms, so a scripted pinch can be compared
 * before/after on a software-GPU emulator (where absolute fps means little but main-thread time does).
 */
internal class FrameProbe(private val activity: Activity) {
    private val intervals = ArrayList<Long>()
    private val uiNanos = ArrayList<Long>()
    private val totalNanos = ArrayList<Long>()
    private var last = 0L
    private var running = false
    private val thread = HandlerThread("frame-probe").apply { start() }

    private val vsync = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (last != 0L) intervals += frameTimeNanos - last
            last = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val metrics = Window.OnFrameMetricsAvailableListener { _, m, _ ->
        val ui = m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + m.getMetric(FrameMetrics.ANIMATION_DURATION) +
            m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + m.getMetric(FrameMetrics.DRAW_DURATION)
        synchronized(uiNanos) {
            uiNanos += ui
            totalNanos += m.getMetric(FrameMetrics.TOTAL_DURATION)
        }
    }

    private var gc0 = 0L
    private var gcTime0 = 0L
    private var alloc0 = 0L

    private fun allocBytes() = android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0L

    private fun gcCount() = android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L
    private fun gcTime() = android.os.Debug.getRuntimeStat("art.gc.blocking-gc-time")?.toLongOrNull() ?: 0L

    fun start() {
        SectionClock.reset()
        SectionClock.enabled = true
        gc0 = gcCount()
        gcTime0 = gcTime()
        alloc0 = allocBytes()
        running = true
        last = 0L
        Choreographer.getInstance().postFrameCallback(vsync)
        activity.window.addOnFrameMetricsAvailableListener(metrics, Handler(thread.looper))
    }

    fun stop(): String {
        running = false
        SectionClock.enabled = false
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(metrics) }
        thread.quitSafely()
        return summary()
    }

    private fun summary(): String {
        val ui = synchronized(uiNanos) { uiNanos.toLongArray() }
        val total = synchronized(uiNanos) { totalNanos.toLongArray() }
        val gaps = intervals.toLongArray()
        fun ms(n: Long) = "%.1f".format(n / 1e6)
        fun stats(name: String, a: LongArray): String {
            if (a.isEmpty()) return "$name(n=0)"
            val s = a.sortedArray()
            val over16 = a.count { it > 16_667_000L }
            val over50 = a.count { it > 50_000_000L }
            return "$name(n=${a.size} p50=${ms(s[s.size / 2])} p90=${ms(s[s.size * 9 / 10])} p99=${ms(s[(s.size * 99 / 100).coerceAtMost(s.size - 1)])} " +
                "max=${ms(s.last())} sum=${ms(a.sum())} >16=$over16 >50=$over50)"
        }
        return "${stats("vsyncGap", gaps)} ${stats("mainThread", ui)} ${stats("frameTotal", total)} " +
            "javaAllocMb=${"%.1f".format((allocBytes() - alloc0) / 1048576.0)} gcs=${gcCount() - gc0} blockingGcMs=${gcTime() - gcTime0} sections: ${SectionClock.summary()}"
    }
}
