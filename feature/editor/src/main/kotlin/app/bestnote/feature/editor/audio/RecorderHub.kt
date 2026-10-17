package app.bestnote.feature.editor.audio

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import app.bestnote.core.storage.AudioWorkFiles
import app.bestnote.core.storage.SealCrypto
import app.bestnote.core.storage.SealedStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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

    /** A recording whose file is complete at [AudioFiles.fileOf] (or [AudioFiles.sealedOf] for a locked notebook). */
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

    /** Keys handed from a locked notebook's editor to the recorder service (memory only, taken once). */
    private val sealKeys = ConcurrentHashMap<String, SealCrypto.Keys>()

    fun handKeys(notebookId: String, keys: SealCrypto.Keys?) {
        if (keys == null) sealKeys.remove(notebookId) else sealKeys[notebookId] = keys
    }

    fun takeKeys(notebookId: String): SealCrypto.Keys? = sealKeys.remove(notebookId)
}

/**
 * Where a notebook's working copies of its audio live; the notebook package holds the durable ones. A locked
 * notebook's copies are [SealedStream] files (encrypted while recording, decrypted only for playback, LIB-11b);
 * see [AudioWorkFiles] for the names.
 */
internal object AudioFiles {
    const val RECORDING_SUFFIX = AudioWorkFiles.PLAIN_PART

    fun root(context: Context): File = File(context.filesDir, "audio")

    fun dir(context: Context, notebookId: String): File = AudioWorkFiles.dir(root(context), notebookId)

    fun fileOf(context: Context, notebookId: String, recordingId: String) = File(dir(context, notebookId), recordingId + AudioWorkFiles.PLAIN)

    fun partOf(context: Context, notebookId: String, recordingId: String) = File(dir(context, notebookId), "$recordingId$RECORDING_SUFFIX")

    /** The encrypted working copy of a locked notebook's recording. */
    fun sealedOf(context: Context, notebookId: String, recordingId: String) = File(dir(context, notebookId), recordingId + AudioWorkFiles.SEALED)

    /** The encrypted recording in progress (or cut off by a crash) of a locked notebook. */
    fun sealedPartOf(context: Context, notebookId: String, recordingId: String) = File(dir(context, notebookId), recordingId + AudioWorkFiles.SEALED_PART)

    /** Length of an audio file in ms, or null if it can't be read. */
    fun durationOf(file: File): Long? = duration { it.setDataSource(file.path) }

    /** Length of the audio [source] serves (decrypted on the fly), or null if it can't be read. */
    fun durationOf(source: MediaDataSource): Long? = duration { it.setDataSource(source) }

    private inline fun duration(open: (MediaMetadataRetriever) -> Unit): Long? {
        val r = MediaMetadataRetriever()
        return try {
            open(r)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            null
        } finally {
            r.release()
        }
    }
}

/** Plays a locked notebook's encrypted working copy, decrypting one chunk at a time (nothing is written in plain). */
internal class SealedMediaDataSource(private val reader: SealedStream.Reader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = try {
        reader.read(position, buffer, offset, size)
    } catch (_: IOException) {
        -1
    }

    override fun getSize(): Long = reader.size

    override fun close() = reader.close()
}
