package app.bestnote.engine.export

import android.graphics.Bitmap
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.Page
import app.bestnote.core.model.PageElement
import java.io.ByteArrayOutputStream

/** Small pictures of whole pages, e.g. for search results (REC-03). */
object PageThumbnails {
    /** [page] with its paper, typed text and ink, [widthPx] wide, as WebP bytes. Pictures on the page are left out. */
    fun webp(page: Page, strokes: List<InkStroke>, elements: List<PageElement>, widthPx: Int, quality: Int = 80): ByteArray {
        val bitmap = PageGraphics.bitmap(page, strokes, scale = widthPx / page.widthPt, elements = elements)
        try {
            val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            return ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
