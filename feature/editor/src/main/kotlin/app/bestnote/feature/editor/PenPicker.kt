package app.bestnote.feature.editor

import app.bestnote.engine.canvas.EraserMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.reducedMotion
import app.bestnote.core.designsystem.theme.softShadow
import kotlin.math.roundToInt

/** Places a popover next to its anchor, on the page side of the docked tray, clamped to the window. */
private class AnchoredPopoverPosition(private val side: TraySide, private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        fun clampX(x: Int) = x.coerceIn(margin, (windowSize.width - w - margin).coerceAtLeast(margin))
        fun clampY(y: Int) = y.coerceIn(margin, (windowSize.height - h - margin).coerceAtLeast(margin))
        return when (side) {
            TraySide.Top -> IntOffset(clampX(anchorBounds.center.x - w / 2), anchorBounds.bottom + gap) // never over the tray
            TraySide.Bottom -> IntOffset(clampX(anchorBounds.center.x - w / 2), clampY(anchorBounds.top - gap - h)) // opens upward
            TraySide.Left -> IntOffset(clampX(anchorBounds.right + gap), clampY(anchorBounds.center.y - h / 2))
            TraySide.Right -> IntOffset(clampX(anchorBounds.left - gap - w), clampY(anchorBounds.center.y - h / 2))
        }
    }
}

/**
 * A popover anchored to its parent (DS-04): grows from the anchor with [Motion.Enter.popover],
 * dismisses on an outside tap with [Motion.Exit.popover], then calls [onDismissed].
 */
@Composable
internal fun AnchoredPopover(side: TraySide, onDismissed: () -> Unit, content: @Composable () -> Unit) {
    val reduce = reducedMotion()
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val position = remember(side, density) {
        // The popup window includes PopoverInset of shadow room on every side.
        with(density) { AnchoredPopoverPosition(side, gap = (18.dp - PopoverInset).roundToPx(), margin = (12.dp - PopoverInset).roundToPx()) }
    }
    val origin = when (side) {
        TraySide.Top -> TransformOrigin(0.5f, 0f)
        TraySide.Bottom -> TransformOrigin(0.5f, 1f)
        TraySide.Left -> TransformOrigin(0f, 0.5f)
        TraySide.Right -> TransformOrigin(1f, 0.5f)
    }
    LaunchedEffect(visible.currentState, visible.isIdle) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) onDismissed()
    }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = { visible.targetState = false },
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(visible, enter = Motion.Enter.popover(reduce, origin), exit = Motion.Exit.popover(reduce, origin)) {
            Box(
                Modifier
                    .padding(PopoverInset) // room for the shadow inside the popup window
                    .softShadow(Elevation.High, BestNoteShapes.Card)
                    .clip(BestNoteShapes.Card)
                    .background(MaterialTheme.colorScheme.surface),
            ) { content() }
        }
    }
}

private val PopoverInset = Spacing.XL

@Composable
private fun Overline(text: String) {
    Text(text, style = BestNoteTheme.type.overline, color = BestNoteTheme.colors.muted)
}

/** DS-04: presets, color grid + custom color, size with a live stroke, opacity for the highlighter. */
@Composable
internal fun PenPickerContent(
    kind: PenKind,
    toolbox: Toolbox,
    onToolbox: (Toolbox) -> Unit,
    startWithCustom: Boolean = false,
) {
    val settings = toolbox.settingsOf(kind)
    val dpi = LocalConfiguration.current.densityDpi
    val mm = PenDefaults.formatMm(PenDefaults.widthMm(kind, settings.size, dpi))
    var custom by remember { mutableStateOf(startWithCustom) }
    Column(
        Modifier
            .width(PickerWidth)
            .heightIn(max = pickerMaxHeight())
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(penName(kind), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Text(stringResource(R.string.pen_mm, mm), style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
        }
        // Live stroke on paper, drawn with the canvas's own brush.
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(BestNoteShapes.Control)
                .background(BestNoteTheme.colors.paper.page)
                .border(1.dp, BestNoteTheme.colors.paper.ruledLine, BestNoteShapes.Control),
        ) {
            val label = stringResource(R.string.picker_preview)
            Spacer(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp).inkSquiggle(kind, settings).semantics { contentDescription = label })
        }

        Overline(stringResource(R.string.picker_presets))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            PenDefaults.presets(kind).forEachIndexed { i, preset ->
                val on = preset.colorArgb == settings.colorArgb && preset.size == settings.size
                val presetSettings = settings.copy(colorArgb = preset.colorArgb, size = preset.size)
                val label = stringResource(R.string.picker_preset, i + 1)
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(BestNoteShapes.Control)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(if (on) 2.dp else 0.dp, if (on) MaterialTheme.colorScheme.primary else Color.Transparent, BestNoteShapes.Control)
                        .clickable(role = Role.Button, onClickLabel = label) { onToolbox(toolbox.withPreset(preset, kind)) }
                        .semantics { contentDescription = label },
                ) {
                    Spacer(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp).inkSquiggle(kind, presetSettings))
                }
            }
        }

        Overline(stringResource(R.string.picker_colors))
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            PenDefaults.grid(kind).chunked(GridColumns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { c ->
                        Swatch(Color(c), selected = c == settings.colorArgb) { onToolbox(toolbox.withColor(c, kind)) }
                    }
                }
            }
        }
        Row(
            Modifier
                .clip(BestNoteShapes.Pill)
                .clickable(role = Role.Button) { custom = !custom }
                .padding(end = Spacing.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(ConicBrush), contentAlignment = Alignment.Center) {
                    Icon(AtelierIcons.Plus, null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
            Text(stringResource(R.string.picker_custom), style = MaterialTheme.typography.labelLarge)
        }
        if (custom) CustomColor(Color(settings.colorArgb)) { onToolbox(toolbox.withColor(it.toArgb(), kind)) }
        SavedColors(toolbox, kind, onToolbox)

        Overline(stringResource(R.string.picker_size))
        WedgeSlider(
            value = settings.size,
            range = PenDefaults.sizeRange(kind),
            onChange = { onToolbox(toolbox.withSize(it, kind)) },
            color = Color(settings.colorArgb),
            label = stringResource(R.string.picker_size),
        )
        if (kind == PenKind.Highlighter) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Overline(stringResource(R.string.picker_opacity))
                Spacer(Modifier.weight(1f))
                Text("${(settings.opacity * 100).roundToInt()}%", style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
            }
            WedgeSlider(
                value = settings.opacity,
                range = PenDefaults.MIN_OPACITY..1f,
                onChange = { onToolbox(toolbox.withOpacity(it, kind)) },
                color = Color(settings.colorArgb),
                label = stringResource(R.string.picker_opacity),
                wedge = false,
            )
        }
        if (kind.inks) PenFeel(kind, settings, toolbox, onToolbox)
        if (kind.inks) FavoriteButton(toolbox, onToolbox)
    }
}

/** INK-04/05: pressure sensitivity (pens with a pressure curve) and stabilization, as percentages. */
@Composable
private fun PenFeel(kind: PenKind, settings: PenSettings, toolbox: Toolbox, onToolbox: (Toolbox) -> Unit) {
    val ink = Color(settings.colorArgb)
    if (kind != PenKind.Highlighter) {
        LabeledPercent(stringResource(R.string.picker_pressure), (settings.pressureSensitivity * 100).roundToInt())
        WedgeSlider(
            value = settings.pressureSensitivity,
            range = 0f..1f,
            onChange = { onToolbox(toolbox.withSensitivity(it, kind)) },
            color = ink,
            label = stringResource(R.string.picker_pressure),
            wedge = false,
        )
    }
    LabeledPercent(stringResource(R.string.picker_stabilization), settings.stabilization)
    WedgeSlider(
        value = settings.stabilization.toFloat(),
        range = 0f..app.bestnote.engine.ink.PenTuning.MAX_STABILIZATION.toFloat(),
        onChange = { onToolbox(toolbox.withStabilization(it.roundToInt(), kind)) },
        color = ink,
        label = stringResource(R.string.picker_stabilization),
        wedge = false,
    )
}

@Composable
private fun LabeledPercent(label: String, percent: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Overline(label)
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.picker_percent, percent), style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
    }
}

/** INK-06: the user's saved custom colors. "+" saves the current color; long-press removes one. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SavedColors(toolbox: Toolbox, kind: PenKind, onToolbox: (Toolbox) -> Unit) {
    val current = toolbox.settingsOf(kind).colorArgb
    Overline(stringResource(R.string.picker_saved_colors))
    val removeLabel = stringResource(R.string.picker_remove_color)
    val saveLabel = stringResource(R.string.picker_save_color)
    val showAdd = current !in toolbox.savedColors && toolbox.savedColors.size < Toolbox.MAX_SAVED_COLORS
    // null = the "+" cell.
    val cells: List<Int?> = toolbox.savedColors + if (showAdd) listOf(null) else emptyList()
    Column {
        cells.chunked(GridColumns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                row.forEach { c ->
                    if (c == null) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClickLabel = saveLabel) { onToolbox(toolbox.saveColor(current)) }
                                .semantics { contentDescription = saveLabel },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(30.dp).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Icon(AtelierIcons.Plus, null, tint = BestNoteTheme.colors.muted, modifier = Modifier.size(16.dp)) }
                        }
                    } else {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    role = Role.RadioButton,
                                    onLongClickLabel = removeLabel,
                                    onLongClick = { onToolbox(toolbox.removeSavedColor(c)) },
                                    onClick = { onToolbox(toolbox.withColor(c, kind)) },
                                )
                                .semantics {
                                    customActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction(removeLabel) { onToolbox(toolbox.removeSavedColor(c)); true })
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val selected = c == current
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                    .padding(if (selected) 4.dp else 2.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .lowContrastRing(Color(c)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** INK-06: save the pen in hand (type + color + size + feel) as a favorite, or take it out again. */
@Composable
private fun FavoriteButton(toolbox: Toolbox, onToolbox: (Toolbox) -> Unit) {
    val existing = toolbox.activeFavorite
    val full = existing == null && !toolbox.canAddFavorite
    val label = stringResource(
        when {
            existing != null -> R.string.picker_favorite_remove
            full -> R.string.picker_favorites_full
            else -> R.string.picker_favorite_add
        },
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(BestNoteShapes.Pill)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(enabled = !full, role = Role.Button) {
                onToolbox(if (existing != null) toolbox.removeFavorite(existing.id) else toolbox.addFavorite(java.util.UUID.randomUUID().toString()))
            }
            .padding(vertical = Spacing.M),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (existing != null) AtelierIcons.Close else AtelierIcons.Star,
            null,
            tint = if (full) BestNoteTheme.colors.muted else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.S))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (full) BestNoteTheme.colors.muted else MaterialTheme.colorScheme.onSurface)
    }
}

private val PickerWidth = 340.dp

/** Tall enough for everything on a tablet; scrolls on short windows, never covers the tray. */
@Composable
private fun pickerMaxHeight(): androidx.compose.ui.unit.Dp =
    with(androidx.compose.ui.platform.LocalDensity.current) {
        (androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() - 200.dp).coerceIn(240.dp, 640.dp)
    }

/**
 * Size / opacity slider: a wedge track that grows like the stroke (or a flat track), filled with the
 * ink up to a round thumb. Tap or drag anywhere on it. Mirrors in RTL with the chrome.
 */
@Composable
private fun WedgeSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    color: Color,
    label: String,
    wedge: Boolean = true,
) {
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == LayoutDirection.Rtl
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val thumb = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    val span = range.endInclusive - range.start
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    val current = androidx.compose.runtime.rememberUpdatedState(onChange)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(value, range)
                setProgress { current.value(it.coerceIn(range.start, range.endInclusive)); true }
            }
            .pointerInput(range, rtl) {
                fun at(x: Float) {
                    val pad = size.height / 2f
                    var f = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                    if (rtl) f = 1f - f
                    current.value(range.start + f * span)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    at(down.position.x)
                    drag(down.id) { at(it.position.x) }
                }
            },
    ) {
        val pad = size.height / 2f
        val cy = size.height / 2f
        val left = pad
        val right = size.width - pad
        val thin = 2.dp.toPx()
        val thick = if (wedge) 14.dp.toPx() else 6.dp.toPx()
        fun xAt(f: Float) = if (rtl) right - f * (right - left) else left + f * (right - left)
        fun halfAt(f: Float) = (if (wedge) thin + (thick - thin) * f else thick) / 2f
        fun wedgePath(f0: Float, f1: Float) = androidx.compose.ui.graphics.Path().apply {
            val steps = 24
            for (i in 0..steps) {
                val f = f0 + (f1 - f0) * i / steps
                if (i == 0) moveTo(xAt(f), cy - halfAt(f)) else lineTo(xAt(f), cy - halfAt(f))
            }
            for (i in steps downTo 0) {
                val f = f0 + (f1 - f0) * i / steps
                lineTo(xAt(f), cy + halfAt(f))
            }
            close()
        }
        drawPath(wedgePath(0f, 1f), track)
        if (fraction > 0f) drawPath(wedgePath(0f, fraction), color)
        val c = Offset(xAt(fraction), cy)
        drawCircle(Color.Black.copy(alpha = 0.12f), 12.dp.toPx(), c + Offset(0f, 1.dp.toPx()))
        drawCircle(thumb, 11.dp.toPx(), c)
        drawCircle(outline, 11.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        drawCircle(color, 4.dp.toPx(), c)
    }
}
private const val GridColumns = 6

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .border(if (selected) 2.dp else 0.dp, if (selected) ring else Color.Transparent, CircleShape)
                .padding(if (selected) 4.dp else 2.dp)
                .clip(CircleShape)
                .background(color)
                .lowContrastRing(color),
        )
    }
}

/** Custom color: a saturation/brightness pad and a hue bar. Every drag applies live. */
@Composable
private fun CustomColor(initial: Color, onColor: (Color) -> Unit) {
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    fun emit() = onColor(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))))
    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    val satLabel = stringResource(R.string.picker_saturation)
    val hueLabel = stringResource(R.string.picker_hue)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(128.dp)
                .clip(BestNoteShapes.Control)
                .semantics { contentDescription = satLabel }
                .pointerInput(Unit) {
                    fun at(p: Offset) {
                        sat = (p.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (p.y / size.height).coerceIn(0f, 1f)
                        emit()
                    }
                    detectTapGestures { at(it) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        sat = (change.position.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        emit()
                    }
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val c = Offset(sat * size.width, (1f - value) * size.height)
            drawCircle(Color.White, 9.dp.toPx(), c, style = Stroke(2.5.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.25f), 10.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clip(BestNoteShapes.Pill)
                .semantics { contentDescription = hueLabel }
                .pointerInput(Unit) {
                    detectTapGestures {
                        hue = (it.x / size.width).coerceIn(0f, 1f) * HUE_MAX
                        emit()
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        hue = (change.position.x / size.width).coerceIn(0f, 1f) * HUE_MAX
                        emit()
                    }
                },
        ) {
            drawRect(Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
            val x = hue / HUE_MAX * size.width
            drawCircle(Color.White, size.height / 2 - 2.dp.toPx(), Offset(x.coerceIn(size.height / 2, size.width - size.height / 2), size.height / 2), style = Stroke(2.5.dp.toPx()))
        }
    }
}

private const val HUE_MAX = 360f

/** Eraser settings: stroke, pixel or highlighter-only erasing, and clear page. */
@Composable
internal fun EraserPickerContent(mode: EraserMode, onMode: (EraserMode) -> Unit, onClearPage: () -> Unit) {
    Column(Modifier.width(300.dp).padding(vertical = Spacing.M)) {
        Text(
            penName(PenKind.Eraser),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = Spacing.S),
        )
        EraserOption(stringResource(R.string.eraser_stroke), stringResource(R.string.eraser_stroke_hint), mode == EraserMode.Stroke) { onMode(EraserMode.Stroke) }
        EraserOption(stringResource(R.string.eraser_pixel), stringResource(R.string.eraser_pixel_hint), mode == EraserMode.Pixel) { onMode(EraserMode.Pixel) }
        EraserOption(stringResource(R.string.eraser_highlighter_only), stringResource(R.string.eraser_highlighter_only_hint), mode == EraserMode.HighlighterOnly) {
            onMode(EraserMode.HighlighterOnly)
        }
        Text(
            stringResource(R.string.eraser_side_button),
            style = BestNoteTheme.type.caption,
            color = BestNoteTheme.colors.muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = Spacing.S),
        )
        Row(
            Modifier
                .padding(horizontal = Spacing.L, vertical = Spacing.S)
                .fillMaxWidth()
                .clip(BestNoteShapes.Pill)
                .background(MaterialTheme.colorScheme.errorContainer)
                .clickable(role = Role.Button, onClick = onClearPage)
                .padding(vertical = Spacing.M),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AtelierIcons.Trash, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.S))
            Text(stringResource(R.string.editor_clear_page), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun EraserOption(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.S, vertical = Spacing.XS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(Spacing.M))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (selected) MaterialTheme.colorScheme.onSurface else BestNoteTheme.colors.muted)
            if (subtitle != null) Text(subtitle, style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
        }
    }
}
