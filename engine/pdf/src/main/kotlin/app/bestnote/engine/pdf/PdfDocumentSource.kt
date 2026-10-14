/**
 * :engine:pdf — rasterizes PDF pages so they can sit underneath ink as page backgrounds.
 *
 * [PdfDocumentSource] is the contract the editor depends on; [PdfRendererDocumentSource]
 * implements it with the platform's [PdfRenderer]. Rendering is CPU-heavy: call from a
 * background dispatcher.
 */
package app.bestnote.engine.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/** Page size in PDF points (1/72 inch). */
data class PdfPageSize(val widthPt: Int, val heightPt: Int)

interface PdfDocumentSource : Closeable {
    val pageCount: Int

    fun pageSize(index: Int): PdfPageSize

    /** Renders page [index] scaled to [widthPx] wide (height follows the aspect ratio) on white. */
    fun renderPage(index: Int, widthPx: Int): Bitmap
}

/**
 * [PdfDocumentSource] over [PdfRenderer]. PdfRenderer allows one open page at a time and is
 * not thread-safe, so every call is serialized on this instance.
 */
class PdfRendererDocumentSource(descriptor: ParcelFileDescriptor) : PdfDocumentSource {
    private val renderer = PdfRenderer(descriptor)
    private val lock = Any()

    override val pageCount: Int get() = synchronized(lock) { renderer.pageCount }

    override fun pageSize(index: Int): PdfPageSize = synchronized(lock) {
        renderer.openPage(index).use { PdfPageSize(it.width, it.height) }
    }

    override fun renderPage(index: Int, widthPx: Int): Bitmap = synchronized(lock) {
        require(widthPx > 0) { "widthPx must be positive" }
        renderer.openPage(index).use { page ->
            val scale = widthPx.toFloat() / page.width
            val heightPx = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    override fun close() = synchronized(lock) { renderer.close() }

    companion object {
        fun open(file: File) = PdfRendererDocumentSource(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
    }
}
