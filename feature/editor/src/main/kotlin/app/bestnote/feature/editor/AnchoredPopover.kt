package app.bestnote.feature.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.reducedMotion
import app.bestnote.core.designsystem.theme.softShadow

/** The popup window includes this much shadow room on every side of the card. */
private val PopoverInset = Spacing.XL

/** Caret: a soft triangle on the card edge that faces the anchor (DS-12). */
private val CaretWidth = 28.dp
private val CaretHeight = 13.dp

/** Air between the anchor and the caret's tip. */
private val CaretAir = 2.dp

/** Keeps the caret on the straight part of the edge, clear of the Card's 18 dp corners. */
private val CaretCornerClearance = 18.dp + CaretWidth / 2

/** Places the popover with [PopoverMath] and publishes where its caret goes. */
private class AnchoredPopoverPosition(
    private val side: TraySide,
    private val gap: Int,
    private val margin: Int,
    private val caretInset: Int,
    private val placed: MutableState<PopoverPlacement?>,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val p = PopoverMath.place(
            anchor = PxRect(anchorBounds.left, anchorBounds.top, anchorBounds.right, anchorBounds.bottom),
            windowWidth = windowSize.width,
            windowHeight = windowSize.height,
            width = popupContentSize.width,
            height = popupContentSize.height,
            side = side,
            gap = gap,
            margin = margin,
            caretInset = caretInset,
        )
        if (placed.value != p) placed.value = p
        return IntOffset(p.x, p.y)
    }
}

/**
 * A popover that points at what opened it (DS-04, DS-12): the tapped pen, the colour or width
 * button, a chrome button. It opens on the page side of [side] (below a top tray, above a bottom
 * one, beside a side one), flips when that side is too short, stays inside the window, and a caret
 * on its edge keeps pointing at the anchor. It grows from the caret with [Motion.Enter.popover],
 * dismisses on an outside tap with [Motion.Exit.popover], then calls [onDismissed].
 *
 * [content] receives `dismiss`, which closes the popover with the same exit motion.
 */
@Composable
internal fun AnchoredPopover(side: TraySide, onDismissed: () -> Unit, content: @Composable (dismiss: () -> Unit) -> Unit) {
    val reduce = reducedMotion()
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val density = LocalDensity.current
    val placed = remember { mutableStateOf<PopoverPlacement?>(null) }
    val position = remember(side, density) {
        with(density) {
            val inset = PopoverInset.roundToPx()
            AnchoredPopoverPosition(
                side = side,
                gap = (CaretHeight + CaretAir).roundToPx() - inset,
                margin = 12.dp.roundToPx() - inset,
                caretInset = inset + CaretCornerClearance.roundToPx(),
                placed = placed,
            )
        }
    }
    LaunchedEffect(visible.currentState, visible.isIdle) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) onDismissed()
    }
    val dismiss = { visible.targetState = false }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = dismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val placement = placed.value
        val inset = with(density) { PopoverInset.toPx() }
        val shape = remember(placement, density) { placement?.let { CaretCardShape(it.edge, it.caret - inset, density) } ?: BestNoteShapes.Card }
        AnimatedVisibility(visible, enter = Motion.Enter.popover(reduce, originOf(placement, side)), exit = Motion.Exit.popover(reduce, originOf(placement, side))) {
            // Never over the anchor: on a short side the card is capped and its content scrolls.
            val cap = placement?.maxHeight?.takeIf { it < Int.MAX_VALUE }?.let { with(density) { (it.toDp() - PopoverInset * 2).coerceAtLeast(120.dp) } }
            Box(
                Modifier
                    .padding(PopoverInset) // room for the shadow and the caret inside the popup window
                    .then(if (cap != null) Modifier.heightIn(max = cap) else Modifier)
                    .softShadow(Elevation.High, shape)
                    .background(MaterialTheme.colorScheme.surface, shape)
                    .clip(BestNoteShapes.Card),
            ) { content(dismiss) }
        }
    }
}

/** The popover grows out of its caret. */
private fun originOf(p: PopoverPlacement?, side: TraySide): TransformOrigin {
    if (p == null) {
        return when (side) {
            TraySide.Top -> TransformOrigin(0.5f, 0f)
            TraySide.Bottom -> TransformOrigin(0.5f, 1f)
            TraySide.Left -> TransformOrigin(0f, 0.5f)
            TraySide.Right -> TransformOrigin(1f, 0.5f)
        }
    }
    // The caret offset is in popup px; without the size here, a fraction along the edge is close enough.
    return when (p.edge) {
        CaretEdge.Top -> TransformOrigin(0.5f, 0f)
        CaretEdge.Bottom -> TransformOrigin(0.5f, 1f)
        CaretEdge.Left -> TransformOrigin(0f, 0.5f)
        CaretEdge.Right -> TransformOrigin(1f, 0.5f)
    }
}

/**
 * The Card's rounded rectangle plus a caret just outside the [edge], centered [caret] px along it
 * (card coordinates). Used for the shadow and the fill, so both include the caret.
 */
private class CaretCardShape(private val edge: CaretEdge, private val caret: Float, private val density: Density) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val d = this.density
        val r = with(d) { 18.dp.toPx() }
        val half = with(d) { CaretWidth.toPx() } / 2f
        val h = with(d) { CaretHeight.toPx() }
        val body = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))) }
        val along = when (edge) {
            CaretEdge.Top, CaretEdge.Bottom -> caret.coerceIn(r + half, (size.width - r - half).coerceAtLeast(r + half))
            CaretEdge.Left, CaretEdge.Right -> caret.coerceIn(r + half, (size.height - r - half).coerceAtLeast(r + half))
        }
        // A triangle with a slightly rounded tip: two quads meeting at the point.
        val tip = Path().apply {
            when (edge) {
                CaretEdge.Top -> {
                    moveTo(along - half, 1f)
                    quadraticTo(along - half * 0.35f, 1f, along - 2f, -h + 2f)
                    quadraticTo(along, -h - 0.5f, along + 2f, -h + 2f)
                    quadraticTo(along + half * 0.35f, 1f, along + half, 1f)
                }
                CaretEdge.Bottom -> {
                    val y = size.height
                    moveTo(along - half, y - 1f)
                    quadraticTo(along - half * 0.35f, y - 1f, along - 2f, y + h - 2f)
                    quadraticTo(along, y + h + 0.5f, along + 2f, y + h - 2f)
                    quadraticTo(along + half * 0.35f, y - 1f, along + half, y - 1f)
                }
                CaretEdge.Left -> {
                    moveTo(1f, along - half)
                    quadraticTo(1f, along - half * 0.35f, -h + 2f, along - 2f)
                    quadraticTo(-h - 0.5f, along, -h + 2f, along + 2f)
                    quadraticTo(1f, along + half * 0.35f, 1f, along + half)
                }
                CaretEdge.Right -> {
                    val x = size.width
                    moveTo(x - 1f, along - half)
                    quadraticTo(x - 1f, along - half * 0.35f, x + h - 2f, along - 2f)
                    quadraticTo(x + h + 0.5f, along, x + h - 2f, along + 2f)
                    quadraticTo(x - 1f, along + half * 0.35f, x - 1f, along + half)
                }
            }
            close()
        }
        return Outline.Generic(Path().apply { op(body, tip, PathOperation.Union) })
    }

    override fun equals(other: Any?) = other is CaretCardShape && other.edge == edge && other.caret == caret

    override fun hashCode() = edge.hashCode() * 31 + caret.hashCode()
}
