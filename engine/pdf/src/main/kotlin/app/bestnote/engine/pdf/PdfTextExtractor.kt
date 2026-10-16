package app.bestnote.engine.pdf

import android.annotation.SuppressLint
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import java.io.Closeable
import java.io.File

/** A run of text on a PDF page and its box in page points (the page's own size, y down). */
data class PdfTextRun(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Text of imported PDFs, for search (REC-03). The platform's [PdfRenderer] exposes text from Android 15
 * (SDK extension 13 of Android 12 and later); on older systems [open] returns null and PDF pages are simply not
 * searchable by their printed text.
 */
object PdfTextExtractor {
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= EXTENSION

    /** Opens [file] for reading text, or null when unsupported or the PDF cannot be opened. Close it when done. */
    @SuppressLint("NewApi")
    fun open(file: File): Reader? {
        if (!isSupported) return null
        return try {
            Reader(PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)))
        } catch (_: Exception) {
            null // damaged, password protected, or unreadable
        }
    }

    class Reader internal constructor(private val renderer: PdfRenderer) : Closeable {
        val pageCount: Int get() = renderer.pageCount

        /** The text runs of page [index]; empty for a scanned page or when it cannot be read. */
        @SuppressLint("NewApi")
        fun text(index: Int): List<PdfTextRun> = try {
            renderer.openPage(index).use { page ->
                page.textContents.mapNotNull { content ->
                    val text = content.text.trim()
                    val bounds = content.bounds
                    if (text.isEmpty() || bounds.isEmpty()) {
                        null
                    } else {
                        PdfTextRun(
                            text,
                            bounds.minOf { it.left }.toFloat(),
                            bounds.minOf { it.top }.toFloat(),
                            bounds.maxOf { it.right }.toFloat(),
                            bounds.maxOf { it.bottom }.toFloat(),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("BestNotePdfText", "Cannot read text of page $index", e)
            emptyList()
        }

        override fun close() = renderer.close()
    }

    private const val EXTENSION = 13
}
