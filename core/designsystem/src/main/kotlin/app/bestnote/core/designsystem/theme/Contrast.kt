package app.bestnote.core.designsystem.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** WCAG AA minimums: body text, and icons / borders / other meaningful non-text marks. */
object ContrastAA {
    const val TEXT = 4.5f
    const val NON_TEXT = 3f
}

/** WCAG 2.x contrast ratio (1..21). A translucent [foreground] is composited over [background] first. */
fun contrastRatio(foreground: Color, background: Color): Float {
    val fg = if (foreground.alpha < 1f) foreground.compositeOver(background) else foreground
    val a = fg.luminance() + LUMINANCE_FLARE
    val b = background.luminance() + LUMINANCE_FLARE
    return if (a > b) a / b else b / a
}

/**
 * This color, nudged toward white (on a dark [background]) or black (on a light one) just enough to
 * reach [minRatio], keeping its hue. For small marks that carry a user's color (folder glyphs, ink
 * previews): navy ink stays recognisably navy on dark chrome without vanishing.
 */
fun Color.legibleOn(background: Color, minRatio: Float = ContrastAA.NON_TEXT): Color {
    if (this == Color.Unspecified || contrastRatio(this, background) >= minRatio) return this
    val target = if (background.luminance() < DARK_BACKGROUND) Color.White else Color.Black
    var t = 0f
    var out = this
    while (t < 1f && contrastRatio(out, background) < minRatio) {
        t += LEGIBLE_STEP
        out = lerp(this, target, t.coerceAtMost(1f))
    }
    return out
}

/** True when [color] needs a ring to read as a swatch on [background] (below the 3:1 non-text minimum). */
fun needsSwatchOutline(color: Color, background: Color): Boolean =
    contrastRatio(color, background) < ContrastAA.NON_TEXT

/**
 * A hairline ring ([BestNoteColors.swatchOutline]) around a color swatch that would vanish on
 * [background] (navy or black ink on dark chrome, yellow or white on light). No-op otherwise.
 */
@Composable
fun Modifier.swatchOutline(
    color: Color,
    shape: Shape = CircleShape,
    background: Color = MaterialTheme.colorScheme.surface,
    width: Dp = SwatchOutlineWidth,
): Modifier = if (needsSwatchOutline(color, background)) border(width, BestNoteTheme.colors.swatchOutline, shape) else this

/** The ring width for [swatchOutline]: 1.5 dp reads at arm's length on a tablet without looking heavy. */
val SwatchOutlineWidth: Dp = 1.5.dp

/** True when [color] is a dark chrome color (dark theme surfaces). */
fun isDarkChrome(color: Color): Boolean = color.luminance() < DARK_BACKGROUND

private const val LUMINANCE_FLARE = 0.05f
private const val DARK_BACKGROUND = 0.18f
private const val LEGIBLE_STEP = 0.04f
