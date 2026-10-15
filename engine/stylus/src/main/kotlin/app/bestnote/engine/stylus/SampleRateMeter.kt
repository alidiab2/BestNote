package app.bestnote.engine.stylus

/**
 * Measures the digitizer sample rate of one stroke (SPEN-01 "Done when": ≈240 Hz on Tab S).
 * Feed every sample time of a stroke (historical samples included), then read [hz].
 */
class SampleRateMeter {
    private var first = -1L
    private var last = -1L
    var samples = 0
        private set

    fun start() {
        first = -1L
        last = -1L
        samples = 0
    }

    fun add(timeMillis: Long) {
        if (first < 0) first = timeMillis
        last = timeMillis
        samples++
    }

    val durationMillis: Long get() = if (first < 0) 0 else last - first

    /** Samples per second over the stroke, or null when too short to measure. */
    val hz: Float?
        get() = if (samples < MIN_SAMPLES || durationMillis <= 0) null else (samples - 1) * 1000f / durationMillis

    private companion object {
        const val MIN_SAMPLES = 5
    }
}
