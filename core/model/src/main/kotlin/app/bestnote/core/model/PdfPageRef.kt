package app.bestnote.core.model

/**
 * Which page of an imported PDF a [Page] shows as its background. [asset] is the PDF's archive path
 * (`assets/<name>.pdf`) inside the same notebook; [pageIndex] is zero-based. The page's own size
 * (`widthPt`/`heightPt`) is the PDF page's size, so ink coordinates stay valid at every zoom.
 */
data class PdfPageRef(val asset: String, val pageIndex: Int) {
    init {
        require(asset.isNotBlank()) { "PDF asset path must not be blank" }
        require(pageIndex >= 0) { "PDF page index must be >= 0" }
    }
}
