/**
 * :feature:library — the home screen: a grid of the user's notebooks (Room-backed) with a
 * "New notebook" action that creates one and opens it in the editor.
 */
package app.bestnote.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.bestnote.core.database.NotebookDao
import app.bestnote.core.database.toEntity
import app.bestnote.core.database.toModel
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.ui.EmptyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import javax.inject.Inject

@Serializable
data object LibraryRoute

fun NavGraphBuilder.libraryScreen(onOpenNotebook: (NotebookId) -> Unit, onOpenSettings: () -> Unit) {
    composable<LibraryRoute> { LibraryScreen(onOpenNotebook, onOpenSettings) }
}

@HiltViewModel
class LibraryViewModel @Inject constructor(private val dao: NotebookDao) : ViewModel() {
    /** null while loading. */
    val notebooks: StateFlow<List<Notebook>?> = dao.observeAll()
        .map { list -> list.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun createNotebook(onCreated: (NotebookId) -> Unit) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val count = notebooks.value?.size ?: 0
            val notebook = Notebook(NotebookId(UUID.randomUUID().toString()), "Notebook ${count + 1}", now, now)
            dao.upsert(notebook.toEntity())
            onCreated(notebook.id)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenNotebook: (NotebookId) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val notebooks by viewModel.notebooks.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BestNote") },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("New notebook") },
                icon = { Icon(Icons.Default.Add, null) },
                onClick = { viewModel.createNotebook(onOpenNotebook) },
            )
        },
    ) { padding ->
        val list = notebooks
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding))
            list.isEmpty() -> EmptyState(
                title = "No notebooks yet",
                message = "Tap New notebook to start writing.",
                modifier = Modifier.padding(padding),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(Spacing.L),
                horizontalArrangement = Arrangement.spacedBy(Spacing.L),
                verticalArrangement = Arrangement.spacedBy(Spacing.L),
            ) {
                items(list, key = { it.id.value }) { notebook ->
                    NotebookCard(
                        notebook,
                        onClick = { onOpenNotebook(notebook.id) },
                        modifier = Modifier.animateItem(placementSpec = Motion.Spring.gentle()),
                    )
                }
            }
        }
    }
}

@Composable
private fun NotebookCard(notebook: Notebook, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        // Cover placeholder until page thumbnails exist.
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.75f).padding(Spacing.S),
        ) {}
        Column(Modifier.padding(horizontal = Spacing.M, vertical = Spacing.S)) {
            Text(notebook.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(notebook.updatedAtMillis)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
