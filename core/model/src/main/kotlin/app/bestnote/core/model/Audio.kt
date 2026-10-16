package app.bestnote.core.model

/**
 * A voice recording attached to a notebook (MEDIA-04). The audio itself is the notebook asset
 * [assetPath] (AAC in an m4a container); this is its metadata.
 *
 * @property id stable key, `[A-Za-z0-9_-]{1,64}`; it names the asset file.
 * @property title the user's name for it, or null for the default ("Recording" + start time).
 * @property startedAtMillis wall-clock epoch millis when recording started.
 * @property durationMillis length of the audio.
 */
data class AudioRecording(
    val id: String,
    val title: String? = null,
    val startedAtMillis: Long,
    val durationMillis: Long,
) {
    init {
        require(ID_PATTERN.matches(id)) { "Invalid recording id '$id'" }
        require(durationMillis >= 0) { "Duration must be >= 0" }
    }

    val assetPath: String get() = assetPathOf(id)

    companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        const val ASSET_DIR = "assets/audio/"
        const val EXTENSION = ".m4a"

        fun assetPathOf(id: String) = "$ASSET_DIR$id$EXTENSION"
    }
}

/** A stroke was written [offsetMillis] after recording [recordingId] started (MEDIA-05). */
data class AudioSync(val recordingId: String, val offsetMillis: Long) {
    init {
        require(AudioRecording.ID_PATTERN.matches(recordingId)) { "Invalid recording id '$recordingId'" }
        require(offsetMillis >= 0) { "Offset must be >= 0" }
    }
}

/**
 * Pure rules for linking ink and audio (MEDIA-05): stamping strokes while recording, seeking from a
 * tapped stroke, and which ink is "not yet written" at a playhead.
 */
object AudioTimeline {
    /** How far before a tapped stroke playback starts, so the listener hears the lead-in to the writing. */
    const val LEAD_IN_MILLIS = 1_000L

    /**
     * The sync stamp for a stroke whose pen went down at [strokeStartUptime] while a recording that
     * started at [recordingStartUptime] (same clock) was running, or null if it started before it.
     */
    fun stamp(recordingId: String, recordingStartUptime: Long, strokeStartUptime: Long): AudioSync? =
        if (strokeStartUptime < recordingStartUptime) null else AudioSync(recordingId, strokeStartUptime - recordingStartUptime)

    /** Where to seek in a recording of [durationMillis] when [sync]'s stroke is tapped. */
    fun seekTarget(sync: AudioSync, durationMillis: Long): Long =
        (sync.offsetMillis - LEAD_IN_MILLIS).coerceIn(0L, maxOf(0L, durationMillis))

    /** True if the stroke stamped [sync] is written after [playheadMillis] of recording [recordingId]: it is dimmed. */
    fun isLater(sync: AudioSync?, recordingId: String, playheadMillis: Long): Boolean =
        sync != null && sync.recordingId == recordingId && sync.offsetMillis > playheadMillis

    /** How many of the sorted [offsets] are not later than [playheadMillis] (a cheap signature of the dim set). */
    fun writtenCount(offsets: LongArray, playheadMillis: Long): Int {
        var lo = 0
        var hi = offsets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] <= playheadMillis) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
