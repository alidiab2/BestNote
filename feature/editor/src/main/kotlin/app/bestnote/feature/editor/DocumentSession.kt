package app.bestnote.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import app.bestnote.core.database.NotebookRepository
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.storage.Autosaver
import app.bestnote.core.storage.InkChunkCodec
import app.bestnote.engine.canvas.document.DocumentPage
import app.bestnote.engine.canvas.document.PagedDocument
import app.bestnote.engine.canvas.document.PagedDocumentView
import app.bestnote.engine.canvas.document.PaperPainter
import app.bestnote.engine.pdf.PdfNotebookFiles
import app.bestnote.engine.pdf.PdfPageBackgrounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * Connects the paged [document] to storage (DOC-03, CANVAS-07) and the thumbnail cache (DOC-10).
 *
 * Loading decodes and meshes every page off the main thread, then fills [document]. After that the
 * session listens to the document: a page's ink change hands only that page to the [Autosaver]
 * (strokes are converted once each), and added pages or paper/size changes hand over the page
 * layout. [onStop] flushes when the app leaves the foreground; [close] flushes and re-renders the
 * library thumbnail from the first page. All methods except the background work run on the main thread.
 *
 * If the notebook can't be opened, [fallback] fills the document so the user can still write.
 */
internal class DocumentSession(
    private val notebookId: NotebookId,
    private val repository: NotebookRepository,
    private val document: PagedDocument,
    scope: CoroutineScope,
    private val pdfCacheDir: File,
    private val fallback: suspend () -> Notebook?,
) : PagedDocument.Listener {
    private var saver: Autosaver? = null

    /** The (storage id, paper) of each page as last handed to the [Autosaver], in order. */
    private var savedLayout: List<Pair<String, Page>> = emptyList()

    /** Stored strokes this build cannot draw (unknown brush), per page id; kept so saving never drops them. */
    private val preserved = HashMap<String, List<InkStroke>>()
    private val models = IdentityHashMap<Stroke, InkStroke>()
    private var view: PagedDocumentView? = null

    /** Rasters of the notebook's PDF pages (PDF-01), or null for a plain notebook. */
    private var pdf: PdfPageBackgrounds? = null

    init {
        scope.launch { load() }
    }

    private suspend fun load() {
        val open = try {
            repository.openForEditing(notebookId)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "Cannot open $notebookId; editing without autosave", e)
            fallback()?.let { nb -> if (!document.isLoaded) document.reset(List(nb.pageCount) { Page(notebookId, it, nb.template) }) }
            return
        }
        val pages = open.pkg.pages
        val loaded = try {
            withContext(Dispatchers.Default) {
                pages.map { page ->
                    InkChunkCodec.decode(page.strokes).map { model -> model to runCatching { InkConversion.toInk(model) }.getOrNull() }
                }
            }
        } catch (e: CancellationException) {
            repository.backgroundScope.launch { open.autosaver.close() } // closed before loading finished
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Damaged page (DOC-04 adds the placeholder): never overwrite it with an empty page.
            Log.e(TAG, "A page of $notebookId is damaged; autosave disabled", e)
            repository.backgroundScope.launch { open.autosaver.close() }
            return
        }
        pages.forEachIndexed { i, content ->
            preserved[content.id] = loaded[i].filter { it.second == null }.map { it.first }
        }
        savedLayout = pages.mapIndexed { i, content -> content.id to content.page.copy(index = i) }
        val strokes = loaded.map { page -> page.mapNotNull { (model, stroke) -> stroke?.also { models[it] = model } } }
        saver = open.autosaver
        pdf = withContext(Dispatchers.IO) { PdfNotebookFiles.open(open.pkg, pdfCacheDir) }
        document.backgrounds = pdf
        document.reset(savedLayout.map { it.second }, savedLayout.map { it.first }) { strokes[it] }
        document.addListener(this)
    }

    /** Strokes made elsewhere (Smart Board) whose stored form is already known; call before adding them. */
    fun adopt(strokes: List<Pair<InkStroke, Stroke>>) {
        for ((model, stroke) in strokes) models[stroke] = model
    }

    /** The view that draws (for pen-down brushes). Call again after a configuration change. */
    fun attach(view: PagedDocumentView) {
        this.view = view
    }

    override fun onPageContentChanged(index: Int) {
        val saver = saver ?: return
        val page = document.pages.getOrNull(index) ?: return
        saver.updatePage(page.id, modelsOf(page))
    }

    private fun modelsOf(page: DocumentPage): List<InkStroke> {
        val strokes = page.strokes
        val out = ArrayList<InkStroke>(strokes.size + (preserved[page.id]?.size ?: 0))
        preserved[page.id]?.let { out += it }
        for (stroke in strokes) out += models.getOrPut(stroke) { InkConversion.toModel(stroke, brushOf(stroke)) }
        return out
    }

    /**
     * Pages were added, deleted, reordered, duplicated or re-papered (also by undo): hand storage the
     * new order. A page storage doesn't have yet (new, duplicated, or brought back by undo) also gets its ink.
     */
    override fun onPagesChanged() {
        val saver = saver ?: return
        val layout = document.pages.map { it.id to it.page }
        if (layout == savedLayout) return
        val known = savedLayout.mapTo(HashSet()) { it.first }
        savedLayout = layout
        saver.updateLayout(layout)
        for (page in document.pages) if (page.id !in known && page.strokes.isNotEmpty()) saver.updatePage(page.id, modelsOf(page))
    }

    /** App left the foreground: get everything on disk now. */
    fun onStop() {
        val saver = saver ?: return
        val first = firstPage()
        repository.backgroundScope.launch {
            try {
                saver.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.e(TAG, "Flush failed; autosave keeps retrying", e)
            }
            refreshThumbnail(saver, first, pdf)
        }
    }

    /** The editor closed: final save, then the library thumbnail. */
    fun close() {
        document.removeListener(this)
        view = null
        val saver = saver ?: return
        this.saver = null
        val first = firstPage()
        val pdf = pdf
        this.pdf = null
        val closedAt = SystemClock.elapsedRealtime()
        repository.backgroundScope.launch {
            try {
                saver.close()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.e(TAG, "Final save of $notebookId failed", e)
            }
            val thumbnailed = refreshThumbnail(saver, first, pdf)
            pdf?.close()
            if (thumbnailed) {
                Log.i(TAG, "Thumbnail of $notebookId updated ${SystemClock.elapsedRealtime() - closedAt} ms after closing")
            }
        }
    }

    private fun firstPage(): Pair<Page, List<Stroke>>? = document.pages.firstOrNull()?.let { it.page to it.strokes.toList() }

    /**
     * The brush captured at pen-down for [stroke]. The fallback (a stroke the canvas has no record
     * of) identifies the family from the stroke's own androidx.ink brush, never from the toolbar.
     */
    private fun brushOf(stroke: Stroke): InkBrush = view?.brushOf(stroke) ?: InkConversion.brushFromInk(stroke.brush)

    /** Re-renders the thumbnail when the cached one predates the last save. @return true if rendered. */
    private suspend fun refreshThumbnail(saver: Autosaver, first: Pair<Page, List<Stroke>>?, pdf: PdfPageBackgrounds? = this.pdf): Boolean {
        val (page, strokes) = first ?: return false
        val notebook = saver.notebook
        if (!repository.thumbnailIsStale(notebookId, notebook.updatedAtMillis)) return false
        return try {
            val bytes = withContext(Dispatchers.Default) { renderThumbnail(page, strokes, pdf?.renderNow(page, THUMB_WIDTH)) }
            repository.saveThumbnail(notebookId, notebook.updatedAtMillis, bytes)
            Log.i(TAG, "Thumbnail of $notebookId rendered from ${strokes.size} strokes")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "Thumbnail of $notebookId failed", e)
            false
        }
    }

    private companion object {
        const val TAG = "BestNoteAutosave"
    }
}

private const val THUMB_WIDTH = 320
private const val THUMB_QUALITY = 80
private const val MIN_THUMB_STROKE_PX = 1.5f

/**
 * Renders [page] (paper, template and [strokes] in page points) [THUMB_WIDTH] px wide as WebP.
 * Strokes are immutable, so this runs off the main thread.
 */
internal fun renderThumbnail(page: Page, strokes: List<Stroke>, pdfRaster: Bitmap? = null): ByteArray {
    val bitmap = renderPageBitmap(page, strokes, THUMB_WIDTH, pdfRaster)
    try {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        return ByteArrayOutputStream().also { bitmap.compress(format, THUMB_QUALITY, it) }.toByteArray()
    } finally {
        bitmap.recycle()
    }
}

/** Draws [page] (paper, template and [strokes] in page points) into a new bitmap [widthPx] wide. Caller owns it. */
internal fun renderPageBitmap(page: Page, strokes: List<Stroke>, widthPx: Int, pdfRaster: Bitmap? = null): Bitmap {
    val scale = widthPx / page.widthPt
    val height = (page.heightPt * scale).roundToInt().coerceAtLeast(1)
    val bitmap = createBitmap(widthPx, height)
    val canvas = Canvas(bitmap)
    if (page.pdf != null) {
        // A PDF page is its own paper: its raster (white while it is not available) and no template lines.
        canvas.drawColor(PaperPainter.pageColor(page))
        if (pdfRaster != null) canvas.drawBitmap(pdfRaster, null, android.graphics.Rect(0, 0, widthPx, height), null)
    } else {
        PaperPainter.draw(canvas, page, scale, widthPx, height)
    }
    // Plain polylines: a cover image does not need brush tips, and this works on any (software) canvas.
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val path = Path()
    val input = StrokeInput()
    for (stroke in strokes) {
        val inputs = stroke.inputs
        if (inputs.isEmpty()) continue
        path.rewind()
        for (i in 0 until inputs.size) {
            inputs.populate(i, input)
            if (i == 0) path.moveTo(input.x * scale, input.y * scale) else path.lineTo(input.x * scale, input.y * scale)
        }
        if (inputs.size == 1) path.lineTo(input.x * scale + MIN_THUMB_STROKE_PX / 2, input.y * scale)
        ink.color = stroke.brush.colorIntArgb
        ink.strokeWidth = (stroke.brush.size * scale).coerceAtLeast(MIN_THUMB_STROKE_PX)
        canvas.drawPath(path, ink)
    }
    return bitmap
}
