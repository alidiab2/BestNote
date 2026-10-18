/**
 * :feature:library — the home screen in the approved "Atelier" design: a sidebar (library, recent,
 * favorites, quicknotes, Smart Board, folders, trash) and a main area with a greeting, search, a
 * "Continue writing" row and the notebooks as a bookshelf, a grid or a list.
 *
 * Every notebook has a visible "⋯" (and long-press) menu; notebooks drag onto sidebar folders;
 * "Select" enables bulk move / favorite / delete. Data comes from [LibraryRepository] (the
 * `NotebookStore` interface) and [LibraryMetadata] (folders, covers, favorites).
 */
package app.bestnote.feature.library

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.bestnote.core.designsystem.theme.WidthClass
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import app.bestnote.core.ui.keys.ShortcutAction
import app.bestnote.core.ui.keys.ShortcutHandler
import app.bestnote.core.ui.keys.ShortcutScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.hairline
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.core.model.NotebookId
import app.bestnote.engine.export.ExportDialog
import app.bestnote.engine.export.ExportFormat
import app.bestnote.engine.export.ExportRunner
import app.bestnote.engine.export.rememberExportRunner
import app.bestnote.core.security.LockDialogs
import app.bestnote.core.security.rememberLockController
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.Date

@Serializable
data object LibraryRoute

fun NavGraphBuilder.libraryScreen(
    onOpenNotebook: (NotebookId) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPage: (NotebookId, pageId: String, query: String) -> Unit = { id, _, _ -> onOpenNotebook(id) },
) {
    composable<LibraryRoute> { LibraryScreen(onOpenNotebook, onOpenSettings, onOpenPage) }
}

@Composable
fun LibraryScreen(
    onOpenNotebook: (NotebookId) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPage: (NotebookId, pageId: String, query: String) -> Unit = { id, _, _ -> onOpenNotebook(id) },
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val quicknoteTitle = quicknoteTitle()
    val quicknoteRequested by viewModel.quicknoteRequested.collectAsStateWithLifecycle()
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPdf(uri.toString())
    }
    val pdfRequested by viewModel.pdfRequested.collectAsStateWithLifecycle()
    LaunchedEffect(pdfRequested) { if (pdfRequested != null) viewModel.takePdfRequest() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    // LIB-11: locking, unlocking and their prompts.
    val locker = rememberLockController()
    val lockScope = rememberCoroutineScope()
    val whenUnlocked: (NotebookId, () -> Unit) -> Unit = { id, then ->
        lockScope.launch {
            if (!locker.manager.isLocked(id) || locker.unlock(viewModel.unlockGroup(id))) {
                viewModel.locksChanged()
                then()
            }
        }
    }
    LaunchedEffect(quicknoteRequested) { if (quicknoteRequested) viewModel.takeQuicknoteRequest(quicknoteTitle) }
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is LibraryEvent.Open -> onOpenNotebook(event.id)
                is LibraryEvent.OpenPage -> onOpenPage(event.id, event.pageId, event.query)
                is LibraryEvent.Lock -> launch {
                    if (locker.lock(event.ids, event.folder, keepUnlocked = event.open != null)) viewModel.locksChanged()
                    event.open?.let(onOpenNotebook)
                }
                is LibraryEvent.Trashed -> launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val message = if (event.ids.size == 1) {
                        resources.getString(R.string.library_trashed, event.title)
                    } else {
                        resources.getQuantityString(R.plurals.library_trashed_many, event.ids.size, event.ids.size)
                    }
                    val result = snackbar.showSnackbar(message, resources.getString(R.string.library_undo), duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) viewModel.restore(event.ids)
                }
                is LibraryEvent.Message -> launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(noticeText(resources, event), duration = SnackbarDuration.Short)
                }
            }
        }
    }
    LibraryContent(
        state = state,
        now = System.currentTimeMillis(),
        snackbar = snackbar,
        callbacks = LibraryCallbacks(
            select = viewModel::select,
            query = viewModel::setQuery,
            layout = viewModel::setLayout,
            filter = viewModel::setFilter,
            folderColor = viewModel::setFolderColor,
            clearFilters = viewModel::clearFilters,
            sort = viewModel::setSort,
            quicknote = { viewModel.quicknote(quicknoteTitle) },
            importPdf = { pickPdf.launch(arrayOf(PDF_MIME)) },
            create = viewModel::create,
            open = { id -> whenUnlocked(id) { viewModel.open(id) } },
            openPage = viewModel::openPage,
            downloadModels = viewModel::downloadModels,
            rename = { id, title -> whenUnlocked(id) { viewModel.rename(id, title) } },
            setCover = viewModel::setCover,
            setCoverImage = { ids, uri -> viewModel.setCoverImage(ids, uri) },
            resetCover = viewModel::resetCover,
            toggleTag = viewModel::toggleTag,
            tag = viewModel::setTag,
            duplicate = { id, title -> whenUnlocked(id) { viewModel.duplicate(id, title) } },
            move = viewModel::moveToFolder,
            favorite = viewModel::setFavorite,
            trash = { viewModel.moveToTrash(it) },
            restore = { viewModel.restore(it) },
            exportRunner = rememberExportRunner(),
            startSelection = viewModel::startSelection,
            toggleSelected = viewModel::toggleSelected,
            selectAll = viewModel::selectAll,
            endSelection = viewModel::endSelection,
            toggleFavoriteSelection = viewModel::toggleFavoriteSelection,
            createFolder = { name, color, parent, ids -> viewModel.createFolder(name, color, parent, ids) },
            editFolder = viewModel::editFolder,
            deleteFolder = viewModel::deleteFolder,
            settings = onOpenSettings,
            lock = { item, action ->
                lockScope.launch {
                    when (action) {
                        NotebookMenuAction.Lock -> locker.lock(listOf(item.id))
                        NotebookMenuAction.Unlock -> locker.unlock(viewModel.unlockGroup(item.id))
                        NotebookMenuAction.LockNow -> locker.manager.relock(viewModel.unlockGroup(item.id))
                        NotebookMenuAction.RemoveLock -> locker.removeLock(listOf(item.id))
                        else -> Unit
                    }
                    viewModel.locksChanged()
                }
            },
            lockFolder = { folder, lock ->
                lockScope.launch {
                    val ids = viewModel.notebooksInFolder(folder.id)
                    val sealed = locker.manager.sealed.value
                    val done = if (lock) {
                        ids.none { it !in sealed } || locker.lock(ids.filter { it !in sealed }, folder = folder.name)
                    } else {
                        ids.none { it in sealed } || locker.removeLock(ids.filter { it in sealed })
                    }
                    if (done) viewModel.setFolderLocked(folder.id, lock)
                    viewModel.locksChanged()
                }
            },
            prepareExport = { item, then ->
                lockScope.launch {
                    if (!item.sealed) {
                        then()
                    } else if ((!locker.manager.isLocked(item.id) || locker.unlock(viewModel.unlockGroup(item.id))) && locker.confirmPlainExport()) {
                        viewModel.locksChanged()
                        then()
                    }
                }
            },
        ),
    )
    LockDialogs(locker)
}

private const val PDF_MIME = "application/pdf"

/** "Quicknote · 15 Oct", in the user's locale. */
@Composable
private fun quicknoteTitle(): String {
    val locale = LocalLocale.current.platformLocale
    val date = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "MMMd"), Date()).toString()
    return stringResource(R.string.library_quicknote_dated, date)
}

private fun noticeText(resources: android.content.res.Resources, event: LibraryEvent.Message): String = when (event.notice) {
    Notice.ImportingPdf -> resources.getString(R.string.library_importing_pdf)
    Notice.ImportPdfFailed -> resources.getString(R.string.library_import_pdf_failed)
    Notice.Duplicated -> resources.getString(R.string.library_duplicated, event.name)
    Notice.Moved -> if (event.name.isEmpty()) {
        resources.getQuantityString(R.plurals.library_moved_library, event.count, event.count)
    } else {
        resources.getQuantityString(R.plurals.library_moved, event.count, event.count, event.name)
    }
    Notice.FolderCreated -> resources.getString(R.string.library_folder_created, event.name)
    Notice.FolderDeleted -> resources.getString(R.string.library_folder_deleted, event.name)
    Notice.AddedToFavorites -> resources.getQuantityString(R.plurals.library_favorited, event.count, event.count)
    Notice.RemovedFromFavorites -> resources.getQuantityString(R.plurals.library_unfavorited, event.count, event.count)
    Notice.CoverImageFailed -> resources.getString(R.string.library_cover_image_failed)
}

/** Everything the stateless [LibraryContent] can ask for. */
internal data class LibraryCallbacks(
    val select: (LibraryDestination) -> Unit = {},
    val query: (String) -> Unit = {},
    val layout: (LibraryLayout) -> Unit = {},
    val filter: (NotebookFilter) -> Unit = {},
    val folderColor: (Int?) -> Unit = {},
    val clearFilters: () -> Unit = {},
    val sort: (SortOrder) -> Unit = {},
    /** Opens the new-notebook sheet (set by [LibraryContent]). */
    val newNotebook: () -> Unit = {},
    /** Opens the new-folder dialog (set by [LibraryContent]). */
    val newFolder: () -> Unit = {},
    val quicknote: () -> Unit = {},
    val importPdf: () -> Unit = {},
    val create: (NewNotebookSpec) -> Unit = {},
    val open: (NotebookId) -> Unit = {},
    val openPage: (NotebookId, pageId: String, query: String) -> Unit = { _, _, _ -> },
    val downloadModels: () -> Unit = {},
    val rename: (NotebookId, String) -> Unit = { _, _ -> },
    val setCover: (Collection<NotebookId>, CoverChoice) -> Unit = { _, _ -> },
    /** LIB-10: cover from the photo at this URI. */
    val setCoverImage: (Collection<NotebookId>, String) -> Unit = { _, _ -> },
    val resetCover: (Collection<NotebookId>) -> Unit = {},
    val toggleTag: (Collection<NotebookId>, Int) -> Unit = { _, _ -> },
    /** LIB-10: filter by colour tag. */
    val tag: (Int?) -> Unit = {},
    val duplicate: (NotebookId, String) -> Unit = { _, _ -> },
    val move: (Collection<NotebookId>, String?) -> Unit = { _, _ -> },
    val favorite: (Collection<NotebookId>, Boolean) -> Unit = { _, _ -> },
    val trash: (Collection<NotebookId>) -> Unit = {},
    val restore: (NotebookId) -> Unit = {},
    /** Runs exports for the Export / Share menu items; null in previews and tests. */
    val exportRunner: ExportRunner? = null,
    val startSelection: (NotebookId?) -> Unit = {},
    val toggleSelected: (NotebookId) -> Unit = {},
    val selectAll: () -> Unit = {},
    val endSelection: () -> Unit = {},
    val toggleFavoriteSelection: () -> Unit = {},
    val createFolder: (name: String, color: Int, parentId: String?, moveIds: Collection<NotebookId>) -> Unit = { _, _, _, _ -> },
    val editFolder: (id: String, name: String, color: Int) -> Unit = { _, _, _ -> },
    val deleteFolder: (String) -> Unit = {},
    val settings: () -> Unit = {},
    /** LIB-11: a lock item of a notebook's menu (Lock, Unlock, LockNow, RemoveLock). */
    val lock: (NotebookItem, NotebookMenuAction) -> Unit = { _, _ -> },
    /** LIB-11: lock (true) or remove the lock of (false) a folder and the notebooks in it. */
    val lockFolder: (Folder, Boolean) -> Unit = { _, _ -> },
    /** Runs the export once a locked notebook is unlocked and the plain copy is confirmed. */
    val prepareExport: (NotebookItem, () -> Unit) -> Unit = { _, then -> then() },
)

/** The dialog or sheet currently open over the library. */
private sealed interface LibraryDialog {
    data object NewNotebook : LibraryDialog
    data class Rename(val id: NotebookId) : LibraryDialog
    data class Cover(val ids: List<NotebookId>) : LibraryDialog
    data class Move(val ids: List<NotebookId>) : LibraryDialog
    data class NewFolder(val parentId: String?, val moveIds: List<NotebookId> = emptyList()) : LibraryDialog
    data class EditFolder(val id: String) : LibraryDialog
    data class DeleteFolder(val id: String) : LibraryDialog

    /** Export dialog for one notebook; [share] skips the format choice and shares a PDF straight away. */
    data class Export(val id: NotebookId, val share: Boolean) : LibraryDialog
}

/**
 * DS-05. Compact windows (< 600 dp: phone, Fold outer screen, narrow free-form window) swap the fixed
 * sidebar for a drawer behind a menu button; below [CompactBreakpoint] the header controls tighten.
 */
private val CompactBreakpoint = 1320.dp
private val MainPaddingH = 40.dp
private val PhonePaddingH = 16.dp

@Composable
internal fun LibraryContent(
    state: LibraryUiState,
    now: Long,
    callbacks: LibraryCallbacks,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    val drag = remember { LibraryDragState() }
    val copyTitle = stringResource(R.string.library_copy_title)
    val ui = remember(callbacks) {
        callbacks.copy(
            newNotebook = { dialog = LibraryDialog.NewNotebook },
            newFolder = { dialog = LibraryDialog.NewFolder(parentId = null) },
        )
    }
    val controls = state.controls
    // SPEN-10: Ctrl+N a new notebook, Ctrl+F the search field.
    val searchFocus = remember { FocusRequester() }
    ShortcutHandler(ShortcutScope.Library) { action ->
        if (dialog != null) return@ShortcutHandler false
        when (action) {
            ShortcutAction.NewNotebook -> ui.newNotebook()
            ShortcutAction.SearchLibrary -> runCatching { searchFocus.requestFocus() }
            else -> return@ShortcutHandler false
        }
        true
    }
    val actions = NotebookActions(
        open = { ui.open(it.id) },
        menu = { item, action ->
            when (action) {
                NotebookMenuAction.Open -> ui.open(item.id)
                NotebookMenuAction.Rename -> dialog = LibraryDialog.Rename(item.id)
                NotebookMenuAction.ChangeCover -> dialog = LibraryDialog.Cover(listOf(item.id))
                NotebookMenuAction.Duplicate -> ui.duplicate(item.id, copyTitle.format(item.title))
                NotebookMenuAction.Move -> dialog = LibraryDialog.Move(listOf(item.id))
                NotebookMenuAction.Favorite -> ui.favorite(listOf(item.id), !item.favorite)
                NotebookMenuAction.Export -> ui.prepareExport(item) { dialog = LibraryDialog.Export(item.id, share = false) }
                NotebookMenuAction.Share -> ui.prepareExport(item) { dialog = LibraryDialog.Export(item.id, share = true) }
                NotebookMenuAction.Select -> ui.startSelection(item.id)
                NotebookMenuAction.Trash -> ui.trash(listOf(item.id))
                NotebookMenuAction.Lock, NotebookMenuAction.Unlock, NotebookMenuAction.LockNow, NotebookMenuAction.RemoveLock -> ui.lock(item, action)
            }
        },
        toggleSelect = { ui.toggleSelected(it.id) },
        drop = { payload, dest -> onDrop(payload, dest, ui) },
        selecting = controls.selecting,
        selected = controls.selected,
    )
    CompositionLocalProvider(LocalLibraryDrag provides drag) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).libraryDragRoot(drag)) {
            val phone = WidthClass.of(maxWidth) == WidthClass.Compact
            val showSidebar = !phone
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            val sidebar: @Composable (Boolean) -> Unit = { inDrawer ->
                LibrarySidebar(
                    state,
                    onSelect = {
                        ui.select(it)
                        if (inDrawer) scope.launch { drawerState.close() }
                    },
                    onOpenSettings = {
                        if (inDrawer) scope.launch { drawerState.close() }
                        ui.settings()
                    },
                    onNewFolder = ui.newFolder,
                    onFolderMenu = { folder, action ->
                        when (action) {
                            FolderMenuAction.Rename, FolderMenuAction.Recolor -> dialog = LibraryDialog.EditFolder(folder.id)
                            FolderMenuAction.NewSubfolder -> dialog = LibraryDialog.NewFolder(parentId = folder.id)
                            FolderMenuAction.Delete -> dialog = LibraryDialog.DeleteFolder(folder.id)
                            FolderMenuAction.Lock -> ui.lockFolder(folder, true)
                            FolderMenuAction.RemoveLock -> ui.lockFolder(folder, false)
                        }
                    },
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(BestNoteTheme.colors.sidebar) // runs under the system bars
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
                )
            }
            val main: @Composable () -> Unit = {
            Row(Modifier.fillMaxSize()) {
                if (showSidebar) sidebar(false)
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            if (showSidebar) WindowInsetsSides.Vertical + WindowInsetsSides.End else WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal,
                        ),
                    ),
                ) {
                    val compact = maxWidth < CompactBreakpoint
                    val padH = if (phone) PhonePaddingH else MainPaddingH
                    val inner = maxWidth - padH * 2
                    val shelfColumns = ((inner - Spacing.L * 2 + ShelfGap) / (CoverWidth + ShelfGap)).toInt().coerceAtLeast(1)
                    val gridColumns = ((inner + Spacing.L) / (GridMinCell + Spacing.L)).toInt().coerceAtLeast(1)
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = padH, end = padH, top = if (phone) Spacing.S else Spacing.XXL, bottom = Spacing.XXL * 4),
                    ) {
                        if (phone) {
                            item(key = "phone-bar", contentType = "phone-bar") {
                                PhoneBar(onMenu = { scope.launch { drawerState.open() } }, onSettings = ui.settings)
                            }
                        }
                        item(key = "header", contentType = "header") { LibraryHeader(state, compact, ui, searchFocus = searchFocus, phone = phone) }
                        val last = state.continueWith
                        if (state.showContinueWriting && last != null) {
                            item(key = "continue-title", contentType = "section") { SectionTitle(stringResource(R.string.library_continue_writing)) }
                            item(key = "continue", contentType = "continue") {
                                ContinueWritingRow(
                                    last,
                                    now,
                                    onOpen = { ui.open(it.id) },
                                    onQuicknote = ui.quicknote,
                                    onSmartBoard = { ui.select(LibraryDestination.SmartBoard) },
                                )
                            }
                        }
                        notebooksSection(state, now, shelfColumns, gridColumns, actions, ui) { parentId -> dialog = LibraryDialog.NewFolder(parentId) }
                    }
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(Spacing.L),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SnackbarHost(snackbar) { data ->
                                Snackbar(data, shape = BestNoteShapes.Control, actionColor = BestNoteTheme.colors.aiAccent)
                            }
                            AnimatedVisibility(
                                controls.selecting,
                                enter = Motion.Enter.toolbar(BestNoteTheme.reducedMotion),
                                exit = Motion.Exit.toolbar(BestNoteTheme.reducedMotion),
                            ) {
                                SelectionBar(
                                    count = controls.selected.size,
                                    onClose = ui.endSelection,
                                    onSelectAll = ui.selectAll,
                                    onFavorite = ui.toggleFavoriteSelection,
                                    onMove = { dialog = LibraryDialog.Move(controls.selected.toList()) },
                                    onCover = { dialog = LibraryDialog.Cover(controls.selected.toList()) },
                                    onTrash = { ui.trash(controls.selected.toList()) },
                                )
                            }
                        }
                    }
                }
            }
            }
            if (phone) {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerContainerColor = BestNoteTheme.colors.sidebar,
                            drawerShape = RectangleShape,
                            windowInsets = WindowInsets(0),
                        ) { sidebar(true) }
                    },
                    content = main,
                )
            } else {
                main()
            }
            DragGhost(drag)
        }
    }
    LibraryDialogs(dialog, state, ui) { dialog = it }
}

private fun onDrop(payload: DragPayload, destination: LibraryDestination, ui: LibraryCallbacks) {
    when (destination) {
        LibraryDestination.Library -> ui.move(payload.ids, null)
        LibraryDestination.Favorites -> ui.favorite(payload.ids, true)
        LibraryDestination.Trash -> ui.trash(payload.ids)
        is LibraryDestination.InFolder -> ui.move(payload.ids, destination.folderId)
        else -> Unit
    }
}

@Composable
private fun LibraryDialogs(dialog: LibraryDialog?, state: LibraryUiState, ui: LibraryCallbacks, set: (LibraryDialog?) -> Unit) {
    val dismiss = { set(null) }
    // LIB-10: the system photo picker needs no storage permission. The launcher lives here, not in the cover
    // dialog, which closes while the picker is up.
    var photoIds by remember { mutableStateOf<List<NotebookId>>(emptyList()) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) ui.setCoverImage(photoIds, uri.toString())
    }
    val notebook = { id: NotebookId -> state.items.firstOrNull { it.id == id } ?: state.continueWith?.takeIf { it.id == id } }
    val folder = { id: String -> state.folders.firstOrNull { it.folder.id == id } }
    when (dialog) {
        null -> Unit
        LibraryDialog.NewNotebook -> {
            val folderName = state.breadcrumb.lastOrNull()?.name.takeIf { state.controls.destination is LibraryDestination.InFolder }
            NewNotebookSheet(folderName, onDismiss = dismiss) { spec ->
                set(null)
                ui.create(spec)
            }
        }
        is LibraryDialog.Export -> {
            val item = notebook(dialog.id)
            val runner = ui.exportRunner
            if (item != null && runner != null) {
                ExportDialog(
                    runner = runner,
                    notebookId = item.id,
                    pageCount = item.pageCount,
                    fileName = item.title,
                    onDismiss = dismiss,
                    startWith = if (dialog.share) ExportFormat.Pdf else null,
                )
            }
        }
        is LibraryDialog.Rename -> {
            val item = notebook(dialog.id)
            if (item != null) {
                RenameDialog(item.title, dismiss) { title ->
                    ui.rename(item.id, title)
                    set(null)
                }
            }
        }
        is LibraryDialog.Cover -> {
            val first = notebook(dialog.ids.first())
            val items = dialog.ids.mapNotNull(notebook)
            CoverDialog(
                title = first?.title.orEmpty(),
                current = CoverChoice(first?.coverIndex ?: 0, first?.coverStyle ?: CoverStyle.Plain),
                imagePath = first?.coverImagePath,
                tags = items.map { it.tags.toSet() }.reduceOrNull { a, b -> a intersect b }.orEmpty(),
                onDismiss = dismiss,
                onPick = { cover ->
                    ui.setCover(dialog.ids, cover)
                    set(null)
                },
                onPickPhoto = {
                    photoIds = dialog.ids
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    set(null)
                },
                onReset = {
                    ui.resetCover(dialog.ids)
                    set(null)
                },
                onToggleTag = { ui.toggleTag(dialog.ids, it) },
            )
        }
        is LibraryDialog.Move -> {
            val current = dialog.ids.map { notebook(it)?.folder?.id }.distinct().singleOrNull()
            MoveDialog(
                count = dialog.ids.size,
                folders = state.folders,
                currentFolderId = current,
                onDismiss = dismiss,
                onMove = { folderId ->
                    ui.move(dialog.ids, folderId)
                    set(null)
                },
                onNewFolder = { set(LibraryDialog.NewFolder(parentId = null, moveIds = dialog.ids)) },
            )
        }
        is LibraryDialog.NewFolder -> {
            val parent = dialog.parentId?.let(folder)
            FolderDialog(
                title = stringResource(if (parent == null) R.string.library_folder_new else R.string.library_folder_new_sub),
                initialName = "",
                initialColor = (state.folders.size + 1) % COVER_COLORS,
                confirmLabel = stringResource(R.string.library_create),
                parentName = parent?.folder?.name,
                onDismiss = dismiss,
            ) { name, color ->
                ui.createFolder(name, color, dialog.parentId, dialog.moveIds)
                set(null)
            }
        }
        is LibraryDialog.EditFolder -> {
            val item = folder(dialog.id) ?: return
            FolderDialog(
                title = stringResource(R.string.library_folder_edit),
                initialName = item.folder.name,
                initialColor = item.folder.colorIndex,
                confirmLabel = stringResource(R.string.library_save),
                parentName = item.folder.parentId?.let(folder)?.folder?.name,
                onDismiss = dismiss,
            ) { name, color ->
                ui.editFolder(item.folder.id, name, color)
                set(null)
            }
        }
        is LibraryDialog.DeleteFolder -> {
            val item = folder(dialog.id) ?: return
            val ids = descendantsOf(item.folder.id, state.folders.map { it.folder }) + item.folder.id
            val count = state.folders.filter { it.folder.id in ids }.sumOf { it.count }
            DeleteFolderDialog(item.folder.name, count, dismiss) {
                ui.deleteFolder(item.folder.id)
                set(null)
            }
        }
    }
}

private const val COVER_COLORS = 6

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
    onNewSubfolder: (String) -> Unit,
) {
    when (state.controls.destination) {
        LibraryDestination.Trash -> {
            item(key = "trash-gap") { Spacer(Modifier.height(Spacing.XXL)) }
            if (state.trash.isEmpty()) {
                item(key = "trash-empty") {
                    EmptyState(EmptyArt.Bin, stringResource(R.string.library_trash_empty), stringResource(R.string.library_trash_body))
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
    val folderId = (state.controls.destination as? LibraryDestination.InFolder)?.folderId
    if (folderId != null && !state.searching) {
        item(key = "subfolders", contentType = "subfolders") {
            SubfolderRow(state.subfolders, onOpen = { callbacks.select(LibraryDestination.InFolder(it)) }, onNew = { onNewSubfolder(folderId) })
        }
    }
    item(key = "notebooks-header", contentType = "section") {
        NotebooksHeader(state, callbacks, Modifier.padding(top = Spacing.XXL, bottom = Spacing.XL))
    }
    if (state.loading) {
        item(key = "loading", contentType = "loading") { LibraryLoadingShelf() }
        return
    }
    handwritingModelsPrompt(state, callbacks)
    if (state.items.isEmpty()) {
        // While the page search is still running there is nothing to call "no results" yet.
        if ((!state.searching || state.pageResults.isEmpty()) && (!state.searching || state.searchSettled)) {
            item(key = "empty") {
                NotebooksEmpty(state.emptyKind, state.controls.query, state.breadcrumb.lastOrNull()?.name, callbacks)
            }
        }
    } else {
        // Search results always use the list, which shows where each notebook lives.
        val layout = if (state.searching) LibraryLayout.List else state.controls.layout
        when (layout) {
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
    // Handwriting, typed text and PDF text on pages (REC-03).
    if (state.searching) pageSearchSection(state, gridColumns, callbacks)
}

/** Subfolders of the open folder as pills, plus "New subfolder". */
@Composable
private fun SubfolderRow(subfolders: List<FolderItem>, onOpen: (String) -> Unit, onNew: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.XL).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        subfolders.forEach { f ->
            Row(
                Modifier
                    .height(52.dp)
                    .softShadow(Elevation.Low, BestNoteShapes.Control)
                    .clip(BestNoteShapes.Control)
                    .background(MaterialTheme.colorScheme.surface)
                    .dropTarget(LibraryDestination.InFolder(f.folder.id))
                    .clickable(role = Role.Button, onClick = { onOpen(f.folder.id) })
                    .padding(horizontal = Spacing.L),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.M),
            ) {
                FolderGlyph(f.folder.colorIndex, Modifier.size(24.dp, 20.dp))
                Text(f.folder.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(f.count.toString(), style = MaterialTheme.typography.labelMedium, color = BestNoteTheme.colors.muted)
            }
        }
        Row(
            Modifier
                .height(52.dp)
                .clip(BestNoteShapes.Control)
                .hairline(BestNoteShapes.Control)
                .clickable(role = Role.Button, onClick = onNew)
                .padding(horizontal = Spacing.L),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            Icon(Icons.Outlined.CreateNewFolder, null, tint = BestNoteTheme.colors.muted, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.library_folder_new_sub), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Floating bar while selecting: count, select all, favorite, move, cover, delete, done. */
@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onFavorite: () -> Unit,
    onMove: () -> Unit,
    onCover: () -> Unit,
    onTrash: () -> Unit,
) {
    val enabled = count > 0
    Row(
        Modifier
            .padding(top = Spacing.S)
            .softShadow(Elevation.High, BestNoteShapes.Pill)
            .clip(BestNoteShapes.Pill)
            .background(MaterialTheme.colorScheme.surface)
            .hairline(BestNoteShapes.Pill)
            .padding(horizontal = Spacing.S, vertical = Spacing.XS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Icons.Outlined.Close, stringResource(R.string.library_done), onClose, showLabel = false)
        Text(
            pluralStringResource(R.plurals.library_selected, count, count),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Spacing.S).width(110.dp),
            maxLines = 1,
        )
        BarButton(Icons.Outlined.DoneAll, stringResource(R.string.library_select_all), onSelectAll)
        Box(Modifier.padding(horizontal = Spacing.XS).width(Elevation.Hairline).height(28.dp).background(MaterialTheme.colorScheme.outline))
        BarButton(Icons.Outlined.StarOutline, stringResource(R.string.library_filter_favorites), onFavorite, enabled = enabled)
        BarButton(Icons.Outlined.Folder, stringResource(R.string.library_move), onMove, enabled = enabled)
        BarButton(Icons.Outlined.Palette, stringResource(R.string.library_cover), onCover, enabled = enabled)
        BarButton(Icons.Outlined.DeleteOutline, stringResource(R.string.library_delete), onTrash, enabled = enabled, destructive = true)
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, destructive: Boolean = false, showLabel: Boolean = true) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier
            .height(48.dp)
            .clip(BestNoteShapes.Pill)
            .background(Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Icon(icon, if (showLabel) null else label, tint = tint, modifier = Modifier.size(20.dp))
        if (showLabel) Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
    }
}

/** Compact windows only: the menu button that opens the sidebar drawer, the wordmark and Settings. */
@Composable
private fun PhoneBar(onMenu: () -> Unit, onSettings: () -> Unit) {
    val menu = stringResource(R.string.library_open_menu)
    val settings = stringResource(R.string.library_nav_settings)
    Row(Modifier.fillMaxWidth().padding(bottom = Spacing.S), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenu, modifier = Modifier.size(48.dp).semantics { contentDescription = menu }) {
            Icon(Icons.Outlined.Menu, null, tint = MaterialTheme.colorScheme.onSurface)
        }
        Wordmark(Modifier.weight(1f).padding(start = Spacing.XS))
        IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).semantics { contentDescription = settings }) {
            Icon(Icons.Outlined.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
private const val DISABLED_ALPHA = 0.38f
