package app.bestnote.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Raw "Atelier" palette (approved direction A: stationery made digital, warm paper chrome,
 * ink is the hero). Feature code reads roles from [androidx.compose.material3.MaterialTheme.colorScheme]
 * or [BestNoteTheme.colors], never these constants directly.
 */
object AtelierPalette {
    // Chrome (light)
    val Paper = Color(0xFFF6F4EF)
    val Sidebar = Color(0xFFEFECE5)
    val Surface = Color(0xFFFFFFFF)
    val DeskCenter = Color(0xFFF4F1EB)
    val DeskEdge = Color(0xFFE1DCD2)
    val Outline = Color(0xFFE2DED5)
    val OutlineVariant = Color(0xFFECE7DD)
    val Ink = Color(0xFF1E2433)
    val InkVariant = Color(0xFF3B4150)
    val Muted = Color(0xFF6A6F7C)

    // Accents
    val FountainBlue = Color(0xFF2A4BD7)
    val FountainBlueContainer = Color(0xFFE3E8FB)
    val Vermilion = Color(0xFFD9462B)
    val Sage = Color(0xFF2F7D57)
    val QuickNote = Color(0xFFFFF5D9)
    val OnQuickNote = Color(0xFF6E5A22)
    val AiAccent = Color(0xFFA9B6FF)
    val Highlighter = Color(0xFFF7D84A)

    // Chrome (dark: graphite)
    val Graphite = Color(0xFF121417)
    val GraphiteRaised = Color(0xFF1C1F24)
    val GraphiteHigh = Color(0xFF252931)
    val GraphiteHighest = Color(0xFF2E333C)
    val GraphiteOutline = Color(0xFF353A44)
    val GraphiteOutlineVariant = Color(0xFF2A2E36)
    val DeskDarkCenter = Color(0xFF1E2127)
    val DeskDarkEdge = Color(0xFF0E1013)
    val InkOnDark = Color(0xFFE9E7E1)
    val InkVariantOnDark = Color(0xFFC3C6CF)
    val MutedOnDark = Color(0xFF8E939E)
    val FountainBlueOnDark = Color(0xFFA9B6FF)
    val FountainBlueContainerDark = Color(0xFF263572)
    val VermilionOnDark = Color(0xFFFF8A70)
    val SageOnDark = Color(0xFF7CC39C)
    val QuickNoteDark = Color(0xFF3A3320)
    val OnQuickNoteDark = Color(0xFFF3DFA2)

    // Paper (never follows the theme or dynamic color)
    val Page = Color(0xFFFFFEFB)
    val RuledLine = Color(0xFFDCE5F1)
    val MarginLine = Color(0x73E27869) // rgba(226,120,105,.45)
}

/** Default ink swatches offered in a fresh pen picker. */
object InkPalette {
    val Black = Color(0xFF1E2433)
    val Blue = Color(0xFF2A4BD7)
    val Red = Color(0xFFD9462B)
    val Green = Color(0xFF2F7D57)
    val Highlighter = Color(0xFFF7D84A)
    val Defaults: List<Color> = listOf(Black, Blue, Red, Green)
}

/** Notebook cover colors. */
object CoverPalette {
    val Navy = Color(0xFF23314F)
    val Terracotta = Color(0xFFB4553A)
    val Sage = Color(0xFF5F8069)
    val Mustard = Color(0xFFC99A2E)
    val Lilac = Color(0xFF7D6CA6)
    val Black = Color(0xFF1F1F22)
    val All: List<Color> = listOf(Navy, Terracotta, Sage, Mustard, Lilac, Black)
}

/** The page itself. Stays light in dark mode; a "dark paper" variant is a page template, not the theme. */
@Immutable
data class PaperColors(
    val page: Color = AtelierPalette.Page,
    val ruledLine: Color = AtelierPalette.RuledLine,
    val marginLine: Color = AtelierPalette.MarginLine,
)

/** Roles Material 3 has no slot for. Read via [BestNoteTheme.colors]. */
@Immutable
data class BestNoteColors(
    val sidebar: Color,
    val deskCenter: Color,
    val deskEdge: Color,
    val muted: Color,
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val aiAccent: Color,
    /** Primary dark button ("inverse" CTA). */
    val strongButton: Color,
    val onStrongButton: Color,
    /** Tint of the soft long shadow under cards, covers and the tool tray. */
    val shadowTint: Color,
    val paper: PaperColors = PaperColors(),
) {
    /** Editor background: a soft radial "desk" behind the page. */
    fun deskBrush(): Brush = Brush.radialGradient(listOf(deskCenter, deskEdge))
}

internal val LightBestNoteColors = BestNoteColors(
    sidebar = AtelierPalette.Sidebar,
    deskCenter = AtelierPalette.DeskCenter,
    deskEdge = AtelierPalette.DeskEdge,
    muted = AtelierPalette.Muted,
    success = AtelierPalette.Sage,
    onSuccess = Color.White,
    warning = AtelierPalette.QuickNote,
    onWarning = AtelierPalette.OnQuickNote,
    aiAccent = AtelierPalette.AiAccent,
    strongButton = AtelierPalette.Ink,
    onStrongButton = AtelierPalette.Paper,
    shadowTint = Color(0x73503C28), // warm brown at ~45%
)

internal val DarkBestNoteColors = BestNoteColors(
    sidebar = AtelierPalette.GraphiteRaised,
    deskCenter = AtelierPalette.DeskDarkCenter,
    deskEdge = AtelierPalette.DeskDarkEdge,
    muted = AtelierPalette.MutedOnDark,
    success = AtelierPalette.SageOnDark,
    onSuccess = AtelierPalette.Graphite,
    warning = AtelierPalette.QuickNoteDark,
    onWarning = AtelierPalette.OnQuickNoteDark,
    aiAccent = AtelierPalette.AiAccent,
    strongButton = AtelierPalette.InkOnDark,
    onStrongButton = AtelierPalette.Graphite,
    shadowTint = Color(0x99000000),
)

internal val LightAtelierScheme: ColorScheme = lightColorScheme(
    primary = AtelierPalette.FountainBlue,
    onPrimary = Color.White,
    primaryContainer = AtelierPalette.FountainBlueContainer,
    onPrimaryContainer = Color(0xFF14286F),
    inversePrimary = AtelierPalette.FountainBlueOnDark,
    secondary = AtelierPalette.InkVariant,
    onSecondary = Color.White,
    secondaryContainer = AtelierPalette.Sidebar,
    onSecondaryContainer = AtelierPalette.Ink,
    tertiary = AtelierPalette.Sage,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDEFE4),
    onTertiaryContainer = Color(0xFF123A27),
    background = AtelierPalette.Paper,
    onBackground = AtelierPalette.Ink,
    surface = AtelierPalette.Surface,
    onSurface = AtelierPalette.Ink,
    surfaceVariant = AtelierPalette.Sidebar,
    onSurfaceVariant = AtelierPalette.InkVariant,
    surfaceTint = Color.Transparent, // no M3 tonal tint: Atelier uses shadows + hairlines
    inverseSurface = AtelierPalette.Ink,
    inverseOnSurface = AtelierPalette.Paper,
    error = AtelierPalette.Vermilion,
    onError = Color.White,
    errorContainer = Color(0xFFFBE3DD),
    onErrorContainer = Color(0xFF6B1A0C),
    outline = AtelierPalette.Outline,
    outlineVariant = AtelierPalette.OutlineVariant,
    scrim = Color(0xFF000000),
    surfaceBright = AtelierPalette.Surface,
    surfaceDim = AtelierPalette.DeskEdge,
    surfaceContainerLowest = AtelierPalette.Surface,
    surfaceContainerLow = AtelierPalette.Paper,
    surfaceContainer = AtelierPalette.Sidebar,
    surfaceContainerHigh = Color(0xFFE8E4DB),
    surfaceContainerHighest = AtelierPalette.DeskEdge,
)

internal val DarkAtelierScheme: ColorScheme = darkColorScheme(
    primary = AtelierPalette.FountainBlueOnDark,
    onPrimary = Color(0xFF0E1A55),
    primaryContainer = AtelierPalette.FountainBlueContainerDark,
    onPrimaryContainer = Color(0xFFDDE3FF),
    inversePrimary = AtelierPalette.FountainBlue,
    secondary = AtelierPalette.InkVariantOnDark,
    onSecondary = AtelierPalette.Graphite,
    secondaryContainer = AtelierPalette.GraphiteHigh,
    onSecondaryContainer = AtelierPalette.InkOnDark,
    tertiary = AtelierPalette.SageOnDark,
    onTertiary = AtelierPalette.Graphite,
    tertiaryContainer = Color(0xFF1D3F2E),
    onTertiaryContainer = Color(0xFFC6EBD5),
    background = AtelierPalette.Graphite,
    onBackground = AtelierPalette.InkOnDark,
    surface = AtelierPalette.GraphiteRaised,
    onSurface = AtelierPalette.InkOnDark,
    surfaceVariant = AtelierPalette.GraphiteHigh,
    onSurfaceVariant = AtelierPalette.InkVariantOnDark,
    surfaceTint = Color.Transparent,
    inverseSurface = AtelierPalette.InkOnDark,
    inverseOnSurface = AtelierPalette.Graphite,
    error = AtelierPalette.VermilionOnDark,
    onError = Color(0xFF3D0C02),
    errorContainer = Color(0xFF5C1E11),
    onErrorContainer = Color(0xFFFFDAD2),
    outline = AtelierPalette.GraphiteOutline,
    outlineVariant = AtelierPalette.GraphiteOutlineVariant,
    scrim = Color(0xFF000000),
    surfaceBright = AtelierPalette.GraphiteHighest,
    surfaceDim = AtelierPalette.Graphite,
    surfaceContainerLowest = Color(0xFF0D0F12),
    surfaceContainerLow = AtelierPalette.Graphite,
    surfaceContainer = AtelierPalette.GraphiteRaised,
    surfaceContainerHigh = AtelierPalette.GraphiteHigh,
    surfaceContainerHighest = AtelierPalette.GraphiteHighest,
)

internal val LocalBestNoteColors = staticCompositionLocalOf { LightBestNoteColors }
