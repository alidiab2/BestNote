package app.bestnote.feature.settings.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.LineWeight
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.bestnote.core.datastore.FingerDrawing
import app.bestnote.core.datastore.PressureCurve
import app.bestnote.core.datastore.SideButtonAction
import app.bestnote.core.datastore.HoverButtonAction
import app.bestnote.core.datastore.UserPreferences
import app.bestnote.core.datastore.StylusPreferences
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.InkPalette
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.hairline
import app.bestnote.core.designsystem.theme.reducedMotion
import app.bestnote.feature.settings.R
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun StylusSection(
    prefs: UserPreferences,
    update: ((StylusPreferences) -> StylusPreferences) -> Unit,
    updateRoot: ((UserPreferences) -> UserPreferences) -> Unit,
) {
    val stylus = prefs.stylus
    val writing = prefs.writing
    val hoverAction = prefs.hoverButtonAction
    val doubleClickUndo = prefs.hoverDoubleClickUndo
    DetailHeader(stringResource(R.string.settings_cat_stylus), stringResource(R.string.settings_stylus_desc))

    SettingsGroup(stringResource(R.string.settings_stylus_hold_group)) {
        row {
            RadioRow(
                stringResource(R.string.settings_hold_eraser),
                stylus.sideButtonHold == SideButtonAction.TemporaryEraser,
                { update { it.copy(sideButtonHold = SideButtonAction.TemporaryEraser) } },
                subtitle = stringResource(R.string.settings_hold_eraser_sub),
                icon = Icons.Outlined.AutoFixNormal,
            )
        }
        row {
            RadioRow(
                stringResource(R.string.settings_hold_lasso),
                stylus.sideButtonHold == SideButtonAction.Lasso,
                { update { it.copy(sideButtonHold = SideButtonAction.Lasso) } },
                subtitle = stringResource(R.string.settings_hold_lasso_sub),
                icon = Icons.Outlined.Gesture,
            )
        }
        row {
            RadioRow(
                stringResource(R.string.settings_off),
                stylus.sideButtonHold == SideButtonAction.Off,
                { update { it.copy(sideButtonHold = SideButtonAction.Off) } },
                subtitle = stringResource(R.string.settings_hold_off_sub),
                icon = Icons.Outlined.Block,
            )
        }
    }

    SettingsGroup(stringResource(R.string.settings_stylus_click_group)) {
        row { HoverRingIllustration(writing.colorArgb) }
        row {
            RadioRow(
                stringResource(R.string.settings_click_palette),
                hoverAction == HoverButtonAction.QuickPalette,
                { updateRoot { it.copy(hoverButtonAction = HoverButtonAction.QuickPalette) } },
                subtitle = stringResource(R.string.settings_click_palette_sub),
                icon = Icons.Outlined.TipsAndUpdates,
            )
        }
        row {
            RadioRow(
                stringResource(R.string.settings_off),
                hoverAction == HoverButtonAction.Off,
                { updateRoot { it.copy(hoverButtonAction = HoverButtonAction.Off) } },
                subtitle = stringResource(R.string.settings_click_off_sub),
                icon = Icons.Outlined.Block,
            )
        }
        row {
            SwitchRow(
                stringResource(R.string.settings_click_undo),
                doubleClickUndo,
                { v -> updateRoot { it.copy(hoverDoubleClickUndo = v) } },
                subtitle = stringResource(R.string.settings_click_undo_sub),
                icon = Icons.AutoMirrored.Outlined.Undo,
            )
        }
    }

    SettingsGroup(stringResource(R.string.settings_palm_group)) {
        row {
            SettingsRow(
                stringResource(R.string.settings_palm_pen),
                subtitle = stringResource(R.string.settings_palm_pen_sub),
                icon = Icons.Outlined.Verified,
            )
        }
        row {
            // SPEN-02: off (default) = auto stylus-only once a pen is seen; on = fingers always draw.
            val drawWithFinger = stylus.fingerDrawing == FingerDrawing.Always
            SwitchRow(
                stringResource(R.string.settings_finger_draws),
                drawWithFinger,
                { v -> update { it.copy(fingerDrawing = if (v) FingerDrawing.Always else FingerDrawing.WhenNoPen) } },
                subtitle = stringResource(if (drawWithFinger) R.string.settings_finger_always_sub else R.string.settings_finger_nopen_sub),
                icon = Icons.Outlined.PanTool,
            )
        }
    }

    SettingsGroup(stringResource(R.string.settings_feel_group)) {
        row {
            SwitchRow(
                stringResource(R.string.settings_hover_cursor),
                stylus.hoverCursor,
                { v -> update { it.copy(hoverCursor = v) } },
                subtitle = stringResource(R.string.settings_hover_cursor_sub),
                icon = Icons.Outlined.Mouse,
            )
        }
        row {
            SwitchRow(
                stringResource(R.string.settings_tilt),
                stylus.tiltShading,
                { v -> update { it.copy(tiltShading = v) } },
                subtitle = stringResource(R.string.settings_tilt_sub),
                icon = Icons.Outlined.Swipe,
            )
        }
        row {
            StackedRow(stringResource(R.string.settings_pressure), subtitle = stringResource(R.string.settings_pressure_sub), icon = Icons.Outlined.LineWeight) {
                Column {
                    Segmented(
                        PressureCurve.entries,
                        stylus.pressureCurve,
                        label = {
                            when (it) {
                                PressureCurve.Soft -> stringResource(R.string.settings_pressure_soft)
                                PressureCurve.Normal -> stringResource(R.string.settings_pressure_normal)
                                PressureCurve.Firm -> stringResource(R.string.settings_pressure_firm)
                            }
                        },
                        onSelect = { v -> update { it.copy(pressureCurve = v) } },
                    )
                    PressurePreview(stylus.pressureCurve, Color(writing.colorArgb), Modifier.padding(top = Spacing.M))
                }
            }
        }
    }
}

/**
 * Live pressure preview: a sample stroke whose pressure swells and fades, drawn through the
 * selected curve, plus a pad to try it with the pen (each point's real pressure goes through the curve).
 */
@Composable
private fun PressurePreview(curve: PressureCurve, ink: Color, modifier: Modifier = Modifier) {
    val points = remember { mutableStateListOf<List<Triple<Float, Float, Float>>>() }
    val current = remember { mutableStateListOf<Triple<Float, Float, Float>>() }
    val paper = BestNoteTheme.colors.paper
    val hint = stringResource(R.string.settings_pressure_try)
    Box(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(PreviewHeight)
                .clip(BestNoteShapes.Control)
                .background(paper.page)
                .hairline(BestNoteShapes.Control)
                .semantics { contentDescription = hint }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        current.clear()
                        current += Triple(down.position.x, down.position.y, down.pressure)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            current += Triple(change.position.x, change.position.y, change.pressure)
                            change.consume()
                        }
                        points += current.toList()
                        current.clear()
                    }
                },
        ) {
            // Ruled lines, like a page.
            var y = size.height / 4
            while (y < size.height) {
                drawLine(paper.ruledLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                y += size.height / 4
            }
            if (points.isEmpty() && current.isEmpty()) drawSampleStroke(curve, ink)
            (points + listOf(current.toList())).forEach { drawPressureStroke(it, curve, ink) }
        }
        if (points.isNotEmpty()) {
            IconButton(onClick = { points.clear() }, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.settings_pressure_clear), tint = BestNoteTheme.colors.muted)
            }
        } else {
            Text(
                hint,
                style = BestNoteTheme.type.caption,
                color = BestNoteTheme.colors.muted,
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.S),
            )
        }
    }
}

private fun DrawScope.widthFor(pressure: Float, curve: PressureCurve) =
    MinStroke.toPx() + (MaxStroke - MinStroke).toPx() * curve.apply(pressure)

private fun DrawScope.drawSampleStroke(curve: PressureCurve, ink: Color) {
    val steps = SampleSteps
    val left = size.width * 0.08f
    val span = size.width * 0.84f
    var prev: Offset? = null
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val p = sin(PI * t).toFloat() // light → heavy → light
        val pt = Offset(left + span * t, size.height / 2 + sin(t * 4 * PI).toFloat() * size.height * 0.18f)
        prev?.let { drawLine(ink, it, pt, strokeWidth = widthFor(p, curve), cap = StrokeCap.Round) }
        prev = pt
    }
}

private fun DrawScope.drawPressureStroke(stroke: List<Triple<Float, Float, Float>>, curve: PressureCurve, ink: Color) {
    stroke.zipWithNext { a, b ->
        drawLine(ink, Offset(a.first, a.second), Offset(b.first, b.second), strokeWidth = widthFor(b.third, curve), cap = StrokeCap.Round)
    }
}

/** A pen hovering over the page; a ring of tools opens around its tip (the quick palette). */
@Composable
private fun HoverRingIllustration(inkArgb: Int) {
    val reduced = reducedMotion()
    val ring = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        if (reduced) {
            ring.snapTo(1f)
            return@LaunchedEffect
        }
        while (true) {
            ring.snapTo(0f)
            delay(Motion.Duration.ToolbarRestoreDelay.toLong())
            ring.animateTo(1f, Motion.Spring.gentle())
            delay(Motion.Duration.Long * HoldFactor.toLong())
            ring.animateTo(0f, Motion.tween(Motion.Duration.Medium))
        }
    }
    // Drawn on paper, which never follows the theme: fixed ink tones.
    val outline = BestNoteTheme.colors.paper.ruledLine
    val pen = InkPalette.Black
    val surface = BestNoteTheme.colors.paper.page
    val accent = InkPalette.Blue
    val swatches = listOf(Color(inkArgb), InkPalette.Blue, InkPalette.Red, InkPalette.Green, InkPalette.Highlighter, accent)
    Row(Modifier.fillMaxWidth().padding(Spacing.L), verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            Modifier
                .size(width = IllustrationWidth, height = IllustrationHeight)
                .clip(BestNoteShapes.Control)
                .background(BestNoteTheme.colors.paper.page)
                .hairline(BestNoteShapes.Control),
        ) {
            val tip = Offset(size.width * 0.5f, size.height * 0.56f)
            val r = size.minDimension * 0.30f
            val progress = ring.value
            // Ring of tools.
            drawCircle(outline.copy(alpha = 0.6f * progress), radius = r, center = tip, style = Stroke(1.dp.toPx()))
            swatches.forEachIndexed { i, c ->
                val a = -PI / 2 + i * 2 * PI / swatches.size
                val center = Offset(tip.x + (r * cos(a)).toFloat(), tip.y + (r * sin(a)).toFloat())
                val dot = 7.dp.toPx() * progress
                drawCircle(surface, radius = dot + 2.dp.toPx() * progress, center = center)
                drawCircle(c, radius = dot, center = center)
            }
            // Hover dot under the tip.
            drawCircle(pen.copy(alpha = 0.25f), radius = 3.dp.toPx(), center = tip)
            // The pen, tilted, hovering a little above the tip.
            rotate(PenTilt, pivot = tip) {
                val lift = 6.dp.toPx()
                val body = Offset(tip.x, tip.y - lift)
                drawLine(pen, body, Offset(body.x, body.y - size.height * 0.55f), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                drawLine(pen, body, Offset(body.x, body.y - 2.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                // Side button.
                drawLine(accent, Offset(body.x + 2.5f.dp.toPx(), body.y - size.height * 0.22f), Offset(body.x + 2.5f.dp.toPx(), body.y - size.height * 0.30f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text(
            stringResource(R.string.settings_click_illustration),
            style = MaterialTheme.typography.bodySmall,
            color = BestNoteTheme.colors.muted,
            modifier = Modifier.padding(start = Spacing.L).weight(1f),
        )
    }
}

private val PreviewHeight = 120.dp
private val MinStroke = 0.8.dp
private val MaxStroke = 9.dp
private const val SampleSteps = 90
private val IllustrationWidth = 132.dp
private val IllustrationHeight = 96.dp
private const val PenTilt = 28f
private const val HoldFactor = 3
