package app.bestnote.feature.editor

import app.bestnote.engine.canvas.EraserMode
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import app.bestnote.engine.ink.BrushCatalog
import app.bestnote.engine.ink.InkPalette
import kotlin.math.roundToInt

/** The pens in the tray, left to right (DS-03). Each remembers its own color, size and opacity. */
enum class PenKind(val tool: Tool, val style: PenStyle) {
    Fountain(Tool.Pen, PenStyle.Fountain),
    Ballpoint(Tool.Pen, PenStyle.Ball),
    Brush(Tool.Pen, PenStyle.Brush),
    Pencil(Tool.Pencil, PenStyle.Fountain),
    Highlighter(Tool.Highlighter, PenStyle.Fountain),
    Eraser(Tool.Eraser, PenStyle.Fountain),
    ;

    val inks: Boolean get() = tool.inks
}

/** One pen's settings. [size] is in stroke units (the canvas brush size), [opacity] 0..1. */
data class PenSettings(val colorArgb: Int, val size: Float, val opacity: Float = 1f) {
    /** Color handed to the brush: the user's opacity goes into alpha (the brush adds its own on top). */
    val brushColorArgb: Int get() = BrushCatalog.applyOpacity(colorArgb or OPAQUE, opacity)
}

/** A quick preset in the picker. */
data class PenPreset(val colorArgb: Int, val size: Float)

private const val OPAQUE = 0xFF000000.toInt()
private const val QUICK_COLORS = 5

/** Everything the pen tray shows. Pure value: the view model swaps it atomically. */
data class Toolbox(
    val active: PenKind = PenKind.Fountain,
    val settings: Map<PenKind, PenSettings> = PenDefaults.settings,
    /** Per pen, the 5 color dots in the tray, most recently used first. */
    val recentColors: Map<PenKind, List<Int>> = PenDefaults.quickColors,
    /** What the eraser removes (CANVAS-01/02); also used by the side-button and pen-end eraser. */
    val eraserMode: EraserMode = EraserMode.Stroke,
    /** The lasso (CANVAS-08) is the tool in hand instead of [active]; picking a pen puts it down. */
    val lasso: Boolean = false,
) {
    /** The canvas tool in hand. */
    val tool: Tool get() = if (lasso) Tool.Lasso else active.tool

    val current: PenSettings get() = settingsOf(active)

    fun settingsOf(kind: PenKind): PenSettings = settings[kind] ?: PenDefaults.settings.getValue(kind)

    /** The 5 color dots for [kind]. The current color is always one of them. */
    fun quickColors(kind: PenKind = active): List<Int> = recentColors[kind] ?: PenDefaults.quickColors.getValue(kind)

    fun select(kind: PenKind) = copy(active = kind, lasso = false)

    fun withLasso(on: Boolean) = copy(lasso = on)

    /** Sets the active pen's color; a color not in the dots replaces the least recently added one. */
    fun withColor(colorArgb: Int, kind: PenKind = active): Toolbox {
        val color = colorArgb or OPAQUE
        val dots = quickColors(kind)
        val nextDots = if (color in dots) dots else (listOf(color) + dots).take(QUICK_COLORS)
        return copy(
            settings = settings + (kind to settingsOf(kind).copy(colorArgb = color)),
            recentColors = recentColors + (kind to nextDots),
        )
    }

    fun withSize(size: Float, kind: PenKind = active): Toolbox {
        val range = PenDefaults.sizeRange(kind)
        return copy(settings = settings + (kind to settingsOf(kind).copy(size = size.coerceIn(range.start, range.endInclusive))))
    }

    fun withOpacity(opacity: Float, kind: PenKind = active): Toolbox =
        copy(settings = settings + (kind to settingsOf(kind).copy(opacity = opacity.coerceIn(PenDefaults.MIN_OPACITY, 1f))))

    fun withPreset(preset: PenPreset, kind: PenKind = active): Toolbox = withColor(preset.colorArgb, kind).withSize(preset.size, kind)

    /** Quick palette center: the next preset size up, wrapping around to the smallest. */
    fun cycleSize(kind: PenKind = active): Toolbox {
        val sizes = PenDefaults.presets(kind).map { it.size }.distinct().sorted()
        if (sizes.isEmpty()) return this
        val now = settingsOf(kind).size
        return withSize(sizes.firstOrNull { it > now + SIZE_EPSILON } ?: sizes.first(), kind)
    }

    private companion object {
        const val SIZE_EPSILON = 0.01f
    }
}

/** Atelier defaults for every pen: inks, sizes, presets and the picker's color grid. */
object PenDefaults {
    const val MIN_OPACITY = 0.2f

    private const val LILAC = 0xFF7D6CA6.toInt()
    private const val HL_MINT = 0xFF8EE3C8.toInt()
    private const val HL_PINK = 0xFFF7A1C4.toInt()
    private const val HL_BLUE = 0xFF9CC7FF.toInt()
    private const val HL_ORANGE = 0xFFFFB870.toInt()

    val settings: Map<PenKind, PenSettings> = mapOf(
        PenKind.Fountain to PenSettings(InkPalette.BLACK, 5f),
        PenKind.Ballpoint to PenSettings(InkPalette.BLUE, 5f),
        PenKind.Brush to PenSettings(InkPalette.BLACK, 4f),
        PenKind.Pencil to PenSettings(InkPalette.GRAPHITE, 5f),
        PenKind.Highlighter to PenSettings(InkPalette.HIGHLIGHTER_YELLOW, 5f),
        PenKind.Eraser to PenSettings(InkPalette.BLACK, 5f),
    )

    private val inkDots = listOf(InkPalette.BLACK, InkPalette.BLUE, InkPalette.RED, InkPalette.GREEN, LILAC)

    val quickColors: Map<PenKind, List<Int>> = mapOf(
        PenKind.Fountain to inkDots,
        PenKind.Ballpoint to listOf(InkPalette.BLUE) + inkDots.filter { it != InkPalette.BLUE },
        PenKind.Brush to inkDots,
        PenKind.Pencil to listOf(InkPalette.GRAPHITE, InkPalette.BLACK, InkPalette.BLUE, InkPalette.RED, InkPalette.GREEN),
        PenKind.Highlighter to listOf(InkPalette.HIGHLIGHTER_YELLOW, HL_MINT, HL_PINK, HL_BLUE, HL_ORANGE),
        PenKind.Eraser to emptyList(),
    )

    /** The picker's color grid (6 columns). */
    val inkGrid: List<Int> = listOf(
        0xFF1E2433, 0xFF3B4150, 0xFF6A6F7C, 0xFF9AA1AD, 0xFF000000, 0xFF5B3A29,
        0xFF2A4BD7, 0xFF1B6FB5, 0xFF0E8A9E, 0xFF7D6CA6, 0xFF5B2A86, 0xFF23314F,
        0xFFD9462B, 0xFFB4553A, 0xFFC2185B, 0xFFE07A1F, 0xFFC99A2E, 0xFF8A1C1C,
        0xFF2F7D57, 0xFF5F8069, 0xFF3E8E41, 0xFF6B8E23, 0xFF0F5132, 0xFF1F6F5C,
    ).map { it.toInt() }

    val highlighterGrid: List<Int> = listOf(
        0xFFF7D84A, 0xFFFFE066, 0xFFFFB870, 0xFFFF8A80, 0xFFF7A1C4, 0xFFE58FE6,
        0xFFB39DFF, 0xFF9CC7FF, 0xFF7FDBFF, 0xFF8EE3C8, 0xFFB5E48C, 0xFFD9D9D9,
    ).map { it.toInt() }

    fun grid(kind: PenKind): List<Int> = if (kind == PenKind.Highlighter) highlighterGrid else inkGrid

    fun sizeRange(kind: PenKind): ClosedFloatingPointRange<Float> = when (kind) {
        PenKind.Highlighter -> 2f..16f
        PenKind.Brush -> 1f..16f
        else -> 1f..30f
    }

    fun presets(kind: PenKind): List<PenPreset> = when (kind) {
        PenKind.Highlighter -> listOf(
            PenPreset(InkPalette.HIGHLIGHTER_YELLOW, 4f),
            PenPreset(HL_MINT, 5f),
            PenPreset(HL_PINK, 6f),
            PenPreset(HL_BLUE, 8f),
        )
        PenKind.Pencil -> listOf(
            PenPreset(InkPalette.GRAPHITE, 3f),
            PenPreset(InkPalette.GRAPHITE, 6f),
            PenPreset(InkPalette.BLUE, 4f),
            PenPreset(InkPalette.RED, 8f),
        )
        PenKind.Brush -> listOf(
            PenPreset(InkPalette.BLACK, 2f),
            PenPreset(InkPalette.BLACK, 4f),
            PenPreset(InkPalette.RED, 6f),
            PenPreset(InkPalette.BLUE, 9f),
        )
        PenKind.Eraser -> emptyList()
        else -> listOf(
            PenPreset(InkPalette.BLACK, 3f),
            PenPreset(InkPalette.BLUE, 5f),
            PenPreset(InkPalette.RED, 8f),
            PenPreset(InkPalette.BLACK, 12f),
        )
    }

    /** Width the brush actually lays down, in mm on this screen, for labels like "0.4". */
    fun widthMm(kind: PenKind, size: Float, densityDpi: Int): Float {
        val scale = BrushCatalog.spec(kind.tool, kind.style)?.sizeScale ?: 1f
        return size * scale / (densityDpi / MM_PER_INCH)
    }

    /** "0.4", "1.2", "12": one decimal below 10 mm. */
    fun formatMm(mm: Float): String = if (mm >= 10f) mm.roundToInt().toString() else ((mm * 10).roundToInt() / 10f).toString()

    private const val MM_PER_INCH = 25.4f
}
