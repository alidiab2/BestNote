/**
 * :engine:ink — BestNote's brushes, defined on top of androidx.ink brush families.
 *
 * [BrushSpec] is the pure, testable description of how a [Tool]/[PenStyle] pair should look
 * (which ink family, size scale, opacity). [BrushCatalog.brush] turns it into an
 * androidx.ink [Brush]; [InkRenderer] draws finished strokes with highlighters under ink.
 * Families are versioned by [InkFamily] key (persisted next to each stroke) so stored strokes
 * keep rendering identically after brush tuning. Tuning constants live in [BrushTuning].
 */
package app.bestnote.engine.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool

/** Stable keys for BestNote's brush families; storage persists [key]/[clientId] with each stroke. */
enum class InkFamily(val key: String) {
    /** Pressure-sensitive width (stock pressure pen). */
    Fountain("fountain.v1"),

    /** Near-constant width. */
    Ballpoint("ballpoint.v1"),

    /** Wide pressure range, tapered ends. */
    BrushPen("brush-pen.v1"),

    /** Constant-width graphite placeholder until INK-02. */
    Pencil("pencil.v1"),

    /** Flat chisel; translucent, drawn under ink, overlaps don't darken. */
    Highlighter("highlighter-chisel.v1"),
    ;

    /** Namespaced id to persist with strokes (androidx.ink 1.1 keeps its own family ids internal). */
    val clientId: String get() = ID_PREFIX + key

    companion object {
        const val ID_PREFIX = "bestnote:"

        /** The family a stored stroke was drawn with, or null for foreign/unknown ids. */
        fun fromClientId(id: String?): InkFamily? = entries.firstOrNull { it.clientId == id }
    }
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
            PenStyle.Fountain -> BrushSpec(InkFamily.Fountain, BrushTuning.Fountain.SIZE_SCALE, opacity = 1f)
            PenStyle.Ball -> BrushSpec(InkFamily.Ballpoint, BrushTuning.Ballpoint.SIZE_SCALE, opacity = 1f)
            PenStyle.Brush -> BrushSpec(InkFamily.BrushPen, BrushTuning.BrushPen.SIZE_SCALE, opacity = 1f)
        }
        Tool.Pencil -> BrushSpec(InkFamily.Pencil, BrushTuning.Pencil.SIZE_SCALE, BrushTuning.Pencil.OPACITY)
        Tool.Highlighter -> BrushSpec(InkFamily.Highlighter, BrushTuning.Highlighter.SIZE_SCALE, BrushTuning.Highlighter.OPACITY)
        Tool.Eraser, Tool.Lasso -> null
    }

    fun family(family: InkFamily): BrushFamily = BrushFamilies[family]

    /** True when [brush] is a BestNote highlighter (drawn under ink by [InkRenderer]). */
    fun isHighlighter(brush: Brush): Boolean = familyOf(brush) == InkFamily.Highlighter

    /** Which BestNote family [brush] uses, or null for brushes BestNote didn't create. */
    fun familyOf(brush: Brush): InkFamily? = BrushFamilies.identify(brush.family)

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
