package app.bestnote.feature.library

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** A cover: a `CoverPalette` color plus a finish. */
data class CoverChoice(val colorIndex: Int, val style: CoverStyle = CoverStyle.Plain)

/** The covers offered by the new-notebook sheet and "Change cover": the six Atelier colors, then patterns. */
internal object CoverChoices {
    val all: List<CoverChoice> = List(COLORS) { CoverChoice(it) } + listOf(
        CoverChoice(0, CoverStyle.Stripes),
        CoverChoice(1, CoverStyle.Dots),
        CoverChoice(2, CoverStyle.Grid),
        CoverChoice(4, CoverStyle.Dots),
        CoverChoice(5, CoverStyle.Stripes),
        CoverChoice(3, CoverStyle.Grid),
    )

    private const val COLORS = 6
}

private const val PATTERN_TINT = 0.16f

/**
 * Prints [style] onto a cover of color [cover]: fine pinstripes, a dot grid or a graph grid, in a
 * slightly lighter shade of the cloth, so the label stays the focus. [scale] shrinks the pattern.
 */
internal fun Modifier.coverPattern(style: CoverStyle, cover: Color, paper: Color, scale: Float = 1f): Modifier {
    if (style == CoverStyle.Plain) return this
    return drawWithCache {
        val ink = lerp(cover, paper, PATTERN_TINT)
        val step = (if (style == CoverStyle.Stripes) 7.dp else 10.dp).toPx() * scale
        val stroke = 1.dp.toPx().coerceAtLeast(1f) * scale.coerceAtLeast(0.6f)
        onDrawBehind {
            when (style) {
                CoverStyle.Stripes -> {
                    var x = step
                    while (x < size.width) {
                        drawLine(ink, Offset(x, 0f), Offset(x, size.height), stroke)
                        x += step
                    }
                }
                CoverStyle.Dots -> {
                    var y = step / 2
                    while (y < size.height) {
                        var x = step / 2
                        while (x < size.width) {
                            drawCircle(ink, radius = stroke * 1.2f, center = Offset(x, y))
                            x += step
                        }
                        y += step
                    }
                }
                CoverStyle.Grid -> {
                    var x = step
                    while (x < size.width) {
                        drawLine(ink, Offset(x, 0f), Offset(x, size.height), stroke * 0.8f)
                        x += step
                    }
                    var y = step
                    while (y < size.height) {
                        drawLine(ink, Offset(0f, y), Offset(size.width, y), stroke * 0.8f)
                        y += step
                    }
                }
                CoverStyle.Plain -> Unit
            }
        }
    }
}
