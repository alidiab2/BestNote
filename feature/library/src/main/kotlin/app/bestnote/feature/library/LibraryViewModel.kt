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
import javax.inject.Inject

/** Seeds demo notebooks. A real implementation exists only in debug builds. */
fun interface LibrarySeeder {
    suspend fun seed()
}

/** Wall clock, injectable for tests. */
fun interface LibraryClock {
    fun now(): Long
}

sealed interface LibraryEvent {
    data class Open(val id: NotebookId) : LibraryEvent
    data class Trashed(val id: NotebookId, val title: String) : LibraryEvent
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val metadata: LibraryMetadata,
    private val seeder: LibrarySeeder,
    private val clock: LibraryClock,
) : ViewModel() {
    private val notebooks = MutableStateFlow<List<Notebook>?>(null)
    private val trash = MutableStateFlow<List<TrashedNotebook>>(emptyList())
    private val controls = MutableStateFlow(LibraryControls())
    private val events = Channel<LibraryEvent>(Channel.BUFFERED)

    val state: StateFlow<LibraryUiState> = combine(notebooks, trash, controls) { list, bin, c ->
        buildLibraryState(list, bin, c, metadata, CoverPalette.All.size, clock.now())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    /** One-shot UI events: navigation and the undo snackbar. */
    val eventFlow: Flow<LibraryEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            seeder.seed()
            repository.purgeExpired()
            reload()
        }
    }

    /** Re-reads the store, e.g. when returning from the editor. */
    fun refresh() {
        viewModelScope.launch { reload() }
    }

    fun select(destination: LibraryDestination) = controls.update { it.copy(destination = destination) }
    fun setLayout(layout: LibraryLayout) = controls.update { it.copy(layout = layout) }
    fun setFilter(filter: NotebookFilter) = controls.update { it.copy(filter = filter) }
    fun setQuery(query: String) = controls.update { it.copy(query = query) }

    fun open(id: NotebookId) {
        events.trySend(LibraryEvent.Open(id))
    }

    /** Creates a notebook called [title] and opens it. LIB-02 replaces this with the new-notebook sheet. */
    fun create(title: String) {
        viewModelScope.launch {
            val notebook = repository.create(title)
            reload()
            events.send(LibraryEvent.Open(notebook.id))
        }
    }

    fun rename(id: NotebookId, title: String) {
        viewModelScope.launch {
            repository.rename(id, title)
            reload()
        }
    }

    fun moveToTrash(id: NotebookId) {
        viewModelScope.launch {
            val title = notebooks.value?.firstOrNull { it.id == id }?.title.orEmpty()
            if (repository.moveToTrash(id)) {
                reload()
                events.send(LibraryEvent.Trashed(id, title))
            }
        }
    }

    /** Undo for [moveToTrash], and the Trash screen's "Restore". */
    fun restore(id: NotebookId) {
        viewModelScope.launch {
            repository.restore(id)
            reload()
        }
    }

    private suspend fun reload() {
        notebooks.value = repository.list()
        trash.value = repository.trash()
    }
}
