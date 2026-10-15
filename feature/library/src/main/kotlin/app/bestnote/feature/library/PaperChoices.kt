package app.bestnote.feature.library

import androidx.annotation.StringRes
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperTemplate

/**
 * The single list of paper templates the library offers. Built from [PaperTemplate.entries], so
 * templates PAGES-01/06 add show up automatically; add a label (and, if wanted, a preview style in
 * `PaperPreview`) for them here. Order: the most used first.
 */
internal object PaperTemplates {
    private val preferredOrder = listOf(PaperTemplate.Ruled, PaperTemplate.Blank, PaperTemplate.Grid, PaperTemplate.Dotted)

    private val labels: Map<PaperTemplate, Int> = mapOf(
        PaperTemplate.Blank to R.string.library_template_blank,
        PaperTemplate.Ruled to R.string.library_template_ruled,
        PaperTemplate.Grid to R.string.library_template_grid,
        PaperTemplate.Dotted to R.string.library_template_dotted,
    )

    val all: List<PaperTemplate> =
        preferredOrder.filter { it in PaperTemplate.entries } + PaperTemplate.entries.filterNot { it in preferredOrder }

    /** String resource for [template], or null for a template added after this list (show its name). */
    @StringRes
    fun label(template: PaperTemplate): Int? = labels[template]

    val Default: PaperTemplate = PaperTemplate.Ruled

    /** Quicknotes always start on a blank page. */
    val Quicknote: PaperTemplate = PaperTemplate.Blank
}

/** Paper sizes in points (1/72 inch), portrait. PAGES-02 may move this into `:core:model`. */
enum class PaperSize(val widthPt: Float, val heightPt: Float, @StringRes val label: Int) {
    A4(Page.A4_WIDTH_PT, Page.A4_HEIGHT_PT, R.string.library_size_a4),
    A5(420f, 595f, R.string.library_size_a5),
    Letter(612f, 792f, R.string.library_size_letter),
}

/** A paper size plus orientation. */
data class PaperFormat(val size: PaperSize = PaperSize.A4, val landscape: Boolean = false) {
    val widthPt: Float get() = if (landscape) size.heightPt else size.widthPt
    val heightPt: Float get() = if (landscape) size.widthPt else size.heightPt
    val aspectRatio: Float get() = widthPt / heightPt
}

/** Everything the new-notebook sheet asks for. */
data class NewNotebookSpec(
    val title: String,
    val cover: CoverChoice = CoverChoices.all.first(),
    val template: PaperTemplate = PaperTemplates.Default,
    val format: PaperFormat = PaperFormat(),
)
