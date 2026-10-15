package app.bestnote.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.model.NotebookId
import kotlin.math.roundToInt

/** What is being dragged: one notebook, or the whole selection when the dragged one is selected. */
internal data class DragPayload(val ids: List<NotebookId>, val title: String, val coverIndex: Int, val coverStyle: CoverStyle)

/**
 * In-app drag of notebooks onto sidebar destinations (folders, Library, Favorites, Trash). Positions
 * are in the coordinates of the library root, set with [Modifier.libraryDragRoot].
 */
@Stable
internal class LibraryDragState {
    var payload by mutableStateOf<DragPayload?>(null)
        private set
    var position by mutableStateOf(Offset.Zero)
        private set
    internal var root: LayoutCoordinates? = null
    private val targets = HashMap<LibraryDestination, Rect>()

    /** The destination under the pointer while dragging. */
    val hovered: LibraryDestination?
        get() = if (payload == null) null else targets.entries.firstOrNull { it.value.contains(position) }?.key

    fun start(value: DragPayload, at: Offset) {
        payload = value
        position = at
    }

    fun move(to: Offset) {
        position = to
    }

    /** Ends the drag. @return the drop destination, if any. */
    fun drop(): Pair<DragPayload, LibraryDestination>? {
        val result = payload?.let { p -> hovered?.let { p to it } }
        payload = null
        return result
    }

    fun cancel() {
        payload = null
    }

    fun register(destination: LibraryDestination, coordinates: LayoutCoordinates) {
        val r = root ?: return
        if (r.isAttached && coordinates.isAttached) targets[destination] = r.localBoundingBoxOf(coordinates)
    }

    fun unregister(destination: LibraryDestination) {
        targets.remove(destination)
    }

    fun toRoot(coordinates: LayoutCoordinates?, local: Offset): Offset {
        val r = root ?: return local
        return if (coordinates != null && coordinates.isAttached && r.isAttached) r.localPositionOf(coordinates, local) else local
    }
}

internal val LocalLibraryDrag = staticCompositionLocalOf { LibraryDragState() }

internal fun Modifier.libraryDragRoot(state: LibraryDragState): Modifier = onGloballyPositioned { state.root = it }

/** Marks a sidebar row as a drop destination. */
@Composable
internal fun Modifier.dropTarget(destination: LibraryDestination): Modifier {
    val drag = LocalLibraryDrag.current
    DisposableEffect(destination) { onDispose { drag.unregister(destination) } }
    return onGloballyPositioned { drag.register(destination, it) }
}

/**
 * Notebook gestures, the same for shelf, grid and list:
 * tap opens (or toggles the selection while selecting); long-press opens the action menu
 * ([onLongPress]); long-press then move lifts the notebook so it can be dropped on the sidebar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.notebookGestures(
    item: NotebookItem,
    selection: Set<NotebookId>,
    selecting: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDragLift: () -> Unit,
    onDrop: (DragPayload, LibraryDestination) -> Unit,
    longPressLabel: String,
): Modifier {
    val drag = LocalLibraryDrag.current
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val latestItem by rememberUpdatedState(item)
    val latestSelection by rememberUpdatedState(selection)
    val tap by rememberUpdatedState(onTap)
    val lift by rememberUpdatedState(onDragLift)
    val drop by rememberUpdatedState(onDrop)
    return this
        .onGloballyPositioned { coords[0] = it }
        .combinedClickable(role = Role.Button, onClick = { tap() }, onLongClickLabel = longPressLabel, onLongClick = onLongPress)
        // Declared after the clickable so it sees the gesture first and can consume the long-press release.
        .pointerInput(selecting) {
            var moved = false
            var travelled = Offset.Zero
            detectDragGesturesAfterLongPress(
                // The clickable's onLongClick (with its haptic) opens the menu; this only tracks the drag.
                onDragStart = {
                    moved = false
                    travelled = Offset.Zero
                },
                onDrag = { change, amount ->
                    travelled += amount
                    val at = drag.toRoot(coords[0], change.position)
                    if (!moved && travelled.getDistance() > viewConfiguration.touchSlop) {
                        moved = true
                        lift()
                        val it = latestItem
                        val ids = if (it.id in latestSelection) latestSelection.toList() else listOf(it.id)
                        drag.start(DragPayload(ids, it.title, it.coverIndex, it.coverStyle), at)
                    }
                    if (moved) drag.move(at)
                },
                onDragEnd = {
                    if (moved) drag.drop()?.let { (payload, dest) -> drop(payload, dest) }
                },
                onDragCancel = { if (moved) drag.cancel() },
            )
        }
}

/** The lifted cover following the pointer, with a count badge for several notebooks. */
@Composable
internal fun DragGhost(state: LibraryDragState) {
    val payload = state.payload ?: return
    val density = LocalDensity.current
    val half = with(density) { Offset(GhostWidth.toPx() / 2, GhostHeight.toPx() * GHOST_GRAB) }
    Box(
        Modifier
            .offset { (state.position - half).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
            .graphicsLayer {
                rotationZ = GHOST_TILT
                scaleX = GHOST_SCALE
                scaleY = GHOST_SCALE
            }
            .semantics { contentDescription = payload.title },
    ) {
        NotebookCover(payload.title, payload.coverIndex, Modifier.size(GhostWidth, GhostHeight), scale = GHOST_COVER_SCALE, style = payload.coverStyle)
        if (payload.ids.size > 1) {
            Text(
                payload.ids.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = Spacing.S, y = -Spacing.S)
                    .clip(BestNoteShapes.Pill)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = Spacing.S, vertical = Spacing.XXS),
            )
        }
    }
}

private val GhostWidth = 74.dp
private val GhostHeight = 100.dp
private const val GHOST_GRAB = 0.35f
private const val GHOST_TILT = -4f
private const val GHOST_SCALE = 1.04f
private const val GHOST_COVER_SCALE = 0.6f
