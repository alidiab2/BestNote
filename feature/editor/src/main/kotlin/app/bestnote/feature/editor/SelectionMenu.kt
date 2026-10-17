package app.bestnote.feature.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.bestnote.core.model.EquationElement
import app.bestnote.core.model.ImageElement
import app.bestnote.core.model.TextElement
import app.bestnote.engine.canvas.document.PagedDocumentView
import app.bestnote.engine.canvas.select.PenType
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
    /** MATH-01: read the selected handwriting as an equation; edit / copy a selected equation. */
    val onConvertEquation: () -> Unit = {},
    val onEditEquation: () -> Unit = {},
    val onCopyLatex: () -> Unit = {},
    val onTidy: () -> Unit = {},
    /** INK-11: fill closed shapes with this ARGB (alpha = opacity); null clears the fill. */
    val onFill: (Int?) -> Unit = {},
    /** CANVAS-10: draw the selected strokes with another pen. */
    val onPen: (PenType) -> Unit = {},
    /** CANVAS-14: group / ungroup the selection, and restack it. */
    val onGroup: () -> Unit = {},
    val onUngroup: () -> Unit = {},
    val onToFront: () -> Unit = {},
    val onToBack: () -> Unit = {},
)

private enum class MenuPanel { Main, Fill, Pen, Arrange }

/** Opacity steps the Fill panel offers (INK-11). */
private val FILL_OPACITIES = listOf(0.25f, 0.5f, 1f)

/** [rgb] with [opacity] as its alpha, packed ARGB. */
internal fun fillColor(rgb: Int, opacity: Float): Int = ((opacity * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)

private fun PenType.label(): Int = when (this) {
    PenType.Fountain -> R.string.pen_fountain
    PenType.Ballpoint -> R.string.pen_ballpoint
    PenType.Brush -> R.string.pen_brush
    PenType.Pencil -> R.string.pen_pencil
    PenType.Highlighter -> R.string.pen_highlighter
}

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
    chromeBottomPx: Float = 0f,
    bottomChromeTopPx: Float = 0f,
) {
    val reduce = reducedMotion()
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    var panel by remember { mutableStateOf(MenuPanel.Main) }
    var opacity by remember { mutableStateOf(FILL_OPACITIES[1]) }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visibleState = shown,
            enter = Motion.Enter.popover(reduce),
            exit = ExitTransition.None,
            modifier = Modifier.anchoredTo(menu, origin, chromeBottomPx, bottomChromeTopPx),
        ) {
            Row(
                Modifier
                    .chromeSurface(MenuShape)
                    .horizontalScroll(rememberScrollState()) // a phone is narrower than the full row
                    .padding(horizontal = Spacing.S, vertical = Spacing.XS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (menu.count == 0) {
                    MenuText(stringResource(R.string.selection_paste), onClick = actions.onPaste)
                    return@Row
                }
                if (panel == MenuPanel.Fill) {
                    MenuText(stringResource(R.string.selection_back), onClick = { panel = MenuPanel.Main })
                    colors.forEach { c ->
                        RecolorDot(Color(c), label = R.string.selection_fill_with, onClick = { actions.onFill(fillColor(c, opacity)) })
                    }
                    MenuDivider()
                    FILL_OPACITIES.forEach { o ->
                        MenuText(
                            "${(o * 100).roundToInt()}%",
                            color = if (o == opacity) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            onClick = { opacity = o },
                        )
                    }
                    if (menu.filled > 0) {
                        MenuDivider()
                        MenuText(stringResource(R.string.selection_fill_none), onClick = { actions.onFill(null) })
                    }
                    return@Row
                }
                if (panel == MenuPanel.Pen) {
                    MenuText(stringResource(R.string.selection_back), onClick = { panel = MenuPanel.Main })
                    PenType.entries.forEach { type -> MenuText(stringResource(type.label()), onClick = { actions.onPen(type) }) }
                    return@Row
                }
                if (panel == MenuPanel.Arrange) {
                    MenuText(stringResource(R.string.selection_back), onClick = { panel = MenuPanel.Main })
                    MenuText(stringResource(R.string.selection_to_front), onClick = actions.onToFront)
                    MenuText(stringResource(R.string.selection_to_back), onClick = actions.onToBack)
                    MenuDivider()
                    if (menu.canGroup) MenuText(stringResource(R.string.selection_group), onClick = actions.onGroup)
                    if (menu.grouped) MenuText(stringResource(R.string.selection_ungroup), onClick = actions.onUngroup)
                    return@Row
                }
                val element = menu.element
                if (menu.onlyLocked && element != null) {
                    // A locked picture can only be unlocked (or copied): it ignores everything else until then.
                    MenuText(stringResource(R.string.selection_unlock), onClick = actions.onToggleLock)
                    MenuText(stringResource(R.string.selection_copy), onClick = actions.onCopy)
                    return@Row
                }
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
                    MenuText(stringResource(R.string.selection_pen), onClick = { panel = MenuPanel.Pen })
                    if (menu.fillable > 0) MenuText(stringResource(R.string.selection_fill), onClick = { panel = MenuPanel.Fill })
                    MenuDivider()
                    MenuText(stringResource(R.string.selection_tidy), onClick = actions.onTidy)
                    MenuText(stringResource(R.string.selection_convert), onClick = actions.onConvert)
                    MenuText(stringResource(R.string.selection_convert_equation), onClick = actions.onConvertEquation)
                    MenuDivider()
                }
                if (element is TextElement) MenuText(stringResource(R.string.selection_edit), onClick = actions.onEditText)
                if (element is EquationElement) {
                    MenuText(stringResource(R.string.selection_edit), onClick = actions.onEditEquation)
                    MenuText(stringResource(R.string.selection_copy_latex), onClick = actions.onCopyLatex)
                }
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
                MenuText(stringResource(R.string.selection_arrange), onClick = { panel = MenuPanel.Arrange })
                MenuDivider()
                MenuText(stringResource(R.string.selection_cut), onClick = actions.onCut)
                MenuText(stringResource(R.string.selection_copy), onClick = actions.onCopy)
                MenuText(stringResource(R.string.selection_duplicate), onClick = actions.onDuplicate)
                MenuText(stringResource(R.string.selection_delete), color = MaterialTheme.colorScheme.error, onClick = actions.onDelete)
            }
        }
    }
}

/**
 * Where the lasso menu's top edge goes (px). It prefers the spot above the selection, but never
 * under the floating chrome ([chromeBottom]: title row and pen tray) or the bottom chrome
 * ([chromeTop] of the compact bottom tray): when "above" would cover them it flips below the
 * selection, and when neither fits it is pinned inside the free band (over the selection, never
 * over the tray). Pure so it can be tested (DS-05).
 */
internal fun selectionMenuY(
    selTop: Float,
    selBottom: Float,
    menuHeight: Float,
    aboveGap: Float,
    belowGap: Float,
    margin: Float,
    chromeBottom: Float,
    containerHeight: Float,
    bottomChromeTop: Float = containerHeight,
): Float {
    val minY = maxOf(margin, chromeBottom)
    val maxY = (minOf(containerHeight - margin, bottomChromeTop) - menuHeight).coerceAtLeast(minY)
    val above = selTop - aboveGap - menuHeight
    val below = selBottom + belowGap
    val y = when {
        above >= minY -> above
        below <= maxY -> below
        else -> maxY
    }
    return y.coerceIn(minY, maxY)
}

/** Centers the child over the selection, above it when it fits (else below), clamped inside the free band. */
private fun Modifier.anchoredTo(
    menu: PagedDocumentView.SelectionMenu,
    origin: Offset,
    chromeBottomPx: Float,
    bottomChromeTopPx: Float,
): Modifier = layout { measurable, constraints ->
    val margin = EdgeMargin.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (constraints.maxWidth - margin * 2).coerceAtLeast(0)),
    )
    val cx = origin.x + (menu.left + menu.right) / 2
    val y = selectionMenuY(
        selTop = origin.y + menu.top,
        selBottom = origin.y + menu.bottom,
        menuHeight = placeable.height.toFloat(),
        aboveGap = AboveGap.toPx(),
        belowGap = BelowGap.toPx(),
        margin = margin.toFloat(),
        chromeBottom = chromeBottomPx,
        containerHeight = constraints.maxHeight.toFloat(),
        bottomChromeTop = bottomChromeTopPx.takeIf { it > 0f } ?: constraints.maxHeight.toFloat(),
    )
    val maxX = (constraints.maxWidth - placeable.width - margin).coerceAtLeast(margin)
    val x = (cx - placeable.width / 2f).roundToInt().coerceIn(margin, maxX)
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(x, y.roundToInt())
    }
}

@Composable
private fun RecolorDot(color: Color, label: Int = R.string.selection_recolor, onClick: () -> Unit) {
    val hex = "#%06X".format(color.value.toLong().ushr(32).toInt() and 0xFFFFFF)
    val label = stringResource(label, hex)
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
