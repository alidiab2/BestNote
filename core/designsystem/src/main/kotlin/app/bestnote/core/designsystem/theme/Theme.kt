package app.bestnote.core.designsystem.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

internal val LocalReducedMotion = staticCompositionLocalOf { false }
internal val LocalBestNoteTextStyles = staticCompositionLocalOf { bestNoteTextStyles() }

/**
 * BestNote theme, "Atelier" direction: warm paper chrome, graphite in dark mode, fountain-blue accent.
 *
 * @param dynamicColor opt-in Material You colors on Android 12+. Off by default; only chrome follows
 *   it. Paper, ink, cover and semantic colors in [BestNoteTheme.colors] never do.
 * @param reducedMotion defaults to the system "Remove animations" setting.
 * @param chromeWarmth 1 = the Atelier warm paper chrome, 0 = neutral grays of the same lightness.
 *   Applies to chrome only (backgrounds, sidebar, desk, outlines); the page and ink never change.
 */
@Composable
fun BestNoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    reducedMotion: Boolean = systemReducedMotion(),
    chromeWarmth: Float = 1f,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkAtelierScheme
        else -> LightAtelierScheme
    }
    val colorScheme = remember(baseScheme, chromeWarmth) { baseScheme.withChromeWarmth(chromeWarmth) }
    val baseColors = if (darkTheme) DarkBestNoteColors else LightBestNoteColors
    val bestNoteColors = remember(baseColors, chromeWarmth) { baseColors.withChromeWarmth(chromeWarmth) }
    val arabic = LocalConfiguration.current.locales[0].language == "ar"
    val typography = remember(arabic) { bestNoteTypography(arabic) }
    val textStyles = remember(arabic) { bestNoteTextStyles(arabic) }
    CompositionLocalProvider(
        LocalBestNoteColors provides bestNoteColors,
        LocalBestNoteTextStyles provides textStyles,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = AtelierMaterialShapes,
            content = content,
        )
    }
}

/** Accessors for BestNote-specific tokens that Material 3 has no slot for. */
object BestNoteTheme {
    val colors: BestNoteColors
        @Composable @ReadOnlyComposable get() = LocalBestNoteColors.current

    val type: BestNoteTextStyles
        @Composable @ReadOnlyComposable get() = LocalBestNoteTextStyles.current

    /** True when the user turned animations off. Swap transforms for fades (see [Motion.Enter]). */
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

/** System "Remove animations" (animator duration scale 0). Read once per configuration. */
@Composable
fun systemReducedMotion(): Boolean {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    return remember(config) { isReducedMotion(context) }
}

internal fun isReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
