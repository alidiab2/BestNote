package app.bestnote.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import app.bestnote.core.database.NotebookRepository
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.Tool
import app.bestnote.core.storage.Autosaver
import app.bestnote.core.storage.InkChunkCodec
import app.bestnote.engine.canvas.PageCanvasView
import app.bestnote.engine.ink.BrushCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * Connects the editor's canvas to storage (DOC-03) and the thumbnail cache (DOC-10).
 *
 * Loading decodes and meshes strokes off the main thread. After every canvas change, [onChanged]
 * converts only the strokes it has not seen before (each one once) and hands an immutable snapshot
 * to the [Autosaver], which encodes and writes in the background. [onStop] flushes when the app
 * leaves the foreground, so a later process kill loses nothing; [close] flushes and re-renders the
 * library thumbnail. All methods except the background work run on the main thread.
 */
internal class PageSession(
    private val notebookId: NotebookId,
    private val repository: NotebookRepository,
    scope: CoroutineScope,
) {
    private val _ready = MutableStateFlow(false)

    /** True once the page is loaded and can be [attach]ed to a canvas. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var saver: Autosaver? = null
    private var pageId = ""
    private var template = PaperTemplate.Ruled

    /** Stored strokes this build cannot draw (unknown brush); kept so saving never drops them. */
    private var preserved: List<InkStroke> = emptyList()
    private var current: List<Stroke> = emptyList()
    private val models = IdentityHashMap<Stroke, InkStroke>()
    private var width = 0
    private var height = 0
    private var density = 1f
    private var applying = false

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
            return
        }
        val page = open.pkg.pages.first()
        val loaded = try {
            withContext(Dispatchers.Default) {
                InkChunkCodec.decode(page.strokes).map { model -> model to runCatching { InkConversion.toInk(model) }.getOrNull() }
            }
        } catch (e: CancellationException) {
            repository.backgroundScope.launch { open.autosaver.close() } // closed before loading finished
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Damaged page (DOC-04 adds the placeholder): never overwrite it with an empty page.
            Log.e(TAG, "Page ${page.id} of $notebookId is damaged; autosave disabled", e)
            repository.backgroundScope.launch { open.autosaver.close() }
            return
        }
        preserved = loaded.filter { it.second == null }.map { it.first }
        current = loaded.mapNotNull { (model, stroke) -> stroke?.also { models[it] = model } }
        pageId = page.id
        template = page.page.template
        saver = open.autosaver
        _ready.value = true
    }

    /** Shows the loaded page on [view] (also after a configuration change recreated the view). */
    fun attach(view: PageCanvasView) {
        if (!_ready.value) return
        val drawnWhileLoading = view.strokes.isNotEmpty()
        applying = true
        view.setStrokes(current + view.strokes)
        applying = false
        density = view.resources.displayMetrics.density
        if (drawnWhileLoading) onChanged(view) else rememberSize(view)
    }

    /** Called after every change to the canvas (draw, erase, undo, redo, clear). Cheap and non-blocking. */
    fun onChanged(view: PageCanvasView) {
        val saver = saver ?: return
        if (applying || !_ready.value) return
        val strokes = view.strokes.toList()
        val page = ArrayList<InkStroke>(preserved.size + strokes.size)
        page += preserved
        for (stroke in strokes) page += models.getOrPut(stroke) { InkConversion.toModel(stroke, brushOf(view, stroke)) }
        current = strokes
        rememberSize(view)
        saver.updatePage(pageId, page)
    }

    /** App left the foreground: get everything on disk now. */
    fun onStop() {
        val saver = saver ?: return
        val snapshot = current
        repository.backgroundScope.launch {
            try {
                saver.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.e(TAG, "Flush failed; autosave keeps retrying", e)
            }
            refreshThumbnail(saver, snapshot)
        }
    }

    /** The editor closed: final save, then the library thumbnail. */
    fun close() {
        val saver = saver ?: return
        this.saver = null
        val snapshot = current
        val closedAt = SystemClock.elapsedRealtime()
        repository.backgroundScope.launch {
            try {
                saver.close()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.e(TAG, "Final save of $notebookId failed", e)
            }
            if (refreshThumbnail(saver, snapshot)) {
                Log.i(TAG, "Thumbnail of $notebookId updated ${SystemClock.elapsedRealtime() - closedAt} ms after closing")
            }
        }
    }

    private fun rememberSize(view: PageCanvasView) {
        if (view.width > 0 && view.height > 0) {
            width = view.width
            height = view.height
        }
    }

    /** The brush the canvas drew [stroke] with: the view's current tool, at the stroke's own size. */
    private fun brushOf(view: PageCanvasView, stroke: Stroke): InkBrush {
        val tool = view.tool.takeIf { it.inks } ?: Tool.Pen
        val scale = BrushCatalog.spec(tool, view.penStyle)?.sizeScale ?: 1f
        return InkBrush(tool, view.penStyle, view.colorArgb, stroke.brush.size / scale)
    }

    /** Re-renders the thumbnail when the cached one predates the last save. @return true if rendered. */
    private suspend fun refreshThumbnail(saver: Autosaver, strokes: List<Stroke>): Boolean {
        val notebook = saver.notebook
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || !repository.thumbnailIsStale(notebookId, notebook.updatedAtMillis)) return false
        return try {
            val bytes = withContext(Dispatchers.Default) { renderThumbnail(strokes, w, h, template, density) }
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

/** Renders the page at [THUMB_WIDTH] px wide as WebP. Strokes are immutable, so this runs off the main thread. */
internal fun renderThumbnail(strokes: List<Stroke>, viewWidth: Int, viewHeight: Int, template: PaperTemplate, density: Float): ByteArray {
    val scale = THUMB_WIDTH / viewWidth.toFloat()
    val bitmap = Bitmap.createBitmap(THUMB_WIDTH, (viewHeight * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (template == PaperTemplate.Blank) 0xFFFFFFFF.toInt() else 0xFFFFFDF7.toInt())
        val gap = 32 * density * scale
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332D5BFF }
        if (template == PaperTemplate.Ruled || template == PaperTemplate.Grid) {
            var y = if (template == PaperTemplate.Ruled) gap * 2 else gap
            while (y < bitmap.height) { canvas.drawLine(0f, y, bitmap.width.toFloat(), y, line); y += gap }
        }
        if (template == PaperTemplate.Grid) {
            var x = gap
            while (x < bitmap.width) { canvas.drawLine(x, 0f, x, bitmap.height.toFloat(), line); x += gap }
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
