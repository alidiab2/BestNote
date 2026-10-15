package app.bestnote.engine.pdf

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.PdfPageRef
import app.bestnote.core.storage.InkChunkCodec
import app.bestnote.core.storage.NotebookPackage
import app.bestnote.core.storage.NotebookStore
import app.bestnote.core.storage.PageContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

/** The PDF could not become a notebook: not a PDF, damaged, password protected or empty. */
class PdfImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns a PDF into a new notebook (PDF-01): the file is stored whole as `assets/source.pdf` inside
 * the notebook package, and each PDF page becomes a notebook page of the same size whose
 * [Page.pdf] points at it. Nothing is rasterized here (pages render lazily when shown).
 */
class PdfImporter(
    private val store: NotebookStore,
    private val pageSizes: (File) -> List<PdfPageSize> = ::readPageSizes,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * Reads the PDF from [input], stores it as a notebook titled [title] and returns it.
     * [workDir] holds a temporary copy (PdfRenderer needs a seekable file).
     * @throws PdfImportException if [input] is not a usable PDF.
     */
    suspend fun import(input: InputStream, title: String, workDir: File): Notebook = withContext(io) {
        workDir.mkdirs()
        val tmp = File.createTempFile("import-", ".pdf", workDir)
        try {
            input.use { src -> tmp.outputStream().use { src.copyTo(it) } }
            val sizes = try {
                pageSizes(tmp)
            } catch (e: SecurityException) {
                throw PdfImportException("The PDF is password protected", e)
            } catch (e: java.io.IOException) {
                throw PdfImportException("Not a readable PDF", e)
            } catch (e: IllegalArgumentException) {
                throw PdfImportException("Not a readable PDF", e)
            }
            val pkg = buildPackage(NotebookId(newId()), title, sizes, tmp.readBytes(), clock())
            store.write(pkg)
            pkg.notebook
        } finally {
            tmp.delete()
        }
    }

    companion object {
        /** Archive path of the imported PDF inside its notebook. */
        const val ASSET = "assets/source.pdf"

        /** The notebook for [pdf]: one blank-paper page per entry of [sizes], each showing its PDF page. */
        fun buildPackage(id: NotebookId, title: String, sizes: List<PdfPageSize>, pdf: ByteArray, now: Long): NotebookPackage {
            if (sizes.isEmpty()) throw PdfImportException("The PDF has no pages")
            val cleanTitle = cleanTitle(title)
            val notebook = Notebook(id, cleanTitle, now, now, PaperTemplate.Blank, sizes.size)
            val emptyInk = InkChunkCodec.encode(emptyList())
            val pages = sizes.mapIndexed { i, size ->
                val page = Page(id, i, PaperTemplate.Blank, size.widthPt.coerceAtLeast(1).toFloat(), size.heightPt.coerceAtLeast(1).toFloat(), pdf = PdfPageRef(ASSET, i))
                PageContent(page, emptyInk, "page-$i")
            }
            return NotebookPackage(notebook, pages, mapOf(ASSET to pdf))
        }

        /** "Lecture 3.pdf" -> "Lecture 3"; blank names become "PDF". */
        fun cleanTitle(name: String): String =
            name.trim().removeSuffix(".pdf").removeSuffix(".PDF").trim().ifEmpty { "PDF" }

        fun readPageSizes(file: File): List<PdfPageSize> =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    List(r.pageCount) { i -> r.openPage(i).use { PdfPageSize(it.width, it.height) } }
                }
            }
    }
}
