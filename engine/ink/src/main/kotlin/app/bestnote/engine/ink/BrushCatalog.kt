/**
 * :engine:ink — BestNote's brushes, defined on top of androidx.ink's stock brush families.
 *
 * [BrushSpec] is the pure, testable description of how a [Tool]/[PenStyle] pair should look
 * (which ink family, size scale, opacity). [BrushCatalog.brush] turns it into an
 * androidx.ink [Brush]. Specs are versioned by [InkFamily] so stored strokes keep rendering
 * identically after brush tuning.
 */
package app.bestnote.engine.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool

/** Stable keys for the androidx.ink stock families BestNote uses (persisted with strokes). */
enum class InkFamily(val key: String) {
    /** Pressure-sensitive width: fountain pen and brush. */
    PressurePen("pressure-pen.v1"),

    /** Constant width: ballpoint and pencil. */
    Marker("marker.v1"),

    /** Translucent, overlapping strokes don't darken. */
    Highlighter("highlighter.v1"),
}

/**
 * @property sizeScale multiplier applied to the user's chosen size.
 * @property opacity 0..1, multiplied into the color's alpha.
 */
data class BrushSpec(val family: InkFamily, val sizeScale: Float, val opacity: Float)

object BrushCatalog {
    /** Default width in stroke units (view px at 1x zoom). */
    const val DEFAULT_SIZE = 5f

    /** Geometry simplification tolerance in stroke units; 0.1 is androidx.ink's recommendation for screen units. */
    const val EPSILON = 0.1f

    /** @return null for tools that don't lay down ink (Eraser, Lasso). */
    fun spec(tool: Tool, penStyle: PenStyle = PenStyle.Fountain): BrushSpec? = when (tool) {
        Tool.Pen -> when (penStyle) {
            PenStyle.Fountain -> BrushSpec(InkFamily.PressurePen, sizeScale = 1f, opacity = 1f)
            PenStyle.Ball -> BrushSpec(InkFamily.Marker, sizeScale = 0.7f, opacity = 1f)
            PenStyle.Brush -> BrushSpec(InkFamily.PressurePen, sizeScale = 2.4f, opacity = 1f)
        }
        Tool.Pencil -> BrushSpec(InkFamily.Marker, sizeScale = 0.5f, opacity = 0.75f)
        Tool.Highlighter -> BrushSpec(InkFamily.Highlighter, sizeScale = 4f, opacity = 0.4f)
        Tool.Eraser, Tool.Lasso -> null
    }

    fun family(family: InkFamily): BrushFamily = when (family) {
        InkFamily.PressurePen -> StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
        InkFamily.Marker -> StockBrushes.marker(StockBrushes.MarkerVersion.V1)
        InkFamily.Highlighter -> StockBrushes.highlighter(SelfOverlap.DISCARD, StockBrushes.HighlighterVersion.V1)
    }

    /** Applies [opacity] to an ARGB color int. */
    fun applyOpacity(colorArgb: Int, opacity: Float): Int {
        val alpha = ((colorArgb ushr 24) * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (colorArgb and 0x00FFFFFF)
    }

    /** An androidx.ink [Brush] for [tool], or null when the tool doesn't ink. */
    fun brush(tool: Tool, penStyle: PenStyle, colorArgb: Int, size: Float = DEFAULT_SIZE): Brush? {
        val spec = spec(tool, penStyle) ?: return null
        return Brush.createWithColorIntArgb(family(spec.family), applyOpacity(colorArgb, spec.opacity), size * spec.sizeScale, EPSILON)
    }
}
