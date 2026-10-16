/**
 * :feature:editor — the notebook page editor, "Atelier" direction (DS-03, DS-04, CANVAS-03):
 * the page on a warm desk, floating pills on top, the signature pen tray (realistic pens, colors,
 * live stroke width) with a lighter tools capsule, a pen picker popover, the S Pen quick palette,
 * and undo/redo over the bounded command stack. Ink is drawn by a [PagedDocumentView] of real pages.
 */
package app.bestnote.feature.editor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.bestnote.feature.editor.audio.AudioController
import app.bestnote.feature.editor.audio.AudioPill
import app.bestnote.feature.editor.audio.RecorderHub
import app.bestnote.feature.editor.audio.RecordingsSheet
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.round
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import app.bestnote.core.designsystem.theme.WidthClass
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
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
import app.bestnote.core.datastore.FingerDrawing
import app.bestnote.core.datastore.HoverButtonAction
import app.bestnote.core.datastore.UserPreferences
import app.bestnote.core.datastore.ToolbarDock
import app.bestnote.core.datastore.UserPreferencesRepository
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.reducedMotion
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.ai.vision.providers.AiProviders
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PageRect
import app.bestnote.engine.recognition.HandwritingModels
import app.bestnote.engine.recognition.HandwritingRecognizer
import app.bestnote.engine.recognition.PageSearch
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.Tool
import app.bestnote.core.ui.keys.ShortcutAction
import app.bestnote.core.ui.keys.ShortcutHandler
import app.bestnote.core.ui.keys.ShortcutScope
import app.bestnote.core.ui.keys.TypingHandler
import app.bestnote.core.model.ListStyle
import app.bestnote.core.model.ParagraphStyle
import app.bestnote.feature.editor.text.CanvasTextEditor
import app.bestnote.feature.editor.text.Flag
import app.bestnote.feature.editor.text.FormatBarActions
import app.bestnote.feature.editor.text.TextFormatBar
import app.bestnote.engine.canvas.document.PagedDocument
import app.bestnote.engine.canvas.document.PagedDocumentView
import app.bestnote.engine.export.ExportDialog
import app.bestnote.engine.export.rememberExportRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data class EditorRoute(val notebookId: String, val pageId: String? = null, val query: String? = null)

fun NavController.navigateToEditor(id: NotebookId) = navigate(EditorRoute(id.value))

/** Opens [id] at page [pageId], with what matches [query] marked (REC-03). */
fun NavController.navigateToEditor(id: NotebookId, pageId: String, query: String) = navigate(EditorRoute(id.value, pageId, query))

/** A search result to show: [rects] on page [pageIndex], in page points. */
class SearchTarget(val pageIndex: Int, val rects: List<PageRect>)

private const val LOAD_POLL_MS = 50L

/** Ink written elsewhere (Smart Board), waiting to be inserted as new pages of the open notebook. */
/**
 * Handwriting to insert (Smart Board, AI-07): [pages] on [template] paper, right after page
 * [afterIndex] (-1: after the page on screen). In a [freshNotebook] the first page goes on the
 * notebook's empty first page.
 */
class InkInsert(
    val template: PaperTemplate,
    val pages: List<List<InkStroke>>,
    val afterIndex: Int = -1,
    val freshNotebook: Boolean = false,
    /** Text boxes per page (Smart Board's handwriting-font output), parallel to [pages]. */
    val elements: List<List<PageElement>> = emptyList(),
    /** TPL-01: the notes (compact element format) and template, recorded with the notebook. */
    val notes: String? = null,
    val templateId: String? = null,
    /** A follow-up photo: these Smart Board pages are re-laid out instead of inserting new ones. */
    val followUpPageIds: List<String> = emptyList(),
)

/**
 * @param onSmartBoard opens Smart Board for the notebook, with the page being read (its size and paper); on a
 *   Smart Board page "Continue / add photo" also passes that page's storage id and the lines the user wrote on.
 * @param takeInsert hands over (once) ink to insert when the editor comes back.
 */
fun NavGraphBuilder.editorScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onSmartBoard: (NotebookId, Page, String?, String?) -> Unit = { _, _, _, _ -> },
    takeInsert: () -> InkInsert? = { null },
) {
    composable<EditorRoute> { EditorScreen(onBack, onOpenSettings, onSmartBoard, takeInsert) }
}

data class EditorUiState(
    val title: String = "",
    val template: PaperTemplate = PaperTemplate.Ruled,
    val pageCount: Int = 1,
    /** Until settings load, fingers never draw (stylus-first); then the real setting applies. */
    val input: FingerInput = FingerInput.BeforeSettings,
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
    @ApplicationContext context: Context,
    private val pageSearch: PageSearch,
    val models: HandwritingModels,
    val recognizer: HandwritingRecognizer,
    /** Smart Board AI providers, for "Convert with AI" (REC-05). */
    val aiProviders: AiProviders,
    /** Smart Board per-notebook settings (notes language for Tidy). */
    private val smartBoardStyles: app.bestnote.ai.vision.providers.SmartBoardStyles,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<EditorRoute>()
    val notebookId = NotebookId(route.notebookId)

    /** The language Tidy writes in: the notebook's Smart Board notes language, or the handwriting's own. */
    fun tidyLanguage(): String? = smartBoardStyles.languageFor(notebookId.value).promptName

    /**
     * Where a search result asked to land: the page's index and the boxes to mark (REC-03). Waits for the notebook to
     * load; null when the editor was opened normally or the page is gone.
     */
    suspend fun searchTarget(): SearchTarget? {
        val pageId = route.pageId ?: return null
        val query = route.query ?: return null
        while (!document.isLoaded) delay(LOAD_POLL_MS)
        val index = document.pages.indexOfFirst { it.id == pageId }.takeIf { it >= 0 } ?: return null
        return SearchTarget(index, runCatching { pageSearch.highlights(notebookId, pageId, query) }.getOrDefault(emptyList()))
    }

    /** The notebook's pages and ink; outlives the view across configuration changes. */
    val document = PagedDocument().apply { blankPage = Page(notebookId, 0) }

    /** Loads the pages, autosaves them and keeps the thumbnail fresh (DOC-03, DOC-10, CANVAS-07). */
    internal val audio = AudioController(context, notebookId, viewModelScope)
    internal val session = DocumentSession(notebookId, repository, document, viewModelScope, File(context.cacheDir, "pdf"), audio) {
        dao.observe(notebookId.value).filterNotNull().first().toModel()
    }

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
            input = fingerInput(prefs),
            dock = prefs.toolbarDock,
            leftHanded = prefs.leftHanded,
            quickPalette = prefs.hoverButtonAction == HoverButtonAction.QuickPalette,
            doubleClickUndo = prefs.hoverDoubleClickUndo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState())

    fun selectPen(kind: PenKind) = toolboxState.update { it.select(kind) }
    fun setLasso(on: Boolean) = toolboxState.update { it.withLasso(on) }
    fun setShapes(on: Boolean) = toolboxState.update { it.withShapes(on) }
    fun updateToolbox(toolbox: Toolbox) = toolboxState.update { toolbox }
    fun setDock(dock: ToolbarDock) = viewModelScope.launch { preferences.setToolbarDock(dock) }
    fun setLeftHanded(enabled: Boolean) = viewModelScope.launch { preferences.setLeftHanded(enabled) }
    fun markPenSeen() = viewModelScope.launch { preferences.markPenSeen() }
}

/** SPEN-02: settings to finger routing. "Draw with finger" off (default) = auto stylus-only. */
internal fun fingerInput(prefs: UserPreferences) = FingerInput(
    stylusOnly = prefs.stylus.fingerDrawing == FingerDrawing.Never,
    autoStylusOnly = prefs.stylus.fingerDrawing == FingerDrawing.WhenNoPen,
    penSeen = prefs.stylus.penSeen,
    twoFingerTapUndo = prefs.gestures.twoFingerTapUndo,
    threeFingerTapRedo = prefs.gestures.threeFingerTapRedo,
)

/** Left-handed mode moves side docks to the other physical side, away from the writing hand. */
internal fun traySide(dock: ToolbarDock, leftHanded: Boolean): TraySide = when (dock) {
    ToolbarDock.Top -> TraySide.Top
    ToolbarDock.Left -> if (leftHanded) TraySide.Right else TraySide.Left
    ToolbarDock.Right -> if (leftHanded) TraySide.Left else TraySide.Right
}

/** The dock that puts the tray on physical [side] (the inverse of [traySide]). */
internal fun dockFor(side: TraySide, leftHanded: Boolean): ToolbarDock = when (side) {
    TraySide.Top, TraySide.Bottom -> ToolbarDock.Top
    TraySide.Left -> if (leftHanded) ToolbarDock.Right else ToolbarDock.Left
    TraySide.Right -> if (leftHanded) ToolbarDock.Left else ToolbarDock.Right
}

/**
 * Where a tray dragged to ([x], [y]) on a [width] x [height] screen lands: the nearest of the top
 * edge and the two physical sides. The top wins ties: it is the default and the calmest place.
 */
internal fun dropSide(x: Float, y: Float, width: Float, height: Float): TraySide {
    val top = y / TOP_PULL
    val left = x
    val right = width - x
    return when {
        top <= left && top <= right -> TraySide.Top
        left <= right -> TraySide.Left
        else -> TraySide.Right
    }.takeIf { width > 0f && height > 0f } ?: TraySide.Top
}

/** The top edge pulls from further away than the sides (a tray below the title row still docks on top). */
private const val TOP_PULL = 2.5f

private const val INSERT_POLL_MS = 50L

/** Ctrl+= / Ctrl+- zoom by this factor per press. */
private const val KEY_ZOOM_STEP = 1.25f
private const val SEARCH_SETTLE_MS = 150L
private const val SEARCH_MARK_HOLD_MS = 3_500L
private val ChromeEdge = 20.dp
private val CompactChromeEdge = 8.dp
private val BottomTrayGap = 8.dp
private val TopRowTop = 12.dp
private val TopRowHeight = 64.dp
private val TrayTop = 66.dp
private val ChromeGap = 12.dp

/** Below this width the top dock floats the tools capsule on its own, down the end edge. */
private val WideTopTray = 960.dp
private val AudioPillBottom = 64.dp
private val FormatBarGap = 8.dp

/** Tools picked with a bare letter key; with the Text tool on these letters are typed instead (TEXT-04). */
private val LetterTools = setOf(ShortcutAction.ToolPen, ShortcutAction.ToolHighlighter, ShortcutAction.ToolEraser, ShortcutAction.ToolLasso, ShortcutAction.ToolText)

/** Arrow-key nudge of a selection in page points: 1 pt, Shift 10 pt. */
private fun nudgeOf(action: ShortcutAction): Pair<Float, Float> {
    val far = action.name.endsWith("Far")
    val step = if (far) NUDGE_FAR_PT else NUDGE_PT
    return when (action) {
        ShortcutAction.NudgeLeft, ShortcutAction.NudgeLeftFar -> -step to 0f
        ShortcutAction.NudgeRight, ShortcutAction.NudgeRightFar -> step to 0f
        ShortcutAction.NudgeUp, ShortcutAction.NudgeUpFar -> 0f to -step
        else -> 0f to step
    }
}

private const val NUDGE_PT = 1f
private const val NUDGE_FAR_PT = 10f
private const val DeskMidStop = 0.6f
private const val DeskMidMix = 0.45f
private const val DeskCenterY = 0.45f
private const val DeskRadius = 0.62f

@Composable
fun EditorScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onSmartBoard: (NotebookId, Page, String?, String?) -> Unit = { _, _, _, _ -> },
    takeInsert: () -> InkInsert? = { null },
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toolbox by viewModel.toolbox.collectAsStateWithLifecycle()
    var canvas by remember { mutableStateOf<PagedDocumentView?>(null) }
    val document = viewModel.document
    var canUndo by remember { mutableStateOf(document.canUndo) }
    var canRedo by remember { mutableStateOf(document.canRedo) }
    var writing by remember { mutableStateOf(false) }
    var autoHidden by remember { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<PickerAnchor?>(null) }
    var temporary by remember { mutableStateOf<PenKind?>(null) }
    var quickAt by remember { mutableStateOf<Offset?>(null) }
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }
    var pageNumber by remember { mutableStateOf(1 to 1) }
    var managerPage by remember { mutableStateOf<Int?>(null) }
    var selectionMenu by remember { mutableStateOf<PagedDocumentView.SelectionMenu?>(null) }
    var exporting by remember { mutableStateOf(false) }
    // TPL-01: on a Smart Board page, Smart Board asks: continue this page (add a photo) or new pages.
    var smartBoardChoice by remember { mutableStateOf<Int?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val messages = mapOf(R.string.image_failed to stringResource(R.string.image_failed), R.string.image_nothing_to_paste to stringResource(R.string.image_nothing_to_paste))
    val elements = rememberElementTools(
        canvas = { canvas },
        document = document,
        session = viewModel.session,
        colors = { toolbox.quickColors(PenKind.Fountain) },
        onSelecting = { viewModel.setLasso(true) },
        onMessage = { id -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(messages.getValue(id)) } },
    )
    // TEXT-04: typing on the page. The editor follows the pages as they scroll and zoom.
    val typing = elements.typing
    var viewportTick by remember { mutableIntStateOf(0) }
    var formatBarPx by remember { mutableIntStateOf(0) }
    DisposableEffect(canvas, typing.textTool) {
        val view = canvas
        view?.textTapListener = if (typing.textTool) PagedDocumentView.InkTapListener { page, x, y -> typing.tapAt(page, x, y) } else null
        onDispose { view?.textTapListener = null }
    }
    LaunchedEffect(writing) { if (writing) typing.commit() }
    androidx.activity.compose.BackHandler(enabled = typing.editing != null) { typing.commit() }
    val audio = viewModel.audio
    val audioState by audio.state.collectAsStateWithLifecycle()
    var recordingsOpen by remember { mutableStateOf(false) }
    val converter = rememberInkConverter(
        canvas = { canvas },
        document = document,
        models = viewModel.models,
        recognizer = viewModel.recognizer,
        onSelecting = { viewModel.setLasso(true) },
        snackbar = snackbar,
        ai = viewModel.aiProviders,
    )
    val tidier = rememberInkTidier(
        canvas = { canvas },
        document = document,
        models = viewModel.models,
        recognizer = viewModel.recognizer,
        ai = viewModel.aiProviders,
        adopt = viewModel.session::adopt,
        snackbar = snackbar,
        language = viewModel::tidyLanguage,
    )
    val reduce = reducedMotion()
    // DS-05: a compact window (phone, Fold outer screen) ignores the dock and floats the tray at the bottom.
    val compact = WidthClass.of(LocalConfiguration.current.screenWidthDp.dp) == WidthClass.Compact
    val side = if (compact) TraySide.Bottom else traySide(state.dock, state.leftHanded)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.session.onStop() }
    DisposableEffect(document) {
        val setHistory = { undo: Boolean, redo: Boolean ->
            canUndo = undo
            canRedo = redo
        }
        val listener = object : PagedDocument.Listener {
            override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) = setHistory(canUndo, canRedo)
        }
        document.addListener(listener)
        onDispose { document.removeListener(listener) }
    }

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
    // DS-05 compact: the format bar takes the bottom while typing, so the tray steps aside.
    val trayShown = !collapsed && !autoHidden && !(compact && typing.editing != null)
    val chromeAlpha by animateFloatAsState(if (writing) Motion.Toolbar.WritingAlpha else 1f, Motion.Toolbar.fadeSpec(), label = "chromeAlpha")

    val micDenied = stringResource(R.string.audio_permission_denied)
    val micFailed = stringResource(R.string.audio_failed)
    val micSaved = stringResource(R.string.audio_saved)
    val micList = stringResource(R.string.audio_recordings)
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            audio.startRecording()
        } else {
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(micDenied)
            }
        }
    }
    fun onMic() {
        picker = null
        if (audioState.recording != null || audioState.recordingElsewhere) {
            audio.stopRecording()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audio.startRecording()
        } else {
            // The notification permission is best effort: the recording runs without it, only the notification is hidden.
            val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                arrayOf(Manifest.permission.RECORD_AUDIO)
            }
            micPermission.launch(wanted)
        }
    }
    fun setPlayFromInk(on: Boolean) = viewModel.session.setPlayFromInk(on)
    LaunchedEffect(audioState.failure) {
        if (audioState.failure != null) {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(micFailed)
            audio.dismissFailure()
        }
    }
    LaunchedEffect(audio) {
        RecorderHub.finished.collect { f ->
            if (f.notebookId != viewModel.notebookId.value) return@collect
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(micSaved, actionLabel = micList) == SnackbarResult.ActionPerformed) recordingsOpen = true
        }
    }
    val comingSoonFormat = stringResource(R.string.editor_coming_soon)
    val clearedText = stringResource(R.string.editor_page_cleared)
    val undoText = stringResource(R.string.editor_undo)
    fun comingSoon(what: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(comingSoonFormat.format(what))
        }
    }
    fun clearPage() {
        picker = null
        canvas?.let { document.clearPage(it.currentPage) }
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(clearedText, actionLabel = undoText) == SnackbarResult.ActionPerformed) document.undo()
        }
    }
    fun toggleLasso() {
        picker = null
        elements.leaveTextTool()
        viewModel.setLasso(!toolbox.lasso)
    }
    fun toggleShapes() {
        picker = null
        elements.leaveTextTool()
        // From the lasso, Shapes means "draw shapes now"; otherwise it toggles.
        viewModel.setShapes(toolbox.lasso || !toolbox.shapes)
    }
    fun onPen(kind: PenKind) {
        elements.leaveTextTool()
        if (kind == toolbox.active && !toolbox.lasso) {
            picker = if (picker == PickerAnchor.Pen) null else PickerAnchor.Pen
        } else {
            picker = null
            viewModel.selectPen(kind)
        }
    }
    val actions = ChromeActions(
        onBack = onBack,
        onUndo = { document.undo() },
        onRedo = { document.redo() },
        onComingSoon = ::comingSoon,
        onDock = { picker = null; viewModel.setDock(it) },
        onLeftHanded = { viewModel.setLeftHanded(it) },
        onClearPage = ::clearPage,
        onSmartBoard = {
            val index = canvas?.currentPage?.takeIf { it >= 0 } ?: 0
            val page = document.pages.getOrNull(index)
            if (page != null && SmartBoardPages.groupOf(viewModel.session, page.id) != null) {
                smartBoardChoice = index
            } else {
                page?.page?.let { onSmartBoard(viewModel.notebookId, it, null, null) }
            }
        },
        pagesMenu = { PaperMenuButton(canvas, onManage = { picker = null; managerPage = canvas?.currentPage?.coerceAtLeast(0) ?: 0 }) },
        onOpenSettings = onOpenSettings,
        onExport = { picker = null; exporting = true },
    )
    // SPEN-10: hardware-keyboard shortcuts. Dialogs are their own windows (they never see these keys); the
    // sheets drawn in this window pause them too.
    val dialogOpen = elements.newText != null || elements.editing != null || elements.cropping != null || elements.sourceMenu ||
        elements.moreFor != null || elements.pickingFont ||
        managerPage != null || exporting || recordingsOpen || smartBoardChoice != null
    ShortcutHandler(ShortcutScope.Editor) { action ->
        val view = canvas
        if (dialogOpen || view == null) return@ShortcutHandler false
        // TEXT-04: with the Text tool on, bare letters are typing (they start a text box), not tools.
        if (typing.textTool && action in LetterTools) return@ShortcutHandler false
        if (action in LetterTools) elements.leaveTextTool()
        picker = null
        when (action) {
            ShortcutAction.Undo -> document.undo()
            ShortcutAction.Redo -> document.redo()
            ShortcutAction.ToolPen -> viewModel.selectPen(if (toolbox.active.tool == Tool.Pen) toolbox.active else PenKind.Fountain)
            ShortcutAction.ToolHighlighter -> viewModel.selectPen(PenKind.Highlighter)
            ShortcutAction.ToolEraser -> viewModel.selectPen(PenKind.Eraser)
            ShortcutAction.ToolLasso -> viewModel.setLasso(true)
            ShortcutAction.ToolText -> {
                if (!typing.textTool && toolbox.lasso) viewModel.setLasso(false)
                elements.toggleTextTool()
            }
            ShortcutAction.NudgeLeft, ShortcutAction.NudgeRight, ShortcutAction.NudgeUp, ShortcutAction.NudgeDown,
            ShortcutAction.NudgeLeftFar, ShortcutAction.NudgeRightFar, ShortcutAction.NudgeUpFar, ShortcutAction.NudgeDownFar,
            -> {
                val (dx, dy) = nudgeOf(action)
                if (!view.nudgeSelection(dx, dy)) return@ShortcutHandler false
            }
            ShortcutAction.ZoomIn -> view.zoomTo(view.zoom * KEY_ZOOM_STEP)
            ShortcutAction.ZoomOut -> view.zoomTo(view.zoom / KEY_ZOOM_STEP)
            ShortcutAction.ZoomReset -> view.zoomTo(1f)
            ShortcutAction.NextPage -> view.revealPage((view.currentPage + 1).coerceIn(0, (document.pages.size - 1).coerceAtLeast(0)))
            ShortcutAction.PreviousPage -> view.revealPage((view.currentPage - 1).coerceAtLeast(0))
            ShortcutAction.NewPage -> view.addPage()
            ShortcutAction.Paste -> if (!view.paste()) elements.pasteImage()
            // These act on a selection; with none, the key goes on to whoever wants it.
            ShortcutAction.Copy, ShortcutAction.Cut, ShortcutAction.Duplicate, ShortcutAction.Delete, ShortcutAction.ClearSelection -> {
                if (!view.hasSelection) return@ShortcutHandler false
                when (action) {
                    ShortcutAction.Copy -> view.copySelection()
                    ShortcutAction.Cut -> view.cutSelection()
                    ShortcutAction.Duplicate -> view.duplicateSelection()
                    ShortcutAction.Delete -> view.deleteSelection()
                    else -> view.clearSelection()
                }
            }
            else -> return@ShortcutHandler false
        }
        true
    }
    // TEXT-04: formatting keys while typing on the page (the "Text" group of the shortcut help).
    ShortcutHandler(ShortcutScope.Text) { action ->
        if (typing.editing == null || dialogOpen) return@ShortcutHandler false
        when (action) {
            ShortcutAction.TextBold -> typing.act { it.toggle(Flag.Bold) }
            ShortcutAction.TextItalic -> typing.act { it.toggle(Flag.Italic) }
            ShortcutAction.TextUnderline -> typing.act { it.toggle(Flag.Underline) }
            ShortcutAction.TextBullets -> typing.act { it.toggleList(ListStyle.Bullet) }
            ShortcutAction.TextNumbers -> typing.act { it.toggleList(ListStyle.Numbered) }
            ShortcutAction.TextTitle -> typing.act { it.setStyle(ParagraphStyle.Title) }
            ShortcutAction.TextHeading -> typing.act { it.setStyle(ParagraphStyle.Heading) }
            ShortcutAction.TextSubheading -> typing.act { it.setStyle(ParagraphStyle.Subheading) }
            ShortcutAction.TextBody -> typing.act { it.setStyle(ParagraphStyle.Body) }
            ShortcutAction.TextIndent, ShortcutAction.TextOutdent -> {
                val session = typing.editing?.session ?: return@ShortcutHandler false
                if (!session.indent(if (action == ShortcutAction.TextIndent) 1 else -1)) return@ShortcutHandler false
                typing.sync()
            }
            ShortcutAction.TextDone -> typing.commit()
            else -> return@ShortcutHandler false
        }
        true
    }
    // TEXT-04: with the Text tool on and nothing selected, a hardware keyboard just types: a box starts at the last
    // tap (or mid-page) and catches every key until the on-page field has focus.
    TypingHandler { event ->
        val code = event.unicodeChar
        if (!typing.textTool || dialogOpen || canvas?.hasSelection == true || code == 0 || Character.isISOControl(code)) {
            return@TypingHandler false
        }
        typing.typeIntoNewBox(String(Character.toChars(code)))
        true
    }
    val pickerContent: @Composable (PickerAnchor) -> Unit = { anchor ->
        AnchoredPopover(side, onDismissed = { if (picker == anchor) picker = null }) {
            if (toolbox.active == PenKind.Eraser) {
                EraserPickerContent(
                    mode = toolbox.eraserMode,
                    onMode = { viewModel.updateToolbox(toolbox.copy(eraserMode = it)) },
                    onClearPage = ::clearPage,
                )
            } else {
                PenPickerContent(toolbox.active, toolbox, viewModel::updateToolbox, startWithCustom = anchor == PickerAnchor.Colors)
            }
        }
    }

    // AI-07: Smart Board's handwriting arrives as new pages after the page it was opened from, as one undo step.
    val insertedFormat = stringResource(R.string.editor_smart_board_inserted)
    LaunchedEffect(Unit) {
        val insert = takeInsert() ?: return@LaunchedEffect
        while (!document.isLoaded) delay(INSERT_POLL_MS)
        val pages = withContext(Dispatchers.Default) {
            insert.pages.map { page -> page.mapNotNull { model -> runCatching { model to InkConversion.toInk(model) }.getOrNull() } }
        }
        val current = insert.afterIndex.takeIf { it in document.pages.indices } ?: canvas?.currentPage?.takeIf { it in document.pages.indices } ?: document.pages.lastIndex
        val base = document.pages.getOrNull(current)?.page ?: document.blankPage ?: return@LaunchedEffect
        val byPage = LinkedHashMap<Int, List<androidx.ink.strokes.Stroke>>()
        pages.forEach { viewModel.session.adopt(it) }
        val emptyFirst = insert.freshNotebook && document.pages.size == 1 && document.pages[0].strokes.isEmpty()
        if (emptyFirst && pages.isNotEmpty()) {
            if (document.pages[0].page.template != insert.template) document.updatePage(0) { it.copy(template = insert.template) }
            document.editContent(0, addedStrokes = pages[0].map { it.second }, elements = document.pages[0].elements + insert.elements.getOrNull(0).orEmpty())
            byPage[0] = pages[0].map { it.second }
        }
        if (insert.followUpPageIds.isNotEmpty()) {
            val written = SmartBoardPages.applyFollowUp(document, viewModel.session, insert, pages)
            if (written != null) {
                written.firstOrNull()?.let { first -> canvas?.scrollToPage(first) }
                snackbar.currentSnackbarData?.dismiss()
                if (snackbar.showSnackbar(insertedFormat.format(written.size), actionLabel = undoText) == SnackbarResult.ActionPerformed) document.undo()
                return@LaunchedEffect
            }
        }
        val rest = if (emptyFirst) pages.drop(1) else pages
        val restElements = if (emptyFirst) insert.elements.drop(1) else insert.elements
        // After the page being read, not at the end; pages, ink and text are one undo step.
        val at = if (emptyFirst) 1 else current + 1
        document.insertPages(at, rest.map { page -> base.copy(template = insert.template) to page.map { it.second } }, restElements)
            .forEachIndexed { i, index -> byPage[index] = rest[i].map { it.second } }
        SmartBoardPages.recordInsert(viewModel.session, byPage.keys.sorted().map { document.pages[it].id }, insert)
        byPage.keys.firstOrNull()?.let { first -> canvas?.scrollToPage(first) }
        snackbar.currentSnackbarData?.dismiss()
        if (snackbar.showSnackbar(insertedFormat.format(byPage.size), actionLabel = undoText) == SnackbarResult.ActionPerformed) document.undo()
    }

    // REC-03: arriving from a search result, jump to the page and mark what matched.
    val highlightColor = BestNoteTheme.colors.warning.toArgb()
    LaunchedEffect(canvas) {
        val view = canvas ?: return@LaunchedEffect
        val target = viewModel.searchTarget() ?: return@LaunchedEffect
        delay(SEARCH_SETTLE_MS) // the pages lay out after the notebook loads
        view.highlightColor = highlightColor
        view.setHighlightTiming(SEARCH_MARK_HOLD_MS, if (reduce) 0L else Motion.Duration.Long.toLong())
        if (target.rects.isEmpty()) return@LaunchedEffect view.scrollToPage(target.pageIndex)
        view.showHighlights(target.pageIndex, target.rects)
        view.focusOn(target.pageIndex, target.rects.reduce { a, b -> a.union(b) })
    }

    // SPEN-02: the first pen event switches fingers to scroll/zoom for good; say so once.
    val penDetectedText = stringResource(R.string.editor_pen_detected)
    val settingsText = stringResource(R.string.editor_settings)
    fun onPenSeen() {
        if (state.input.penSeen) return
        viewModel.markPenSeen()
        if (!state.input.autoStylusOnly) return
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(penDetectedText, actionLabel = settingsText) == SnackbarResult.ActionPerformed) onOpenSettings()
        }
    }

    // The tray can be dragged to the top or a side; it docks where it is dropped.
    var trayDrag by remember { mutableStateOf(Offset.Zero) }
    var gripOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragFrom by remember { mutableStateOf(Offset.Zero) }
    var screen by remember { mutableStateOf(Offset.Zero) }

    /** Height (px) of the expanded tray on the top dock, measured; it sets where page 1 starts. */
    var trayHeight by remember { mutableIntStateOf(0) }
    val trayGrip = Modifier.onGloballyPositioned { gripOrigin = it.positionInRoot() }.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { start ->
                picker = null
                dragFrom = gripOrigin + start
            },
            onDragEnd = {
                // Docks where the finger lets go of the grip.
                val at = dragFrom + trayDrag
                viewModel.setDock(dockFor(dropSide(at.x, at.y, screen.x, screen.y), state.leftHanded))
                trayDrag = Offset.Zero
            },
            onDragCancel = { trayDrag = Offset.Zero },
        ) { change, amount ->
            change.consume()
            trayDrag += amount
        }
    }

    val desk = BestNoteTheme.colors
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onSizeChanged { screen = Offset(it.width.toFloat(), it.height.toFloat()) }
            .drawBehind {
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
        val wide = maxWidth >= WideTopTray
        val windowWidth = maxWidth
        val density = LocalDensity.current
        // The chrome floats over the page: no reserved bar. Only the first page starts below the
        // title row (so the title never hides its top); everything scrolls under the chrome.
        // Page 1 starts fully visible below the chrome: the title row, plus the tray when it floats on top.
        val topInsetPx = with(density) {
            val chrome = if (side == TraySide.Top && trayHeight > 0) TrayTop.roundToPx() + trayHeight + ChromeGap.roundToPx() else TopRowHeight.roundToPx()
            WindowInsets.safeDrawing.getTop(this) + chrome
        }
        var bottomChromePx by remember { mutableIntStateOf(0) }
        val bottomChromeShown = compact && trayShown
        val bottomInsetPx = with(density) { WindowInsets.safeDrawing.getBottom(this) + if (bottomChromeShown) bottomChromePx else 0 }
        val edge = if (compact) CompactChromeEdge else ChromeEdge

        // ---- the pages: real sheets on the desk, full screen. They never mirror (forced LTR) ----
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            PagedDocumentHost(
                document = document,
                tool = toolbox.tool,
                penStyle = toolbox.active.style,
                colorArgb = toolbox.current.brushColorArgb,
                brushSize = toolbox.current.size,
                eraserMode = toolbox.eraserMode,
                shapeTool = toolbox.snapsShapes,
                input = state.input,
                quickPalette = state.quickPalette,
                doubleClickUndo = state.doubleClickUndo,
                onView = { view ->
                    view.layoutDirection = View.LAYOUT_DIRECTION_LTR
                    view.writingListener = PagedDocumentView.WritingListener { writing = it }
                    view.selectionListener = PagedDocumentView.SelectionListener { selectionMenu = it }
                    view.selectionAppearMs = Motion.Duration.Short
                    view.viewportListener = { viewportTick++ }
                    view.temporaryToolListener = { tool -> temporary = if (tool == Tool.Eraser) PenKind.Eraser else null }
                    view.quickPaletteListener = PagedDocumentView.QuickPaletteListener { x, y ->
                        picker = null
                        quickAt = Offset(x, y)
                    }
                    canvas = view
                    viewModel.session.attach(view)
                },
                onIndicator = { page, count -> pageNumber = page + 1 to count },
                onIndicatorClick = { managerPage = canvas?.currentPage?.coerceAtLeast(0) ?: 0 },
                onPenSeen = ::onPenSeen,
                topInsetPx = topInsetPx,
                bottomInsetPx = bottomInsetPx,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { pageOrigin = it.positionInRoot() },
            )
            // TEXT-04: the box being typed into, right on the page (same px as the canvas view).
            val view = canvas
            if (view != null && typing.editing != null) {
                val keyboardTop = screen.y - with(density) { WindowInsets.safeDrawing.getBottom(this) } - formatBarPx -
                    with(density) { FormatBarGap.toPx() }
                Box(Modifier.fillMaxSize()) {
                    CanvasTextEditor(
                        typing = typing,
                        view = view,
                        viewport = viewportTick,
                        visibleTop = topInsetPx.toFloat(),
                        visibleBottom = keyboardTop,
                        frameColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ---- floating chrome ----
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = edge, end = edge, top = TopRowTop)
                    .graphicsLayer { alpha = chromeAlpha },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    TitleCluster(state.title, page = pageNumber.first, pageCount = maxOf(pageNumber.second, 1), onBack = onBack, compact = compact)
                }
                ActionPills(canUndo, canRedo, state.dock, state.leftHanded, actions, compact = compact)
            }

            val trayAlignment = when (side) {
                TraySide.Top -> Alignment.TopCenter
                TraySide.Bottom -> Alignment.BottomCenter
                TraySide.Left -> AbsoluteAlignment.CenterLeft
                TraySide.Right -> AbsoluteAlignment.CenterRight
            }
            val trayPadding = when (side) {
                TraySide.Top -> Modifier.padding(top = TrayTop)
                TraySide.Bottom -> Modifier.padding(bottom = BottomTrayGap)
                TraySide.Left -> Modifier.absolutePadding(left = ChromeEdge, top = TopRowHeight)
                TraySide.Right -> Modifier.absolutePadding(right = ChromeEdge, top = TopRowHeight)
            }
            val tools: @Composable (Boolean) -> Unit = { vertical ->
                ToolsCapsule(
                    vertical = vertical,
                    onComingSoon = ::comingSoon,
                    lassoActive = toolbox.lasso,
                    onLasso = ::toggleLasso,
                    shapesActive = toolbox.shapes && !toolbox.lasso,
                    onShapes = ::toggleShapes,
                    textActive = typing.textTool,
                    onText = {
                        picker = null
                        if (!typing.textTool && toolbox.lasso) viewModel.setLasso(false)
                        elements.toggleTextTool()
                    },
                    onImage = { picker = null; elements.sourceMenu = true },
                    audioActive = audioState.recording != null || recordingsOpen,
                    onAudio = ::onMic,
                    onAudioLongPress = { picker = null; recordingsOpen = true },
                )
            }
            AnimatedVisibility(
                visible = trayShown,
                enter = Motion.Enter.toolbar(reduce),
                exit = Motion.Exit.toolbar(reduce),
                modifier = Modifier
                    .align(trayAlignment)
                    .then(trayPadding)
                    .absoluteOffset { trayDrag.round() },
            ) {
                val tray: @Composable () -> Unit = {
                    PenTray(
                        modifier = Modifier
                            .onSizeChanged { if (side == TraySide.Top && it.height > 0) trayHeight = it.height }
                            .then(if (compact) Modifier.widthIn(max = windowWidth - edge * 2) else Modifier),
                        scrollable = compact,
                        toolbox = toolbox,
                        side = side,
                        temporary = temporary,
                        pickerAnchor = picker,
                        onPen = ::onPen,
                        onColor = { viewModel.updateToolbox(toolbox.withColor(it)) },
                        onOpenPicker = { picker = if (picker == it) null else it },
                        onCollapse = { picker = null; collapsed = true },
                        picker = pickerContent,
                        grip = trayGrip.takeUnless { compact },
                    )
                }
                when (side) {
                    // Compact: the tools capsule rides just above the tray, both in a thumb's reach.
                    TraySide.Bottom -> Column(
                        Modifier.onSizeChanged { bottomChromePx = it.height + with(density) { BottomTrayGap.roundToPx() } },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        tools(false)
                        tray()
                    }
                    TraySide.Top -> if (wide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            tray()
                            tools(false)
                        }
                    } else {
                        tray()
                    }
                    // Side docks are physical: the tray hugs the screen edge, tools sit inward.
                    TraySide.Left, TraySide.Right -> CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (side == TraySide.Left) {
                                tray()
                                tools(true)
                            } else {
                                tools(true)
                                tray()
                            }
                        }
                    }
                }
            }
            // Narrow screens, top dock: the tools capsule floats on its own down the end edge.
            AnimatedVisibility(
                visible = trayShown && side == TraySide.Top && !wide, // never the compact bottom tray
                enter = Motion.Enter.toolbar(reduce),
                exit = Motion.Exit.toolbar(reduce),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = TrayTop + TopRowHeight, end = ChromeEdge),
            ) { tools(true) }
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

            AudioPill(
                audioState,
                audio,
                onPlayFromInk = ::setPlayFromInk,
                onOpenList = { recordingsOpen = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (bottomChromeShown) with(density) { bottomChromePx.toDp() } + 8.dp else AudioPillBottom)
                    .graphicsLayer { alpha = chromeAlpha },
            )
            // TEXT-04: the format bar docks just above the keyboard (safe drawing includes the IME), or the bottom edge.
            AnimatedVisibility(
                visible = typing.editing != null && !writing,
                enter = Motion.Enter.sheet(reduce),
                exit = Motion.Exit.sheet(reduce),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = edge, end = edge, bottom = FormatBarGap)
                    .onSizeChanged { formatBarPx = it.height },
            ) {
                TextFormatBar(
                    typing = typing,
                    colors = toolbox.quickColors(PenKind.Fountain),
                    actions = FormatBarActions(
                        onFont = { elements.pickingFont = true },
                        onMore = { elements.moreFor = typing.editing?.session?.draft?.box },
                        onDone = { typing.commit() },
                    ),
                )
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

        // ---- recordings (MEDIA-04/05) ----
        if (recordingsOpen) {
            RecordingsSheet(audioState, audio, onPlayFromInk = ::setPlayFromInk, onDismiss = { recordingsOpen = false })
        }

        // ---- lasso menu (CANVAS-10/11): over the selection, never under the pen ----
        val menu = selectionMenu
        if (menu != null && toolbox.lasso && managerPage == null) {
            val view = canvas
            SelectionMenu(
                menu = menu,
                origin = pageOrigin,
                colors = toolbox.quickColors(PenKind.Fountain),
                // Never cover the floating chrome (the pen tray) with the menu (DS-05).
                chromeBottomPx = topInsetPx.toFloat(),
                bottomChromeTopPx = if (bottomChromeShown) screen.y - bottomInsetPx else 0f,
                actions = SelectionActions(
                    onColor = { view?.recolorSelection(it) },
                    onThinner = { view?.thickenSelection(1f / THICKEN_STEP) },
                    onThicker = { view?.thickenSelection(THICKEN_STEP) },
                    onCut = { view?.cutSelection() },
                    onCopy = { view?.copySelection() },
                    onDuplicate = { view?.duplicateSelection() },
                    onDelete = { view?.deleteSelection() },
                    onPaste = { view?.paste() },
                    onEditText = elements::editSelectedText,
                    onCrop = elements::cropSelected,
                    onRotate = elements::rotateSelected,
                    onToggleLock = elements::toggleLock,
                    onConvert = converter::convert,
                    onTidy = tidier::tidy,
                    onFill = { view?.fillSelection(it) },
                    onPen = { view?.restyleSelection(it) },
                    onGroup = { view?.groupSelection() },
                    onUngroup = { view?.ungroupSelection() },
                    onToFront = { view?.bringSelectionToFront() },
                    onToBack = { view?.sendSelectionToBack() },
                ),
            )
        }

        elements.Dialogs()
        converter.Dialogs()
        tidier.Dialogs()

        // ---- page manager (PAGES-03/04/05): zooms out of the page being read ----
        managerPage?.let { open ->
            PageManager(
                document = document,
                currentPage = open,
                onClose = { page ->
                    managerPage = null
                    if (page != null) canvas?.scrollToPage(page)
                },
            )
        }

        // ---- TPL-01: on a Smart Board page, continue it (add a photo) or start new pages ----
        smartBoardChoice?.let { index ->
            val page = document.pages.getOrNull(index)
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { smartBoardChoice = null },
                title = { androidx.compose.material3.Text(stringResource(R.string.editor_smart_board_choice_title)) },
                text = { androidx.compose.material3.Text(stringResource(R.string.editor_smart_board_choice_body)) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        smartBoardChoice = null
                        val group = page?.let { SmartBoardPages.groupOf(viewModel.session, it.id) }
                        if (page != null && group != null) {
                            val reserved = SmartBoardPages.reserved(document, viewModel.session, group).encode()
                            onSmartBoard(viewModel.notebookId, page.page, page.id, reserved)
                        }
                    }) { androidx.compose.material3.Text(stringResource(R.string.editor_smart_board_continue)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        smartBoardChoice = null
                        page?.page?.let { onSmartBoard(viewModel.notebookId, it, null, null) }
                    }) { androidx.compose.material3.Text(stringResource(R.string.editor_smart_board_new_pages)) }
                },
            )
        }

        // ---- export (DOC-06/07): PDF, PNG or .bnote, then the share sheet ----
        if (exporting) {
            ExportDialog(
                runner = rememberExportRunner(),
                notebookId = viewModel.notebookId,
                pageCount = document.pages.size.coerceAtLeast(1),
                currentPage = canvas?.currentPage?.coerceAtLeast(0) ?: 0,
                onDismiss = { exporting = false },
                beforeExport = { viewModel.session.flush() },
            )
        }

        // ---- S Pen quick palette, over everything ----
        quickAt?.let { at ->
            QuickPalette(
                hover = pageOrigin + at,
                leftHanded = state.leftHanded,
                toolbox = toolbox,
                onToolbox = viewModel::updateToolbox,
                onPen = { viewModel.selectPen(it) },
                onLasso = { viewModel.setLasso(true) },
                onMore = {
                    collapsed = false
                    autoHidden = false
                    picker = PickerAnchor.Pen
                },
                onDismiss = { quickAt = null },
            )
        }
    }

    DebugStylusHooksEffect(canvas, elements)
}

/** Debug builds: lets scripts inject hover side-button clicks and handwriting (see src/debug). */
@Composable
private fun DebugStylusHooksEffect(canvas: View?, elements: ElementTools) {
    val context = LocalContext.current
    DisposableEffect(canvas) {
        val view = canvas ?: return@DisposableEffect onDispose {}
        val detach = DebugStylusHooks.attach(context, view, elements)
        onDispose { detach() }
    }
}
