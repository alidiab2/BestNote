package app.bestnote.feature.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.reducedMotion
import app.bestnote.core.model.ImageElement
import app.bestnote.core.model.TextElement
import app.bestnote.engine.canvas.document.PagedDocumentView
import kotlin.math.roundToInt

/** What the selection menu can do (CANVAS-10, CANVAS-11). */
internal class SelectionActions(
    val onColor: (Int) -> Unit,
    val onThinner: () -> Unit,
    val onThicker: () -> Unit,
    val onCut: () -> Unit,
    val onCopy: () -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
    val onPaste: () -> Unit,
    val onEditText: () -> Unit = {},
    val onCrop: () -> Unit = {},
    val onRotate: () -> Unit = {},
    val onToggleLock: () -> Unit = {},
    val onConvert: () -> Unit = {},
)

/** Thicker/thinner step for a selection's ink. */
internal const val THICKEN_STEP = 1.25f

private val MenuShape = RoundedCornerShape(20.dp)
private val AboveGap = 48.dp // clears the rotate knob
private val BelowGap = 16.dp
private val EdgeMargin = 12.dp

/**
 * The lasso's small floating menu: recolor dots, thinner/thicker, then Cut, Copy, Duplicate and
 * Delete; or a lone Paste pill where the lasso tapped. It sits above the selection (below when there
 * is no room), in root coordinates ([origin] is the page view's top-left). The view hides it the
 * moment the pen touches down, so it never animates under the pen; it pops in after the pen lifts.
 */
@Composable
internal fun SelectionMenu(
    menu: PagedDocumentView.SelectionMenu,
    origin: Offset,
    colors: List<Int>,
    actions: SelectionActions,
) {
    val reduce = reducedMotion()
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visibleState = shown,
            enter = Motion.Enter.popover(reduce),
            exit = ExitTransition.None,
            modifier = Modifier.anchoredTo(menu, origin),
        ) {
            Row(
                Modifier
                    .chromeSurface(MenuShape)
                    .padding(horizontal = Spacing.S, vertical = Spacing.XS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (menu.count == 0) {
                    MenuText(stringResource(R.string.selection_paste), onClick = actions.onPaste)
                    return@Row
                }
                val element = menu.element
                if (menu.strokes > 0 || menu.texts > 0) {
                    colors.forEach { c -> RecolorDot(Color(c), onClick = { actions.onColor(c) }) }
                    MenuDivider()
                }
                if (menu.strokes > 0) {
                    ChromeIconButton(AtelierIcons.Minus, stringResource(R.string.selection_thinner), actions.onThinner, size = 36.dp)
                    ChromeIconButton(AtelierIcons.Plus, stringResource(R.string.selection_thicker), actions.onThicker, size = 36.dp)
                    MenuDivider()
                }
                if (menu.strokes > 0) {
                    MenuText(stringResource(R.string.selection_convert), onClick = actions.onConvert)
                    MenuDivider()
                }
                if (element is TextElement) MenuText(stringResource(R.string.selection_edit), onClick = actions.onEditText)
                if (element is ImageElement) {
                    MenuText(stringResource(R.string.image_crop), onClick = actions.onCrop)
                    MenuText(stringResource(R.string.image_rotate), onClick = actions.onRotate)
                }
                if (element != null) {
                    MenuText(
                        stringResource(if (element.locked) R.string.selection_unlock else R.string.selection_lock),
                        onClick = actions.onToggleLock,
                    )
                    MenuDivider()
                }
                MenuText(stringResource(R.string.selection_cut), onClick = actions.onCut)
                MenuText(stringResource(R.string.selection_copy), onClick = actions.onCopy)
                MenuText(stringResource(R.string.selection_duplicate), onClick = actions.onDuplicate)
                MenuText(stringResource(R.string.selection_delete), color = MaterialTheme.colorScheme.error, onClick = actions.onDelete)
            }
        }
    }
}

/** Centers the child over the selection, above it when it fits, clamped inside the parent. */
private fun Modifier.anchoredTo(menu: PagedDocumentView.SelectionMenu, origin: Offset): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val margin = EdgeMargin.roundToPx()
    val cx = origin.x + (menu.left + menu.right) / 2
    val above = origin.y + menu.top - AboveGap.toPx() - placeable.height
    val below = origin.y + menu.bottom + BelowGap.toPx()
    val y = if (above >= margin || menu.count == 0) above.coerceAtLeast(margin.toFloat()) else below
    val maxX = (constraints.maxWidth - placeable.width - margin).coerceAtLeast(margin)
    val maxY = (constraints.maxHeight - placeable.height - margin).coerceAtLeast(margin)
    val x = (cx - placeable.width / 2f).roundToInt().coerceIn(margin, maxX)
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(x, y.roundToInt().coerceIn(margin, maxY))
    }
}

@Composable
private fun RecolorDot(color: Color, onClick: () -> Unit) {
    val hex = "#%06X".format(color.value.toLong().ushr(32).toInt() and 0xFFFFFF)
    val label = stringResource(R.string.selection_recolor, hex)
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(color).lowContrastRing(color))
    }
}

@Composable
private fun MenuText(text: String, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.S + 2.dp, vertical = Spacing.S),
    )
}

@Composable
private fun MenuDivider(height: Dp = 24.dp) {
    Box(Modifier.padding(horizontal = 4.dp).size(1.dp, height).background(MaterialTheme.colorScheme.outlineVariant))
}
