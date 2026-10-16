package app.bestnote.engine.canvas.document

import app.bestnote.core.model.PaperColor
import app.bestnote.engine.ink.InkPalette
import app.bestnote.engine.layout.paper.LineRole

/**
 * Colors of the sheet and its printed lines (approved Atelier paper: page #FFFEFB, ruled line
 * #DCE5F1, margin rgba(226,120,105,.45)). Paper never follows the app theme; dark paper is a page
 * property. ARGB ints so the renderer needs no Compose.
 */
data class PaperColors(
    val page: Int,
    val rule: Int,
    val margin: Int,
    val grid: Int,
    val dot: Int,
    val divider: Int,
    val staff: Int,
    val gridMajor: Int = divider,
) {
    fun line(role: LineRole): Int = when (role) {
        LineRole.Rule -> rule
        LineRole.Margin -> margin
        LineRole.Grid -> grid
        LineRole.Divider -> divider
        LineRole.Staff -> staff
        LineRole.GridMajor -> gridMajor
    }

    /** True for dark paper: ink colors switch to their light counterparts ([PaperInk]). */
    val isDark: Boolean get() = luminance(page) < 0.5f

    companion object {
        val White = PaperColors(
            page = 0xFFFFFEFB.toInt(),
            rule = 0xFFDCE5F1.toInt(),
            margin = 0x73E27869,
            grid = 0xFFE3EAF3.toInt(),
            dot = 0xFFB7C3D3.toInt(),
            divider = 0xFFAFBDD0.toInt(),
            staff = 0xFFB9C3CF.toInt(),
            gridMajor = 0xFFC6D2E2.toInt(),
        )
        val Cream = PaperColors(
            page = 0xFFFBF5E6.toInt(),
            rule = 0xFFE0D8C4.toInt(),
            margin = 0x73E27869,
            grid = 0xFFE7DFCC.toInt(),
            dot = 0xFFC4B9A0.toInt(),
            divider = 0xFFC2B59A.toInt(),
            staff = 0xFFC6BBA5.toInt(),
            gridMajor = 0xFFD2C7AE.toInt(),
        )
        val Dark = PaperColors(
            page = 0xFF24272D.toInt(),
            rule = 0xFF3B414B.toInt(),
            margin = 0x99E27869.toInt(),
            grid = 0xFF343943.toInt(),
            dot = 0xFF59606C.toInt(),
            divider = 0xFF5B6371.toInt(),
            staff = 0xFF545B67.toInt(),
            gridMajor = 0xFF4A515D.toInt(),
        )

        fun of(color: PaperColor): PaperColors = when (color) {
            PaperColor.White -> White
            PaperColor.Cream -> Cream
            PaperColor.Dark -> Dark
        }

        /** Relative luminance 0..1 of an opaque ARGB color (sRGB weights, no gamma: good enough to pick a side). */
        fun luminance(argb: Int): Float {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        }
    }
}

/**
 * Keeps the ink palette readable on dark paper. Each default ink has a light twin (the Atelier
 * dark-theme values); on dark paper ink is drawn with the twin, on light paper with the original.
 * The mapping is symmetric, so a stroke written on dark paper turns dark again if the page is
 * switched back to white, and other colors pass through untouched (dark unknown colors are lifted).
 */
object PaperInk {
    private val pairs = listOf(
        InkPalette.BLACK to 0xFFE9E7E1.toInt(),
        InkPalette.BLUE to 0xFFA9B6FF.toInt(),
        InkPalette.RED to 0xFFFF8A70.toInt(),
        InkPalette.GREEN to 0xFF7CC39C.toInt(),
        InkPalette.GRAPHITE to 0xFFC3C6CF.toInt(),
    )
    private val toLight = pairs.associate { (d, l) -> d to l }
    private val toDark = pairs.associate { (d, l) -> l to d }

    /** The color to draw [argb] ink with on [paper]. Alpha is preserved. */
    fun colorOn(argb: Int, paper: PaperColors): Int {
        val alpha = argb and 0xFF000000.toInt()
        val rgb = argb or 0xFF000000.toInt()
        val mapped = if (paper.isDark) {
            toLight[rgb] ?: if (PaperColors.luminance(rgb) < 0.3f) lift(rgb) else rgb
        } else {
            toDark[rgb] ?: rgb
        }
        return alpha or (mapped and 0x00FFFFFF)
    }

    /** Moves a dark color 70 % of the way to white, keeping its hue. */
    private fun lift(rgb: Int): Int {
        fun ch(shift: Int): Int {
            val c = (rgb shr shift) and 0xFF
            return (c + (255 - c) * 0.7f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
