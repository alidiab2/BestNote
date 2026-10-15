/**
 * :feature:editor — the notebook page editor, "Atelier" direction (DS-03, DS-04, CANVAS-03):
 * the page on a warm desk, floating pills on top, the signature pen tray (realistic pens, colors,
 * live stroke width) with a lighter tools capsule, a pen picker popover, the S Pen quick palette,
 * and undo/redo over the page's bounded command stack. Ink is drawn by [PageCanvasView].
 */
package app.bestnote.feature.editor

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.bestnote.core.database.NotebookDao
import app.bestnote.core.database.NotebookRepository
import app.bestnote.core.database.toModel
import app.bestnote.core.datastore.HoverButtonAction
import app.bestnote.core.datastore.ToolbarDock
import app.bestnote.core.datastore.UserPreferencesRepository
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.reducedMotion
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.Tool
import app.bestnote.engine.canvas.PageCanvasView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data class EditorRoute(val notebookId: String)

fun NavController.navigateToEditor(id: NotebookId) = navigate(EditorRoute(id.value))

fun NavGraphBuilder.editorScreen(onBack: () -> Unit) {
    composable<EditorRoute> { EditorScreen(onBack) }
}

data class EditorUiState(
    val title: String = "",
    val template: PaperTemplate = PaperTemplate.Ruled,
    val pageCount: Int = 1,
    val stylusOnly: Boolean = false,
    val dock: ToolbarDock = ToolbarDock.Top,
    val leftHanded: Boolean = false,
    val quickPalette: Boolean = true,
    val doubleClickUndo: Boolean = false,
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    dao: NotebookDao,
    private val preferences: UserPreferencesRepository,
    repository: NotebookRepository,
) : ViewModel() {
    val notebookId = NotebookId(savedStateHandle.toRoute<EditorRoute>().notebookId)

    /** Autosave + thumbnails for the open page (DOC-03, DOC-10). */
    internal val session = PageSession(notebookId, repository, viewModelScope)

    override fun onCleared() = session.close()

    private val toolboxState = MutableStateFlow(Toolbox())

    /** Pens, colors and sizes. Lives with the document, so tool switches never reset anything. */
    val toolbox: StateFlow<Toolbox> = toolboxState.asStateFlow()

    val state: StateFlow<EditorUiState> = combine(
        dao.observe(notebookId.value),
        preferences.preferences,
    ) { entity, prefs ->
        val notebook = entity?.toModel()
        EditorUiState(
            title = notebook?.title.orEmpty(),
            template = notebook?.template ?: PaperTemplate.Ruled,
            pageCount = notebook?.pageCount ?: 1,
            stylusOnly = prefs.stylusOnly,
            dock = prefs.toolbarDock,
            leftHanded = prefs.leftHanded,
            quickPalette = prefs.hoverButtonAction == HoverButtonAction.QuickPalette,
            doubleClickUndo = prefs.hoverDoubleClickUndo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState())

    fun selectPen(kind: PenKind) = toolboxState.update { it.select(kind) }
    fun updateToolbox(toolbox: Toolbox) = toolboxState.update { toolbox }
    fun setDock(dock: ToolbarDock) = viewModelScope.launch { preferences.setToolbarDock(dock) }
    fun setLeftHanded(enabled: Boolean) = viewModelScope.launch { preferences.setLeftHanded(enabled) }
}

/** Left-handed mode moves side docks to the other physical side, away from the writing hand. */
internal fun traySide(dock: ToolbarDock, leftHanded: Boolean): TraySide = when (dock) {
    ToolbarDock.Top -> TraySide.Top
    ToolbarDock.Left -> if (leftHanded) TraySide.Right else TraySide.Left
    ToolbarDock.Right -> if (leftHanded) TraySide.Left else TraySide.Right
}

private val PageShape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
private val ChromeEdge = 20.dp
private val TopRowHeight = 72.dp
private val TrayTop = 76.dp
private val PageMaxWidth = 860.dp
private val SideTrayReach = 168.dp
private const val DeskMidStop = 0.6f
private const val DeskMidMix = 0.45f
private const val DeskCenterY = 0.45f
private const val DeskRadius = 0.62f

@Composable
fun EditorScreen(onBack: () -> Unit, viewModel: EditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toolbox by viewModel.toolbox.collectAsStateWithLifecycle()
    var canvas by remember { mutableStateOf<PageCanvasView?>(null) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var autoHidden by remember { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<PickerAnchor?>(null) }
    var temporary by remember { mutableStateOf<PenKind?>(null) }
    var quickAt by remember { mutableStateOf<Offset?>(null) }
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val reduce = reducedMotion()
    val side = traySide(state.dock, state.leftHanded)
    val loaded by viewModel.session.ready.collectAsStateWithLifecycle()
    LaunchedEffect(loaded, canvas) { canvas?.let(viewModel.session::attach) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.session.onStop() }

    // DS-03: the tray steps aside while the pen is down and returns shortly after it lifts.
    LaunchedEffect(writing) {
        if (writing) {
            autoHidden = true
            picker = null
        } else {
            delay(Motion.Duration.ToolbarRestoreDelay.toLong())
            autoHidden = false
        }
    }
    val trayShown = !collapsed && !autoHidden
    val chromeAlpha by animateFloatAsState(if (writing) Motion.Toolbar.WritingAlpha else 1f, Motion.Toolbar.fadeSpec(), label = "chromeAlpha")

    val comingSoonFormat = stringResource(R.string.editor_coming_soon)
    val clearedText = stringResource(R.string.editor_page_cleared)
    val undoText = stringResource(R.string.editor_undo)
    val lassoText = stringResource(R.string.tool_lasso)
    fun comingSoon(what: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(comingSoonFormat.format(what))
        }
    }
    fun clearPage() {
        picker = null
        canvas?.clear()
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(clearedText, actionLabel = undoText) == SnackbarResult.ActionPerformed) canvas?.undo()
        }
    }
    fun onPen(kind: PenKind) {
        if (kind == toolbox.active) {
            picker = if (picker == PickerAnchor.Pen) null else PickerAnchor.Pen
        } else {
            picker = null
            viewModel.selectPen(kind)
        }
    }
    val actions = ChromeActions(
        onBack = onBack,
        onUndo = { canvas?.undo() },
        onRedo = { canvas?.redo() },
        onComingSoon = ::comingSoon,
        onDock = { picker = null; viewModel.setDock(it) },
        onLeftHanded = { viewModel.setLeftHanded(it) },
        onClearPage = ::clearPage,
    )
    val pickerContent: @Composable (PickerAnchor) -> Unit = { anchor ->
        AnchoredPopover(side, onDismissed = { if (picker == anchor) picker = null }) {
            if (toolbox.active == PenKind.Eraser) {
                EraserPickerContent(onClearPage = ::clearPage, onComingSoon = ::comingSoon)
            } else {
                PenPickerContent(toolbox.active, toolbox, viewModel::updateToolbox, startWithCustom = anchor == PickerAnchor.Colors)
            }
        }
    }

    val desk = BestNoteTheme.colors
    BoxWithConstraints(
        Modifier.fillMaxSize().drawBehind {
            // The desk: a soft warm radial light behind the page (#F4F1EB center to #E1DCD2 edge).
            drawRect(
                Brush.radialGradient(
                    0f to desk.deskCenter,
                    DeskMidStop to lerp(desk.deskCenter, desk.deskEdge, DeskMidMix),
                    1f to desk.deskEdge,
                    center = Offset(size.width / 2, size.height * DeskCenterY),
                    radius = size.maxDimension * DeskRadius,
                ),
            )
        },
    ) {
        val wide = maxWidth >= 1000.dp
        val pageTop: Dp = if (side == TraySide.Top) (if (wide) 160.dp else 222.dp) else TopRowHeight + 8.dp
        val reach = if (side == TraySide.Top) 0.dp else SideTrayReach
        val pageWidth = max(min(PageMaxWidth, maxWidth - reach * 2 - 48.dp), 240.dp)

        // ---- the page: paper on the desk. It never mirrors (forced LTR) ----
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = pageTop)
                    .align(Alignment.TopCenter)
                    .width(pageWidth)
                    .fillMaxHeight()
                    .softShadow(Elevation.High, PageShape)
                    .clip(PageShape)
                    .onGloballyPositioned { pageOrigin = it.positionInRoot() },
            ) {
                AndroidView(
                    factory = { context ->
                        PageCanvasView(context).also { view ->
                            view.layoutDirection = View.LAYOUT_DIRECTION_LTR
                            view.historyListener = PageCanvasView.HistoryListener { undo, redo ->
                                canUndo = undo
                                canRedo = redo
                                viewModel.session.onChanged(view)
                            }
                            view.writingListener = PageCanvasView.WritingListener { writing = it }
                            view.temporaryToolListener = { tool -> temporary = if (tool == Tool.Eraser) PenKind.Eraser else null }
                            view.quickPaletteListener = PageCanvasView.QuickPaletteListener { x, y ->
                                picker = null
                                quickAt = Offset(x, y)
                            }
                            canvas = view
                        }
                    },
                    update = { view ->
                        val pen = toolbox.active
                        view.tool = pen.tool
                        view.penStyle = pen.style
                        view.colorArgb = toolbox.current.brushColorArgb
                        view.brushSize = toolbox.current.size
                        view.stylusOnly = state.stylusOnly
                        view.template = state.template
                        view.quickPaletteEnabled = state.quickPalette
                        view.hoverDoubleClickUndo = state.doubleClickUndo
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ---- floating chrome ----
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = ChromeEdge, end = ChromeEdge, top = 14.dp)
                    .graphicsLayer { alpha = chromeAlpha },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TitleCluster(state.title, page = 1, pageCount = state.pageCount, onBack = onBack, modifier = Modifier.weight(1f))
                ActionPills(canUndo, canRedo, state.dock, state.leftHanded, actions)
            }

            val trayAlignment = when (side) {
                TraySide.Top -> Alignment.TopCenter
                TraySide.Left -> AbsoluteAlignment.CenterLeft
                TraySide.Right -> AbsoluteAlignment.CenterRight
            }
            val trayPadding = when (side) {
                TraySide.Top -> Modifier.padding(top = TrayTop)
                TraySide.Left -> Modifier.absolutePadding(left = ChromeEdge + 8.dp, top = TopRowHeight)
                TraySide.Right -> Modifier.absolutePadding(right = ChromeEdge + 8.dp, top = TopRowHeight)
            }
            AnimatedVisibility(
                visible = trayShown,
                enter = Motion.Enter.toolbar(reduce),
                exit = Motion.Exit.toolbar(reduce),
                modifier = Modifier.align(trayAlignment).then(trayPadding),
            ) {
                val tray: @Composable () -> Unit = {
                    PenTray(
                        toolbox = toolbox,
                        side = side,
                        temporary = temporary,
                        pickerAnchor = picker,
                        onPen = ::onPen,
                        onColor = { viewModel.updateToolbox(toolbox.withColor(it)) },
                        onOpenPicker = { picker = if (picker == it) null else it },
                        onCollapse = { picker = null; collapsed = true },
                        picker = pickerContent,
                    )
                }
                when (side) {
                    TraySide.Top -> if (wide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            tray()
                            ToolsCapsule(vertical = false, onComingSoon = ::comingSoon)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            tray()
                            ToolsCapsule(vertical = false, onComingSoon = ::comingSoon)
                        }
                    }
                    // Side docks are physical: the tray hugs the screen edge, tools sit inward.
                    TraySide.Left, TraySide.Right -> CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (side == TraySide.Left) {
                                tray()
                                ToolsCapsule(vertical = true, onComingSoon = ::comingSoon)
                            } else {
                                ToolsCapsule(vertical = true, onComingSoon = ::comingSoon)
                                tray()
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = !trayShown,
                enter = Motion.Enter.popover(reduce),
                exit = Motion.Exit.popover(reduce),
                modifier = Modifier.align(trayAlignment).then(trayPadding),
            ) {
                CollapsedTrayPill(toolbox, temporary, onRestore = {
                    collapsed = false
                    autoHidden = false
                })
            }

            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) { data ->
                Snackbar(
                    data,
                    shape = BestNoteShapes.Pill,
                    containerColor = BestNoteTheme.colors.strongButton,
                    contentColor = BestNoteTheme.colors.onStrongButton,
                    actionColor = BestNoteTheme.colors.aiAccent,
                )
            }
        }

        // ---- S Pen quick palette, over everything ----
        quickAt?.let { at ->
            QuickPalette(
                hover = pageOrigin + at,
                leftHanded = state.leftHanded,
                toolbox = toolbox,
                onToolbox = viewModel::updateToolbox,
                onPen = { viewModel.selectPen(it) },
                onLasso = { comingSoon(lassoText) },
                onMore = {
                    collapsed = false
                    autoHidden = false
                    picker = PickerAnchor.Pen
                },
                onDismiss = { quickAt = null },
            )
        }
    }

    DebugStylusHooksEffect(canvas)
}

/** Debug builds: lets scripts inject hover side-button clicks and handwriting (see src/debug). */
@Composable
private fun DebugStylusHooksEffect(canvas: PageCanvasView?) {
    val context = LocalContext.current
    DisposableEffect(canvas) {
        val view = canvas ?: return@DisposableEffect onDispose {}
        val detach = DebugStylusHooks.attach(context, view)
        onDispose { detach() }
    }
}
