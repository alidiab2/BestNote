package app.bestnote.engine.export

import app.bestnote.core.model.Page
import app.bestnote.core.storage.NotebookPackage
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import java.io.Closeable
import java.io.File

/**
 * Puts the annotation pages drawn by the exporter on top of the original pages of imported PDFs
 * (PDF-05). The original page is copied as is (text stays selectable, graphics stay vector); the
 * annotations arrive as a form XObject appended to its content.
 */
internal class PdfSourceMerger private constructor(private val docs: Map<String, PDDocument>) : Closeable {
    val isEmpty: Boolean get() = docs.isEmpty()

    /** True when [page]'s original PDF page can be merged (its PDF opened and has that page). */
    fun handles(page: Page): Boolean {
        val ref = page.pdf ?: return false
        val doc = docs[ref.asset] ?: return false
        return ref.pageIndex < doc.numberOfPages
    }

    /** Writes [overlay] (one page per entry of [pages], in order) merged with the originals to [target]. */
    fun merge(overlay: File, pages: List<Page>, target: File) {
        PDDocument.load(overlay, MemoryUsageSetting.setupMixed(MEMORY_BYTES)).use { annotations ->
            PDDocument(MemoryUsageSetting.setupMixed(MEMORY_BYTES)).use { out ->
                val layers = LayerUtility(out)
                pages.forEachIndexed { n, page ->
                    val ref = page.pdf
                    val doc = if (ref != null && handles(page)) docs.getValue(ref.asset) else null
                    val form = layers.importPageAsForm(annotations, n)
                    val matrix: FloatArray
                    val dest: PDPage
                    if (doc != null && ref != null) {
                        dest = out.importPage(doc.getPage(ref.pageIndex))
                        val crop = dest.cropBox
                        matrix = OverlayTransform.of(dest.rotation, crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height, page.widthPt, page.heightPt)
                    } else {
                        dest = PDPage(PDRectangle(page.widthPt, page.heightPt))
                        out.addPage(dest)
                        matrix = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
                    }
                    PDPageContentStream(out, dest, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.saveGraphicsState()
                        cs.transform(Matrix(matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]))
                        cs.drawForm(form)
                        cs.restoreGraphicsState()
                    }
                }
                target.outputStream().buffered().use { out.save(it) }
            }
        }
    }

    override fun close() {
        docs.values.forEach { runCatching { it.close() } }
    }

    companion object {
        private const val MEMORY_BYTES = 32L * 1024 * 1024

        /** Opens the PDFs behind [selected] pages of [pkg]; one PDF that fails to open is just left out. */
        fun open(pkg: NotebookPackage, selected: List<Int>, workDir: File): PdfSourceMerger {
            val docs = HashMap<String, PDDocument>()
            val assets = selected.mapNotNull { pkg.pages[it].page.pdf?.asset }.distinct().filter { it in pkg.assets }
            for (asset in assets) {
                try {
                    val file = File(workDir, "src-${docs.size}-${asset.substringAfterLast('/')}")
                    file.writeBytes(pkg.assets.getValue(asset))
                    docs[asset] = PDDocument.load(file, MemoryUsageSetting.setupMixed(MEMORY_BYTES))
                } catch (_: Exception) {
                    // damaged or password-protected: its pages fall back to a rendered background
                }
            }
            return PdfSourceMerger(docs)
        }
    }
}
