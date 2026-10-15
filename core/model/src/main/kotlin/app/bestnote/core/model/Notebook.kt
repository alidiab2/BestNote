/**
 * :core:model — BestNote's pure-Kotlin domain vocabulary.
 *
 * Plain immutable types shared by every layer (storage, engines, features). No Android,
 * no persistence annotations, no UI: anything here must be cheap to construct in a JVM test.
 */
package app.bestnote.core.model

/** Stable, opaque notebook identifier (a UUID string in practice). */
@JvmInline
value class NotebookId(val value: String) {
    init {
        require(value.isNotBlank()) { "NotebookId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Background pattern printed on a page. Persisted by name, so entries are only ever appended.
 * [Ruled] is college rule; [PaperColor] and the page size are separate properties of [Page].
 */
enum class PaperTemplate { Blank, Ruled, Grid, Dotted, RuledNarrow, RuledWide, Cornell, MusicStaff }

/** A notebook as shown in the library. Timestamps are epoch milliseconds. */
data class Notebook(
    val id: NotebookId,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val template: PaperTemplate = PaperTemplate.Ruled,
    val pageCount: Int = 1,
) {
    init {
        require(pageCount >= 1) { "A notebook always has at least one page" }
    }
}

/**
 * One page of a notebook. Sizes are in points (1/72 inch); the default is A4 portrait.
 * Ink on a page is stored in page-local points, origin at the page's top-left corner.
 */
data class Page(
    val notebookId: NotebookId,
    val index: Int,
    val template: PaperTemplate = PaperTemplate.Ruled,
    val widthPt: Float = A4_WIDTH_PT,
    val heightPt: Float = A4_HEIGHT_PT,
    val paperColor: PaperColor = PaperColor.White,
) {
    init {
        require(index >= 0) { "Page index must be >= 0" }
        require(widthPt > 0f && heightPt > 0f) { "Page size must be positive" }
    }

    val aspectRatio: Float get() = widthPt / heightPt

    val size: PageSize get() = PageSize(widthPt, heightPt)

    companion object {
        const val A4_WIDTH_PT = 595f
        const val A4_HEIGHT_PT = 842f
    }
}
