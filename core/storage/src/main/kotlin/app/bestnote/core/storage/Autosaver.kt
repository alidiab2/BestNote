package app.bestnote.core.storage

import app.bestnote.core.model.AudioRecording
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.Page
import app.bestnote.core.model.PageElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/** Where an [Autosaver] stands. */
sealed interface SaveState {
    /** Everything handed to the saver is on disk. */
    data object Saved : SaveState

    /** Edits are waiting for the debounce window (or for a save in flight). */
    data object Dirty : SaveState

    /** The last save failed; it is retried automatically and on [Autosaver.flush]. */
    data class Failed(val cause: Throwable) : SaveState
}

/**
 * Debounced, per-page autosave for one open notebook (DOC-03).
 *
 * The editor calls [updatePage] with an immutable snapshot of a page's strokes after every edit.
 * That call only records the snapshot and returns: encoding runs on [cpu] and the write goes through
 * [NotebookStore.writePages] (atomic temp + fsync + rename, manifest last) on the store's own I/O
 * dispatcher, so neither the UI nor the ink thread ever waits for the disk.
 *
 * A save starts once edits have been quiet for [debounceMillis], and at the latest [maxDelayMillis]
 * after the first unsaved edit, so continuous writing still reaches the disk every few seconds.
 * Only pages edited since the last save are re-encoded and rewritten.
 *
 * A page is always encoded whole, so one save costs as many bytes (allocated, checksummed and
 * written) as the pages it touches: ~30 MB for a page of 5k strokes. While writing continuously on
 * such a page, saving every few seconds turned into tens of MB/s of garbage and disk traffic. So the
 * worker waits at least `encoded bytes / [saveBytesPerSecond]` (at most [maxCooldownMillis]) after
 * a save before starting the next one; edits made meanwhile are coalesced into it. Normal pages
 * (a few hundred KB) are unaffected; [flush] and [close] never wait. [updateLayout] adds pages and
 * changes their paper or size (PAGES-01/02); only new or changed pages are rewritten. Callers should [flush] when
 * the app goes to the background, so a later process kill finds everything on disk.
 *
 * @param scope long-lived scope (outliving the screen) that runs the background worker.
 * @param onSaved called after each successful commit with the package now on disk (e.g. to
 *   refresh the library index). Its failures never mark the save as failed.
 */
class Autosaver(
    initial: NotebookPackage,
    private val store: NotebookStore,
    private val scope: CoroutineScope,
    private val codec: StrokeChunkCodec = InkChunkCodec,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
    private val saveBytesPerSecond: Long = DEFAULT_SAVE_BYTES_PER_SECOND,
    private val maxCooldownMillis: Long = DEFAULT_MAX_COOLDOWN_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val onSaved: suspend (NotebookPackage) -> Unit = {},
) {
    init {
        require(debounceMillis in 1..maxDelayMillis) { "Need 0 < debounce <= max delay" }
        require(saveBytesPerSecond > 0 && maxCooldownMillis >= 0) { "Need a positive save budget" }
    }

    private val lock = Any()

    /** The package as last committed to the store (or as opened). Guarded by [lock]. */
    private var saved: NotebookPackage = initial

    /** pageId -> newest strokes not yet committed. Guarded by [lock]. */
    private val pending = LinkedHashMap<String, List<InkStroke>>()

    /** pageId -> newest text boxes and images not yet committed. Guarded by [lock]. */
    private val pendingElements = LinkedHashMap<String, List<PageElement>>()

    /** Asset path -> bytes (inserted images) not yet committed. Guarded by [lock]. */
    private val pendingAssets = LinkedHashMap<String, ByteArray>()

    /** New page order and metadata not yet committed, or null. Guarded by [lock]. */
    private var pendingLayout: List<Pair<String, Page>>? = null

    /** New recording list and audio bytes not yet committed, or null (MEDIA-04). Guarded by [lock]. */
    private var pendingAudio: PendingAudio? = null

    /** Extra name -> newest bytes not yet committed (format 5). Guarded by [lock]. */
    private val pendingExtras = LinkedHashMap<String, ByteArray>()
    private var closed = false

    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** Ink bytes encoded by the last save (worker thread only via [saveLocked]); sets the cooldown. */
    @Volatile private var lastEncodedBytes = 0L
    private val saveMutex = Mutex()
    private val _state = MutableStateFlow<SaveState>(SaveState.Saved)

    val state: StateFlow<SaveState> = _state.asStateFlow()

    /** The notebook as last committed. */
    val notebook get() = synchronized(lock) { saved.notebook }

    /** Page ids in order, as last committed. */
    val pageIds: List<String> get() = synchronized(lock) { saved.pages.map { it.id } }

    val hasUnsavedChanges: Boolean get() = synchronized(lock) { pending.isNotEmpty() || pendingElements.isNotEmpty() || pendingAssets.isNotEmpty() || pendingLayout != null || pendingAudio != null || pendingExtras.isNotEmpty() }

    /** The newest bytes of the notebook extra [name] (pending or committed), or null. */
    fun extra(name: String): ByteArray? = synchronized(lock) { pendingExtras[name] ?: saved.extras[name] }

    /**
     * Records [bytes] as the new content of the notebook extra [name] (e.g. `smartboard.json`) and
     * schedules a save. Unlike [addAsset], an existing extra is replaced.
     */
    fun updateExtra(name: String, bytes: ByteArray) {
        require(NotebookPackage.EXTRA_NAME.matches(name)) { "Invalid extra name '$name'" }
        synchronized(lock) {
            check(!closed) { "Autosaver is closed" }
            pendingExtras[name] = bytes
        }
        _state.value = SaveState.Dirty
        signal.trySend(Unit)
    }

    /** The recordings as last committed. */
    val recordings: List<AudioRecording> get() = synchronized(lock) { saved.recordings }

    private class PendingAudio(val recordings: List<AudioRecording>, val added: Map<String, ByteArray>)

    private val worker = scope.launch { runWorker() }

    /**
     * Records [strokes] as the new content of [pageId] and schedules a save. Cheap and non-blocking:
     * safe to call on the main thread after every stroke. [strokes] must not be mutated afterwards.
     */
    fun updatePage(pageId: String, strokes: List<InkStroke>) {
        synchronized(lock) {
            check(!closed) { "Autosaver is closed" }
            val known = pendingLayout?.any { it.first == pageId } ?: saved.pages.any { it.id == pageId }
            require(known) { "Unknown page '$pageId'" }
            pending[pageId] = strokes
        }
        _state.value = SaveState.Dirty
        signal.trySend(Unit)
    }

    /**
     * Records [elements] (text boxes and images, bottom to top) as the new content of [pageId] and
     * schedules a save. Non-blocking like [updatePage]; [elements] must not be mutated afterwards.
     */
    fun updateElements(pageId: String, elements: List<PageElement>) {
        synchronized(lock) {
            check(!closed) { "Autosaver is closed" }
            val known = pendingLayout?.any { it.first == pageId } ?: saved.pages.any { it.id == pageId }
            require(known) { "Unknown page '$pageId'" }
            pendingElements[pageId] = elements
        }
        _state.value = SaveState.Dirty
        signal.trySend(Unit)
    }

    /**
     * Stores [bytes] as the notebook asset [path] (an `assets/...` path) with the next save. A path the
     * notebook already has is left alone, so an image used many times is stored once. Call before (or
     * with) the [updateElements] that refers to it.
     */
    fun addAsset(path: String, bytes: ByteArray) {
        BnoteFormat.requireValidAssetPath(path)
        synchronized(lock) {
            check(!closed) { "Autosaver is closed" }
            if (path in saved.assets || path in pendingAssets) return
            pendingAssets[path] = bytes
        }
        _state.value = SaveState.Dirty
        signal.trySend(Unit)
    }

    /**
     * Records the notebook's pages, in order, with their metadata (template, paper, size). Pages with
     * an id the package doesn't have yet are added with no ink; [Page.index] is renumbered from the order.
     * Cheap and non-blocking, like [updatePage].
     */
    fun updateLayout(pages: List<Pair<String, Page>>) {
        require(pages.isNotEmpty()) { "A notebook always has at least one page" }
        require(pages.map { it.first }.toSet().size == pages.size) { "Page ids must be unique" }
        pages.forEach { (id, _) -> require(PageContent.PAGE_ID_PATTERN.matches(id)) { "Invalid page id '$id'" } }
        synchronized(lock) {
            check(!closed) { "Autosaver is closed" }
            pendingLayout = pages.toList()
        }
        _state.value = SaveState.Dirty
        signal.trySend(Unit)
    }

    /**
     * Records the notebook's voice recordings (MEDIA-04) and schedules a save. [added] holds the audio
     * bytes of recordings the store doesn't have yet, by [AudioRecording.assetPath]; a recording
     * dropped from [recordings] has its audio removed. Cheap and non-blocking, like [updatePage].
     */
    fun updateRecordings(recordings: List<AudioRecording>, added: Map<String, ByteArray> = emptyMap()) {
        require(recordings.map { it.id }.toSet().size == recordings.size) { "Recording ids must be unique" }
        synchronized(lock) {
            check(!closed) { "Autosaver is closed" }
            val previous = pendingAudio
            pendingAudio = PendingAudio(recordings, (previous?.added.orEmpty() + added).filterKeys { k -> recordings.any { it.assetPath == k } })
        }
        _state.value = SaveState.Dirty
        signal.trySend(Unit)
    }

    /** Saves pending edits now and returns once they are on disk. @throws on I/O failure. */
    suspend fun flush() {
        saveMutex.withLock { saveLocked() }
    }

    /** Flushes, then stops the worker. Further [updatePage] calls fail. */
    suspend fun close() {
        try {
            flush()
        } finally {
            synchronized(lock) { closed = true }
            worker.cancel()
        }
    }

    private suspend fun runWorker() {
        var retryDelay = RETRY_MIN_MILLIS
        while (true) {
            signal.receive()
            val first = timeSource.markNow()
            while (true) {
                val left = maxDelayMillis - first.elapsedNow().inWholeMilliseconds
                if (left <= 0) break
                withTimeoutOrNull(minOf(debounceMillis, left)) { signal.receive() } ?: break
            }
            retryDelay = try {
                flush()
                // Big pages: keep the background save traffic within the budget (see class doc).
                val cooldown = (lastEncodedBytes * MILLIS_PER_SECOND / saveBytesPerSecond).coerceAtMost(maxCooldownMillis)
                if (cooldown > 0) delay(cooldown)
                RETRY_MIN_MILLIS
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Already reported through [state]; try again later with the newest content.
                delay(retryDelay)
                signal.trySend(Unit)
                (retryDelay * 2).coerceAtMost(RETRY_MAX_MILLIS)
            }
        }
    }

    private suspend fun saveLocked() {
        val batch: Map<String, List<InkStroke>>
        val elements: Map<String, List<PageElement>>
        val newAssets: Map<String, ByteArray>
        val layout: List<Pair<String, Page>>?
        val base: NotebookPackage
        val audio: PendingAudio?
        val extras: Map<String, ByteArray>
        synchronized(lock) {
            if (pending.isEmpty() && pendingElements.isEmpty() && pendingAssets.isEmpty() && pendingLayout == null && pendingAudio == null && pendingExtras.isEmpty()) {
                lastEncodedBytes = 0
                return
            }
            batch = LinkedHashMap(pending)
            pending.clear()
            elements = LinkedHashMap(pendingElements)
            pendingElements.clear()
            newAssets = LinkedHashMap(pendingAssets)
            pendingAssets.clear()
            layout = pendingLayout
            pendingLayout = null
            audio = pendingAudio
            pendingAudio = null
            extras = LinkedHashMap(pendingExtras)
            pendingExtras.clear()
            base = saved
        }
        val pkg = try {
            val encoded = withContext(cpu) { batch.mapValues { (_, strokes) -> codec.encode(strokes) } }
            lastEncodedBytes = encoded.values.sumOf { it.size.toLong() }
            val (pages, changed) = if (layout == null) {
                base.pages.map { p ->
                    val ink = encoded[p.id]
                    val shapes = elements[p.id]
                    if (ink == null && shapes == null) p else PageContent(p.page, ink ?: p.strokes, p.id, shapes ?: p.elements)
                } to (batch.keys + elements.keys)
            } else {
                relayout(base, layout, encoded, elements)
            }
            val notebook = base.notebook.copy(
                updatedAtMillis = maxOf(clock(), base.notebook.updatedAtMillis),
                pageCount = pages.size,
            )
            val (assets, recordings) = if (audio == null) {
                (base.assets + newAssets) to base.recordings
            } else {
                val keep = audio.recordings.mapTo(HashSet()) { it.assetPath }
                (base.assets.filterKeys { !it.startsWith(AudioRecording.ASSET_DIR) || it in keep } + newAssets + audio.added) to audio.recordings
            }
            NotebookPackage(notebook, pages, assets, recordings, base.extras + extras).also { store.writePages(it, changed) }
        } catch (e: Throwable) {
            // Put the batch back unless the page was edited again meanwhile (newer content wins).
            synchronized(lock) {
                batch.forEach { (id, strokes) -> pending.putIfAbsent(id, strokes) }
                elements.forEach { (id, shapes) -> pendingElements.putIfAbsent(id, shapes) }
                newAssets.forEach { (path, bytes) -> pendingAssets.putIfAbsent(path, bytes) }
                if (pendingLayout == null) pendingLayout = layout
                if (audio != null && pendingAudio == null) pendingAudio = audio
                extras.forEach { (name, bytes) -> pendingExtras.putIfAbsent(name, bytes) }
            }
            if (e !is CancellationException) _state.value = SaveState.Failed(e)
            throw e
        }
        val dirty = synchronized(lock) {
            saved = pkg
            pending.isNotEmpty() || pendingElements.isNotEmpty() || pendingAssets.isNotEmpty() || pendingExtras.isNotEmpty()
        }
        _state.value = if (dirty) SaveState.Dirty else SaveState.Saved
        try {
            onSaved(pkg)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            // The content is safely on disk; a stale index entry is rebuilt from the package later.
        }
    }

    /** Pages in [layout] order with fresh metadata; returns them and the ids whose files must be written. */
    private suspend fun relayout(
        base: NotebookPackage,
        layout: List<Pair<String, Page>>,
        encoded: Map<String, ByteArray>,
        elements: Map<String, List<PageElement>>,
    ): Pair<List<PageContent>, Set<String>> {
        val old = base.pages.associateBy { it.id }
        val empty by lazy { codec.encode(emptyList()) }
        val changed = HashSet<String>(encoded.keys).apply { addAll(elements.keys) }
        val pages = layout.mapIndexed { i, (id, meta) ->
            val page = meta.copy(notebookId = base.notebook.id, index = i)
            val previous = old[id]
            if (previous?.page != page) changed += id
            val ink = encoded[id] ?: previous?.strokes ?: withContext(cpu) { empty }
            val shapes = elements[id] ?: previous?.elements ?: emptyList()
            if (previous != null && previous.elements != shapes) changed += id
            PageContent(page, ink, id, shapes)
        }
        changed.retainAll(layout.mapTo(HashSet()) { it.first })
        return pages to changed
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 500L
        const val DEFAULT_MAX_DELAY_MILLIS = 2_000L

        /** Background save budget: a 30 MB page saves every ~7 s while written on, a 300 KB one every 2 s. */
        const val DEFAULT_SAVE_BYTES_PER_SECOND = 4L * 1024 * 1024
        const val DEFAULT_MAX_COOLDOWN_MILLIS = 10_000L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val RETRY_MIN_MILLIS = 1_000L
        private const val RETRY_MAX_MILLIS = 30_000L
    }
}
