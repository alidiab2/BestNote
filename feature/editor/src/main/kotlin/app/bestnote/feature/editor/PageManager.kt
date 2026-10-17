package app.bestnote.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.reducedMotion
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperColor
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.canvas.document.DocumentPage
import app.bestnote.engine.canvas.document.PageBackgrounds
import app.bestnote.engine.canvas.document.PagedDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val THUMB_PX = 360
private const val CELL_ASPECT = 600f / 776f
private const val ZOOM_FROM = 1.6f
private const val LIFT_SCALE = 1.06f
private const val LIFT_DEGREES = -3f
private const val EDGE_SCROLL_ZONE_DP = 72f
private const val EDGE_SCROLL_PX_PER_FRAME = 18f

/**
 * The page manager (PAGES-03/04/05): every page as a thumbnail on the desk. Tap a page to open it,
 * long-press and drag to reorder (neighbours spring out of the way), tick pages to duplicate, rotate
 * or delete them, and insert a page with a template. Every change is one step of the document's
 * undo history, so it can be undone here or from the editor.
 *
 * It zooms out of the page being read ([ZOOM_FROM] x) and zooms back into the page you pick.
 * [onClose] receives the page to show, or null to stay where the reader was.
 */
@Composable
internal fun PageManager(
    document: PagedDocument,
    currentPage: Int,
    onClose: (openPage: Int?) -> Unit,
    modifier: Modifier = Modifier,
    insert: PageInsertFlow? = null,
) {
    val scope = rememberCoroutineScope()
    val reduce = reducedMotion()
    val snackbar = remember { SnackbarHostState() }
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = (currentPage - 1).coerceAtLeast(0))
    val selection = remember { mutableStateMapOf<String, Unit>() }
    var rev by remember { mutableIntStateOf(0) }
    var inserting by remember { mutableStateOf(false) }
    val reorder = remember { ReorderState() }
    val progress = remember { Animatable(if (reduce) 1f else 0f) }

    DisposableEffect(document) {
        val listener = object : PagedDocument.Listener {
            override fun onPagesChanged() {
                rev++
            }

            override fun onPageContentChanged(index: Int) {
                rev++
            }

            override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {
                rev++
            }
        }
        document.addListener(listener)
        onDispose { document.removeListener(listener) }
    }
    LaunchedEffect(Unit) { if (!reduce) progress.animateTo(1f, Motion.Spring.gentle()) }

    val pages: List<DocumentPage> = remember(rev) { document.pages.toList() }
    EdgeScroller(grid, reorder)
    val shown: List<DocumentPage> = reorder.order?.let { order -> order.mapNotNull { id -> pages.firstOrNull { it.id == id } } } ?: pages
    val selectedIndices = pages.indices.filter { pages[it].id in selection }

    fun close(open: Int?) {
        scope.launch {
            if (!reduce) progress.animateTo(0f, Motion.Spring.gentle())
            onClose(open)
        }
    }

    fun say(text: String, undoable: Boolean = true) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(text, actionLabel = if (undoable) "Undo" else null)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) document.undo()
        }
    }

    // Pages inserted from a PDF or image: report with the Undo snackbar, select and scroll to the first one.
    DisposableEffect(insert) {
        val previousNotify = insert?.notify
        val previousInserted = insert?.onInserted
        insert?.notify = { say(it) }
        insert?.onInserted = { first ->
            selection.clear()
            document.pages.getOrNull(first)?.let { selection[it.id] = Unit }
            scope.launch { grid.animateScrollToItem(first) }
        }
        onDispose {
            if (insert != null) {
                insert.notify = previousNotify ?: { }
                insert.onInserted = previousInserted ?: { }
            }
        }
    }

    BackHandler { close(null) }

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                // Zoom out of the page being read: it scales down around that page's thumbnail.
                val p = progress.value
                val scale = ZOOM_FROM + (1f - ZOOM_FROM) * p
                scaleX = scale
                scaleY = scale
                alpha = p
                transformOrigin = originOf(grid, shown.indexOfFirst { it === pages.getOrNull(currentPage) })
            }
            .background(BestNoteTheme.colors.deskCenter),
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopBar(
                count = pages.size,
                selected = selectedIndices.size,
                canUndo = document.canUndo,
                canRedo = document.canRedo,
                onClose = { close(null) },
                onUndo = { document.undo() },
                onRedo = { document.redo() },
                onSelectAll = {
                    if (selectedIndices.size == pages.size) selection.clear() else pages.forEach { selection[it.id] = Unit }
                },
                onDuplicate = {
                    val n = selectedIndices.size
                    selection.clear()
                    document.duplicatePages(selectedIndices).forEach { selection[document.pages[it].id] = Unit }
                    say(if (n == 1) "Page duplicated" else "$n pages duplicated")
                },
                onRotate = {
                    document.rotatePages(selectedIndices)
                    say("Rotated")
                },
                onDelete = {
                    val removed = document.deletePages(selectedIndices)
                    selection.clear()
                    say(if (removed == 1) "Page deleted" else "$removed pages deleted")
                },
                onInsert = { inserting = true },
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                state = grid,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.XL, vertical = Spacing.L),
                horizontalArrangement = Arrangement.spacedBy(Spacing.XL),
                verticalArrangement = Arrangement.spacedBy(Spacing.XL),
                modifier = Modifier
                    .weight(1f)
                    .onSizeChanged { reorder.viewport = it }
                    .reorderGestures(grid, reorder, shown, scope) { from, to -> document.movePage(from, to) }
                    .semantics { contentDescription = "Pages" },
            ) {
                items(shown, key = { it.id }) { page ->
                    val index = pages.indexOf(page)
                    ThumbnailCell(
                        page = page,
                        backgrounds = document.backgrounds,
                        number = index + 1,
                        current = index == currentPage,
                        selected = page.id in selection,
                        selecting = selection.isNotEmpty(),
                        reorder = reorder,
                        grid = grid,
                        onToggle = { if (page.id in selection) selection.remove(page.id) else selection[page.id] = Unit },
                        onOpen = { close(index) },
                        modifier = if (reorder.dragId == page.id) Modifier else Modifier.animateItem(placementSpec = Motion.Spring.offsetGentle()),
                    )
                }
            }
            Text(
                "Tap a page to open it · long-press and drag to reorder · tick pages to duplicate, rotate or delete",
                style = MaterialTheme.typography.labelMedium,
                color = BestNoteTheme.colors.muted,
                modifier = Modifier.padding(horizontal = Spacing.XL, vertical = Spacing.S),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.XXL)) { data ->
            androidx.compose.material3.Snackbar(
                data,
                shape = BestNoteShapes.Pill,
                containerColor = BestNoteTheme.colors.strongButton,
                contentColor = BestNoteTheme.colors.onStrongButton,
                actionColor = BestNoteTheme.colors.aiAccent,
            )
        }
    }

    if (inserting) {
        val base = (pages.getOrNull(selectedIndices.lastOrNull() ?: currentPage) ?: pages.lastOrNull())?.page
        InsertPageDialog(
            initial = base,
            onDismiss = { inserting = false },
            onFromPdf = insert?.let { flow -> { inserting = false; flow.startPdf(selectedIndices.lastOrNull() ?: currentPage) } },
            onFromImage = insert?.let { flow -> { inserting = false; flow.startImage(selectedIndices.lastOrNull() ?: currentPage) } },
        ) { template, color ->
            inserting = false
            val at = (selectedIndices.lastOrNull() ?: currentPage.coerceIn(0, pages.size - 1)) + 1
            val paper = (base ?: document.blankPage ?: return@InsertPageDialog).copy(template = template, paperColor = color)
            val index = document.insertPage(at, paper)
            selection.clear()
            selection[document.pages[index].id] = Unit
            scope.launch { grid.animateScrollToItem(index) }
            say("Page added")
        }
    }
}

/** Drag-and-drop bookkeeping shared by the gesture handler and the cells. */
internal class ReorderState {
    /** Grid size in px. */
    var viewport by mutableStateOf(IntSize.Zero)

    /** The live display order while dragging (page ids), or null. */
    var order by mutableStateOf<List<String>?>(null)

    var dragId by mutableStateOf<String?>(null)

    /** Finger position in grid coordinates, and where inside the item it grabbed. */
    var pointer by mutableStateOf(Offset.Zero)
    var grab by mutableStateOf(Offset.Zero)

    /** After a drop: the item is still settling into its slot with a spring. */
    var settleId by mutableStateOf<String?>(null)
    val settle = Animatable(Offset.Zero, Offset.VectorConverter)

    val size: Int get() = order?.size ?: 0
}

private fun originOf(grid: LazyGridState, shownIndex: Int): TransformOrigin {
    val info = grid.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == shownIndex } ?: return TransformOrigin.Center
    val w = info.viewportSize.width.takeIf { it > 0 } ?: return TransformOrigin.Center
    val h = info.viewportSize.height.takeIf { it > 0 } ?: return TransformOrigin.Center
    return TransformOrigin((item.offset.x + item.size.width / 2f) / w, (item.offset.y + item.size.height / 2f) / h)
}

private fun Modifier.reorderGestures(
    grid: LazyGridState,
    reorder: ReorderState,
    shownPages: List<DocumentPage>,
    scope: kotlinx.coroutines.CoroutineScope,
    onMoved: (from: Int, to: Int) -> Unit,
): Modifier = composed {
    // The gesture must outlive the order changes it causes, so it is keyed on nothing and reads fresh state.
    val latestShown by rememberUpdatedState(shownPages)
    val latestMoved by rememberUpdatedState(onMoved)
    pointerInput(Unit) {
    fun itemAt(p: Offset): LazyGridItemInfo? = grid.layoutInfo.visibleItemsInfo.firstOrNull {
        p.x >= it.offset.x && p.x < it.offset.x + it.size.width && p.y >= it.offset.y && p.y < it.offset.y + it.size.height
    }
    var from = -1
    detectDragGesturesAfterLongPress(
        onDragStart = { start ->
            val hit = itemAt(start) ?: return@detectDragGesturesAfterLongPress
            val id = latestShown.getOrNull(hit.index)?.id ?: return@detectDragGesturesAfterLongPress
            from = hit.index
            reorder.order = latestShown.map { it.id }
            reorder.dragId = id
            reorder.pointer = start
            reorder.grab = start - Offset(hit.offset.x.toFloat(), hit.offset.y.toFloat())
            scope.launch { reorder.settle.snapTo(Offset.Zero) }
            reorder.settleId = null
        },
        onDrag = { change, amount ->
            val id = reorder.dragId ?: return@detectDragGesturesAfterLongPress
            change.consume()
            reorder.pointer += amount
            val topLeft = reorder.pointer - reorder.grab
            val dragged = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return@detectDragGesturesAfterLongPress
            val center = topLeft + Offset(dragged.size.width / 2f, dragged.size.height / 2f)
            val target = itemAt(center)
            val order = reorder.order
            if (target != null && target.key != id && order != null) {
                val moved = order.toMutableList()
                moved.remove(id)
                moved.add(order.indexOf(target.key as String).coerceIn(0, moved.size), id)
                reorder.order = moved
            }
        },
        onDragEnd = {
            val id = reorder.dragId
            val order = reorder.order
            if (id != null && order != null) {
                val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
                val residual = item?.let { reorder.pointer - reorder.grab - Offset(it.offset.x.toFloat(), it.offset.y.toFloat()) } ?: Offset.Zero
                val to = order.indexOf(id)
                reorder.settleId = id
                reorder.dragId = null
                reorder.order = null
                if (from >= 0 && to >= 0 && from != to) latestMoved(from, to)
                scope.launch {
                    reorder.settle.snapTo(residual)
                    reorder.settle.animateTo(Offset.Zero, Motion.Spring.snappy())
                    if (reorder.settleId == id) reorder.settleId = null
                }
            }
        },
        onDragCancel = {
            reorder.dragId = null
            reorder.order = null
        },
    )
    }
}

/** While a page is dragged near the top or bottom edge, the grid scrolls (frame by frame). */
@Composable
private fun EdgeScroller(grid: LazyGridState, reorder: ReorderState) {
    val density = LocalDensity.current.density
    val dragging = reorder.dragId != null
    LaunchedEffect(dragging) {
        while (dragging && isActive) {
            withFrameNanos { }
            val h = reorder.viewport.height
            val zone = EDGE_SCROLL_ZONE_DP * density
            val y = reorder.pointer.y
            val dy = when {
                y < zone -> -EDGE_SCROLL_PX_PER_FRAME
                y > h - zone -> EDGE_SCROLL_PX_PER_FRAME
                else -> 0f
            }
            if (dy != 0f) grid.dispatchRawDelta(dy)
        }
    }
}

@Composable
private fun ThumbnailCell(
    page: DocumentPage,
    backgrounds: PageBackgrounds?,
    number: Int,
    current: Boolean,
    selected: Boolean,
    selecting: Boolean,
    reorder: ReorderState,
    grid: LazyGridState,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier,
) {
    val id = page.id
    val lifting = reorder.dragId == id
    val colors = BestNoteTheme.colors
    Column(
        modifier
            .graphicsLayer {
                val info = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
                val offset = when {
                    lifting && info != null -> reorder.pointer - reorder.grab - Offset(info.offset.x.toFloat(), info.offset.y.toFloat())
                    reorder.settleId == id -> reorder.settle.value
                    else -> Offset.Zero
                }
                translationX = offset.x
                translationY = offset.y
                if (lifting) {
                    scaleX = LIFT_SCALE
                    scaleY = LIFT_SCALE
                    rotationZ = LIFT_DEGREES
                }
            }
            .semantics { role = Role.Button; this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(CELL_ASPECT), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(6.dp)
            val thumb by rememberThumbnail(page, backgrounds)
            val aspect = page.page.widthPt / page.page.heightPt
            Box(
                Modifier
                    .then(if (aspect >= CELL_ASPECT) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                    .aspectRatio(aspect)
                    .softShadow(if (lifting) Elevation.High else Elevation.Low, shape)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface)
                    .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                    .clickable(onClickLabel = if (selecting) "Select page $number" else "Open page $number") {
                        if (selecting) onToggle() else onOpen()
                    },
            ) {
                thumb?.let { Image(it, contentDescription = "Page $number", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.S)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else colors.deskCenter.copy(alpha = 0.85f))
                    .border(1.5.dp, if (selected) MaterialTheme.colorScheme.primary else colors.muted, CircleShape)
                    .clickable(onClickLabel = "Select page $number") { onToggle() },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(AtelierIcons.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            }
        }
        Text(
            "$number",
            style = MaterialTheme.typography.labelLarge,
            color = if (current) MaterialTheme.colorScheme.primary else colors.muted,
            modifier = Modifier.padding(top = Spacing.S),
        )
    }
}

@Composable
private fun rememberThumbnail(page: DocumentPage, backgrounds: PageBackgrounds?) = produceState<ImageBitmap?>(null, page.id, page.page, page.strokes.size, page.strokes.lastOrNull()) {
    val paper = page.page
    val strokes = page.strokes.toList()
    value = withContext(Dispatchers.Default) {
        renderPageBitmap(paper, strokes, THUMB_PX, backgrounds?.thumbnail(paper, THUMB_PX)).asImageBitmap()
    }
}

@Composable
private fun TopBar(
    count: Int,
    selected: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSelectAll: () -> Unit,
    onDuplicate: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    onInsert: () -> Unit,
) {
    val colors = BestNoteTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.M, vertical = Spacing.S).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) {
        ChromeIconButton(AtelierIcons.Back, "Close page manager", onClose, size = 44.dp)
        Column(Modifier.padding(horizontal = Spacing.S)) {
            Text("Pages", style = MaterialTheme.typography.titleMedium)
            Text(
                if (selected > 0) "$count pages · $selected selected" else "$count pages",
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
            )
        }
        Spacer(Modifier.size(Spacing.L))
        ChromeIconButton(AtelierIcons.Undo, "Undo", onUndo, enabled = canUndo, size = 44.dp)
        ChromeIconButton(AtelierIcons.Redo, "Redo", onRedo, enabled = canRedo, size = 44.dp)
        Spacer(Modifier.size(Spacing.L))
        BarAction("Select all", null, true, onSelectAll)
        BarAction("Duplicate", AtelierIcons.Pages, selected > 0, onDuplicate)
        BarAction("Rotate", null, selected > 0, onRotate)
        BarAction("Delete", AtelierIcons.Trash, selected > 0, onDelete)
        Spacer(Modifier.size(Spacing.S))
        Surface(
            onClick = onInsert,
            shape = BestNoteShapes.Pill,
            color = colors.strongButton,
            contentColor = colors.onStrongButton,
        ) {
            Row(Modifier.padding(horizontal = Spacing.L, vertical = Spacing.S + 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AtelierIcons.Plus, null, Modifier.size(18.dp))
                Text("Insert page", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = Spacing.XS))
            }
        }
    }
}

@Composable
private fun BarAction(label: String, icon: ImageVector?, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = if (icon != null) Spacing.XS else 0.dp))
    }
}

private val pickerTemplates = listOf(
    PaperTemplate.Blank, PaperTemplate.RuledNarrow, PaperTemplate.Ruled, PaperTemplate.RuledWide,
    PaperTemplate.Grid, PaperTemplate.Dotted, PaperTemplate.Graph, PaperTemplate.Cornell,
    PaperTemplate.MusicStaff, PaperTemplate.PlannerDaily, PaperTemplate.PlannerWeekly,
)

/** Template picker for "Insert page" (PAGES-05): live previews of each template on the chosen paper color. */
@Composable
private fun InsertPageDialog(
    initial: Page?,
    onDismiss: () -> Unit,
    onFromPdf: (() -> Unit)? = null,
    onFromImage: (() -> Unit)? = null,
    onInsert: (PaperTemplate, PaperColor) -> Unit,
) {
    var template by remember { mutableStateOf(initial?.template ?: PaperTemplate.Ruled) }
    var color by remember { mutableStateOf(initial?.paperColor ?: PaperColor.White) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = BestNoteShapes.Sheet, color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            Column(Modifier.padding(Spacing.XL), verticalArrangement = Arrangement.spacedBy(Spacing.L)) {
                Text("Insert page", style = MaterialTheme.typography.titleLarge)
                if (onFromPdf != null || onFromImage != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                        onFromPdf?.let { OutlinedButton(onClick = it) { Text(stringResource(R.string.insert_from_pdf)) } }
                        onFromImage?.let { OutlinedButton(onClick = it) { Text(stringResource(R.string.insert_from_image)) } }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    pickerTemplates.forEach { t ->
                        TemplateTile(t, color, initial, selected = t == template) { template = t }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalAlignment = Alignment.CenterVertically) {
                    Text("Paper", style = MaterialTheme.typography.labelLarge, color = BestNoteTheme.colors.muted)
                    PaperColor.entries.forEach { c ->
                        val label = if (c == PaperColor.Dark) "Dark" else c.name
                        androidx.compose.material3.FilterChip(
                            selected = c == color,
                            onClick = { color = c },
                            label = { Text(label) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onInsert(template, color) }) { Text("Insert") }
                }
            }
        }
    }
}

@Composable
private fun TemplateTile(template: PaperTemplate, color: PaperColor, base: Page?, selected: Boolean, onClick: () -> Unit) {
    val preview by produceState<ImageBitmap?>(null, template, color) {
        val paper = (base ?: Page(app.bestnote.core.model.NotebookId("preview"), 0)).copy(template = template, paperColor = color)
        value = withContext(Dispatchers.Default) { renderPageBitmap(paper, emptyList(), THUMB_PX).asImageBitmap() }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(6.dp)
        Box(
            Modifier
                .size(width = 96.dp, height = 124.dp)
                .softShadow(Elevation.Low, shape)
                .clip(shape)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                .clickable(onClick = onClick)
                .semantics { this.selected = selected },
        ) {
            preview?.let { Image(it, template.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(
            template.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else BestNoteTheme.colors.muted,
            modifier = Modifier.padding(top = Spacing.XS),
        )
    }
}
