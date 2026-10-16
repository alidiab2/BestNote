package app.bestnote.feature.editor.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import app.bestnote.core.model.AudioRecording
import app.bestnote.core.model.AudioSync
import app.bestnote.core.model.AudioTimeline
import app.bestnote.core.model.NotebookId
import app.bestnote.core.storage.Autosaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** What the audio UI shows (MEDIA-04, MEDIA-05). */
internal data class AudioState(
    val recordings: List<AudioRecording> = emptyList(),
    /** This notebook's recording in progress, or null. */
    val recording: RecorderHub.Active? = null,
    /** Another notebook is recording; the mic button stops that one instead of starting here. */
    val recordingElsewhere: Boolean = false,
    /** The recording loaded in the player, or null. */
    val current: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    /** "Play from ink": tapping a stroke seeks to when it was written, and later ink is dimmed. */
    val playFromInk: Boolean = false,
    val failure: String? = null,
)

/** What the controller needs from the editor to dim and hit-test ink; implemented by `DocumentSession`. */
internal interface InkSyncHost {
    /** The sync of the stroke at ([x], [y]) on [page] in page points, or null if no synced stroke is there. */
    fun syncAt(page: Int, x: Float, y: Float): AudioSync?

    /** Dims ink written after [playheadMs] of [recordingId]; null restores all ink. */
    fun dim(recordingId: String?, playheadMs: Long)
}

/**
 * Recording, playback and ink sync for one open notebook (MEDIA-04/05). Recordings live in the
 * notebook package (so they export and back up with it); the files under [AudioFiles] are working
 * copies that the player and the recorder use. Main-thread API; storage work runs on IO.
 */
internal class AudioController(
    private val context: Context,
    private val notebookId: NotebookId,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = mutable.asStateFlow()

    var host: InkSyncHost? = null

    private var saver: Autosaver? = null
    private var recordings: List<AudioRecording> = emptyList()
    private val storeLock = Mutex()
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val watcher: Job = scope.launch {
        launch {
            RecorderHub.active.collect { a ->
                mutable.update { it.copy(recording = a?.takeIf { x -> x.notebookId == notebookId.value }, recordingElsewhere = a != null && a.notebookId != notebookId.value) }
            }
        }
        launch { RecorderHub.finished.collect { f -> if (f.notebookId == notebookId.value) adopt(listOf(f.recordingId), f.startedAtMillis) } }
        launch { RecorderHub.failed.collect { m -> mutable.update { it.copy(failure = m) } } }
    }

    /**
     * The notebook finished loading: [stored] are its recordings and [assets] its audio bytes (by asset path).
     * Writes working copies that are missing and adopts recordings finished while no editor was open.
     */
    suspend fun attach(stored: List<AudioRecording>, assets: Map<String, ByteArray>, autosaver: Autosaver) {
        saver = autosaver
        recordings = stored
        withContext(Dispatchers.IO) {
            for (r in stored) {
                val file = AudioFiles.fileOf(context, notebookId.value, r.id)
                if (!file.isFile) assets[r.assetPath]?.let { file.parentFile?.mkdirs(); file.writeBytes(it) }
            }
        }
        mutable.update { it.copy(recordings = stored) }
        val known = stored.mapTo(HashSet()) { it.id }
        val running = RecorderHub.active.value?.takeIf { it.notebookId == notebookId.value }?.recordingId
        val orphans = withContext(Dispatchers.IO) {
            AudioFiles.dir(context, notebookId.value).listFiles { f -> f.isFile && f.name.endsWith(".m4a") }.orEmpty()
                .map { it.name.removeSuffix(".m4a") }
                .filter { it !in known && it != running && AudioRecording.ID_PATTERN.matches(it) }
        }
        if (orphans.isNotEmpty()) adopt(orphans, null)
    }

    /** Starts a recording in this notebook (the caller has the microphone permission). */
    fun startRecording() {
        if (RecorderHub.active.value != null) return
        RecorderHub.clearFailure()
        mutable.update { it.copy(failure = null) }
        pause()
        AudioRecorderService.start(context, notebookId.value, "rec-" + UUID.randomUUID().toString().take(ID_LENGTH), null)
    }

    fun stopRecording() = AudioRecorderService.stop(context)

    fun dismissFailure() {
        RecorderHub.clearFailure()
        mutable.update { it.copy(failure = null) }
    }

    /** The sync stamp for a stroke whose pen went down at [downUptime], while this notebook is recording. */
    fun stamp(downUptime: Long): AudioSync? {
        val a = RecorderHub.active.value?.takeIf { it.notebookId == notebookId.value } ?: return null
        return AudioTimeline.stamp(a.recordingId, a.startUptime, downUptime)
    }

    private suspend fun adopt(ids: List<String>, startedAt: Long?) {
        val added = withContext(Dispatchers.IO) {
            ids.mapNotNull { id ->
                val file = AudioFiles.fileOf(context, notebookId.value, id)
                if (!file.isFile) return@mapNotNull null
                val duration = AudioFiles.durationOf(file)
                if (duration == null) {
                    file.delete() // unreadable: not a finished recording
                    return@mapNotNull null
                }
                val started = startedAt ?: (file.lastModified() - duration)
                AudioRecording(id, null, started, duration) to file.readBytes()
            }
        }
        if (added.isEmpty()) return
        storeLock.withLock {
            val fresh = added.filter { (r, _) -> recordings.none { it.id == r.id } }
            if (fresh.isEmpty()) return
            recordings = (recordings + fresh.map { it.first }).sortedBy { it.startedAtMillis }
            saver?.updateRecordings(recordings, fresh.associate { (r, bytes) -> r.assetPath to bytes })
            mutable.update { it.copy(recordings = recordings) }
        }
    }

    fun rename(id: String, title: String) {
        scope.launch {
            storeLock.withLock {
                recordings = recordings.map { if (it.id == id) it.copy(title = title.trim().ifEmpty { null }) else it }
                saver?.updateRecordings(recordings)
                mutable.update { it.copy(recordings = recordings) }
            }
        }
    }

    fun delete(id: String) {
        if (mutable.value.current == id) closePlayer()
        scope.launch {
            storeLock.withLock {
                recordings = recordings.filter { it.id != id }
                saver?.updateRecordings(recordings)
                mutable.update { it.copy(recordings = recordings) }
            }
            withContext(Dispatchers.IO) { AudioFiles.fileOf(context, notebookId.value, id).delete() }
        }
    }

    // ---------------------------------------------------------------- playback

    fun play(id: String, fromMs: Long? = null) {
        val rec = recordings.firstOrNull { it.id == id } ?: return
        if (mutable.value.current != id && !load(rec)) return
        val p = player ?: return
        if (fromMs != null) seekTo(fromMs)
        // Finished: start again from the top.
        if (!p.isPlaying && fromMs == null && mutable.value.positionMs >= rec.durationMillis - END_SLACK_MS) seekTo(0)
        p.start()
        mutable.update { it.copy(playing = true) }
        startTicker()
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        ticker?.cancel()
        mutable.update { it.copy(playing = false, positionMs = player?.currentPosition?.toLong() ?: it.positionMs) }
        syncDim()
    }

    fun togglePlay(id: String) {
        val s = mutable.value
        if (s.current == id && s.playing) pause() else play(id)
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val to = ms.coerceIn(0, p.duration.toLong().coerceAtLeast(0))
        p.seekTo(to, MediaPlayer.SEEK_CLOSEST)
        mutable.update { it.copy(positionMs = to) }
        syncDim()
    }

    fun closePlayer() {
        ticker?.cancel()
        player?.release()
        player = null
        mutable.update { it.copy(current = null, playing = false, positionMs = 0) }
        syncDim()
    }

    fun setPlayFromInk(on: Boolean) {
        mutable.update { it.copy(playFromInk = on) }
        syncDim()
    }

    /** A tap on the page in "play from ink" mode: seek to when the tapped stroke was written and play. */
    fun onInkTap(page: Int, x: Float, y: Float) {
        val sync = host?.syncAt(page, x, y) ?: return
        val rec = recordings.firstOrNull { it.id == sync.recordingId } ?: return
        play(rec.id, AudioTimeline.seekTarget(sync, rec.durationMillis))
    }

    private fun load(rec: AudioRecording): Boolean {
        closePlayer()
        val file = AudioFiles.fileOf(context, notebookId.value, rec.id)
        return try {
            val p = MediaPlayer()
            p.setDataSource(file.path)
            p.prepare()
            p.setOnCompletionListener {
                ticker?.cancel()
                mutable.update { s -> s.copy(playing = false, positionMs = p.duration.toLong()) }
                syncDim()
            }
            player = p
            mutable.update { it.copy(current = rec.id, positionMs = 0, playing = false) }
            true
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "Cannot play ${rec.id}", e)
            false
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                mutable.update { it.copy(positionMs = p.currentPosition.toLong()) }
                syncDim()
                delay(TICK_MS)
            }
        }
    }

    private fun syncDim() {
        val s = mutable.value
        host?.dim(if (s.playFromInk) s.current else null, s.positionMs)
    }

    /** The editor closed. */
    fun close() {
        watcher.cancel()
        ticker?.cancel()
        player?.release()
        player = null
        host?.dim(null, 0)
        host = null
        saver = null
    }

    private companion object {
        const val TAG = "BestNoteAudio"
        const val ID_LENGTH = 12
        const val TICK_MS = 100L
        const val END_SLACK_MS = 300L
    }
}
