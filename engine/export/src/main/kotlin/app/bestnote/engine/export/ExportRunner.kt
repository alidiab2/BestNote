package app.bestnote.engine.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.Page
import app.bestnote.core.model.NotebookId
import app.bestnote.core.storage.BnoteArchive
import app.bestnote.core.storage.InkChunkCodec
import app.bestnote.core.storage.NotebookPackage
import app.bestnote.core.storage.NotebookStore
import app.bestnote.engine.pdf.PdfNotebookFiles
import app.bestnote.engine.pdf.PdfPageBackgrounds
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

enum class ExportFormat(val mime: String, val extension: String) {
    /** One PDF for the whole notebook: ink as vector paths, page templates drawn. */
    Pdf("application/pdf", "pdf"),

    /** One image per page. */
    Png("image/png", "png"),

    /** The notebook as a single `.bnote` file, which BestNote can import again. */
    Bnote("application/octet-stream", "bnote"),
}

/** Which pages an export covers. */
sealed interface PageRange {
    data object All : PageRange
    data class Single(val index: Int) : PageRange
}

/** Files ready to hand to the share sheet. */
class ExportedFiles(val files: List<File>, val mime: String, val title: String)

class ExportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Progress callback: [done] of [total] pages. Called off the main thread. */
fun interface ExportProgress {
    fun update(done: Int, total: Int)
}

/**
 * Exports notebooks from the saved store (DOC-06, DOC-07). Callers flush any open editor first, so
 * "saved" is what the user sees. All work runs on [Dispatchers.Default] / IO and checks for
 * cancellation between pages; files go to the cache folder, which the share sheet reads through a
 * [FileProvider].
 */
@Singleton
class ExportRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: NotebookStore,
) {
    suspend fun export(
        id: NotebookId,
        format: ExportFormat,
        range: PageRange = PageRange.All,
        progress: ExportProgress = ExportProgress { _, _ -> },
    ): ExportedFiles = withContext(Dispatchers.Default) {
        val pkg = store.read(id) ?: throw ExportException("Notebook no longer exists")
        val dir = freshDirectory()
        val base = ExportNames.fileBase(pkg.notebook.title)
        val files = try {
            when (format) {
                ExportFormat.Pdf -> listOf(writePdf(pkg, File(dir, "$base.pdf"), progress))
                ExportFormat.Png -> writePng(pkg, range, dir, base, progress)
                ExportFormat.Bnote -> listOf(writeBnote(pkg, File(dir, "$base.bnote")))
            }
        } catch (e: CancellationException) {
            dir.deleteRecursively()
            throw e
        }
        ExportedFiles(files, format.mime, pkg.notebook.title)
    }

    /**
     * Width to render an imported-PDF page at: about 200 dpi, but shrunk for long PDFs so all the
     * rendered pages together stay within [PDF_BACKGROUND_BUDGET_BYTES] (PdfDocument keeps every
     * image until the file is written).
     */
    internal fun backgroundWidthPx(page: Page, pdfPages: Int): Int {
        val ideal = (page.widthPt * PDF_BACKGROUND_SCALE).coerceAtMost(MAX_BACKGROUND_PX.toFloat())
        val perPagePx = PDF_BACKGROUND_BUDGET_BYTES / 4f / pdfPages.coerceAtLeast(1)
        val byBudget = kotlin.math.sqrt(perPagePx * page.widthPt / page.heightPt)
        return minOf(ideal, byBudget).roundToInt().coerceAtLeast(MIN_BACKGROUND_PX)
    }

    /** The imported PDF page behind [page], rendered, or null for ordinary paper. Caller recycles it. */
    private fun backgroundOf(backgrounds: PdfPageBackgrounds?, page: Page, widthPx: Int): Bitmap? =
        if (page.pdf == null) null else runCatching { backgrounds?.renderNow(page, widthPx) }.getOrNull()

    private suspend fun writePdf(pkg: NotebookPackage, target: File, progress: ExportProgress): File {
        val document = PdfDocument()
        val backgrounds = PdfNotebookFiles.open(pkg, File(context.cacheDir, PDF_SOURCES_DIR))
        val pdfPages = pkg.pages.count { it.page.pdf != null }
        val rendered = ArrayList<Bitmap>() // must outlive writeTo: the document draws from them then
        try {
            pkg.pages.forEachIndexed { i, content ->
                coroutineContext.ensureActive()
                val page = content.page
                val info = PdfDocument.PageInfo.Builder(page.widthPt.roundToInt(), page.heightPt.roundToInt(), i + 1).create()
                val pdfPage = document.startPage(info)
                // The imported PDF page is rasterized (PdfDocument cannot embed another PDF's page), so its
                // text is not selectable in the export; the ink on top is vector.
                val background = backgroundOf(backgrounds, page, backgroundWidthPx(page, pdfPages))?.also { rendered += it }
                PageGraphics.drawVector(pdfPage.canvas, page, strokesOf(content.strokes), background)
                document.finishPage(pdfPage)
                progress.update(i + 1, pkg.pages.size)
            }
            target.outputStream().buffered().use { document.writeTo(it) }
        } finally {
            document.close()
            backgrounds?.close()
            rendered.forEach { it.recycle() }
        }
        return target
    }

    private suspend fun writePng(pkg: NotebookPackage, range: PageRange, dir: File, base: String, progress: ExportProgress): List<File> {
        val selected = when (range) {
            PageRange.All -> pkg.pages.indices.toList()
            is PageRange.Single -> listOf(range.index.coerceIn(0, pkg.pages.lastIndex))
        }
        val digits = pkg.pages.size.toString().length
        val backgrounds = PdfNotebookFiles.open(pkg, File(context.cacheDir, PDF_SOURCES_DIR))
        try {
            return writePngPages(pkg, selected, dir, base, digits, backgrounds, progress)
        } finally {
            backgrounds?.close()
        }
    }

    private suspend fun writePngPages(
        pkg: NotebookPackage,
        selected: List<Int>,
        dir: File,
        base: String,
        digits: Int,
        backgrounds: PdfPageBackgrounds?,
        progress: ExportProgress,
    ): List<File> =
        selected.mapIndexed { n, index ->
            coroutineContext.ensureActive()
            val content = pkg.pages[index]
            val scale = PageGraphics.DEFAULT_PNG_SCALE
            val background = backgroundOf(backgrounds, content.page, (content.page.widthPt * scale).roundToInt().coerceAtMost(MAX_BACKGROUND_PX))
            val bitmap = try {
                PageGraphics.bitmap(content.page, strokesOf(content.strokes), scale, background)
            } finally {
                background?.recycle()
            }
            val name = if (pkg.pages.size == 1) "$base.png" else "$base-${(index + 1).toString().padStart(digits, '0')}.png"
            val file = File(dir, name)
            try {
                file.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            progress.update(n + 1, selected.size)
            file
        }

    private fun writeBnote(pkg: NotebookPackage, target: File): File {
        target.outputStream().buffered().use { BnoteArchive.export(pkg, it) }
        return target
    }

    /** A page with a damaged or newer-format ink chunk still exports, just without ink. */
    private fun strokesOf(chunk: ByteArray): List<InkStroke> = try {
        InkChunkCodec.decode(chunk)
    } catch (e: IOException) {
        emptyList()
    }

    private fun freshDirectory(): File {
        val root = File(context.cacheDir, EXPORT_DIR)
        root.deleteRecursively() // earlier exports were already shared or abandoned
        return root.also { it.mkdirs() }
    }

    /** The Android share sheet for [exported]. Start it from an activity context. */
    fun shareIntent(exported: ExportedFiles): Intent {
        val uris = exported.files.map { FileProvider.getUriForFile(context, "${context.packageName}.exports", it) }
        val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = exported.mime
            putExtra(Intent.EXTRA_SUBJECT, exported.title)
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newRawUri(exported.title, uris[0]).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, exported.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private companion object {
        const val EXPORT_DIR = "exports"
        const val PDF_SOURCES_DIR = "export-pdf-sources"
        const val PDF_BACKGROUND_SCALE = 2.8f
        const val MAX_BACKGROUND_PX = 2400
        const val MIN_BACKGROUND_PX = 400
        const val PDF_BACKGROUND_BUDGET_BYTES = 160L * 1024 * 1024
    }
}

/** File-name rules for exports. */
internal object ExportNames {
    private val UNSAFE = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

    /** A title as a file name without extension: no path or reserved characters, at most 80 characters. */
    fun fileBase(title: String): String =
        title.replace(UNSAFE, "_").trim().trim('.').take(80).trim().ifEmpty { "Notebook" }
}
