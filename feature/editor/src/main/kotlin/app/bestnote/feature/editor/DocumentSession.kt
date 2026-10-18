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
import app.bestnote.core.model.AudioSync
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PageElement
import app.bestnote.core.storage.Autosaver
import app.bestnote.core.storage.ImageAssets
import app.bestnote.core.storage.InkChunkCodec
import app.bestnote.engine.canvas.StrokeFills
import app.bestnote.engine.canvas.document.AssetImageCache
import app.bestnote.engine.canvas.document.DocumentPage
import app.bestnote.engine.canvas.document.ElementImages
import app.bestnote.engine.canvas.document.ElementPainter
import app.bestnote.engine.canvas.document.PagedDocument
import app.bestnote.engine.canvas.document.PagedDocumentView
import app.bestnote.engine.canvas.document.PaperPainter
import app.bestnote.core.storage.PdfAssets
import app.bestnote.engine.pdf.PdfDocumentSource
import app.bestnote.engine.pdf.PdfNotebookFiles
import app.bestnote.engine.pdf.PdfPageBackgrounds
import app.bestnote.feature.editor.audio.AudioController
import app.bestnote.feature.editor.audio.InkAudioIndex
import app.bestnote.feature.editor.audio.InkSyncHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
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
    /** App-private, no-backup scratch a locked notebook's PDFs are opened from (LIB-11b; nothing stays there). */
    private val lockedPdfDir: File,
    /** Voice recordings of this notebook and their sync with the ink (MEDIA-04/05). */
    internal val audio: AudioController,
    private val fallback: suspend () -> Notebook?,
) : PagedDocument.Listener {
    private var saver: Autosaver? = null

    /** The (storage id, paper) of each page as last handed to the [Autosaver], in order. */
    private var savedLayout: List<Pair<String, Page>> = emptyList()

    /** Stored strokes this build cannot draw (unknown brush), per page id; kept so saving never drops them. */
    private val preserved = HashMap<String, List<InkStroke>>()
    /**
     * Storage model of each stroke, so a save converts only new strokes. Weak keys (androidx.ink
     * strokes compare by identity): erased, split or undone-and-dropped strokes leave with the last
     * reference (the page or the undo history) instead of piling up while the editor stays open.
     */
    private val models = WeakHashMap<Stroke, InkStroke>()
    private var view: PagedDocumentView? = null

    /** The notebook's picture files by archive path (MEDIA-01); read by the image cache on its own thread. */
    private val assets = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val imageCache = AssetImageCache(bytesOf = { assets[it] })

    /** The bytes of the stored picture [path] (for the crop preview), or null. */
    fun assetBytes(path: String): ByteArray? = assets[path]

    /**
     * Stores an already downsampled picture once per notebook (its path is a hash of its bytes, so the same
     * picture added again is the same file) and returns its archive path. Call before adding an element that uses it.
     */
    fun addImage(bytes: ByteArray, ext: String): String {
        val path = ImageAssets.pathFor(bytes, ext)
        if (assets.putIfAbsent(path, bytes) == null) saver?.addAsset(path, bytes)
        return path
    }

    /**
     * Reads the PDF from [open] (PAGES-05) off the main thread, cancellable, and opens it for rendering without
     * touching the notebook yet. [onProgress] gets 0..1. Finish with [insertPdf] or [discard].
     * @throws PdfInsertException if it is not a usable PDF.
     */
    suspend fun stagePdf(totalBytes: Long, open: () -> InputStream?, onProgress: (Float) -> Unit): StagedPdf = withContext(Dispatchers.IO) {
        val bytes = try {
            val input = open() ?: throw PdfInsertException(PdfInsertException.Reason.Unreadable)
            input.use { src ->
                val out = ByteArrayOutputStream(if (totalBytes in 1..MAX_PDF_BYTES) totalBytes.toInt() else DEFAULT_BUFFER)
                val buffer = ByteArray(DEFAULT_BUFFER)
                while (true) {
                    ensureActive()
                    val n = src.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_PDF_BYTES) throw PdfInsertException(PdfInsertException.Reason.TooLarge)
                    if (totalBytes > 0) onProgress(READ_SHARE * (out.size().toFloat() / totalBytes).coerceAtMost(1f))
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            throw PdfInsertException(PdfInsertException.Reason.Unreadable, e)
        } catch (e: SecurityException) {
            throw PdfInsertException(PdfInsertException.Reason.Unreadable, e)
        }
        val asset = PdfAssets.pathFor(bytes)
        val alreadyOpen = pdf?.hasSource(asset) == true
        val reader = try {
            // A locked notebook's PDFs never get a plaintext copy on disk (LIB-11b).
            if (repository.isSealed(notebookId)) PdfNotebookFiles.openAssetPrivate(bytes, lockedPdfDir)
            else PdfNotebookFiles.openAsset(notebookId, asset, bytes, pdfCacheDir)
        } catch (e: SecurityException) {
            throw PdfInsertException(PdfInsertException.Reason.Password, e)
        } catch (e: IOException) {
            throw PdfInsertException(PdfInsertException.Reason.Unreadable, e)
        } catch (e: IllegalArgumentException) {
            throw PdfInsertException(PdfInsertException.Reason.Unreadable, e)
        }
        var keep = false
        try {
            val count = reader.pageCount
            if (count <= 0) throw PdfInsertException(PdfInsertException.Reason.Empty)
            val sizes = ArrayList<Pair<Int, Int>>(count)
            for (i in 0 until count) {
                ensureActive()
                val size = reader.pageSize(i)
                sizes += size.widthPt to size.heightPt
                onProgress(READ_SHARE + (1f - READ_SHARE) * (i + 1) / count)
            }
            // The same PDF inserted again is the same file: the renderer already open for it keeps serving.
            keep = !alreadyOpen
            StagedPdf(asset, bytes, if (alreadyOpen) null else reader, sizes)
        } finally {
            if (!keep) runCatching { reader.close() }
        }
    }

    /** Puts the pages [indices] of [staged] into the document at [at] as ONE undo step. @return their indices. */
    fun insertPdf(staged: StagedPdf, indices: List<Int>, at: Int): List<Int> {
        val path = staged.asset
        if (assets.putIfAbsent(path, staged.bytes) == null) saver?.addAsset(path, staged.bytes)
        val backgrounds = pdf ?: PdfPageBackgrounds(emptyMap()).also {
            pdf = it
            document.backgrounds = it
        }
        staged.source?.let { source -> if (!backgrounds.hasSource(path)) backgrounds.addSource(path, source) else runCatching { source.close() } }
        staged.source = null
        val pages = InsertPlan.pdfPageModels(notebookId, path, indices) { staged.sizes[it] }
        return document.insertPages(at, pages.map { it to emptyList() }, keepPdf = true)
    }

    /** The user backed out: release a staged PDF that was not inserted. */
    fun discard(staged: StagedPdf) {
        staged.source?.let { runCatching { it.close() } }
        staged.source = null
    }

    /** Inserts one page per picture at [at] (the picture is a locked full-page image), as ONE undo step. @return the new indices. */
    fun insertImagePages(pictures: List<ImportedImage>, at: Int): List<Int> {
        val reference = document.pages.getOrNull((at - 1).coerceAtLeast(0))?.page ?: document.blankPage
        val built = pictures.map { picture ->
            InsertPlan.imagePage(notebookId, reference, addImage(picture.bytes, picture.ext), picture.width, picture.height, PageElement.newId())
        }
        return document.insertPages(at, built.map { it.first to emptyList() }, built.map { it.second })
    }

    /** Rasters of the notebook's PDF pages (PDF-01), or null for a plain notebook. */
    private var pdf: PdfPageBackgrounds? = null

    /** Strokes written during a recording (MEDIA-05), rebuilt lazily after the ink changes. */
    private var syncIndex: InkAudioIndex? = null
    private var dimKey: Pair<String, Int>? = null

    init {
        audio.host = object : InkSyncHost {
            override fun syncAt(page: Int, x: Float, y: Float): AudioSync? =
                index().hit(page, x, y, TAP_SLOP_PT)?.sync

            override fun dim(recordingId: String?, playheadMs: Long) = dimInk(recordingId, playheadMs)
        }
        scope.launch { load() }
    }

    private fun index(): InkAudioIndex = syncIndex ?: buildIndex().also { syncIndex = it }

    private fun buildIndex(): InkAudioIndex {
        val entries = ArrayList<InkAudioIndex.Entry>()
        val input = StrokeInput()
        document.pages.forEachIndexed { pageIndex, page ->
            for (stroke in page.strokes) {
                val sync = models[stroke]?.audio ?: view?.brushOf(stroke)?.audio ?: continue
                val inputs = stroke.inputs
                val xs = FloatArray(inputs.size)
                val ys = FloatArray(inputs.size)
                for (i in 0 until inputs.size) {
                    inputs.populate(i, input)
                    xs[i] = input.x
                    ys[i] = input.y
                }
                entries += InkAudioIndex.Entry(pageIndex, sync, xs, ys, stroke.brush.size / 2f, stroke)
            }
        }
        return InkAudioIndex(entries)
    }

    private fun dimInk(recordingId: String?, playheadMs: Long) {
        val view = view
        if (recordingId == null) {
            if (dimKey != null) view?.setDimmedStrokes(emptySet())
            dimKey = null
            return
        }
        val index = index()
        val key = recordingId to index.dimSignature(recordingId, playheadMs)
        if (key == dimKey) return
        dimKey = key
        val later = Collections.newSetFromMap(IdentityHashMap<Stroke, Boolean>())
        index.laterThan(recordingId, playheadMs).mapTo(later) { it.ref as Stroke }
        view?.setDimmedStrokes(later)
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
        adoptLoadedBrushes()
        assets.putAll(open.pkg.assets)
        document.images = imageCache
        audio.attach(open.pkg.recordings, open.pkg.assets, open.autosaver)
        val sealed = repository.isSealed(notebookId)
        pdf = withContext(Dispatchers.IO) {
            if (sealed) PdfNotebookFiles.openPrivate(open.pkg, lockedPdfDir) else PdfNotebookFiles.open(open.pkg, pdfCacheDir)
        }
        document.backgrounds = pdf
        document.reset(savedLayout.map { it.second }, savedLayout.map { it.first }, strokes = { strokes[it] }, elements = { pages[it].elements })
        document.addListener(this)
        // What each page holds on disk, so autosave appends only the difference (DOC-11).
        document.pages.forEach { page -> open.autosaver.adoptBaseline(page.id, modelsOf(page)) }
    }

    /** The notebook's Smart Board record (glossary, the notes of every page Smart Board wrote). */
    fun smartBoardRecord(): app.bestnote.ai.vision.SmartBoardRecord =
        app.bestnote.ai.vision.SmartBoardRecord.decode(saver?.extra(app.bestnote.ai.vision.SmartBoardRecord.EXTRA))

    /** Saves [record] with the notebook (forgetting inserts whose pages were all deleted). */
    fun saveSmartBoardRecord(record: app.bestnote.ai.vision.SmartBoardRecord) {
        val live = document.pages.mapTo(HashSet()) { it.id }
        saver?.updateExtra(app.bestnote.ai.vision.SmartBoardRecord.EXTRA, record.prune(live).encode())
    }

    /** The stored form of [stroke], when known (strokes loaded, drawn or adopted in this session). */
    fun modelOf(stroke: Stroke): InkStroke? = models[stroke]

    /** Strokes made elsewhere (Smart Board) whose stored form is already known; call before adding them. */
    fun adopt(strokes: List<Pair<InkStroke, Stroke>>) {
        for ((model, stroke) in strokes) models[stroke] = model
    }

    /** Tells the view the stored brush (and fill, INK-11) of every loaded stroke, so edits keep them. */
    private fun adoptLoadedBrushes() {
        if (models.isEmpty()) return
        for ((stroke, model) in models) StrokeFills.set(stroke, model.brush.fillArgb)
        view?.adoptBrushes(models.map { (stroke, model) -> stroke to model.brush })
    }

    /** The view that draws (for pen-down brushes). Call again after a configuration change. */
    fun attach(view: PagedDocumentView) {
        this.view = view
        adoptLoadedBrushes()
        view.audioStamper = audio::stamp
        view.inkTapListener = if (audio.state.value.playFromInk) PagedDocumentView.InkTapListener(audio::onInkTap) else null
        dimKey = null
    }

    /** "Play from ink" on or off: while on, taps on the page seek the audio instead of inking. */
    fun setPlayFromInk(on: Boolean) {
        audio.setPlayFromInk(on)
        view?.inkTapListener = if (on) PagedDocumentView.InkTapListener(audio::onInkTap) else null
    }

    override fun onPageContentChanged(index: Int) {
        syncIndex = null
        dimKey = null
        val saver = saver ?: return
        val page = document.pages.getOrNull(index) ?: return
        saver.updatePage(page.id, modelsOf(page))
    }

    override fun onPageElementsChanged(index: Int) {
        val saver = saver ?: return
        val page = document.pages.getOrNull(index) ?: return
        saver.updateElements(page.id, page.elements)
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
        syncIndex = null
        dimKey = null
        val saver = saver ?: return
        val layout = document.pages.map { it.id to it.page }
        if (layout == savedLayout) return
        val known = savedLayout.mapTo(HashSet()) { it.first }
        savedLayout = layout
        saver.updateLayout(layout)
        for (page in document.pages) if (page.id !in known) {
            if (page.strokes.isNotEmpty()) saver.updatePage(page.id, modelsOf(page))
            if (page.elements.isNotEmpty()) saver.updateElements(page.id, page.elements)
        }
    }

    /** Gets every pending edit on disk (before exporting), so a file holds what is on screen. */
    suspend fun flush() {
        saver?.flush()
    }

    /** App left the foreground: get everything on disk now. */
    fun onStop() {
        val saver = saver ?: return
        val first = firstPage()
        val elements = firstElements()
        val pictures = assets.toMap()
        repository.backgroundScope.launch {
            try {
                saver.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.e(TAG, "Flush failed; autosave keeps retrying", e)
            }
            refreshThumbnail(saver, first, pdf, elements, pictures)
        }
    }

    /** The editor closed: final save, then the library thumbnail. */
    fun close() {
        document.removeListener(this)
        audio.close()
        view?.audioStamper = null
        view?.inkTapListener = null
        view = null
        document.images = null
        imageCache.clear()
        val saver = saver ?: return
        this.saver = null
        val first = firstPage()
        val elements = firstElements()
        val pictures = assets.toMap()
        val pdf = pdf
        this.pdf = null
        val closedAt = SystemClock.elapsedRealtime()
        // Tracked, so locking this notebook right after closing it waits for this last plain save (LIB-11).
        repository.launchClosing(notebookId) {
            try {
                saver.close()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.e(TAG, "Final save of $notebookId failed", e)
            }
            val thumbnailed = refreshThumbnail(saver, first, pdf, elements, pictures)
            pdf?.close()
            // A locked notebook's PDFs never stay in a cache (LIB-11b): its scratch goes, and a plain copy made before it
            // was locked from this editor too.
            if (pdf != null && repository.isSealed(notebookId)) {
                withContext(Dispatchers.IO) {
                    PdfNotebookFiles.forget(notebookId, pdfCacheDir)
                    lockedPdfDir.deleteRecursively()
                }
            }
            if (thumbnailed) {
                Log.i(TAG, "Thumbnail of $notebookId updated ${SystemClock.elapsedRealtime() - closedAt} ms after closing")
            }
        }
    }

    private fun firstPage(): Pair<Page, List<Stroke>>? = document.pages.firstOrNull()?.let { it.page to it.strokes.toList() }

    private fun firstElements(): List<PageElement> = document.pages.firstOrNull()?.elements.orEmpty()

    /**
     * The brush captured at pen-down for [stroke]. The fallback (a stroke the canvas has no record
     * of) identifies the family from the stroke's own androidx.ink brush, never from the toolbar.
     */
    private fun brushOf(stroke: Stroke): InkBrush = (view?.brushOf(stroke) ?: InkConversion.brushFromInk(stroke.brush).copy(dash = app.bestnote.engine.ink.StrokeDashes.of(stroke))).let { b ->
        StrokeFills.of(stroke)?.let { b.copy(fillArgb = it) } ?: b
    }

    /** Re-renders the thumbnail when the cached one predates the last save. @return true if rendered. */
    private suspend fun refreshThumbnail(
        saver: Autosaver,
        first: Pair<Page, List<Stroke>>?,
        pdf: PdfPageBackgrounds? = this.pdf,
        elements: List<PageElement> = emptyList(),
        pictures: Map<String, ByteArray> = emptyMap(),
    ): Boolean {
        val (page, strokes) = first ?: return false
        val notebook = saver.notebook
        if (!repository.thumbnailIsStale(notebookId, notebook.updatedAtMillis)) return false
        return try {
            val bytes = withContext(Dispatchers.Default) { renderThumbnail(page, strokes, pdf?.renderNow(page, THUMB_WIDTH), elements, pictures) }
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
        const val MAX_PDF_BYTES = 200L shl 20
        const val DEFAULT_BUFFER = 64 * 1024
        const val READ_SHARE = 0.6f
        const val TAG = "BestNoteAutosave"
        const val TAP_SLOP_PT = 10f
    }
}

private const val THUMB_WIDTH = 320
private const val THUMB_QUALITY = 80
private const val MIN_THUMB_STROKE_PX = 1.5f
private const val THUMB_PICTURE_SAMPLE = 2

/**
 * Renders [page] (paper, template and [strokes] in page points) [THUMB_WIDTH] px wide as WebP.
 * Strokes are immutable, so this runs off the main thread.
 */
internal fun renderThumbnail(
    page: Page,
    strokes: List<Stroke>,
    pdfRaster: Bitmap? = null,
    elements: List<PageElement> = emptyList(),
    pictures: Map<String, ByteArray> = emptyMap(),
): ByteArray {
    val bitmap = renderPageBitmap(page, strokes, THUMB_WIDTH, pdfRaster, elements, pictures)
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
internal fun renderPageBitmap(
    page: Page,
    strokes: List<Stroke>,
    widthPx: Int,
    pdfRaster: Bitmap? = null,
    elements: List<PageElement> = emptyList(),
    pictures: Map<String, ByteArray> = emptyMap(),
): Bitmap {
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
    if (elements.isNotEmpty()) {
        val decoded = HashMap<String, Bitmap?>()
        val images = object : ElementImages {
            override fun bitmap(asset: String, widthPx: Int, onReady: Runnable): Bitmap? = decoded.getOrPut(asset) {
                pictures[asset]?.let { bytes ->
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = THUMB_PICTURE_SAMPLE }
                    runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
                }
            }
        }
        canvas.save()
        canvas.scale(scale, scale)
        ElementPainter().draw(canvas, elements, scale, images, Runnable { })
        canvas.restore()
        decoded.values.forEach { it?.recycle() }
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

/** A PDF read for "Insert from PDF" (PAGES-05) but not yet in the notebook: [sizes] are its pages in points. */
internal class StagedPdf(val asset: String, val bytes: ByteArray, var source: PdfDocumentSource?, val sizes: List<Pair<Int, Int>>) {
    val pageCount: Int get() = sizes.size
}

/** The picked file cannot become pages. */
internal class PdfInsertException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { Unreadable, Password, Empty, TooLarge }
}
