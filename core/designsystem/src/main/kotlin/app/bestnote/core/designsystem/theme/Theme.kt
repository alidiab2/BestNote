/**
 * :core:designsystem — BestNote's visual language: Material 3 theme, motion and spacing tokens.
 *
 * Features consume tokens from here instead of hard-coding colors, durations or dp values,
 * so the whole app can be re-tuned in one place.
 */
package app.bestnote.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val BrandInk = Color(0xFF2D5BFF)
private val BrandInkDark = Color(0xFFB6C4FF)

private val LightColors = lightColorScheme(
    primary = BrandInk,
    secondary = Color(0xFF5A5D72),
    tertiary = Color(0xFF8B4A62),
)

private val DarkColors = darkColorScheme(
    primary = BrandInkDark,
    secondary = Color(0xFFC2C5DD),
    tertiary = Color(0xFFFFB0CB),
)

/**
 * App theme. Uses Material You dynamic color on Android 12+ (API 31) when [dynamicColor]
 * is true, otherwise BestNote's brand palette.
 */
@Composable
fun BestNoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
