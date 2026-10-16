package app.bestnote.core.model

/** A rectangle in page points (the page's own coordinates, y down). */
data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun union(o: PageRect) = PageRect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
}

/** Where searchable text on a page came from (REC-02). */
enum class SegmentKind { Ink, Text, Pdf }

/**
 * A page of a notebook that matches a search (REC-03). [highlights] are the matched words' boxes in page
 * points: handwriting boxes the matched ink, text boxes and PDF text give the frame of what matched.
 * [pageIndex] is where the page was when it was indexed; open it by [pageId], which survives reordering.
 */
data class PageSearchHit(
    val notebookId: NotebookId,
    val pageId: String,
    val pageIndex: Int,
    val snippet: String,
    val kind: SegmentKind,
    val highlights: List<PageRect>,
    /** Cached picture of the page, or null while it has not been rendered. */
    val thumbnailPath: String?,
    val pageWidthPt: Float,
    val pageHeightPt: Float,
)
