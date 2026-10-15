/**
 * :feature:library — the home screen in the approved "Atelier" design: a sidebar (library, recent,
 * favorites, quicknotes, Smart Board, folders, trash) and a main area with a greeting, search, a
 * "Continue writing" row and the notebooks as a bookshelf, a grid or a list.
 *
 * Data comes from [LibraryRepository], which only depends on the `NotebookStore` interface.
 */
package app.bestnote.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.model.NotebookId
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object LibraryRoute

fun NavGraphBuilder.libraryScreen(onOpenNotebook: (NotebookId) -> Unit, onOpenSettings: () -> Unit) {
    composable<LibraryRoute> { LibraryScreen(onOpenNotebook, onOpenSettings) }
}

@Composable
fun LibraryScreen(
    onOpenNotebook: (NotebookId) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is LibraryEvent.Open -> onOpenNotebook(event.id)
                is LibraryEvent.Trashed -> launch {
                    val result = snackbar.showSnackbar(
                        message = resources.getString(R.string.library_trashed, event.title),
                        actionLabel = resources.getString(R.string.library_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.restore(event.id)
                }
            }
        }
    }
    val newTitle = stringResource(R.string.library_new_title)
    val quickTitle = stringResource(R.string.library_quicknote_title)
    LibraryContent(
        state = state,
        now = System.currentTimeMillis(),
        snackbar = snackbar,
        callbacks = LibraryCallbacks(
            select = viewModel::select,
            query = viewModel::setQuery,
            layout = viewModel::setLayout,
            filter = viewModel::setFilter,
            newNotebook = { viewModel.create(newTitle) },
            quicknote = { viewModel.create(quickTitle) },
            open = viewModel::open,
            rename = viewModel::rename,
            trash = viewModel::moveToTrash,
            restore = viewModel::restore,
            settings = onOpenSettings,
        ),
    )
}

/** Everything the stateless [LibraryContent] can ask for. */
internal class LibraryCallbacks(
    val select: (LibraryDestination) -> Unit = {},
    val query: (String) -> Unit = {},
    val layout: (LibraryLayout) -> Unit = {},
    val filter: (NotebookFilter) -> Unit = {},
    val newNotebook: () -> Unit = {},
    val quicknote: () -> Unit = {},
    val open: (NotebookId) -> Unit = {},
    val rename: (NotebookId, String) -> Unit = { _, _ -> },
    val trash: (NotebookId) -> Unit = {},
    val restore: (NotebookId) -> Unit = {},
    val settings: () -> Unit = {},
)

/** Below this width the sidebar is hidden and the header controls tighten. */
private val SidebarBreakpoint = 720.dp
private val CompactBreakpoint = 1000.dp
private val MainPaddingH = 40.dp

@Composable
internal fun LibraryContent(
    state: LibraryUiState,
    now: Long,
    callbacks: LibraryCallbacks,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    val actions = remember(callbacks) {
        NotebookActions(
            open = { callbacks.open(it.id) },
            rename = { renaming = it.id.value },
            trash = { callbacks.trash(it.id) },
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val showSidebar = maxWidth >= SidebarBreakpoint
        val compact = maxWidth < CompactBreakpoint
        Row(Modifier.fillMaxSize()) {
            if (showSidebar) {
                LibrarySidebar(
                    state,
                    onSelect = callbacks.select,
                    onOpenSettings = callbacks.settings,
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(BestNoteTheme.colors.sidebar) // runs under the system bars
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
                )
            }
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        if (showSidebar) WindowInsetsSides.Vertical + WindowInsetsSides.End else WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal,
                    ),
                ),
            ) {
                val inner = maxWidth - MainPaddingH * 2
                val shelfColumns = ((inner - Spacing.L * 2 + ShelfGap) / (CoverWidth + ShelfGap)).toInt().coerceAtLeast(1)
                val gridColumns = ((inner + Spacing.L) / (GridMinCell + Spacing.L)).toInt().coerceAtLeast(1)
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = MainPaddingH, end = MainPaddingH, top = Spacing.XXL, bottom = Spacing.XXL * 2),
                ) {
                    item(key = "header", contentType = "header") {
                        LibraryHeader(state, compact, callbacks.query, callbacks.layout, callbacks.newNotebook)
                    }
                    val last = state.continueWith
                    if (state.showContinueWriting && last != null) {
                        item(key = "continue-title", contentType = "section") { SectionTitle(stringResource(R.string.library_continue_writing)) }
                        item(key = "continue", contentType = "continue") {
                            ContinueWritingRow(
                                last,
                                now,
                                onOpen = { callbacks.open(it.id) },
                                onQuicknote = callbacks.quicknote,
                                onSmartBoard = { callbacks.select(LibraryDestination.SmartBoard) },
                            )
                        }
                    }
                    notebooksSection(state, now, shelfColumns, gridColumns, actions, callbacks)
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(Spacing.L))
            }
        }
    }
    val target = renaming?.let { id -> state.items.firstOrNull { it.id.value == id } }
    if (target != null) {
        RenameDialog(
            initial = target.title,
            onDismiss = { renaming = null },
            onConfirm = { title ->
                callbacks.rename(target.id, title)
                renaming = null
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = Spacing.XXL, bottom = Spacing.L),
    )
}

private fun LazyListScope.notebooksSection(
    state: LibraryUiState,
    now: Long,
    shelfColumns: Int,
    gridColumns: Int,
    actions: NotebookActions,
    callbacks: LibraryCallbacks,
) {
    when (state.controls.destination) {
        LibraryDestination.Trash -> {
            item(key = "trash-gap") { Spacer(Modifier.height(Spacing.XXL)) }
            if (state.trash.isEmpty()) {
                item(key = "trash-empty") {
                    LibraryEmpty(stringResource(R.string.library_trash_empty), stringResource(R.string.library_trash_body))
                }
            } else {
                items(state.trash, key = { "t-" + it.id.value }, contentType = { "trash" }) { item ->
                    TrashRow(item, now, onRestore = { callbacks.restore(item.id) }, modifier = Modifier.animateItem(placementSpec = Motion.Spring.offsetGentle()))
                }
            }
            return
        }
        LibraryDestination.SmartBoard -> {
            item(key = "board-soon") {
                LibraryEmpty(stringResource(R.string.library_nav_smart_board), stringResource(R.string.library_smart_board_soon))
            }
            return
        }
        else -> Unit
    }
    item(key = "notebooks-header", contentType = "section") {
        NotebooksHeader(state.controls.filter, callbacks.filter, Modifier.padding(top = Spacing.XXL, bottom = Spacing.XL))
    }
    if (state.loading) return
    if (state.items.isEmpty()) {
        item(key = "empty") {
            val query = state.controls.query.trim()
            LibraryEmpty(
                stringResource(R.string.library_empty_title),
                if (query.isEmpty()) stringResource(R.string.library_empty_body) else stringResource(R.string.library_empty_search, query),
            )
        }
        return
    }
    when (state.controls.layout) {
        LibraryLayout.Shelf -> {
            val rows = state.items.chunked(shelfColumns)
            itemsIndexed(rows, key = { _, row -> "s-" + row.first().id.value }, contentType = { _, _ -> "shelf" }) { _, row ->
                ShelfRow(row, now, actions, Modifier.padding(bottom = Spacing.XXL).animateItem(placementSpec = Motion.Spring.offsetGentle()))
            }
        }
        LibraryLayout.Grid -> {
            val rows = state.items.chunked(gridColumns)
            itemsIndexed(rows, key = { _, row -> "g-" + row.first().id.value }, contentType = { _, _ -> "grid" }) { _, row ->
                GridRow(row, gridColumns, now, actions, Modifier.padding(bottom = Spacing.L).animateItem(placementSpec = Motion.Spring.offsetGentle()))
            }
        }
        LibraryLayout.List -> items(state.items, key = { "l-" + it.id.value }, contentType = { "list" }) { item ->
            ListRow(item, now, actions, Modifier.animateItem(placementSpec = Motion.Spring.offsetGentle()))
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_rename_title)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.library_rename_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) } },
    )
}
