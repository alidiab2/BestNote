package app.bestnote.feature.editor

import app.bestnote.core.model.ImageElement
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.PaperColor
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.PdfPageRef
import app.bestnote.engine.export.ExportPlan
import app.bestnote.engine.export.RangeParse
import kotlin.math.roundToInt

/** Where inserted pages go (PAGES-05). */
internal enum class InsertPosition { AfterCurrent, AtEnd }

/** Which pages of a picked PDF to insert: all of them, or the text the user typed ("1-3, 5"). */
internal sealed interface PdfSelection {
    data object All : PdfSelection
    data class Range(val text: String) : PdfSelection
}

/** Result of [InsertPlan.pdfPages]. */
internal sealed interface PdfPagesPlan {
    /** Zero-based pages of the PDF, ascending, to insert. */
    data class Pages(val indices: List<Int>) : PdfPagesPlan
    data class Invalid(val token: String) : PdfPagesPlan
}

/** The page and picture frame for one inserted image (page points). */
internal data class ImagePageFit(val widthPt: Float, val heightPt: Float, val frameW: Float, val frameH: Float)

/** Pure decisions of "Insert pages from PDF / image" (PAGES-05), unit tested. */
internal object InsertPlan {
    /** An image page is never narrower than 1:2.5 or wider than 2:1, so a panorama does not make a sliver of a page. */
    private const val MIN_ASPECT = 0.5f // height / width
    private const val MAX_ASPECT = 2.5f

    /** The index the first inserted page gets, for a document of [size] pages with [current] being read. */
    fun insertIndex(position: InsertPosition, current: Int, size: Int): Int = when (position) {
        InsertPosition.AtEnd -> size
        InsertPosition.AfterCurrent -> if (size == 0) 0 else (current.coerceIn(0, size - 1) + 1)
    }

    /** The pages of a [pageCount]-page PDF [selection] means. An empty range text means all pages. */
    fun pdfPages(selection: PdfSelection, pageCount: Int): PdfPagesPlan {
        if (selection is PdfSelection.Range && selection.text.isNotBlank()) {
            return when (val parsed = ExportPlan.parseRange(selection.text, pageCount)) {
                is RangeParse.Ok -> PdfPagesPlan.Pages(parsed.indices)
                is RangeParse.Invalid -> PdfPagesPlan.Invalid(parsed.token)
            }
        }
        return PdfPagesPlan.Pages((0 until pageCount).toList())
    }

    /** Pages for [indices] of the PDF stored at [asset], sized [sizes] (points); pages keep their PDF background. */
    fun pdfPageModels(notebook: NotebookId, asset: String, indices: List<Int>, sizes: (Int) -> Pair<Int, Int>): List<Page> =
        indices.map { i ->
            val (w, h) = sizes(i)
            Page(notebook, 0, PaperTemplate.Blank, w.coerceAtLeast(1).toFloat(), h.coerceAtLeast(1).toFloat(), pdf = PdfPageRef(asset, i))
        }

    /**
     * A page for a [picW] x [picH] picture: as wide as the reference page ([refW]), as tall as the picture's
     * proportions need (within [MIN_ASPECT]..[MAX_ASPECT]); the picture fills it, or is centered at full
     * width / height when the aspect had to be limited.
     */
    fun imageFit(picW: Int, picH: Int, refW: Float): ImagePageFit {
        val aspect = (picH.toFloat() / picW.coerceAtLeast(1)).coerceIn(MIN_ASPECT, MAX_ASPECT)
        val pageH = (refW * aspect).roundToInt().toFloat()
        val trueAspect = picH.toFloat() / picW.coerceAtLeast(1)
        return if (kotlin.math.abs(trueAspect - aspect) < EPS) {
            ImagePageFit(refW, pageH, refW, pageH)
        } else if (trueAspect < aspect) {
            ImagePageFit(refW, pageH, refW, refW * trueAspect) // wider than allowed: full width, bands above and below
        } else {
            ImagePageFit(refW, pageH, pageH / trueAspect, pageH) // taller than allowed: full height
        }
    }

    /** A page showing the picture at [asset] as a locked full-page element. */
    fun imagePage(notebook: NotebookId, template: Page?, asset: String, picW: Int, picH: Int, id: String): Pair<Page, List<PageElement>> {
        val refW = template?.widthPt ?: Page.A4_WIDTH_PT
        val fit = imageFit(picW, picH, refW)
        val page = Page(notebook, 0, PaperTemplate.Blank, fit.widthPt, fit.heightPt, paperColor = template?.paperColor ?: PaperColor.White)
        val element = ImageElement(id, fit.widthPt / 2f, fit.heightPt / 2f, fit.frameW, fit.frameH, asset, picW, picH, locked = true)
        return page to listOf(element)
    }

    private const val EPS = 0.001f
}
