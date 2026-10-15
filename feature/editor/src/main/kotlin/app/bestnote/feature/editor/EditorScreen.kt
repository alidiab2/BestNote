/**
 * :feature:editor — the notebook page editor: a tool bar (pen styles, pencil, highlighter,
 * eraser, undo/redo) over a [PageCanvasView] that draws pressure-sensitive ink with a stylus,
 * or with a finger when stylus-only mode is off.
 */
package app.bestnote.feature.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
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
import app.bestnote.core.datastore.UserPreferencesRepository
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import app.bestnote.engine.canvas.PageCanvasView
import app.bestnote.engine.ink.InkPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    val stylusOnly: Boolean = false,
    val tool: Tool = Tool.Pen,
    val penStyle: PenStyle = PenStyle.Fountain,
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    dao: NotebookDao,
    preferences: UserPreferencesRepository,
    repository: NotebookRepository,
) : ViewModel() {
    val notebookId = NotebookId(savedStateHandle.toRoute<EditorRoute>().notebookId)

    /** Autosave + thumbnails for the open page (DOC-03, DOC-10). */
    internal val session = PageSession(notebookId, repository, viewModelScope)

    override fun onCleared() = session.close()

    private val toolState = MutableStateFlow(Tool.Pen to PenStyle.Fountain)

    val state: StateFlow<EditorUiState> = combine(
        dao.observe(notebookId.value),
        preferences.preferences,
        toolState.asStateFlow(),
    ) { entity, prefs, (tool, style) ->
        val notebook = entity?.toModel()
        EditorUiState(
            title = notebook?.title.orEmpty(),
            template = notebook?.template ?: PaperTemplate.Ruled,
            stylusOnly = prefs.stylusOnly,
            tool = tool,
            penStyle = style,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState())

    fun selectTool(tool: Tool) = toolState.update { it.copy(first = tool) }
    fun selectPenStyle(style: PenStyle) = toolState.update { Tool.Pen to style }
}

private fun toolColor(tool: Tool): Int = when (tool) {
    Tool.Highlighter -> InkPalette.HIGHLIGHTER_YELLOW
    Tool.Pencil -> InkPalette.GRAPHITE
    else -> InkPalette.BLACK
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(onBack: () -> Unit, viewModel: EditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var canvas by remember { mutableStateOf<PageCanvasView?>(null) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var penMenu by remember { mutableStateOf(false) }
    val loaded by viewModel.session.ready.collectAsStateWithLifecycle()
    LaunchedEffect(loaded, canvas) { canvas?.let(viewModel.session::attach) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.session.onStop() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        ToolButton(Icons.Default.Edit, "Pen (${state.penStyle})", state.tool == Tool.Pen) {
                            if (state.tool == Tool.Pen) penMenu = true else viewModel.selectTool(Tool.Pen)
                        }
                        DropdownMenu(expanded = penMenu, onDismissRequest = { penMenu = false }) {
                            PenStyle.entries.forEach { style ->
                                DropdownMenuItem(
                                    text = { Text(style.name) },
                                    onClick = { viewModel.selectPenStyle(style); penMenu = false },
                                )
                            }
                        }
                    }
                    ToolButton(Icons.Default.Draw, "Pencil", state.tool == Tool.Pencil) { viewModel.selectTool(Tool.Pencil) }
                    ToolButton(Icons.Default.Highlight, "Highlighter", state.tool == Tool.Highlighter) { viewModel.selectTool(Tool.Highlighter) }
                    ToolButton(Icons.Default.AutoFixNormal, "Eraser", state.tool == Tool.Eraser) { viewModel.selectTool(Tool.Eraser) }
                    IconButton(onClick = { canvas?.undo() }, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = { canvas?.redo() }, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    IconButton(onClick = { canvas?.clear() }) { Icon(Icons.Default.LayersClear, "Clear page") }
                },
            )
        },
    ) { padding ->
        AndroidView(
            factory = { context ->
                PageCanvasView(context).also { view ->
                    view.historyListener = PageCanvasView.HistoryListener { undo, redo ->
                        canUndo = undo
                        canRedo = redo
                        viewModel.session.onChanged(view)
                    }
                    canvas = view
                }
            },
            update = { view ->
                view.tool = state.tool
                view.penStyle = state.penStyle
                view.colorArgb = toolColor(state.tool)
                view.stylusOnly = state.stylusOnly
                view.template = state.template
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (selected) 1.15f else 1f, Motion.Spring.Snappy, label = "toolScale")
    IconToggleButton(checked = selected, onCheckedChange = { onClick() }) {
        Icon(icon, contentDescription = label, modifier = Modifier.scale(scale))
    }
}
