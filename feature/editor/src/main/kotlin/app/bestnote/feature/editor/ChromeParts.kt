package app.bestnote.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.isDarkChrome
import app.bestnote.core.designsystem.theme.legibleOn
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.core.designsystem.theme.swatchOutline
import app.bestnote.engine.canvas.InkPreview
import app.bestnote.engine.ink.InkRenderer

/** Floating chrome surface: a white (graphite in dark) shape with the soft warm shadow. */
@Composable
internal fun Modifier.chromeSurface(shape: Shape = BestNoteShapes.Pill, alpha: Float = 1f): Modifier =
    softShadow(Elevation.Low, shape).background(MaterialTheme.colorScheme.surface.copy(alpha = alpha), shape)

/** Round icon button used inside the chrome pills (40 dp, a 48 dp target with the pill padding). */
@Composable
internal fun ChromeIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    shape: Shape = CircleShape,
    tint: Color = LocalContentColor.current,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(shape)
            .combinedClickable(
                enabled = enabled,
                onClickLabel = label,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onLongClick = onLongClick,
                onLongClickLabel = longClickLabel,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else DisabledAlpha),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Material's disabled emphasis: ~38% of on-surface, still discernible on dark chrome (>= 3:1 there). */
internal const val DisabledAlpha = 0.38f

/** A ring when [color] would vanish on the chrome surface (navy ink on graphite, white on white). */
@Composable
internal fun Modifier.lowContrastRing(color: Color, shape: Shape = CircleShape): Modifier = swatchOutline(color, shape)

/** Rim drawn around pen bodies on dark chrome, so a black pen still reads as a pen. */
@Composable
internal fun penRim(): Color =
    if (isDarkChrome(MaterialTheme.colorScheme.surface)) Color.White.copy(alpha = PEN_RIM_ALPHA) else Color.Unspecified

private const val PEN_RIM_ALPHA = 0.42f

/** The "more colors" dot. */
internal val ConicBrush = Brush.sweepGradient(
    listOf(Color(0xFFD9462B), Color(0xFFE0A526), Color(0xFF2F7D57), Color(0xFF2A4BD7), Color(0xFF7D6CA6), Color(0xFFD9462B)),
)

/**
 * A real ink stroke preview: the exact androidx.ink brush the canvas uses, as a short squiggle.
 * [maxWidthPx] caps very wide brushes so they stay readable in small buttons.
 */
@Composable
internal fun Modifier.inkSquiggle(
    kind: PenKind,
    settings: PenSettings,
    maxWidthPx: Float? = null,
    /** The chip behind the stroke when it is chrome, not paper: dark ink is lifted to stay visible on it. */
    legibleOn: Color = Color.Unspecified,
): Modifier {
    val renderer = remember { InkRenderer() }
    val shown = if (legibleOn == Color.Unspecified) {
        settings
    } else {
        val ink = Color(settings.colorArgb)
        val lifted = ink.legibleOn(legibleOn)
        if (lifted == ink) settings else settings.copy(colorArgb = lifted.toArgb())
    }
    return drawWithCache {
        val size = maxWidthPx?.let { cap ->
            val scale = app.bestnote.engine.ink.BrushCatalog.spec(kind.tool, kind.style)?.sizeScale ?: 1f
            settings.size.coerceAtMost(cap / scale)
        } ?: settings.size
        val stroke = InkPreview.squiggle(kind.tool, kind.style, shown.brushColorArgb, size, this.size.width, this.size.height, settings.tuning())
        onDrawBehind {
            if (stroke != null) drawIntoCanvas { InkPreview.draw(it.nativeCanvas, stroke, renderer) }
        }
    }
}
