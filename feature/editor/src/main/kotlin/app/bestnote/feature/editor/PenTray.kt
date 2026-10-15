package app.bestnote.feature.editor

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.reducedMotion
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.feature.editor.PenArt.drawPen

/** Physical edge the tray floats on. Side docks never follow RTL (handedness is physical). */
enum class TraySide { Top, Left, Right }

/** What a picker popover is anchored to. */
enum class PickerAnchor { Pen, Colors, Width }

private val SlotLong = 64.dp
private val SlotShort = 40.dp
private val PenRaised = 3.dp
private val PenResting = 16.dp

/** How far past the slot the 26 x 78 unit art reaches when centered (78 - 64) / 2. */
private val ArtOverhang = 7.dp

@Composable
internal fun penName(kind: PenKind): String = stringResource(
    when (kind) {
        PenKind.Fountain -> R.string.pen_fountain
        PenKind.Ballpoint -> R.string.pen_ballpoint
        PenKind.Brush -> R.string.pen_brush
        PenKind.Pencil -> R.string.pen_pencil
        PenKind.Highlighter -> R.string.pen_highlighter
        PenKind.Eraser -> R.string.pen_eraser
    },
)

/** Line container: a Row on the top dock, a Column on side docks. */
@Composable
private fun TrayLine(vertical: Boolean, modifier: Modifier = Modifier, spacing: Dp = 0.dp, content: @Composable () -> Unit) {
    if (vertical) {
        Column(modifier, Arrangement.spacedBy(spacing), Alignment.CenterHorizontally) { content() }
    } else {
        Row(modifier, Arrangement.spacedBy(spacing), Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun TrayDivider(vertical: Boolean) {
    val color = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.then(if (vertical) Modifier.size(30.dp, 1.dp) else Modifier.size(1.dp, 30.dp)).background(color))
}

/**
 * The signature pen tray (DS-03): realistic pens peeking up from a floating capsule, the active one
 * raised with a dot under it, then the 5 color dots + "more colors", the stroke-width button, and a
 * collapse handle. [picker] is shown anchored to whichever [PickerAnchor] is open.
 */
@Composable
internal fun PenTray(
    toolbox: Toolbox,
    side: TraySide,
    temporary: PenKind?,
    pickerAnchor: PickerAnchor?,
    onPen: (PenKind) -> Unit,
    onColor: (Int) -> Unit,
    onOpenPicker: (PickerAnchor) -> Unit,
    onCollapse: () -> Unit,
    picker: @Composable (PickerAnchor) -> Unit,
    modifier: Modifier = Modifier,
    grip: Modifier? = null,
) {
    val vertical = side != TraySide.Top
    val inks = toolbox.active.inks
    TrayLine(
        vertical,
        modifier
            .softShadow(Elevation.Medium, BestNoteShapes.ToolTray)
            .background(MaterialTheme.colorScheme.surface, BestNoteShapes.ToolTray)
            .padding(if (vertical) PaddingTrayVertical else PaddingTrayHorizontal),
        spacing = 10.dp,
    ) {
        if (grip != null) TrayGrip(vertical, grip)
        TrayLine(vertical) {
            PenKind.entries.forEach { kind ->
                // With the lasso in hand no pen is raised (the side-button eraser still shows).
                val shown = temporary ?: toolbox.active.takeUnless { toolbox.lasso }
                PenSlot(
                    kind = kind,
                    ink = Color(toolbox.settingsOf(kind).colorArgb),
                    active = kind == shown,
                    side = side,
                    onClick = { onPen(kind) },
                ) { if (pickerAnchor == PickerAnchor.Pen && kind == toolbox.active) picker(PickerAnchor.Pen) }
            }
        }
        TrayDivider(vertical)
        TrayLine(vertical, Modifier.alpha(if (inks) 1f else DisabledAlpha), spacing = 2.dp) {
            val current = toolbox.current.colorArgb
            toolbox.quickColors().forEach { c ->
                ColorDot(Color(c), selected = inks && c == current, enabled = inks, onClick = { onColor(c) })
            }
            Box {
                MoreColorsDot(enabled = inks) { onOpenPicker(PickerAnchor.Colors) }
                if (pickerAnchor == PickerAnchor.Colors) picker(PickerAnchor.Colors)
            }
        }
        TrayDivider(vertical)
        Box {
            WidthButton(toolbox, vertical, enabled = inks) { onOpenPicker(PickerAnchor.Width) }
            if (pickerAnchor == PickerAnchor.Width) picker(PickerAnchor.Width)
        }
        ChromeIconButton(
            icon = AtelierIcons.Collapse,
            label = stringResource(R.string.toolbar_hide),
            onClick = onCollapse,
            size = 32.dp,
            tint = BestNoteTheme.colors.muted,
            modifier = Modifier.graphicsLayer {
                rotationZ = when (side) {
                    TraySide.Top -> 0f
                    TraySide.Left -> -90f
                    TraySide.Right -> 90f
                }
            },
        )
    }
}

/** Drag handle: two columns of dots. Drag the tray by it to the top or a side ([grip] holds the gesture). */
@Composable
private fun TrayGrip(vertical: Boolean, grip: Modifier) {
    val label = stringResource(R.string.toolbar_move)
    val dot = BestNoteTheme.colors.muted
    Canvas(
        Modifier
            .then(if (vertical) Modifier.size(GripLong, GripShort) else Modifier.size(GripShort, GripLong))
            .semantics { contentDescription = label }
            .then(grip),
    ) {
        val r = 1.6.dp.toPx()
        val step = 6.dp.toPx()
        for (i in -1..1) for (j in 0..1) {
            val along = i * step
            val across = (j - 0.5f) * step
            drawCircle(dot, r, center + if (vertical) Offset(along, across) else Offset(across, along))
        }
    }
}

private val GripShort = 20.dp
private val GripLong = 44.dp

private val PaddingTrayHorizontal = androidx.compose.foundation.layout.PaddingValues(start = 6.dp, end = 8.dp)
private val PaddingTrayVertical = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 8.dp)

/** One pen peeking out of the tray. Raised (Motion.Spring.snappy + TrayLift scale) when active. */
@Composable
private fun PenSlot(kind: PenKind, ink: Color, active: Boolean, side: TraySide, onClick: () -> Unit, anchored: @Composable () -> Unit) {
    val reduce = reducedMotion()
    val sink by animateDpAsState(
        if (active) PenRaised else PenResting,
        if (reduce) Motion.tween(Motion.Duration.Instant) else Motion.Spring.snappy(),
        label = "penSink",
    )
    val lift by animateFloatAsState(if (active && !reduce) Motion.TrayLift.Scale else 1f, Motion.Spring.Snappy, label = "penLift")
    val dotAlpha by animateFloatAsState(if (active) 1f else 0f, Motion.fade(), label = "penDot")
    val dotColor = MaterialTheme.colorScheme.onSurface
    val rim = penRim()
    val name = penName(kind)
    val description = if (active) stringResource(R.string.pen_selected, name) else name
    val vertical = side != TraySide.Top
    Box(
        Modifier.drawBehind {
            if (dotAlpha == 0f) return@drawBehind
            val r = 2.5.dp.toPx()
            val gap = 7.dp.toPx() + r
            val c = when (side) {
                TraySide.Top -> Offset(size.width / 2, size.height + gap)
                TraySide.Left -> Offset(-gap, size.height / 2)
                TraySide.Right -> Offset(size.width + gap, size.height / 2)
            }
            drawCircle(dotColor.copy(alpha = dotAlpha), r, c)
        },
    ) {
        Box(
            Modifier
                .size(if (vertical) SlotLong else SlotShort, if (vertical) SlotShort else SlotLong)
                .clipToBounds()
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    selected = active
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Tab,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                Modifier
                    .requiredSize(26.dp, 78.dp)
                    .graphicsLayer {
                        val shift = (ArtOverhang + sink).toPx()
                        when (side) {
                            TraySide.Top -> translationY = shift
                            TraySide.Left -> {
                                rotationZ = 90f
                                translationX = -shift
                            }
                            TraySide.Right -> {
                                rotationZ = -90f
                                translationX = shift
                            }
                        }
                        scaleX = lift
                        scaleY = lift
                    },
            ) { drawPen(kind, ink, rim) }
        }
        anchored()
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ringWidth by animateDpAsState(if (selected) 2.dp else 0.dp, Motion.Spring.snappy(), label = "ring")
    val hex = "#%06X".format(color.value.toLong().ushr(32).toInt() and 0xFFFFFF)
    val label = if (selected) stringResource(R.string.pen_color_selected, hex) else stringResource(R.string.pen_color, hex)
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, onClickLabel = label, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (selected) 28.dp else 22.dp)
                .then(if (ringWidth > 0.dp) Modifier.border(ringWidth, color, CircleShape) else Modifier)
                .padding(if (selected) 4.5.dp else 0.dp)
                .clip(CircleShape)
                .background(color)
                .lowContrastRing(color)
                .semantics { contentDescription = label },
        )
    }
}


@Composable
private fun MoreColorsDot(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.pen_more_colors)
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(ConicBrush))
    }
}

/** Stroke width: a live mini stroke drawn with the real brush, and the width in mm. */
@Composable
private fun WidthButton(toolbox: Toolbox, vertical: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val dpi = LocalConfiguration.current.densityDpi
    val mm = PenDefaults.formatMm(PenDefaults.widthMm(toolbox.active, toolbox.current.size, dpi))
    val label = stringResource(R.string.pen_stroke_width, mm)
    val previewKind = if (toolbox.active.inks) toolbox.active else PenKind.Fountain
    val preview = if (toolbox.active.inks) toolbox.current else PenDefaults.settings.getValue(PenKind.Fountain)
    val maxPx = with(androidx.compose.ui.platform.LocalDensity.current) { 7.dp.toPx() }
    TrayLine(
        vertical,
        Modifier
            .clip(BestNoteShapes.Control)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .alpha(if (enabled) 1f else DisabledAlpha)
            .padding(horizontal = if (vertical) 6.dp else 10.dp, vertical = if (vertical) 10.dp else 0.dp)
            .then(if (vertical) Modifier.width(48.dp) else Modifier.height(40.dp)),
        spacing = 8.dp,
    ) {
        Spacer(Modifier.size(34.dp, 14.dp).inkSquiggle(previewKind, preview, maxWidthPx = maxPx))
        Text(
            mm,
            style = BestNoteTheme.type.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * The tray collapsed to a small pill while writing (or by the collapse handle). One tap restores it.
 * Shows the active pen and its color, so the state stays readable.
 */
@Composable
internal fun CollapsedTrayPill(toolbox: Toolbox, temporary: PenKind?, onRestore: () -> Unit, modifier: Modifier = Modifier) {
    val kind = temporary ?: toolbox.active
    val label = stringResource(R.string.toolbar_show)
    Row(
        modifier
            .chromeSurface()
            .clip(BestNoteShapes.Pill)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClickLabel = label,
                role = Role.Button,
                onClick = onRestore,
            )
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(start = Spacing.S, end = Spacing.M)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Box(Modifier.size(32.dp).clipToBounds(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.requiredSize(11.dp, 33.dp).graphicsLayer { rotationZ = 40f }) {
                drawPen(kind, Color(toolbox.settingsOf(kind).colorArgb))
            }
        }
        if (kind.inks) {
            val dot = Color(toolbox.settingsOf(kind).colorArgb)
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot).lowContrastRing(dot))
        }
        Icon(
            AtelierIcons.Collapse,
            contentDescription = null,
            tint = BestNoteTheme.colors.muted,
            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 180f },
        )
    }
}
