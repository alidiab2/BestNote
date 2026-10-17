package app.bestnote.feature.editor

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp

/**
 * Realistic pen illustrations for the pen tray (the approved Atelier mock, ported from SVG).
 * Drawn in a 26 x 78 unit box, nib up; [drawPen] scales that box to the draw area's width.
 * Ink-dependent parts (barrel, band, tip) take the pen's current color.
 */
internal object PenArt {
    const val WIDTH = 26f
    const val HEIGHT = 78f

    private val Gold = Brush.horizontalGradient(listOf(Color(0xFFF1D894), Color(0xFFD2AE58), Color(0xFFA9853A)), 7f, 19f)
    private val GoldShade = Color(0xFF7A5E22)
    private val Section = Color(0xFF2B3247)
    private val Hairline = Color(0xFFE0DBD0)
    private val Steel = Color(0xFFC7CBD3)
    private val Wood = Color(0xFFEBCB9C)
    private val Graphite = Color(0xFF4A4C52)
    private val PencilBody = Color(0xFFF2C14E)
    private val PencilLight = Color(0xFFF7D277)
    private val PencilDark = Color(0xFFDDA93A)
    private val EraserPink = Color(0xFFF2AAA2)
    private val EraserCollar = Color(0xFFD8D3C8)
    private val WhiteBody = Brush.horizontalGradient(listOf(Color.White, Color(0xFFF1EFEA), Color(0xFFD9D5CC)), 6f, 20f)
    private val Cream = Color(0xFFF3F0E8)
    private val BrushBody = Brush.horizontalGradient(listOf(Color(0xFF4A4D57), Color(0xFF2B2D33), Color(0xFF17181C)), 6f, 20f)

    // Dark chrome: the same pens under a desk lamp. Lifted barrels so navy/black pens keep their shape.
    private val SectionOnDark = Color(0xFF4F566B)
    private val BrushBodyOnDark = Brush.horizontalGradient(listOf(Color(0xFF7E828D), Color(0xFF5A5D66), Color(0xFF3C3E45)), 6f, 20f)
    private const val BARREL_LIFT_ON_DARK = 0.32f
    private val Ferrule = Brush.horizontalGradient(listOf(Color(0xFFE6E8EC), Color(0xFFB9BDC6), Color(0xFF8D919B)), 8f, 18f)

    /**
     * @param rim optional outline around the body, or [Color.Unspecified]. A rim means dark chrome: dark
     *   barrels are also lifted toward a lighter tone there (the ink band and tip keep the true ink).
     */
    fun DrawScope.drawPen(kind: PenKind, ink: Color, rim: Color = Color.Unspecified) {
        val s = size.width / WIDTH
        val onDark = rim != Color.Unspecified
        scale(s, s, pivot = Offset.Zero) {
            when (kind) {
                PenKind.Fountain -> fountain(ink, onDark)
                PenKind.Ballpoint -> ballpoint(ink)
                PenKind.Brush -> brushPen(ink, onDark)
                PenKind.Pencil -> pencil(ink)
                PenKind.Highlighter -> highlighter(ink)
                PenKind.Eraser -> eraser()
            }
            if (rim != Color.Unspecified) {
                val (x, y, w, r) = when (kind) {
                    PenKind.Fountain -> floatArrayOf(5.5f, 27f, 15f, 5f)
                    PenKind.Ballpoint -> floatArrayOf(6.5f, 19f, 13f, 6f)
                    PenKind.Brush -> floatArrayOf(6.5f, 26f, 13f, 4f)
                    PenKind.Pencil -> floatArrayOf(6.5f, 21f, 13f, 0f)
                    PenKind.Highlighter, PenKind.Eraser -> floatArrayOf(5f, 21f, 16f, 4f)
                }
                drawRoundRect(rim, Offset(x, y), Size(w, HEIGHT + 4f - y), CornerRadius(r, r), style = Stroke(0.9f))
            }
        }
    }

    private fun path(vararg points: Float) = Path().apply {
        moveTo(points[0], points[1])
        var i = 2
        while (i < points.size) {
            lineTo(points[i], points[i + 1])
            i += 2
        }
        close()
    }

    private fun DrawScope.body(x: Float, y: Float, w: Float, h: Float, r: Float, brush: Brush) =
        drawRoundRect(brush, Offset(x, y), Size(w, h), CornerRadius(r, r))

    private fun DrawScope.body(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color) =
        drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(r, r))

    private fun DrawScope.outline(x: Float, y: Float, w: Float, h: Float, r: Float) =
        drawRoundRect(Hairline, Offset(x, y), Size(w, h), CornerRadius(r, r), style = Stroke(0.8f))

    /** Gold nib, dark section, barrel in a deep shade of the ink. */
    private fun DrawScope.fountain(ink: Color, onDark: Boolean) {
        drawPath(path(7.5f, 21f, 13f, 4f, 18.5f, 21f), Gold)
        drawLine(GoldShade, Offset(13f, 9.5f), Offset(13f, 17.5f), strokeWidth = 0.9f)
        drawCircle(GoldShade, 1f, Offset(13f, 15f))
        drawPath(path(7f, 20f, 19f, 20f, 18f, 28f, 8f, 28f), if (onDark) SectionOnDark else Section)
        val base = if (onDark) lerp(ink, Color.White, BARREL_LIFT_ON_DARK) else ink
        val barrel = Brush.horizontalGradient(
            listOf(lerp(base, Color.White, 0.18f), base, lerp(base, Color.Black, if (onDark) 0.25f else 0.45f)),
            5.5f,
            20.5f,
        )
        body(5.5f, 27f, 15f, 54f, 5f, barrel)
        drawRect(Gold, Offset(5.5f, 36f), Size(15f, 2.6f))
        body(8f, 30f, 2f, 40f, 1f, Color.White.copy(alpha = if (onDark) 0.3f else 0.18f))
        // The true ink as a thin cap ring, so a lifted barrel still names its color.
        if (onDark) drawRect(ink, Offset(5.5f, 39.4f), Size(15f, 2.4f))
    }

    private fun DrawScope.ballpoint(ink: Color) {
        drawPath(path(9.5f, 20f, 13f, 7f, 16.5f, 20f), Steel)
        drawCircle(ink, 1.4f, Offset(13f, 7.6f))
        body(6.5f, 19f, 13f, 60f, 6f, WhiteBody)
        outline(6.5f, 19f, 13f, 60f, 6f)
        drawRect(ink, Offset(6.5f, 30f), Size(13f, 5f))
    }

    /** Pointed brush tip dipped in ink, steel ferrule, matte black handle with an ink band. */
    private fun DrawScope.brushPen(ink: Color, onDark: Boolean) {
        val tip = Path().apply {
            moveTo(13f, 3f)
            cubicTo(14.5f, 8f, 17.5f, 14f, 17.2f, 21f)
            lineTo(8.8f, 21f)
            cubicTo(8.5f, 14f, 11.5f, 8f, 13f, 3f)
            close()
        }
        drawPath(tip, ink)
        if (onDark) drawPath(tip, Color.White.copy(alpha = 0.4f), style = Stroke(0.8f))
        drawPath(path(8.5f, 20f, 17.5f, 20f, 17.5f, 27f, 8.5f, 27f), Ferrule)
        body(6.5f, 26f, 13f, 56f, 4f, if (onDark) BrushBodyOnDark else BrushBody)
        drawRect(ink, Offset(6.5f, 34f), Size(13f, 3f))
    }

    private fun DrawScope.pencil(ink: Color) {
        drawPath(path(6.5f, 22f, 13f, 4f, 19.5f, 22f), Wood)
        drawPath(path(11f, 9.5f, 13f, 4f, 15f, 9.5f), if (ink == Color.Unspecified) Graphite else ink)
        drawRect(PencilBody, Offset(6.5f, 21f), Size(13f, 60f))
        drawRect(PencilLight, Offset(11f, 21f), Size(4f, 60f))
        drawRect(PencilDark, Offset(6.5f, 21f), Size(2f, 60f))
    }

    private fun DrawScope.highlighter(ink: Color) {
        drawPath(path(7f, 22f, 8f, 10f, 19f, 7f, 19f, 22f), ink)
        body(5f, 21f, 16f, 60f, 4f, Cream)
        outline(5f, 21f, 16f, 60f, 4f)
        drawRect(ink, Offset(5f, 31f), Size(16f, 6f))
    }

    private fun DrawScope.eraser() {
        body(5f, 6f, 16f, 18f, 6f, EraserPink)
        body(5f, 21f, 16f, 60f, 3f, Color.White)
        outline(5f, 21f, 16f, 60f, 3f)
        drawRect(EraserCollar, Offset(5f, 21f), Size(16f, 4f))
    }
}
