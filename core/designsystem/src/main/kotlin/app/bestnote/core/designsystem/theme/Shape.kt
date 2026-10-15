package app.bestnote.core.designsystem.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** Atelier shapes. All corners use start/end, so they mirror correctly in RTL chrome. */
object BestNoteShapes {
    val Pill: Shape = CircleShape
    val Control: CornerBasedShape = RoundedCornerShape(12.dp)
    val Card: CornerBasedShape = RoundedCornerShape(18.dp)
    val ToolTray: CornerBasedShape = RoundedCornerShape(24.dp)
    val Sheet: CornerBasedShape = RoundedCornerShape(28.dp)
    val SheetTop: CornerBasedShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** Notebook cover: small radius on the spine (start) side, softer on the fore-edge. */
    val NotebookCover: CornerBasedShape =
        RoundedCornerShape(topStart = 3.dp, topEnd = 10.dp, bottomEnd = 10.dp, bottomStart = 3.dp)
}

internal val AtelierMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = BestNoteShapes.Control,
    medium = BestNoteShapes.Card,
    large = BestNoteShapes.ToolTray,
    extraLarge = BestNoteShapes.Sheet,
)

/** Elevation tokens: soft, long, warm shadows and 1 dp hairlines instead of heavy borders. */
object Elevation {
    val Hairline: Dp = 1.dp

    /** Resting card / thumbnail. */
    val Low = ShadowToken(radius = 10.dp, offsetY = 4.dp, alpha = 0.18f)

    /** Notebook covers, floating tool tray. */
    val Medium = ShadowToken(radius = 24.dp, offsetY = 10.dp, alpha = 0.30f)

    /** Sheets, popovers, dragged items (0 16 34 at ~45%). */
    val High = ShadowToken(radius = 34.dp, offsetY = 16.dp, alpha = 0.45f)
}

data class ShadowToken(val radius: Dp, val offsetY: Dp, val alpha: Float)

/** Draws a soft long shadow tinted with [BestNoteColors.shadowTint]. Never use on the page canvas. */
@Composable
fun Modifier.softShadow(token: ShadowToken, shape: Shape): Modifier {
    val tint = BestNoteTheme.colors.shadowTint
    return dropShadow(
        shape = shape,
        shadow = Shadow(
            radius = token.radius,
            color = tint.copy(alpha = token.alpha),
            offset = DpOffset(0.dp, token.offsetY),
        ),
    )
}

/** 1 dp hairline outline in the theme's outline color. */
@Composable
fun Modifier.hairline(shape: Shape): Modifier =
    border(Elevation.Hairline, MaterialTheme.colorScheme.outline, shape)
