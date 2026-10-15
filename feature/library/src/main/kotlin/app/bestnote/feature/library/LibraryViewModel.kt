package app.bestnote.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bestnote.core.designsystem.theme.CoverPalette
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Seeds demo notebooks. A real implementation exists only in debug builds. */
fun interface LibrarySeeder {
    suspend fun seed()
}

/** Wall clock, injectable for tests. */
fun interface LibraryClock {
    fun now(): Long
}

/**
 * App-wide "open today's Quicknote" requests: the launcher shortcut (and LIB-08 widgets later) call
 * [request]; the library screen performs it.
 */
@Singleton
class QuicknoteRequests @Inject constructor() {
    private val pending = MutableStateFlow(false)
    val requested: StateFlow<Boolean> = pending

    fun request() {
        pending.value = true
    }

    /** @return true if a request was pending (and is now taken). */
    fun take(): Boolean = pending.compareAndSet(expect = true, update = false)

    companion object {
        /** Intent action of the launcher shortcut. */
        const val ACTION = "app.bestnote.action.QUICKNOTE"
    }
}

/** Short messages the screen shows in a snackbar. */
enum class Notice { ExportSoon, ImportingPdf, ImportPdfFailed, Duplicated, Moved, FolderCreated, FolderDeleted, AddedToFavorites, RemovedFromFavorites }

sealed interface LibraryEvent {
    data class Open(val id: NotebookId) : LibraryEvent

    /** Undo snackbar for [ids] just moved to the Trash; [title] names the first one. */
    data class Trashed(val ids: List<NotebookId>, val title: String) : LibraryEvent
    data class Message(val notice: Notice, val count: Int = 1, val name: String = "") : LibraryEvent
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val metadata: LibraryMetadata,
    private val seeder: LibrarySeeder,
    private val clock: LibraryClock,
    private val quicknotes: QuicknoteRequests,
    private val pdfImporter: PdfImporting,
    private val pdfRequests: PdfOpenRequests,
) : ViewModel() {
    private val notebooks = MutableStateFlow<List<Notebook>?>(null)
    private val trash = MutableStateFlow<List<TrashedNotebook>>(emptyList())
    private val thumbnails = MutableStateFlow<Map<NotebookId, String>>(emptyMap())
    private val controls = MutableStateFlow(LibraryControls())

    /** Bumped after every metadata change, so the state rebuilds. */
    private val metaVersion = MutableStateFlow(0)
    private val events = Channel<LibraryEvent>(Channel.BUFFERED)

    val state: StateFlow<LibraryUiState> = combine(notebooks, trash, controls, thumbnails, metaVersion) { list, bin, c, thumbs, _ ->
        buildLibraryState(list, bin, c, metadata, CoverPalette.All.size, clock.now(), thumbnails = thumbs)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    /** One-shot UI events: navigation, the undo snackbar and notices. */
    val eventFlow: Flow<LibraryEvent> = events.receiveAsFlow()

    /** Pending launcher-shortcut Quicknote requests. */
    val quicknoteRequested: StateFlow<Boolean> = quicknotes.requested

    /** The PDF an "Open with" / share intent asked to import, if any. */
    val pdfRequested: StateFlow<String?> = pdfRequests.requested

    init {
        viewModelScope.launch {
            metadata.load()
            seeder.seed()
            repository.purgeExpired()
            reload()
            metadata.retain((notebooks.value.orEmpty().map { it.id } + trash.value.map { it.notebook.id }).toSet())
        }
    }

    /** Re-reads the store, e.g. when returning from the editor. */
    fun refresh() {
        // Before the first load (metadata load + seeding) a reload would show a half-seeded library.
        if (notebooks.value == null) return
        viewModelScope.launch { reload() }
    }

    // region View controls

    fun select(destination: LibraryDestination) = controls.update {
        it.copy(destination = destination, query = "", selecting = false, selected = emptySet(), folderColor = null)
    }

    fun setLayout(layout: LibraryLayout) = controls.update { it.copy(layout = layout) }
    fun setFilter(filter: NotebookFilter) = controls.update { it.copy(filter = filter) }

    /** Toggles the folder-color filter. */
    fun setFolderColor(colorIndex: Int?) = controls.update { it.copy(folderColor = if (it.folderColor == colorIndex) null else colorIndex) }
    fun clearFilters() = controls.update { it.copy(filter = NotebookFilter.All, folderColor = null) }
    fun setSort(sort: SortOrder) = controls.update { it.copy(sort = sort) }
    fun setQuery(query: String) = controls.update { it.copy(query = query) }

    // endregion

    // region Selection

    fun startSelection(first: NotebookId? = null) = controls.update { it.copy(selecting = true, selected = setOfNotNull(first)) }
    fun endSelection() = controls.update { it.copy(selecting = false, selected = emptySet()) }
    fun toggleSelected(id: NotebookId) = controls.update {
        it.copy(selecting = true, selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun selectAll() = controls.update { c -> c.copy(selecting = true, selected = state.value.items.map { it.id }.toSet()) }

    // endregion

    // region Notebooks

    fun open(id: NotebookId) {
        events.trySend(LibraryEvent.Open(id))
    }

    /** Creates a notebook from the new-notebook sheet, in the open folder if there is one, and opens it. */
    fun create(spec: NewNotebookSpec) {
        viewModelScope.launch {
            val notebook = repository.create(spec.title, spec.template, spec.format)
            val folderId = (controls.value.destination as? LibraryDestination.InFolder)?.folderId
            metadata.update(notebook.id) {
                it.copy(
                    coverIndex = spec.cover.colorIndex,
                    coverStyle = spec.cover.style,
                    folderId = folderId,
                    favorite = controls.value.destination == LibraryDestination.Favorites,
                )
            }
            reload()
            events.send(LibraryEvent.Open(notebook.id))
        }
    }

    /** Opens today's Quicknote, creating it (blank page) if there is none yet. [title] is used for a new one. */
    fun quicknote(title: String) {
        viewModelScope.launch {
            val now = clock.now()
            val list = notebooks.value ?: repository.list()
            val today = list.filter { metadata.meta(it.id).quicknote && sameDay(it.createdAtMillis, now, TimeZone.getDefault()) }
                .maxByOrNull { it.createdAtMillis }
            val id = today?.id ?: repository.create(title, PaperTemplates.Quicknote).id.also { id ->
                metadata.update(id) { it.copy(quicknote = true, coverIndex = QUICKNOTE_COVER, coverStyle = CoverStyle.Plain) }
                reload()
            }
            events.send(LibraryEvent.Open(id))
        }
    }

    /** Runs a pending launcher-shortcut request, if any. */
    fun takeQuicknoteRequest(title: String) {
        if (quicknotes.take()) quicknote(title)
    }

    fun rename(id: NotebookId, title: String) {
        viewModelScope.launch {
            repository.rename(id, title)
            reload()
        }
    }

    fun setCover(ids: Collection<NotebookId>, cover: CoverChoice) {
        viewModelScope.launch {
            metadata.update(ids) { it.copy(coverIndex = cover.colorIndex, coverStyle = cover.style) }
            metaChanged()
        }
    }

    /** Duplicates [id] as "[copyTitle]"; the copy keeps the cover, folder and favorite state. */
    fun duplicate(id: NotebookId, copyTitle: String) {
        viewModelScope.launch {
            val copy = repository.duplicate(id, copyTitle) ?: return@launch
            val meta = metadata.meta(id)
            metadata.update(copy.id) { meta.copy(lastPageIndex = 0, quicknote = false) }
            reload()
            events.send(LibraryEvent.Message(Notice.Duplicated, name = copy.title))
        }
    }

    /** Moves [ids] into [folderId], or out of every folder when null. Unknown folders are ignored. */
    fun moveToFolder(ids: Collection<NotebookId>, folderId: String?) {
        if (ids.isEmpty()) return
        if (folderId != null && metadata.folders().none { it.id == folderId }) return
        viewModelScope.launch {
            metadata.update(ids) { it.copy(folderId = folderId) }
            metaChanged()
            endSelection()
            val name = metadata.folders().firstOrNull { it.id == folderId }?.name.orEmpty()
            events.send(LibraryEvent.Message(Notice.Moved, ids.size, name))
        }
    }

    fun setFavorite(ids: Collection<NotebookId>, favorite: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            metadata.update(ids) { it.copy(favorite = favorite) }
            metaChanged()
            events.send(LibraryEvent.Message(if (favorite) Notice.AddedToFavorites else Notice.RemovedFromFavorites, ids.size))
        }
    }

    /** Selection bar star: favorites all unless all already are, then removes them. */
    fun toggleFavoriteSelection() {
        val ids = controls.value.selected
        val allFavorite = ids.isNotEmpty() && ids.all { metadata.meta(it).favorite }
        setFavorite(ids, !allFavorite)
        endSelection()
    }

    fun moveToTrash(id: NotebookId) = moveToTrash(listOf(id))

    fun moveToTrash(ids: Collection<NotebookId>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val titles = notebooks.value.orEmpty().associate { it.id to it.title }
            val moved = ids.filter { repository.moveToTrash(it) }
            endSelection()
            if (moved.isNotEmpty()) {
                reload()
                events.send(LibraryEvent.Trashed(moved, titles[moved.first()].orEmpty()))
            }
        }
    }

    /** Undo for [moveToTrash], and the Trash screen's "Restore". */
    fun restore(id: NotebookId) = restore(listOf(id))

    fun restore(ids: Collection<NotebookId>) {
        viewModelScope.launch {
            ids.forEach { repository.restore(it) }
            reload()
        }
    }

    fun exportSoon() {
        events.trySend(LibraryEvent.Message(Notice.ExportSoon))
    }

    /** Runs a pending "Open with" PDF import, if any. */
    fun takePdfRequest() {
        pdfRequests.take()?.let(::importPdf)
    }

    /** Imports the PDF at [uri] as a new notebook (in the open folder, if any) and opens it. */
    fun importPdf(uri: String) {
        viewModelScope.launch {
            events.send(LibraryEvent.Message(Notice.ImportingPdf))
            val notebook = try {
                pdfImporter.import(uri)
            } catch (@Suppress("SwallowedException") e: PdfImportFailed) {
                events.send(LibraryEvent.Message(Notice.ImportPdfFailed))
                return@launch
            }
            val folderId = (controls.value.destination as? LibraryDestination.InFolder)?.folderId
            metadata.update(notebook.id) { it.copy(folderId = folderId) }
            reload()
            events.send(LibraryEvent.Open(notebook.id))
        }
    }

    // endregion

    // region Folders

    /** Creates a folder (under [parentId] if given). @return its id. */
    fun createFolder(name: String, colorIndex: Int, parentId: String? = null, moveIds: Collection<NotebookId> = emptyList()): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val folder = Folder(UUID.randomUUID().toString(), clean, colorIndex.mod(CoverPalette.All.size), parentId?.takeIf { p -> metadata.folders().any { it.id == p } })
        viewModelScope.launch {
            metadata.putFolder(folder)
            if (moveIds.isNotEmpty()) {
                metadata.update(moveIds) { it.copy(folderId = folder.id) }
                endSelection()
            }
            metaChanged()
            events.send(LibraryEvent.Message(Notice.FolderCreated, name = clean))
        }
        return folder.id
    }

    /** Renames and/or recolors a folder. Blank names are ignored. */
    fun editFolder(id: String, name: String, colorIndex: Int) {
        val folder = metadata.folders().firstOrNull { it.id == id } ?: return
        val clean = name.trim().ifEmpty { folder.name }
        viewModelScope.launch {
            metadata.putFolder(folder.copy(name = clean, colorIndex = colorIndex.mod(CoverPalette.All.size)))
            metaChanged()
        }
    }

    /** Deletes a folder and its subfolders; their notebooks move up to the parent (never deleted). */
    fun deleteFolder(id: String) {
        val folder = metadata.folders().firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            metadata.deleteFolder(id)
            val dest = controls.value.destination
            if (dest is LibraryDestination.InFolder && metadata.folders().none { it.id == dest.folderId }) {
                select(folder.parentId?.let { LibraryDestination.InFolder(it) } ?: LibraryDestination.Library)
            }
            metaChanged()
            events.send(LibraryEvent.Message(Notice.FolderDeleted, name = folder.name))
        }
    }

    // endregion

    private fun metaChanged() = metaVersion.update { it + 1 }

    private suspend fun reload() {
        val list = repository.list()
        thumbnails.value = repository.thumbnails(list.map { it.id })
        notebooks.value = list
        trash.value = repository.trash()
        val existing = notebooks.value.orEmpty().mapTo(HashSet()) { it.id }
        controls.update { c -> if (c.selected.all { it in existing }) c else c.copy(selected = c.selected.filterTo(HashSet()) { it in existing }) }
        metaChanged()
    }

    private companion object {
        /** Quicknotes get the mustard cover, matching the yellow Quicknote card. */
        const val QUICKNOTE_COVER = 3
    }
}
