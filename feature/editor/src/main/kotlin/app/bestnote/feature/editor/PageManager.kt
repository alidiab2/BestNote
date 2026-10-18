package app.bestnote.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
private const val ADD_CELL_KEY = "add-page"

/** The gap between two thumbnails: it holds the "+" that inserts a page there. */
private val InsertGap = 36.dp

/**
 * The page manager (PAGES-03/04/05/10): every page as a thumbnail on the desk. Tap a page to open it,
 * long-press and drag to reorder (neighbours spring out of the way), tap "+" between two pages to
 * insert one there, and switch to Select to duplicate, rotate, move, export or delete several pages
 * at once. Every change is one step of the document's undo history, offered right away in a snackbar
 * and on the bar's Undo / Redo.
 *
 * It zooms out of the page being read ([ZOOM_FROM] x) and zooms back into the page you pick.
 * [onClose] receives the page to show, or null to stay where the reader was. [onExport] exports the
 * chosen pages (zero-based) with the export dialog.
 */
@Composable
internal fun PageManager(
    document: PagedDocument,
    currentPage: Int,
    onClose: (openPage: Int?) -> Unit,
    modifier: Modifier = Modifier,
    insert: PageInsertFlow? = null,
    onExport: ((List<Int>) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val reduce = reducedMotion()
    val snackbar = remember { SnackbarHostState() }
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = (currentPage - 1).coerceAtLeast(0))
    val selection = remember { mutableStateMapOf<String, Unit>() }
    var selecting by remember { mutableStateOf(false) }
    var rev by remember { mutableIntStateOf(0) }
    /** The gap a page is being inserted into: the page index it will get. */
    var insertingAt by remember { mutableStateOf<Int?>(null) }
    var moving by remember { mutableStateOf(false) }
    /** The "Paper" dialog for the selected pages is open (PAGES-11). */
    var repapering by remember { mutableStateOf(false) }
    val reorder = remember { ReorderState() }
    val progress = remember { Animatable(if (reduce) 1f else 0f) }
    val compact = with(LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() } < COMPACT_WIDTH_DP.dp
    val res = androidx.compose.ui.platform.LocalResources.current
    val undoLabel = stringResource(R.string.editor_undo)

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
    // Pages deleted (or undone away) drop out of the selection.
    LaunchedEffect(pages) { selection.keys.retainAll(pages.map { it.id }.toSet()) }
    EdgeScroller(grid, reorder)
    val shown: List<DocumentPage> = reorder.order?.let { order -> order.mapNotNull { id -> pages.firstOrNull { it.id == id } } } ?: pages
    val selectedIndices = PageOps.indicesOf(pages.map { it.id }, selection.keys)

    fun close(open: Int?) {
        scope.launch {
            if (!reduce) progress.animateTo(0f, Motion.Spring.gentle())
            onClose(open)
        }
    }

    fun say(text: String, undoable: Boolean = true) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(text, actionLabel = if (undoable) undoLabel else null)
            if (result == SnackbarResult.ActionPerformed) document.undo()
        }
    }

    fun select(indices: List<Int>) {
        selection.clear()
        indices.forEach { i -> document.pages.getOrNull(i)?.let { selection[it.id] = Unit } }
    }

    // Pages inserted from a PDF or image: report with the Undo snackbar, select and scroll to the first one.
    DisposableEffect(insert) {
        val previousNotify = insert?.notify
        val previousInserted = insert?.onInserted
        insert?.notify = { say(it) }
        insert?.onInserted = { first ->
            select(listOf(first))
            scope.launch { grid.animateScrollToItem(first) }
        }
        onDispose {
            if (insert != null) {
                insert.notify = previousNotify ?: { }
                insert.onInserted = previousInserted ?: { }
            }
        }
    }

    BackHandler {
        if (selecting) {
            selecting = false
            selection.clear()
        } else {
            close(null)
        }
    }

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
                selecting = selecting,
                allSelected = pages.isNotEmpty() && selectedIndices.size == pages.size,
                compact = compact,
                canUndo = document.canUndo,
                canRedo = document.canRedo,
                onClose = { close(null) },
                onUndo = { document.undo() },
                onRedo = { document.redo() },
                onSelect = { selecting = true },
                onDone = {
                    selecting = false
                    selection.clear()
                },
                onSelectAll = {
                    val all = PageOps.selectAll(pages.map { it.id }, selection.keys.toSet())
                    selection.clear()
                    all.forEach { selection[it] = Unit }
                },
                onInsert = { insertingAt = PageOps.insertIndex(currentPage.coerceIn(-1, pages.size - 1), pages.size) },
            )
            val minCell = if (compact) 128.dp else 168.dp
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = minCell + InsertGap),
                state = grid,
                // Each cell carries its own trailing gap (the "+"), so the end padding is a gap shorter.
                contentPadding = PaddingValues(start = Spacing.XL, end = Spacing.S, top = Spacing.L, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.L),
                modifier = Modifier
                    .weight(1f)
                    .onSizeChanged { reorder.viewport = it }
                    .reorderGestures(grid, reorder, shown, scope) { from, to ->
                        document.movePage(from, to)
                        say(res.getQuantityString(R.plurals.pages_moved, 1, 1))
                    }
                    .semantics { contentDescription = res.getString(R.string.editor_pages) },
            ) {
                items(shown, key = { it.id }) { page ->
                    val index = pages.indexOf(page)
                    ThumbnailCell(
                        page = page,
                        backgrounds = document.backgrounds,
                        number = index + 1,
                        current = index == currentPage,
                        selected = page.id in selection,
                        selecting = selecting,
                        reorder = reorder,
                        grid = grid,
                        onToggle = { if (page.id in selection) selection.remove(page.id) else selection[page.id] = Unit },
                        onOpen = { close(index) },
                        onInsertAfter = { insertingAt = PageOps.insertIndex(index, pages.size) },
                        modifier = if (reorder.dragId == page.id) Modifier else Modifier.animateItem(placementSpec = Motion.Spring.offsetGentle()),
                    )
                }
                item(key = ADD_CELL_KEY, span = { GridItemSpan(1) }) {
                    AddPageCell(Modifier.animateItem(placementSpec = Motion.Spring.offsetGentle())) { insertingAt = pages.size }
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = Spacing.L),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
            SnackbarHost(snackbar) { data ->
                androidx.compose.material3.Snackbar(
                    data,
                    shape = BestNoteShapes.Pill,
                    containerColor = BestNoteTheme.colors.strongButton,
                    contentColor = BestNoteTheme.colors.onStrongButton,
                    actionColor = BestNoteTheme.colors.aiAccent,
                )
            }
            AnimatedVisibility(selecting, enter = Motion.Enter.sheet(reduce), exit = Motion.Exit.sheet(reduce)) {
                SelectionBar(
                    enabled = selectedIndices.isNotEmpty(),
                    compact = compact,
                    canExport = onExport != null,
                    onDuplicate = {
                        val n = selectedIndices.size
                        select(document.duplicatePages(selectedIndices))
                        say(res.getQuantityString(R.plurals.pages_duplicated, n, n))
                    },
                    onRotate = {
                        val n = selectedIndices.size
                        document.rotatePages(selectedIndices)
                        say(res.getQuantityString(R.plurals.pages_rotated, n, n))
                    },
                    onPaper = { repapering = true },
                    onMove = { moving = true },
                    onExport = { onExport?.invoke(selectedIndices) },
                    onDelete = {
                        val removed = document.deletePages(selectedIndices)
                        selection.clear()
                        say(res.getQuantityString(R.plurals.pages_deleted, removed, removed))
                    },
                )
            }
            if (!selecting) {
                Text(
                    stringResource(R.string.pages_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = BestNoteTheme.colors.muted,
                    modifier = Modifier
                        .padding(horizontal = Spacing.XL)
                        .chromeSurface(alpha = HINT_ALPHA)
                        .padding(horizontal = Spacing.L, vertical = Spacing.S),
                )
            }
        }
    }

    insertingAt?.let { at ->
        val base = (pages.getOrNull(at - 1) ?: pages.getOrNull(at) ?: pages.lastOrNull())?.page
        InsertPageDialog(
            initial = document.newPagePaper ?: base,
            afterPage = at,
            onDismiss = { insertingAt = null },
            onFromPdf = insert?.let { flow -> { insertingAt = null; flow.startPdf((at - 1).coerceAtLeast(0)) } },
            onFromImage = insert?.let { flow -> { insertingAt = null; flow.startImage((at - 1).coerceAtLeast(0)) } },
        ) { template, color ->
            insertingAt = null
            val paper = (base ?: document.blankPage ?: return@InsertPageDialog).copy(template = template, paperColor = color)
            val index = document.insertPage(at, paper)
            select(listOf(index))
            scope.launch { grid.animateScrollToItem(index) }
            say(res.getString(R.string.pages_added))
        }
    }

    if (repapering) {
        val picked = selectedIndices
        PaperForPagesDialog(
            initial = picked.firstOrNull()?.let { pages.getOrNull(it)?.page },
            count = picked.size,
            onDismiss = { repapering = false },
        ) { template, color ->
            repapering = false
            val changed = PageOps.applyToPages(document, picked) { it.copy(template = template, paperColor = color) }
            say(res.getQuantityString(R.plurals.pages_repapered, changed.size, changed.size))
        }
    }

    if (moving) {
        MoveDialog(
            count = pages.size,
            selected = selectedIndices,
            onDismiss = { moving = false },
        ) { afterPage ->
            moving = false
            val order = PageOps.moveBlock(pages.size, selectedIndices, afterPage)
            val moves = PageOps.movesFor(order)
            if (moves.isNotEmpty()) {
                document.asOneStep { moves.forEach { (from, to) -> document.movePage(from, to) } }
                val n = selectedIndices.size
                say(res.getQuantityString(R.plurals.pages_moved, n, n))
                val first = order.indexOf(selectedIndices.first())
                scope.launch { grid.animateScrollToItem(first.coerceAtLeast(0)) }
            }
        }
    }
}

private const val COMPACT_WIDTH_DP = 600

/** Beyond this many pages the Move slider is continuous (ticks would be a dotted blur). */
private const val MAX_SLIDER_TICKS = 12
private const val HINT_ALPHA = 0.92f

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
            if (target != null && target.key != id && order != null && target.key in order) {
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
    onInsertAfter: () -> Unit,
    modifier: Modifier,
) {
    val id = page.id
    val lifting = reorder.dragId == id
    val colors = BestNoteTheme.colors
    val primary = MaterialTheme.colorScheme.primary
    val openLabel = stringResource(R.string.pages_open, number)
    val selectLabel = stringResource(R.string.pages_select_page, number)
    val pageLabel = stringResource(R.string.pages_page, number)
    val currentLabel = stringResource(R.string.pages_current)
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
            },
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.weight(1f).aspectRatio(CELL_ASPECT), contentAlignment = Alignment.Center) {
                val shape = RoundedCornerShape(6.dp)
                val thumb by rememberThumbnail(page, backgrounds)
                val aspect = page.page.widthPt / page.page.heightPt
                val ring = when {
                    selected -> BorderStroke(3.dp, primary)
                    current -> BorderStroke(2.dp, primary.copy(alpha = CURRENT_RING_ALPHA))
                    else -> null
                }
                Box(
                    Modifier
                        .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < CELL_ASPECT)
                        .softShadow(if (lifting) Elevation.High else Elevation.Low, shape)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(onClickLabel = if (selecting) selectLabel else openLabel) { if (selecting) onToggle() else onOpen() }
                        .semantics {
                            role = Role.Button
                            this.selected = selected
                            contentDescription = if (current) "$pageLabel, $currentLabel" else pageLabel
                        },
                ) {
                    thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    if (ring != null) Box(Modifier.matchParentSize().border(ring, shape))
                    if (selecting) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(Spacing.S)
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(if (selected) primary else Color.White.copy(alpha = CHECK_BACKDROP_ALPHA))
                                .border(1.5.dp, if (selected) primary else Color.Black.copy(alpha = CHECK_RING_ALPHA), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(AtelierIcons.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            // The gap after this page: "+" inserts a page right here (hidden while selecting or dragging).
            Box(Modifier.width(InsertGap).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (!selecting && reorder.dragId == null) InsertGapButton(number, onInsertAfter)
            }
        }
        Box(Modifier.fillMaxWidth().padding(end = InsertGap, top = Spacing.S), contentAlignment = Alignment.Center) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium),
                color = if (current) MaterialTheme.colorScheme.onPrimary else colors.muted,
                modifier = Modifier
                    .clip(BestNoteShapes.Pill)
                    .background(if (current) primary else Color.Transparent)
                    .padding(horizontal = Spacing.S + 2.dp, vertical = 2.dp),
            )
        }
    }
}

private const val CURRENT_RING_ALPHA = 0.55f
private const val GAP_BUTTON_ALPHA = 0.9f
// An empty ring that reads on white, cream and dark paper alike: a light disc with a soft dark edge.
private const val CHECK_BACKDROP_ALPHA = 0.85f
private const val CHECK_RING_ALPHA = 0.35f

@Composable
private fun InsertGapButton(afterPage: Int, onClick: () -> Unit) {
    val label = stringResource(R.string.pages_insert_after, afterPage)
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = GAP_BUTTON_ALPHA))
            .border(Dp.Hairline, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(AtelierIcons.Plus, null, tint = BestNoteTheme.colors.muted, modifier = Modifier.size(15.dp))
    }
}

/** The last cell: a dashed page outline that adds a page at the end. */
@Composable
private fun AddPageCell(modifier: Modifier, onClick: () -> Unit) {
    val label = stringResource(R.string.pages_add)
    val outline = BestNoteTheme.colors.muted
    Column(modifier.padding(end = InsertGap)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(CELL_ASPECT)
                .padding(horizontal = Spacing.S)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            Canvas(Modifier.matchParentSize()) {
                val dash = with(density) { 6.dp.toPx() }
                drawRoundRect(
                    color = outline.copy(alpha = DASH_ALPHA),
                    cornerRadius = CornerRadius(with(density) { 8.dp.toPx() }),
                    style = Stroke(width = with(density) { 1.5.dp.toPx() }, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) { Icon(AtelierIcons.Plus, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp)) }
                Text(label, style = MaterialTheme.typography.labelLarge, color = outline, modifier = Modifier.padding(top = Spacing.S))
            }
        }
    }
}

private const val DASH_ALPHA = 0.7f

@Composable
private fun TopBar(
    count: Int,
    selected: Int,
    selecting: Boolean,
    allSelected: Boolean,
    compact: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSelect: () -> Unit,
    onDone: () -> Unit,
    onSelectAll: () -> Unit,
    onInsert: () -> Unit,
) {
    val colors = BestNoteTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.M, vertical = Spacing.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Row(Modifier.chromeSurface().padding(horizontal = 2.dp)) {
            ChromeIconButton(AtelierIcons.Back, stringResource(R.string.pages_close), onClose, size = 44.dp)
        }
        Column(Modifier.weight(1f).padding(horizontal = Spacing.XS)) {
            Text(
                stringResource(R.string.editor_pages),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            Text(
                if (selecting) pluralStringResource(R.plurals.pages_selected, selected, selected) else pluralStringResource(R.plurals.pages_count, count, count),
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
                maxLines = 1,
            )
        }
        Row(Modifier.chromeSurface().padding(horizontal = 2.dp)) {
            ChromeIconButton(AtelierIcons.Undo, stringResource(R.string.editor_undo), onUndo, enabled = canUndo, size = 44.dp)
            ChromeIconButton(AtelierIcons.Redo, stringResource(R.string.editor_redo), onRedo, enabled = canRedo, size = 44.dp)
        }
        if (selecting) {
            PillButton(stringResource(if (allSelected) R.string.pages_deselect_all else R.string.pages_select_all), null, strong = false, onClick = onSelectAll)
            PillButton(stringResource(R.string.pages_done), AtelierIcons.Check, strong = true, onClick = onDone)
        } else {
            PillButton(stringResource(R.string.pages_select), AtelierIcons.Select, strong = false, iconOnly = false, onClick = onSelect)
            PillButton(stringResource(R.string.pages_insert), AtelierIcons.Plus, strong = true, iconOnly = compact, onClick = onInsert)
        }
    }
}

@Composable
private fun PillButton(label: String, icon: ImageVector?, strong: Boolean, iconOnly: Boolean = false, onClick: () -> Unit) {
    val colors = BestNoteTheme.colors
    Surface(
        onClick = onClick,
        shape = BestNoteShapes.Pill,
        color = if (strong) colors.strongButton else MaterialTheme.colorScheme.surface,
        contentColor = if (strong) colors.onStrongButton else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.softShadow(Elevation.Low, BestNoteShapes.Pill).semantics { contentDescription = label },
    ) {
        Row(
            Modifier.height(44.dp).padding(horizontal = if (iconOnly) Spacing.M else Spacing.L),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp))
            if (!iconOnly) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    modifier = Modifier.padding(start = if (icon != null) Spacing.S else 0.dp),
                )
            }
        }
    }
}

/** Select mode's floating bar: duplicate, rotate, move, export, delete the selected pages. */
@Composable
private fun SelectionBar(
    enabled: Boolean,
    compact: Boolean,
    canExport: Boolean,
    onDuplicate: () -> Unit,
    onRotate: () -> Unit,
    onPaper: () -> Unit,
    onMove: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .padding(horizontal = Spacing.L)
            .softShadow(Elevation.Medium, BestNoteShapes.ToolTray)
            .background(MaterialTheme.colorScheme.surface, BestNoteShapes.ToolTray)
            .padding(horizontal = Spacing.S, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BarAction(AtelierIcons.Duplicate, stringResource(R.string.pages_duplicate), enabled, compact, onClick = onDuplicate)
        BarAction(AtelierIcons.Rotate, stringResource(R.string.pages_rotate), enabled, compact, onClick = onRotate)
        BarAction(AtelierIcons.Paper, stringResource(R.string.pages_paper), enabled, compact, onClick = onPaper)
        BarAction(AtelierIcons.Move, stringResource(R.string.pages_move), enabled, compact, onClick = onMove)
        if (canExport) BarAction(AtelierIcons.Export, stringResource(R.string.pages_export), enabled, compact, onClick = onExport)
        Box(Modifier.padding(horizontal = Spacing.XS).size(1.dp, 28.dp).background(MaterialTheme.colorScheme.outlineVariant))
        BarAction(AtelierIcons.Trash, stringResource(R.string.pages_delete), enabled, compact, tint = MaterialTheme.colorScheme.error, onClick = onDelete)
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, enabled: Boolean, compact: Boolean, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    val color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    Column(
        Modifier
            .clip(BestNoteShapes.Control)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .widthIn(min = 64.dp)
            .padding(horizontal = Spacing.S, vertical = 6.dp)
            .graphicsLayer { alpha = if (enabled) 1f else DisabledAlpha },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        if (!compact) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** "Insert page" (PAGES-05/10): the paper sheet's live previews and colors, or a PDF / an image. */
@Composable
private fun InsertPageDialog(
    initial: Page?,
    afterPage: Int,
    onDismiss: () -> Unit,
    onFromPdf: (() -> Unit)? = null,
    onFromImage: (() -> Unit)? = null,
    onInsert: (PaperTemplate, PaperColor) -> Unit,
) {
    var template by remember { mutableStateOf(initial?.template ?: PaperTemplate.Ruled) }
    var color by remember { mutableStateOf(initial?.paperColor ?: PaperColor.White) }
    var group by remember { mutableStateOf(groupOf(template)) }
    val base = (initial ?: previewPage()).copy(paperColor = color, pdf = null)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = BestNoteShapes.Sheet, color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(Spacing.XL),
                verticalArrangement = Arrangement.spacedBy(Spacing.L),
            ) {
                Column {
                    Text(stringResource(R.string.pages_insert), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        if (afterPage <= 0) stringResource(R.string.pages_move_start) else stringResource(R.string.insert_after_page, afterPage),
                        style = BestNoteTheme.type.caption,
                        color = BestNoteTheme.colors.muted,
                    )
                }
                GroupTabs(group) { group = it }
                TemplateGrid(PageOps.groups.getValue(group), base.copy(template = template)) { template = it }
                SheetOverline(stringResource(R.string.paper_color))
                PaperColorChips(base) { color = it }
                if (onFromPdf != null || onFromImage != null) {
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column {
                        onFromPdf?.let { SheetAction(AtelierIcons.Pdf, stringResource(R.string.paper_insert_pdf), it) }
                        onFromImage?.let { SheetAction(AtelierIcons.Image, stringResource(R.string.paper_insert_image), it) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.insert_cancel)) }
                    Spacer(Modifier.width(Spacing.S))
                    PillButton(stringResource(R.string.insert_action), AtelierIcons.Plus, strong = true) { onInsert(template, color) }
                }
            }
        }
    }
}

/** "Paper" for the selected pages (PAGES-11): template and color, applied to all of them as one undo step. */
@Composable
private fun PaperForPagesDialog(initial: Page?, count: Int, onDismiss: () -> Unit, onApply: (PaperTemplate, PaperColor) -> Unit) {
    var template by remember { mutableStateOf(initial?.template ?: PaperTemplate.Ruled) }
    var color by remember { mutableStateOf(initial?.paperColor ?: PaperColor.White) }
    var group by remember { mutableStateOf(groupOf(template)) }
    val base = (initial ?: previewPage()).copy(paperColor = color, pdf = null)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = BestNoteShapes.Sheet, color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(Spacing.XL),
                verticalArrangement = Arrangement.spacedBy(Spacing.L),
            ) {
                Column {
                    Text(stringResource(R.string.editor_paper), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    Text(pluralStringResource(R.plurals.pages_paper_for, count, count), style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
                }
                GroupTabs(group) { group = it }
                TemplateGrid(PageOps.groups.getValue(group), base.copy(template = template)) { template = it }
                SheetOverline(stringResource(R.string.paper_color))
                PaperColorChips(base) { color = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.insert_cancel)) }
                    Spacer(Modifier.width(Spacing.S))
                    PillButton(stringResource(R.string.pages_paper_apply), AtelierIcons.Paper, strong = true) { onApply(template, color) }
                }
            }
        }
    }
}

/** "Move to…": where the selected pages go, as a block, in their current order. */
@Composable
private fun MoveDialog(count: Int, selected: List<Int>, onDismiss: () -> Unit, onMove: (afterPage: Int) -> Unit) {
    var after by remember { mutableFloatStateOf((selected.firstOrNull() ?: 0).toFloat()) }
    val n = selected.size
    val target = after.toInt().coerceIn(0, count)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = BestNoteShapes.Sheet, color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            Column(Modifier.widthIn(max = 420.dp).padding(Spacing.XL), verticalArrangement = Arrangement.spacedBy(Spacing.L)) {
                Text(pluralStringResource(R.plurals.pages_move_title, n, n), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                Text(
                    when (target) {
                        0 -> stringResource(R.string.pages_move_start)
                        count -> stringResource(R.string.pages_move_end)
                        else -> stringResource(R.string.pages_move_after, target)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChromeIconButton(AtelierIcons.Minus, stringResource(R.string.pages_move_start), { after = (target - 1).coerceAtLeast(0).toFloat() }, size = 40.dp)
                    Slider(
                        value = after,
                        onValueChange = { after = it },
                        valueRange = 0f..count.toFloat(),
                        steps = if (count <= MAX_SLIDER_TICKS) (count - 1).coerceAtLeast(0) else 0,
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                    )
                    ChromeIconButton(AtelierIcons.Plus, stringResource(R.string.pages_move_end), { after = (target + 1).coerceAtMost(count).toFloat() }, size = 40.dp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.insert_cancel)) }
                    Spacer(Modifier.width(Spacing.S))
                    PillButton(stringResource(R.string.pages_move_action), AtelierIcons.Move, strong = true) { onMove(target) }
                }
            }
        }
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
