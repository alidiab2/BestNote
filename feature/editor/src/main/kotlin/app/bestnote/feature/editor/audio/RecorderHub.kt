package app.bestnote.feature.editor.audio

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Process-wide state of the one running recording (MEDIA-04). The [AudioRecorderService] writes it,
 * editors read it: the service outlives any screen, so the screen can never own the recorder.
 */
internal object RecorderHub {
    /**
     * A recording in progress. [startUptime] is `SystemClock.uptimeMillis()` when the recorder started,
     * the same clock as `MotionEvent.getDownTime()`, so strokes can be stamped against it (MEDIA-05).
     */
    data class Active(val notebookId: String, val recordingId: String, val startUptime: Long, val startedAtMillis: Long)

    /** A recording whose file is complete at [AudioFiles.fileOf]. */
    data class Finished(val notebookId: String, val recordingId: String, val startedAtMillis: Long)

    private val activeFlow = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = activeFlow.asStateFlow()

    private val finishedFlow = MutableSharedFlow<Finished>(extraBufferCapacity = 8)
    val finished: SharedFlow<Finished> = finishedFlow.asSharedFlow()

    /** Set when the recorder could not start (microphone busy, no input); cleared by the screen that shows it. */
    private val failedFlow = MutableStateFlow<String?>(null)
    val failed: StateFlow<String?> = failedFlow.asStateFlow()

    fun started(active: Active) {
        failedFlow.value = null
        activeFlow.value = active
    }

    fun stopped(finished: Finished?) {
        activeFlow.value = null
        if (finished != null) finishedFlow.tryEmit(finished)
    }

    fun failed(message: String) {
        activeFlow.value = null
        failedFlow.value = message
    }

    fun clearFailure() {
        failedFlow.value = null
    }
}

/** Where a notebook's working copies of its audio live; the notebook package holds the durable ones. */
internal object AudioFiles {
    const val RECORDING_SUFFIX = ".rec"

    fun dir(context: Context, notebookId: String): File =
        File(context.filesDir, "audio/" + notebookId.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    fun fileOf(context: Context, notebookId: String, recordingId: String) = File(dir(context, notebookId), "$recordingId.m4a")

    fun partOf(context: Context, notebookId: String, recordingId: String) = File(dir(context, notebookId), "$recordingId$RECORDING_SUFFIX")

    /** Length of an audio file in ms, or null if it can't be read. */
    fun durationOf(file: File): Long? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            null
        } finally {
            r.release()
        }
    }
}
