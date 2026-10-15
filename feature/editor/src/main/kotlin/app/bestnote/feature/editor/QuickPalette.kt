package app.bestnote.feature.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.reducedMotion
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.feature.editor.PenArt.drawPen
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val PaletteRadius = 124.dp
private val OuterRing = 92.dp
private val InnerRing = 50.dp
private val TipGap = 18.dp
private val EdgeMargin = 12.dp
private const val OUTER_SLOTS = 8
private const val ENTER_SCALE = 0.25f

/** Outer ring, clockwise from the top: every pen, the lasso, then "all settings". */
private sealed interface RingItem {
    data class Pen(val kind: PenKind) : RingItem
    data object Lasso : RingItem
    data object More : RingItem
}

private val RingItems: List<RingItem> = PenKind.entries.map { RingItem.Pen(it) } + RingItem.Lasso + RingItem.More

/**
 * S Pen quick palette (side button clicked while hovering). Springs out of the pen tip at [hover]
 * (root px) to a spot that never covers the tip and flips at screen edges ([QuickPaletteGeometry]).
 * Any touch outside it dismisses it without drawing.
 */
@Composable
internal fun QuickPalette(
    hover: Offset,
    leftHanded: Boolean,
    toolbox: Toolbox,
    onToolbox: (Toolbox) -> Unit,
    onPen: (PenKind) -> Unit,
    onLasso: () -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val reduce = reducedMotion()
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { progress.animateTo(1f, if (reduce) Motion.fade() else Motion.Spring.snappy()) }
    fun close(then: () -> Unit = {}) {
        scope.launch {
            progress.animateTo(0f, Motion.tween(Motion.Duration.Short))
            onDismiss()
            then()
        }
    }
    val title = stringResource(R.string.quick_palette)
    val rim = penRim()
    val area = remember { mutableIntSize() }
    val size = area.value
    val r = with(density) { PaletteRadius.toPx() }
    val center = with(density) {
        QuickPaletteGeometry.place(
            QuickPaletteGeometry.Point(hover.x, hover.y),
            radius = r,
            gap = TipGap.toPx(),
            margin = EdgeMargin.toPx(),
            width = size.width.toFloat(),
            height = size.height.toFloat(),
            leftHanded = leftHanded,
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { area.value = it }
            .semantics { paneTitle = title }
            .pointerInput(center) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val down = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                        val outside = QuickPaletteGeometry.covers(center, r, QuickPaletteGeometry.Point(down.position.x, down.position.y)).not()
                        if (outside) {
                            event.changes.forEach { it.consume() }
                            close()
                        }
                    }
                }
            },
    ) {
        if (size == IntSize.Zero) return@Box
        Box(
            Modifier
                .absoluteOffset { IntOffset((center.x - r).roundToInt(), (center.y - r).roundToInt()) }
                .size(PaletteRadius * 2)
                .graphicsLayer {
                    val p = progress.value
                    alpha = p.coerceIn(0f, 1f)
                    if (!reduce) {
                        val s = ENTER_SCALE + (1f - ENTER_SCALE) * p
                        scaleX = s
                        scaleY = s
                        translationX = (hover.x - center.x) * (1f - p)
                        translationY = (hover.y - center.y) * (1f - p)
                    }
                }
                .softShadow(Elevation.High, CircleShape)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
        ) {
            // Faint guide ring between the two rings.
            val guide = MaterialTheme.colorScheme.outlineVariant
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(guide, (OuterRing.toPx() + InnerRing.toPx()) / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
            RingItems.forEachIndexed { i, item ->
                val angle = -PI / 2 + i * 2 * PI / OUTER_SLOTS
                Polar(angle, OuterRing) {
                    when (item) {
                        is RingItem.Pen -> {
                            val kind = item.kind
                            RingButton(penName(kind), selected = kind == toolbox.active, onClick = { onPen(kind); close() }) {
                                Box(Modifier.size(44.dp).clipToBounds(), contentAlignment = Alignment.Center) {
                                    Canvas(Modifier.requiredSize(14.dp, 42.dp).graphicsLayer { rotationZ = 35f }) {
                                        drawPen(kind, Color(toolbox.settingsOf(kind).colorArgb), rim)
                                    }
                                }
                            }
                        }
                        RingItem.Lasso -> {
                            val label = stringResource(R.string.tool_lasso)
                            RingButton(label, selected = false, onClick = { close(onLasso) }) {
                                Icon(AtelierIcons.Lasso, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                            }
                        }
                        RingItem.More -> {
                            val label = stringResource(R.string.quick_palette_all)
                            RingButton(label, selected = false, onClick = { close(onMore) }) {
                                Box(Modifier.size(24.dp).clip(CircleShape).background(ConicBrush), contentAlignment = Alignment.Center) {
                                    Icon(AtelierIcons.More, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
            val inks = toolbox.active.inks
            toolbox.quickColors().forEachIndexed { i, c ->
                val angle = -PI / 2 + i * 2 * PI / QUICK_DOTS
                Polar(angle, InnerRing) {
                    val selected = inks && c == toolbox.current.colorArgb
                    RingButton(stringResource(R.string.pen_color, "#%06X".format(c and 0xFFFFFF)), selected = selected, enabled = inks, size = 34.dp, onClick = {
                        onToolbox(toolbox.withColor(c))
                        close()
                    }) {
                        Box(Modifier.size(if (selected) 24.dp else 20.dp).clip(CircleShape).background(Color(c)).lowContrastRing(Color(c)))
                    }
                }
            }
            SizeCenter(toolbox, enabled = inks) { onToolbox(toolbox.cycleSize()) }
        }
    }
}

private const val QUICK_DOTS = 5

private fun mutableIntSize() = androidx.compose.runtime.mutableStateOf(IntSize.Zero)

/** Places [content] centered at polar ([angle], [radius]) from the parent's center (physical, not mirrored). */
@Composable
private fun Polar(angle: Double, radius: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .absoluteOffset { IntOffset((radius.toPx() * cos(angle)).roundToInt(), (radius.toPx() * sin(angle)).roundToInt()) },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun RingButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val grow by animateFloatAsState(if (hovered) HOVER_SCALE else 1f, Motion.Spring.Snappy, label = "ringHover")
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .scale(grow)
            .size(size)
            .clip(CircleShape)
            .background(if (hovered) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
            .border(if (selected) 2.dp else 0.dp, if (selected) ring else Color.Transparent, CircleShape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .alpha(if (enabled) 1f else DisabledAlpha),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private const val HOVER_SCALE = 1.14f

/** Center: the current width (mm + a dot that size). Tap cycles through the preset sizes. */
@Composable
private fun SizeCenter(toolbox: Toolbox, enabled: Boolean, onCycle: () -> Unit) {
    val dpi = LocalConfiguration.current.densityDpi
    val mm = PenDefaults.formatMm(PenDefaults.widthMm(toolbox.active, toolbox.current.size, dpi))
    val label = stringResource(R.string.quick_palette_size, mm)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onCycle)
                .semantics(mergeDescendants = true) { contentDescription = label }
                .alpha(if (enabled) 1f else DisabledAlpha),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Text(mm, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.pen_mm, "").trim(), style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
        }
    }
}
