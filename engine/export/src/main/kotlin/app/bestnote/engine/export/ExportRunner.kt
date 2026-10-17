package app.bestnote.engine.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.Page
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.storage.BnoteArchive
import app.bestnote.core.storage.InkChunkCodec
import app.bestnote.core.storage.NotebookPackage
import app.bestnote.core.storage.NotebookStore
import app.bestnote.engine.canvas.document.ElementImages
import app.bestnote.engine.pdf.PdfNotebookFiles
import app.bestnote.engine.pdf.PdfPageBackgrounds
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
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
        options: ExportOptions = ExportOptions(),
        progress: ExportProgress = ExportProgress { _, _ -> },
    ): ExportedFiles = withContext(Dispatchers.Default) {
        val pkg = store.read(id) ?: throw ExportException("Notebook no longer exists")
        val dir = freshDirectory()
        val base = ExportNames.fileBase(pkg.notebook.title)
        val selected = ExportPlan.select(range, pkg.pages.size)
        if (selected.isEmpty() && format != ExportFormat.Bnote) throw ExportException("No pages to export")
        val files = try {
            when (format) {
                ExportFormat.Pdf -> listOf(writePdf(pkg, selected, options, File(dir, "$base.pdf"), dir, progress))
                ExportFormat.Png -> writePng(pkg, selected, options, dir, base, progress)
                ExportFormat.Bnote -> listOf(writeBnote(pkg, File(dir, "$base.bnote")))
            }
        } catch (e: Throwable) {
            dir.deleteRecursively() // cancelled or failed: leave no half-written file behind
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

    /** [page] without its ruling/grid when the user left the template out (imported PDF pages keep their content). */
    private fun styled(page: Page, options: ExportOptions): Page =
        if (!options.includeTemplate && page.pdf == null && page.template != PaperTemplate.Blank) page.copy(template = PaperTemplate.Blank) else page

    /**
     * The PDF export (PDF-05). Annotations (paper, text boxes, pictures, ink) are drawn as vector
     * content with [PdfDocument]. Pages of an imported PDF are then merged with their original page
     * through PDFBox, so the original text and graphics are kept exactly (selectable, not rasterized)
     * and the annotations lie on top. A PDF PDFBox cannot read falls back to a rendered background.
     */
    private suspend fun writePdf(
        pkg: NotebookPackage,
        selected: List<Int>,
        options: ExportOptions,
        target: File,
        dir: File,
        progress: ExportProgress,
    ): File {
        PDFBoxResourceLoader.init(context)
        val sourcesDir = File(dir, ".sources").apply { mkdirs() }
        val merger = PdfSourceMerger.open(pkg, selected, sourcesDir)
        var backgrounds: PdfPageBackgrounds? = null
        val overlay = PdfDocument()
        val images = SyncImages(pkg.assets)
        val rendered = ArrayList<Bitmap>() // must outlive writeTo: the document draws from them then
        try {
            val needRaster = selected.any { i -> pkg.pages[i].page.pdf != null && !merger.handles(pkg.pages[i].page) }
            if (needRaster) backgrounds = PdfNotebookFiles.openPrivate(pkg, File(context.noBackupFilesDir, PDF_SCRATCH_DIR))
            val pdfPages = pkg.pages.count { it.page.pdf != null }
            selected.forEachIndexed { n, i ->
                coroutineContext.ensureActive()
                val content = pkg.pages[i]
                val page = styled(content.page, options)
                val info = PdfDocument.PageInfo.Builder(page.widthPt.roundToInt(), page.heightPt.roundToInt(), n + 1).create()
                val pdfPage = overlay.startPage(info)
                if (page.pdf != null && merger.handles(page)) {
                    PageGraphics.drawAnnotations(pdfPage.canvas, strokesOf(content.strokes), content.elements, images)
                } else {
                    val background = backgroundOf(backgrounds, page, backgroundWidthPx(page, pdfPages))?.also { rendered += it }
                    PageGraphics.drawVector(pdfPage.canvas, page, strokesOf(content.strokes), background, content.elements, images)
                }
                overlay.finishPage(pdfPage)
                progress.update(n + 1, selected.size)
            }
            coroutineContext.ensureActive()
            if (merger.isEmpty) {
                target.outputStream().buffered().use { overlay.writeTo(it) }
            } else {
                val overlayFile = File(dir, ".annotations.pdf")
                overlayFile.outputStream().buffered().use { overlay.writeTo(it) }
                merger.merge(overlayFile, selected.map { pkg.pages[it].page }, target)
            }
        } finally {
            overlay.close()
            merger.close()
            backgrounds?.close()
            rendered.forEach { it.recycle() }
            images.release()
            sourcesDir.deleteRecursively()
            File(dir, ".annotations.pdf").delete()
        }
        return target
    }

    private suspend fun writePng(pkg: NotebookPackage, selected: List<Int>, options: ExportOptions, dir: File, base: String, progress: ExportProgress): List<File> {
        val digits = pkg.pages.size.toString().length
        val backgrounds = PdfNotebookFiles.openPrivate(pkg, File(context.noBackupFilesDir, PDF_SCRATCH_DIR))
        try {
            return writePngPages(pkg, selected, options, dir, base, digits, backgrounds, progress)
        } finally {
            backgrounds?.close()
        }
    }

    private suspend fun writePngPages(
        pkg: NotebookPackage,
        selected: List<Int>,
        options: ExportOptions,
        dir: File,
        base: String,
        digits: Int,
        backgrounds: PdfPageBackgrounds?,
        progress: ExportProgress,
    ): List<File> =
        selected.mapIndexed { n, index ->
            coroutineContext.ensureActive()
            val content = pkg.pages[index]
            val page = styled(content.page, options)
            val scale = PageGraphics.DEFAULT_PNG_SCALE
            val background = backgroundOf(backgrounds, page, (page.widthPt * scale).roundToInt().coerceAtMost(MAX_BACKGROUND_PX))
            val images = SyncImages(pkg.assets)
            val bitmap = try {
                PageGraphics.bitmap(page, strokesOf(content.strokes), scale, background, content.elements, images)
            } finally {
                background?.recycle()
                images.release()
            }
            val name = if (selected.size == 1 && pkg.pages.size == 1) "$base.png" else "$base-${(index + 1).toString().padStart(digits, '0')}.png"
            val file = File(dir, name)
            try {
                file.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            progress.update(n + 1, selected.size)
            file
        }

    /** Copies a finished single-file export to a document the user picked in the system file picker (Save to Files). */
    suspend fun saveTo(exported: ExportedFiles, uri: android.net.Uri) = withContext(Dispatchers.IO) {
        val file = exported.files.singleOrNull() ?: throw ExportException("Nothing to save")
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw ExportException("Cannot write there")
        out.use { sink -> file.inputStream().use { it.copyTo(sink) } }
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
        /** Where older builds cached plain PDF copies for exports (swept at start, LIB-11b). */
        const val PDF_SOURCES_DIR = "export-pdf-sources"

        /** PDFs open for an export are never cached in plain (a locked notebook's included): see openPrivate. */
        const val PDF_SCRATCH_DIR = "locked-pdf/export"
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

/**
 * Decodes a notebook's pictures right when asked (exports run off the main thread and need them now).
 * Each is decoded once (they are stored downsampled already) and kept until [release].
 */
internal class SyncImages(private val assets: Map<String, ByteArray>) : ElementImages {
    private val decoded = HashMap<String, Bitmap?>()

    override fun bitmap(asset: String, widthPx: Int, onReady: Runnable): Bitmap? = decoded.getOrPut(asset) {
        val bytes = assets[asset] ?: return@getOrPut null
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }

    fun release() {
        decoded.values.forEach { it?.recycle() }
        decoded.clear()
    }
}
