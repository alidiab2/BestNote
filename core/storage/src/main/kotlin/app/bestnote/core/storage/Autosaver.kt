package app.bestnote.core.storage

import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.Page
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
 * Only pages edited since the last save are re-encoded and rewritten. [updateLayout] adds pages and
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
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val onSaved: suspend (NotebookPackage) -> Unit = {},
) {
    init {
        require(debounceMillis in 1..maxDelayMillis) { "Need 0 < debounce <= max delay" }
    }

    private val lock = Any()

    /** The package as last committed to the store (or as opened). Guarded by [lock]. */
    private var saved: NotebookPackage = initial

    /** pageId -> newest strokes not yet committed. Guarded by [lock]. */
    private val pending = LinkedHashMap<String, List<InkStroke>>()

    /** New page order and metadata not yet committed, or null. Guarded by [lock]. */
    private var pendingLayout: List<Pair<String, Page>>? = null
    private var closed = false

    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val saveMutex = Mutex()
    private val _state = MutableStateFlow<SaveState>(SaveState.Saved)

    val state: StateFlow<SaveState> = _state.asStateFlow()

    /** The notebook as last committed. */
    val notebook get() = synchronized(lock) { saved.notebook }

    /** Page ids in order, as last committed. */
    val pageIds: List<String> get() = synchronized(lock) { saved.pages.map { it.id } }

    val hasUnsavedChanges: Boolean get() = synchronized(lock) { pending.isNotEmpty() || pendingLayout != null }

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
        val layout: List<Pair<String, Page>>?
        val base: NotebookPackage
        synchronized(lock) {
            if (pending.isEmpty() && pendingLayout == null) return
            batch = LinkedHashMap(pending)
            pending.clear()
            layout = pendingLayout
            pendingLayout = null
            base = saved
        }
        val pkg = try {
            val encoded = withContext(cpu) { batch.mapValues { (_, strokes) -> codec.encode(strokes) } }
            val (pages, changed) = if (layout == null) {
                base.pages.map { p -> encoded[p.id]?.let { PageContent(p.page, it, p.id) } ?: p } to batch.keys
            } else {
                relayout(base, layout, encoded)
            }
            val notebook = base.notebook.copy(
                updatedAtMillis = maxOf(clock(), base.notebook.updatedAtMillis),
                pageCount = pages.size,
            )
            NotebookPackage(notebook, pages, base.assets).also { store.writePages(it, changed) }
        } catch (e: Throwable) {
            // Put the batch back unless the page was edited again meanwhile (newer content wins).
            synchronized(lock) {
                batch.forEach { (id, strokes) -> pending.putIfAbsent(id, strokes) }
                if (pendingLayout == null) pendingLayout = layout
            }
            if (e !is CancellationException) _state.value = SaveState.Failed(e)
            throw e
        }
        val dirty = synchronized(lock) {
            saved = pkg
            pending.isNotEmpty()
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
    ): Pair<List<PageContent>, Set<String>> {
        val old = base.pages.associateBy { it.id }
        val empty by lazy { codec.encode(emptyList()) }
        val changed = HashSet<String>(encoded.keys)
        val pages = layout.mapIndexed { i, (id, meta) ->
            val page = meta.copy(notebookId = base.notebook.id, index = i)
            val previous = old[id]
            if (previous?.page != page) changed += id
            val ink = encoded[id] ?: previous?.strokes ?: withContext(cpu) { empty }
            PageContent(page, ink, id)
        }
        changed.retainAll(layout.mapTo(HashSet()) { it.first })
        return pages to changed
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 500L
        const val DEFAULT_MAX_DELAY_MILLIS = 2_000L
        private const val RETRY_MIN_MILLIS = 1_000L
        private const val RETRY_MAX_MILLIS = 30_000L
    }
}
