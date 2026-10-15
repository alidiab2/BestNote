package app.bestnote.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Chrome warmth (Settings → Appearance). Blends a warm chrome color toward a neutral gray of the same
 * luminance: [warmth] 1 keeps the Atelier palette, 0 is fully neutral. Accents, ink and paper are untouched.
 */
internal fun Color.withWarmth(warmth: Float): Color {
    if (warmth >= 1f) return this
    val y = luminance()
    // Inverse sRGB transfer, so the gray has the same perceived lightness.
    val v = if (y <= 0.0031308f) y * 12.92f else 1.055f * Math.pow(y.toDouble(), 1 / 2.4).toFloat() - 0.055f
    val gray = Color(v, v, v, alpha)
    return lerp(gray, this, warmth.coerceIn(0f, 1f))
}

internal fun ColorScheme.withChromeWarmth(warmth: Float): ColorScheme = if (warmth >= 1f) this else copy(
    background = background.withWarmth(warmth),
    surfaceVariant = surfaceVariant.withWarmth(warmth),
    secondaryContainer = secondaryContainer.withWarmth(warmth),
    outline = outline.withWarmth(warmth),
    outlineVariant = outlineVariant.withWarmth(warmth),
    surfaceDim = surfaceDim.withWarmth(warmth),
    surfaceContainerLow = surfaceContainerLow.withWarmth(warmth),
    surfaceContainer = surfaceContainer.withWarmth(warmth),
    surfaceContainerHigh = surfaceContainerHigh.withWarmth(warmth),
    surfaceContainerHighest = surfaceContainerHighest.withWarmth(warmth),
)

internal fun BestNoteColors.withChromeWarmth(warmth: Float): BestNoteColors = if (warmth >= 1f) this else copy(
    sidebar = sidebar.withWarmth(warmth),
    deskCenter = deskCenter.withWarmth(warmth),
    deskEdge = deskEdge.withWarmth(warmth),
)
