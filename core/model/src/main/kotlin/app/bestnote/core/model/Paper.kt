package app.bestnote.core.model

/** Color of the sheet itself. [Dark] is a dark page with light lines (a template, not the app theme). */
enum class PaperColor { White, Cream, Dark }

enum class PaperOrientation { Portrait, Landscape }

/** Size choices offered when adding or changing a page (PAGES-02). */
enum class PaperSizePreset { A4, A5, Letter, FitToScreen, Custom }

/** Paper sizes in points (1/72 in). Portrait sizes have width <= height. */
object PaperSizes {
    val A4 = PageSize.A4
    val A5 = PageSize(420f, 595f)
    val Letter = PageSize.Letter

    /** Custom sizes are kept between 2 in and 40 in per side. */
    const val MIN_SIDE_PT = 144f
    const val MAX_SIDE_PT = 2880f

    const val PT_PER_MM = 72f / 25.4f

    /**
     * The size for [preset] in [orientation].
     *
     * [FitToScreen][PaperSizePreset.FitToScreen] keeps A4's width and takes the height from the
     * viewport's aspect ratio ([viewportAspect] = height / width), so one page fills the screen.
     * [Custom][PaperSizePreset.Custom] uses [custom] (clamped), oriented like the other presets.
     */
    fun of(
        preset: PaperSizePreset,
        orientation: PaperOrientation = PaperOrientation.Portrait,
        viewportAspect: Float = A4.heightPt / A4.widthPt,
        custom: PageSize = A4,
    ): PageSize {
        val base = when (preset) {
            PaperSizePreset.A4 -> A4
            PaperSizePreset.A5 -> A5
            PaperSizePreset.Letter -> Letter
            PaperSizePreset.FitToScreen -> {
                // The viewport already is the shape the user holds: never swap it.
                val aspect = viewportAspect.takeIf { it.isFinite() && it > 0f } ?: (A4.heightPt / A4.widthPt)
                return clamp(PageSize(A4.widthPt, A4.widthPt * aspect))
            }
            PaperSizePreset.Custom -> clamp(custom)
        }
        return oriented(base, orientation)
    }

    fun oriented(size: PageSize, orientation: PaperOrientation): PageSize {
        val short = minOf(size.widthPt, size.heightPt)
        val long = maxOf(size.widthPt, size.heightPt)
        return if (orientation == PaperOrientation.Portrait) PageSize(short, long) else PageSize(long, short)
    }

    fun orientationOf(size: PageSize): PaperOrientation =
        if (size.widthPt > size.heightPt) PaperOrientation.Landscape else PaperOrientation.Portrait

    /** The named preset [size] matches in either orientation (within 1 pt), else [PaperSizePreset.Custom]. */
    fun presetOf(size: PageSize): PaperSizePreset {
        val short = minOf(size.widthPt, size.heightPt)
        val long = maxOf(size.widthPt, size.heightPt)
        fun near(p: PageSize) = kotlin.math.abs(p.widthPt - short) <= 1f && kotlin.math.abs(p.heightPt - long) <= 1f
        return when {
            near(A4) -> PaperSizePreset.A4
            near(A5) -> PaperSizePreset.A5
            near(Letter) -> PaperSizePreset.Letter
            else -> PaperSizePreset.Custom
        }
    }

    fun clamp(size: PageSize) = PageSize(
        size.widthPt.coerceIn(MIN_SIDE_PT, MAX_SIDE_PT),
        size.heightPt.coerceIn(MIN_SIDE_PT, MAX_SIDE_PT),
    )
}
