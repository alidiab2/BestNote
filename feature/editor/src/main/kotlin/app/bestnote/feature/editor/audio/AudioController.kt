package app.bestnote.feature.editor.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import app.bestnote.core.model.AudioRecording
import app.bestnote.core.model.AudioSync
import app.bestnote.core.model.AudioTimeline
import app.bestnote.core.model.NotebookId
import app.bestnote.core.storage.AudioWorkFiles
import app.bestnote.core.storage.Autosaver
import app.bestnote.core.storage.NotebookKeyring
import app.bestnote.core.storage.SealCrypto
import app.bestnote.core.storage.SealedStream
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
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
    /** Keys of unlocked locked notebooks (LIB-11): with one for this notebook, its working copies are encrypted. */
    private val keyring: NotebookKeyring? = null,
) {
    private val mutable = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = mutable.asStateFlow()

    var host: InkSyncHost? = null

    private var saver: Autosaver? = null
    private var recordings: List<AudioRecording> = emptyList()
    private val storeLock = Mutex()
    private var player: MediaPlayer? = null

    /** Length of the recording loaded in [player]. */
    private var loadedDuration = 0L

    /** True while [player] decrypts an encrypted working copy. */
    private var sealedPlayer = false
    private var ticker: Job? = null
    private val watcher: Job = scope.launch {
        launch {
            RecorderHub.active.collect { a ->
                mutable.update { it.copy(recording = a?.takeIf { x -> x.notebookId == notebookId.value }, recordingElsewhere = a != null && a.notebookId != notebookId.value) }
            }
        }
        launch { RecorderHub.finished.collect { f -> if (f.notebookId == notebookId.value) adopt(listOf(f.recordingId), f.startedAtMillis) } }
        launch { RecorderHub.failed.collect { m -> mutable.update { it.copy(failure = m) } } }
        // Relocked (LIB-11b): the player decrypting this notebook's audio stops with the key.
        keyring?.let { ring -> launch { ring.unlocked.collect { ids -> if (sealedPlayer && notebookId !in ids) closePlayer() } } }
    }

    /** This notebook's keys while it is locked and unlocked, else null (a plain notebook). */
    private fun keys(): SealCrypto.Keys? = keyring?.get(notebookId)?.let { SealCrypto.Keys(it.dataKey) }

    private fun aad(recordingId: String) = AudioWorkFiles.aad(notebookId, recordingId)

    /**
     * The notebook finished loading: [stored] are its recordings and [assets] its audio bytes (by asset path).
     * Writes working copies that are missing and adopts recordings finished while no editor was open.
     */
    suspend fun attach(stored: List<AudioRecording>, assets: Map<String, ByteArray>, autosaver: Autosaver) {
        saver = autosaver
        recordings = stored
        val keys = keys()
        withContext(Dispatchers.IO) {
            if (keys != null) {
                // Locked notebook: working copies are only ever written encrypted (LIB-11b); plain ones an older
                // build left are encrypted now.
                AudioWorkFiles.seal(AudioFiles.dir(context, notebookId.value), notebookId, keys)
                for (r in stored) {
                    val file = AudioFiles.sealedOf(context, notebookId.value, r.id)
                    if (!file.isFile) assets[r.assetPath]?.let { SealedStream.encrypt(keys, aad(r.id), ByteArrayInputStream(it), file) }
                }
            } else {
                for (r in stored) {
                    val file = AudioFiles.fileOf(context, notebookId.value, r.id)
                    if (!file.isFile) assets[r.assetPath]?.let { file.parentFile?.mkdirs(); file.writeBytes(it) }
                }
            }
        }
        mutable.update { it.copy(recordings = stored) }
        val known = stored.mapTo(HashSet()) { it.id }
        val running = RecorderHub.active.value?.takeIf { it.notebookId == notebookId.value }?.recordingId
        val orphans = withContext(Dispatchers.IO) {
            val dir = AudioFiles.dir(context, notebookId.value)
            // A `.rec` that nothing is recording into was cut off (process killed): it has no index and can't play.
            dir.listFiles { f -> f.name.endsWith(AudioFiles.RECORDING_SUFFIX) }.orEmpty()
                .filter { it.name.removeSuffix(AudioFiles.RECORDING_SUFFIX) != running }.forEach { it.delete() }
            // An encrypted one keeps its whole chunks (ADTS plays up to there): it becomes a finished recording.
            if (keys != null) {
                dir.listFiles { f -> f.name.endsWith(AudioWorkFiles.SEALED_PART) }.orEmpty()
                    .filter { it.name.removeSuffix(AudioWorkFiles.SEALED_PART) != running }
                    .forEach { it.renameTo(File(dir, it.name.removeSuffix(AudioWorkFiles.SEALED_PART) + AudioWorkFiles.SEALED)) }
            }
            val finished = if (keys != null) AudioWorkFiles.SEALED else AudioWorkFiles.PLAIN
            dir.listFiles { f -> f.isFile && f.name.endsWith(finished) }.orEmpty()
                .map { it.name.removeSuffix(finished) }
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
        RecorderHub.handKeys(notebookId.value, keys()) // a locked notebook records encrypted (LIB-11b)
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
        val keys = keys()
        val added = withContext(Dispatchers.IO) {
            if (keys != null) return@withContext ids.mapNotNull { id -> readSealed(id, keys, startedAt) }
            ids.mapNotNull { id ->
                val file = AudioFiles.fileOf(context, notebookId.value, id)
                if (!file.isFile) return@mapNotNull null
                val duration = if (file.length() == 0L) null else AudioFiles.durationOf(file)
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

    /** A locked notebook's finished (or recovered) encrypted recording [id] and its bytes for the package. */
    private fun readSealed(id: String, keys: SealCrypto.Keys, startedAt: Long?): Pair<AudioRecording, ByteArray>? {
        val file = AudioFiles.sealedOf(context, notebookId.value, id)
        if (!file.isFile) return null
        return try {
            val duration = SealedStream.Reader(file, keys, aad(id), allowTruncated = true).use { r ->
                if (r.size == 0L) null else AudioFiles.durationOf(SealedMediaDataSource(r))
            }
            if (duration == null) {
                file.delete() // nothing recorded, or not audio
                return null
            }
            val started = startedAt ?: (file.lastModified() - duration)
            AudioRecording(id, null, started, duration) to SealedStream.decryptAll(keys, aad(id), file, allowTruncated = true)
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read encrypted recording $id", e) // wrong key or damaged: left for a later try
            null
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
            withContext(Dispatchers.IO) {
                AudioFiles.fileOf(context, notebookId.value, id).delete()
                AudioFiles.sealedOf(context, notebookId.value, id).delete()
            }
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
        val to = ms.coerceIn(0, loadedDuration.coerceAtLeast(0))
        p.seekTo(to, MediaPlayer.SEEK_CLOSEST)
        mutable.update { it.copy(positionMs = to) }
        syncDim()
    }

    fun closePlayer() {
        ticker?.cancel()
        player?.release()
        player = null
        sealedPlayer = false
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
        val keys = keys()
        return try {
            val p = MediaPlayer()
            if (keys != null) {
                // Decrypted chunk by chunk as the player reads (LIB-11b): no plaintext copy, not even in the cache.
                val reader = SealedStream.Reader(AudioFiles.sealedOf(context, notebookId.value, rec.id), keys, aad(rec.id), allowTruncated = true)
                p.setDataSource(SealedMediaDataSource(reader))
            } else {
                p.setDataSource(AudioFiles.fileOf(context, notebookId.value, rec.id).path)
            }
            p.prepare()
            val length = p.duration.toLong().takeIf { it > 0 } ?: rec.durationMillis
            p.setOnCompletionListener {
                // No player call here: after an error the player is unusable and MediaPlayer would report every call
                // as a new error (and, with no error listener, call this again).
                ticker?.cancel()
                mutable.update { s -> s.copy(playing = false, positionMs = length) }
                syncDim()
            }
            p.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Playback of ${rec.id} failed ($what, $extra)")
                scope.launch { closePlayer() }
                true
            }
            loadedDuration = length
            player = p
            sealedPlayer = keys != null
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
