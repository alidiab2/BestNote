package app.bestnote.core.designsystem.showcase

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.CoverPalette
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.InkPalette
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.hairline
import app.bestnote.core.designsystem.theme.reducedMotion
import app.bestnote.core.designsystem.theme.softShadow

/**
 * Living sample of every Atelier token (colors, type, shapes, elevation, motion). Shown in previews
 * and, in debug builds, by `ThemeShowcaseActivity` for device screenshots.
 */
@Composable
fun ThemeShowcaseScreen(initialDark: Boolean = false, initialRtl: Boolean = false) {
    var dark by remember { mutableStateOf(initialDark) }
    var rtl by remember { mutableStateOf(initialRtl) }
    BestNoteTheme(darkTheme = dark) {
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(
                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(Spacing.XL),
                    verticalArrangement = Arrangement.spacedBy(Spacing.XL),
                ) {
                    Header(dark, rtl, onDark = { dark = it }, onRtl = { rtl = it })
                    Section("Chrome roles") { ColorRoles() }
                    Section("Ink · Covers") { InkAndCovers() }
                    Section("Desk · Paper") { DeskAndPaper() }
                    Section("Controls") { Controls() }
                    Section("Motion · tool tray") { ToolTray() }
                    Section("Type") { TypeSamples() }
                }
            }
        }
    }
}

@Composable
private fun Header(dark: Boolean, rtl: Boolean, onDark: (Boolean) -> Unit, onRtl: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Text("ATELIER · THEME V1", style = BestNoteTheme.type.overline, color = BestNoteTheme.colors.muted)
        Text("Stationery, made digital.", style = MaterialTheme.typography.displayMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            FilterChip(selected = dark, onClick = { onDark(!dark) }, label = { Text("Dark chrome") }, shape = BestNoteShapes.Pill)
            FilterChip(selected = rtl, onClick = { onRtl(!rtl) }, label = { Text("RTL") }, shape = BestNoteShapes.Pill)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Swatch(color: Color, label: String, onColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
        Box(Modifier.size(56.dp).clip(BestNoteShapes.Control).background(color).hairline(BestNoteShapes.Control))
        Text(label, style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted, maxLines = 1)
    }
}

@Composable
private fun ColorRoles() {
    val c = MaterialTheme.colorScheme
    val x = BestNoteTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Swatch(c.background, "background")
        Swatch(x.sidebar, "sidebar")
        Swatch(c.surface, "surface")
        Swatch(c.outline, "outline")
        Swatch(c.onSurface, "ink")
        Swatch(x.muted, "muted")
        Swatch(c.primary, "primary")
        Swatch(c.primaryContainer, "primary ctr")
        Swatch(c.error, "error")
        Swatch(x.success, "success")
        Swatch(x.warning, "quick note")
        Swatch(x.aiAccent, "AI")
    }
}

@Composable
private fun InkAndCovers() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.L)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
            (InkPalette.Defaults + InkPalette.Highlighter).forEach {
                Box(Modifier.size(32.dp).clip(CircleShape).background(it))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.L)) {
            CoverPalette.All.forEach { color ->
                Box(
                    Modifier.size(width = 72.dp, height = 96.dp)
                        .softShadow(Elevation.Medium, BestNoteShapes.NotebookCover)
                        .clip(BestNoteShapes.NotebookCover)
                        .background(color),
                ) {
                    // Spine band on the start side: mirrors with RTL chrome.
                    Box(Modifier.width(8.dp).fillMaxSize().background(Color.Black.copy(alpha = 0.12f)))
                }
            }
        }
    }
}

@Composable
private fun DeskAndPaper() {
    val x = BestNoteTheme.colors
    Box(
        Modifier.fillMaxWidth().height(220.dp).clip(BestNoteShapes.Card).background(x.deskBrush()),
        contentAlignment = Alignment.Center,
    ) {
        // The page never mirrors: force LTR geometry inside it.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(
                Modifier.size(width = 260.dp, height = 180.dp)
                    .softShadow(Elevation.High, BestNoteShapes.Control)
                    .background(x.paper.page),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val gap = 22.dp.toPx()
                    var y = gap * 1.5f
                    while (y < size.height) {
                        drawLine(x.paper.ruledLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        y += gap
                    }
                    val m = 36.dp.toPx()
                    drawLine(x.paper.marginLine, Offset(m, 0f), Offset(m, size.height), strokeWidth = 1.dp.toPx())
                }
                Text(
                    "ink is the hero",
                    style = BestNoteTheme.type.handSample,
                    color = InkPalette.Blue,
                    modifier = Modifier.padding(start = 48.dp, top = 30.dp),
                )
            }
        }
    }
}

@Composable
private fun Controls() {
    val x = BestNoteTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.M), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
        Button(onClick = {}, shape = BestNoteShapes.Pill) { Text("New notebook") }
        Button(
            onClick = {},
            shape = BestNoteShapes.Pill,
            colors = ButtonDefaults.buttonColors(containerColor = x.strongButton, contentColor = x.onStrongButton),
        ) { Text("Continue writing") }
        OutlinedButton(onClick = {}, shape = BestNoteShapes.Pill) { Text("Import PDF") }
        Surface(shape = BestNoteShapes.Card, color = x.warning, contentColor = x.onWarning) {
            Text("Quick note · today", Modifier.padding(Spacing.L), style = MaterialTheme.typography.labelLarge)
        }
        Surface(shape = BestNoteShapes.Pill, color = x.aiAccent.copy(alpha = 0.25f)) {
            Text("✦ Smart Board", Modifier.padding(horizontal = Spacing.L, vertical = Spacing.S), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ToolTray() {
    var active by remember { mutableIntStateOf(1) }
    var showHint by remember { mutableStateOf(true) }
    val reduce = reducedMotion()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
        Row(
            Modifier.softShadow(Elevation.Medium, BestNoteShapes.ToolTray)
                .clip(BestNoteShapes.ToolTray)
                .background(MaterialTheme.colorScheme.surface)
                .hairline(BestNoteShapes.ToolTray)
                .padding(horizontal = Spacing.L, vertical = Spacing.M),
            horizontalArrangement = Arrangement.spacedBy(Spacing.L),
            verticalAlignment = Alignment.Bottom,
        ) {
            (InkPalette.Defaults + InkPalette.Highlighter).forEachIndexed { i, ink ->
                val lifted = i == active
                val offset by animateDpAsState(if (lifted && !reduce) Motion.TrayLift.Offset else 0.dp, Motion.Spring.snappy(), label = "lift")
                val scale by animateFloatAsState(if (lifted && !reduce) Motion.TrayLift.Scale else 1f, Motion.Spring.Snappy, label = "scale")
                Column(
                    Modifier.graphicsLayer {
                        translationY = offset.toPx()
                        scaleX = scale
                        scaleY = scale
                    }.clickable { active = i; showHint = !showHint },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(width = 14.dp, height = 44.dp).clip(BestNoteShapes.Pill).background(ink))
                    Spacer(Modifier.height(Spacing.XS))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (lifted) MaterialTheme.colorScheme.primary else Color.Transparent))
                }
            }
        }
        AnimatedVisibility(showHint, enter = Motion.Enter.popover(reduce), exit = Motion.Exit.popover(reduce)) {
            Surface(shape = BestNoteShapes.Control, color = MaterialTheme.colorScheme.inverseSurface) {
                Text(
                    "Tap a pen: it lifts with Motion.Spring.Snappy" + if (reduce) " (reduced motion: fade only)" else "",
                    Modifier.padding(Spacing.M),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}

@Composable
private fun TypeSamples() {
    val t = MaterialTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Text("Display 40 · Fraunces", style = t.displayMedium)
        Text("Title large 24 · Biology, term 2", style = t.titleLarge)
        Text("Title 19 · Chapter 4 notes", style = t.titleMedium)
        Text("Body 15 · Instrument Sans keeps the chrome quiet so the page can speak.", style = t.bodyMedium)
        Text("Label 14 · Pens & highlighters", style = t.labelLarge)
        Text("Caption 12.5 · Edited 2 min ago", style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
        Text("OVERLINE 12 · RECENT", style = BestNoteTheme.type.overline, color = BestNoteTheme.colors.muted)
        Text("دفتر الأحياء · Biology notebook ١٢", style = t.bodyLarge)
        Text("A quick handwritten sample", style = BestNoteTheme.type.handSample)
    }
}

@Preview(name = "Light", widthDp = 900, heightDp = 1600)
@Composable
private fun ShowcaseLightPreview() = ThemeShowcaseScreen()

@Preview(name = "Dark", widthDp = 900, heightDp = 1600)
@Composable
private fun ShowcaseDarkPreview() = ThemeShowcaseScreen(initialDark = true)

@Preview(name = "RTL", widthDp = 900, heightDp = 1600)
@Composable
private fun ShowcaseRtlPreview() = ThemeShowcaseScreen(initialRtl = true)
