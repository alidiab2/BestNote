/*
 * Debug-only: wires the handwriting replay into the running app without touching main code.
 *
 * A ContentProvider (declared only in the debug manifest) registers activity callbacks; while an
 * activity is resumed it listens for:
 *
 *   adb shell am broadcast -a app.bestnote.debug.REPLAY -p app.bestnote.debug \
 *       --es file lecture_en.json --es pen fountain --es color '#1E2433' --ef size 5 --ez clear true
 *
 * file   name under files/replay/ (push with run-as), or an absolute path the app can read
 * pen    fountain | ballpoint | brush | pencil | highlighter | lasso (default fountain)
 * color  #RRGGBB or #AARRGGBB (default: the tool's usual colour)
 * size   brush size in px (default BrushCatalog.DEFAULT_SIZE)
 * clear  clear the page first (default false)
 * batch  frame window in ms for historical batching (default 8.33 = 120 Hz; 0 = one event per sample)
 * slow   time scale (default 1 = real time)
 * scale  extra page scale (default: 1 mm = 1 mm on screen, shrunk to fit the canvas)
 * side   temporary tool for the side button: eraser (default) | none
 * left/top  where the writing starts, in dp from the canvas' corner (default 16; 112 on the paged
 *        notebook, so the words land on the first page's writing lines)
 *
 * Progress goes to files/replay/status ("running …" / "done …" / "error …") and logcat tag
 * BestNoteReplay. scripts/agent/replay.sh wraps all of this.
 */
package app.bestnote.debug.replay

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import app.bestnote.engine.canvas.PageCanvasView
import app.bestnote.engine.canvas.document.PagedDocumentView
import app.bestnote.engine.ink.BrushCatalog
import app.bestnote.engine.ink.InkPalette
import java.io.File

class ReplayInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return false
        app.registerActivityLifecycleCallbacks(Callbacks)
        return true
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    private object Callbacks : Application.ActivityLifecycleCallbacks {
        private var receiver: BroadcastReceiver? = null
        private var running: StrokeReplayer? = null

        override fun onActivityResumed(activity: Activity) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) =
                    if (intent.action == PINCH) pinch(activity, intent) else replay(activity, intent)
            }
            ContextCompat.registerReceiver(activity, r, IntentFilter(ACTION).apply { addAction(PINCH) }, ContextCompat.RECEIVER_EXPORTED)
            receiver = r
        }

        override fun onActivityPaused(activity: Activity) {
            receiver?.let { runCatching { activity.unregisterReceiver(it) } }
            receiver = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit

        /** Two-finger pinch/pan on the notebook (see [FingerGesture]); status file like a replay. */
        private fun pinch(activity: Activity, intent: Intent) {
            val status = File(File(activity.filesDir, "replay").apply { mkdirs() }, "status")
            val canvas = findCanvas(activity.window.decorView) ?: return status.writeText("error no notebook canvas on screen\n")
            status.writeText("running pinch\n")
            FingerGesture(
                canvas,
                intent.getFloatExtra("cx", canvas.width / 2f),
                intent.getFloatExtra("cy", canvas.height / 2f),
                intent.getFloatExtra("span0", 200f),
                intent.getFloatExtra("span1", 400f),
                intent.getFloatExtra("dx", 0f),
                intent.getFloatExtra("dy", 0f),
                intent.getIntExtra("steps", 40),
            ) {
                val zoom = (canvas as? PagedDocumentView)?.zoom
                Log.i(TAG, "PINCH zoom=$zoom")
                status.writeText("done pinch zoom=$zoom\n")
            }.start()
        }

        private fun replay(activity: Activity, intent: Intent) {
            val dir = File(activity.filesDir, "replay").apply { mkdirs() }
            val status = File(dir, "status")
            try {
                val name = intent.getStringExtra("file") ?: error("missing --es file")
                val file = if (name.startsWith("/")) File(name) else File(dir, name)
                val strokes = StrokeFile.parse(file.readText())
                val canvas = findCanvas(activity.window.decorView) ?: error("no notebook canvas on screen: open a notebook first")
                running?.cancel()

                val pen = (intent.getStringExtra("pen") ?: "fountain").lowercase()
                val (tool, style) = PENS[pen] ?: error("unknown pen '$pen' (${PENS.keys.joinToString()})")
                val color = intent.getStringExtra("color")?.toColorInt() ?: defaultColor(tool)
                val size = intent.getFloatExtra("size", BrushCatalog.DEFAULT_SIZE)
                val side = intent.getStringExtra("side")
                val sideTool = if (side == "none") null else Tool.Eraser
                fun applyPen() = when (canvas) {
                    is PagedDocumentView -> {
                        canvas.tool = tool
                        canvas.penStyle = style
                        canvas.colorArgb = color
                        canvas.brushSize = size
                        canvas.sideButtonTool = sideTool
                    }
                    is PageCanvasView -> {
                        canvas.tool = tool
                        canvas.penStyle = style
                        canvas.colorArgb = color
                        canvas.brushSize = size
                        canvas.sideButtonTool = sideTool
                    }
                    else -> Unit
                }
                applyPen()
                if (intent.getBooleanExtra("clear", false)) {
                    when (canvas) {
                        is PagedDocumentView -> canvas.document?.clearPage(canvas.currentPage)
                        is PageCanvasView -> canvas.clear()
                    }
                }

                val dm = activity.resources.displayMetrics
                val pxPerMmRaw = (if (dm.xdpi > 100f) dm.xdpi else dm.densityDpi.toFloat()) / 25.4f
                val inset = if (canvas is PagedDocumentView) PAGED_INSET_DP else DEFAULT_INSET_DP
                val left = intent.getFloatExtra("left", inset) * dm.density
                val top = intent.getFloatExtra("top", inset) * dm.density
                val margin = maxOf(left, top)
                val fit = ReplayPlan.fitScale(strokes, pxPerMmRaw, canvas.width.toFloat(), canvas.height.toFloat(), margin)
                val pxPerMm = pxPerMmRaw * fit * intent.getFloatExtra("scale", 1f)
                val events = ReplayPlan.build(
                    strokes,
                    pxPerMm,
                    left,
                    top,
                    frameMs = intent.getFloatExtra("batch", 1000f / 120f).toDouble(),
                    timeScale = intent.getFloatExtra("slow", 1f).toDouble(),
                )
                status.writeText("running ${strokes.name}\n")
                Log.i(TAG, "replaying ${strokes.name}: ${strokes.strokes.size} strokes, ${events.size} events, pen=$pen, ${"%.2f".format(pxPerMm)} px/mm")
                running = StrokeReplayer(canvas, events, beforeDown = ::applyPen) { n, ms ->
                    status.writeText("done ${strokes.name} events=$n ms=$ms\n")
                    Log.i(TAG, "DONE ${strokes.name}: $n events in $ms ms")
                    running = null
                }.also { it.start() }
            } catch (t: Throwable) {
                status.writeText("error ${t.message}\n")
                Log.e(TAG, "replay failed", t)
            }
        }

        /** The paged notebook view, or the single-page canvas. */
        private fun findCanvas(v: View): View? {
            if (v is PagedDocumentView || v is PageCanvasView) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) findCanvas(v.getChildAt(i))?.let { return it }
            return null
        }

        private fun defaultColor(tool: Tool) = when (tool) {
            Tool.Highlighter -> InkPalette.HIGHLIGHTER_YELLOW
            Tool.Pencil -> InkPalette.GRAPHITE
            else -> InkPalette.BLACK
        }
    }

    private companion object {
        const val TAG = "BestNoteReplay"
        const val ACTION = "app.bestnote.debug.REPLAY"
        const val PINCH = "app.bestnote.debug.PINCH"
        const val DEFAULT_INSET_DP = 16f
        const val PAGED_INSET_DP = 112f
        val PENS = mapOf(
            "fountain" to (Tool.Pen to PenStyle.Fountain),
            "ballpoint" to (Tool.Pen to PenStyle.Ball),
            "ball" to (Tool.Pen to PenStyle.Ball),
            "brush" to (Tool.Pen to PenStyle.Brush),
            "pencil" to (Tool.Pencil to PenStyle.Fountain),
            "highlighter" to (Tool.Highlighter to PenStyle.Fountain),
            // CANVAS-08: replays a lasso loop (or a drag on the selection) instead of ink.
            "lasso" to (Tool.Lasso to PenStyle.Fountain),
        )
    }
}
