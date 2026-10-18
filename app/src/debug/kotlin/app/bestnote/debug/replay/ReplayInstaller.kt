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
 * sensitivity / stabilization  override the pen's INK-04/05 tuning for this replay (--ef 0..1 /
 *        --ei 0..100; default: what the pen picker set)
 *
 * Also: app.bestnote.debug.SEED (--es file, --ei cols, --ei rows) puts a corpus file's handwriting
 * straight onto the current page as finished strokes, to build 5k-stroke pages in a second, and
 * app.bestnote.debug.STATE --ez tiles false|true switches the CANVAS-06 tile cache for A/B checks.
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
                    when (intent.action) {
                        PINCH -> pinch(activity, intent)
                        STATE -> state(activity, intent)
                        SEED -> seed(activity, intent)
                        TEXTS -> texts(activity, intent)
                        PERF -> perf(activity, intent)
                        ONBOARDING -> showOnboardingAgain(activity)
                        else -> replay(activity, intent)
                    }
            }
            val filter = IntentFilter(ACTION).apply {
                addAction(PINCH)
                addAction(STATE)
                addAction(SEED)
                addAction(TEXTS)
                addAction(PERF)
                addAction(ONBOARDING)
            }
            ContextCompat.registerReceiver(activity, r, filter, ContextCompat.RECEIVER_EXPORTED)
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

        /**
         * CANVAS-05 frame-time check: runs [PERF_SCRIPT] (a series of two-finger pinches, --ei rounds N
         * times) while [FrameProbe] records frame times, then writes the summary to the status file:
         *   am broadcast -a app.bestnote.debug.PERF -p <pkg> --ei rounds 2 --ei steps 40
         */
        private fun perf(activity: Activity, intent: Intent) {
            val status = File(File(activity.filesDir, "replay").apply { mkdirs() }, "status")
            val canvas = findCanvas(activity.window.decorView) ?: return status.writeText("error no notebook canvas on screen\n")
            status.writeText("running perf\n")
            val rounds = intent.getIntExtra("rounds", 2)
            val steps = intent.getIntExtra("steps", 40)
            val gapMs = intent.getIntExtra("gap", 150).toLong()
            val probe = FrameProbe(activity).also { it.start() }
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val script = List(rounds) { PERF_SCRIPT }.flatten()
            fun run(i: Int) {
                if (i == script.size) {
                    handler.postDelayed({
                        val line = "done perf gestures=${script.size} zoom=${(canvas as? PagedDocumentView)?.zoom} ${probe.stop()}"
                        Log.i(TAG, line)
                        status.writeText(line + "\n")
                    }, 400)
                    return
                }
                val (s0, s1, dx, dy) = script[i]
                FingerGesture(canvas, canvas.width / 2f, canvas.height / 2f, s0, s1, dx, dy, steps) {
                    handler.postDelayed({ run(i + 1) }, gapMs)
                }.start()
            }
            run(0)
        }

        /**
         * Logs and writes the notebook view's state, so scripts can check what a gesture did:
         *   adb shell am broadcast -a app.bestnote.debug.STATE -p <pkg>; run-as <pkg> cat files/replay/status
         */
        private fun state(activity: Activity, intent: Intent) {
            val status = File(File(activity.filesDir, "replay").apply { mkdirs() }, "status")
            val canvas = findCanvas(activity.window.decorView) as? PagedDocumentView ?: return status.writeText("error no notebook canvas on screen\n")
            // CANVAS-06 A/B: --ez tiles false draws without the dry-ink tile cache.
            if (intent.hasExtra("tiles")) canvas.tileCacheEnabled = intent.getBooleanExtra("tiles", true)
            val strokes = canvas.document?.pages?.sumOf { it.strokes.size } ?: -1
            val line = "done state zoom=${canvas.zoom} page=${canvas.currentPage} scrollX=${canvas.scrollOffsetX} scrollY=${canvas.scrollOffsetY} strokes=$strokes ${canvas.renderStats} ${memStats()}"
            Log.i(TAG, line)
            status.writeText(line + "\n")
        }

        /**
         * Memory counters for leak checks, read without forcing a GC (dumpsys meminfo does force one):
         * javaAllocMb = bytes ever allocated on the Java heap (rate = churn), javaUsedMb = live + garbage
         * not yet collected, nativeMb = malloc'd bytes in use (bitmap pixels, ink meshes, paths).
         */
        private fun memStats(): String {
            val rt = Runtime.getRuntime()
            val alloc = android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L
            val gcs = android.os.Debug.getRuntimeStat("art.gc.gc-count") ?: "?"
            return "javaAllocMb=%.1f javaUsedMb=%.1f nativeMb=%.1f gcs=%s".format(
                alloc / MB, (rt.totalMemory() - rt.freeMemory()) / MB, android.os.Debug.getNativeHeapAllocatedSize() / MB, gcs,
            )
        }

        /**
         * CANVAS-06: puts a corpus file's real handwriting (not lines) straight onto the current page,
         * repeated on a copiesX x copiesY grid, as finished strokes (one undo step). Fills a page
         * with thousands of strokes in a second, where a live replay would take minutes:
         *   am broadcast -a app.bestnote.debug.SEED -p <pkg> --es file lecture_en.json --ei cols 4 --ei rows 11
         */
        private fun seed(activity: Activity, intent: Intent) {
            val dir = File(activity.filesDir, "replay").apply { mkdirs() }
            val status = File(dir, "status")
            try {
                val canvas = findCanvas(activity.window.decorView) as? PagedDocumentView ?: error("no notebook canvas on screen")
                val doc = canvas.document ?: error("no document")
                val page = canvas.currentPage.coerceAtLeast(0)
                val (wPt, hPt) = canvas.pageSize(page) ?: error("no page")
                val file = StrokeFile.parse(File(dir, intent.getStringExtra("file") ?: "lecture_en.json").readText())
                val cols = intent.getIntExtra("cols", 4)
                val rows = intent.getIntExtra("rows", 11)
                val ptPerMm = minOf(wPt / cols / file.widthMm, hPt / rows / file.heightMm)
                val brush = BrushCatalog.brush(Tool.Pen, PenStyle.Fountain, InkPalette.BLACK, BrushCatalog.DEFAULT_SIZE * 0.3f * 0.5f) ?: error("no brush")
                // --ei pages N seeds N pages from the current one (adding pages as needed): a dense notebook for PERF.
                val pageCount = intent.getIntExtra("pages", 1).coerceAtLeast(1)
                var total = 0
                for (target in page until page + pageCount) {
                while (target >= doc.size) doc.addPage()
                val out = ArrayList<androidx.ink.strokes.Stroke>()
                for (r in 0 until rows) for (c in 0 until cols) for (st in file.strokes) {
                    if (st.hover || st.samples.isEmpty()) continue
                    val batch = androidx.ink.strokes.MutableStrokeInputBatch()
                    val t0 = st.samples[0].tMs
                    for (p in st.samples) {
                        batch.add(
                            androidx.ink.brush.InputToolType.STYLUS,
                            c * file.widthMm * ptPerMm + p.x * ptPerMm,
                            r * file.heightMm * ptPerMm + p.y * ptPerMm,
                            (p.tMs - t0).toLong(), 2.54f / 72f, p.pressure, p.tiltRad, p.orientationRad,
                        )
                    }
                    out += androidx.ink.strokes.Stroke(brush, batch)
                }
                doc.addStrokes(target, out)
                total += out.size
                }
                status.writeText("done seed $total strokes on $pageCount page(s) from $page\n")
            } catch (t: Throwable) {
                status.writeText("error ${t.message}\n")
                Log.e(TAG, "seed failed", t)
            }
        }

        /**
         * CANVAS-20 checks: adds text boxes to the page in view, one per "|"-separated text, stacked from [y] (page points):
         *   am broadcast -a app.bestnote.debug.TEXTS -p <pkg> --es texts "مرحبا|שלום" [--es font heebo] [--ef x 300] [--ef y 120]
         */
        private fun texts(activity: Activity, intent: Intent) {
            val status = File(File(activity.filesDir, "replay").apply { mkdirs() }, "status")
            try {
                val canvas = findCanvas(activity.window.decorView) as? PagedDocumentView ?: error("no notebook canvas on screen")
                val doc = canvas.document ?: error("no document")
                val page = canvas.currentPage.coerceAtLeast(0)
                val painter = app.bestnote.engine.canvas.document.ElementPainter()
                var y = intent.getFloatExtra("y", 120f)
                val x = intent.getFloatExtra("x", 300f)
                val added = (intent.getStringExtra("texts") ?: error("missing --es texts")).split('|').map { t ->
                    val box = painter.fitted(
                        app.bestnote.core.model.TextElement(
                            app.bestnote.core.model.PageElement.newId(), x, y + 15f, 220f, 30f, t,
                            sizePt = 22f, font = intent.getStringExtra("font"),
                        ),
                    )
                    y += box.height + 16f
                    box
                }
                doc.replaceElements(page, doc.pages[page].elements + added)
                status.writeText("done texts ${added.size}\n")
            } catch (t: Throwable) {
                status.writeText("error ${t.message}\n")
                Log.e(TAG, "texts failed", t)
            }
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
                val tuning = if (intent.hasExtra("sensitivity") || intent.hasExtra("stabilization")) {
                    app.bestnote.engine.ink.PenTuning(intent.getFloatExtra("sensitivity", 1f), intent.getIntExtra("stabilization", 0))
                } else {
                    null
                }
                val dash = intent.getStringExtra("dash")?.let { d -> app.bestnote.core.model.LineDash.entries.firstOrNull { it.name.equals(d, ignoreCase = true) } ?: error("unknown dash '$d'") }
                fun applyPen() = when (canvas) {
                    is PagedDocumentView -> {
                        canvas.lineDash = dash ?: app.bestnote.core.model.LineDash.Solid
                        canvas.tool = tool
                        canvas.penStyle = style
                        canvas.colorArgb = color
                        canvas.brushSize = size
                        canvas.sideButtonTool = sideTool
                        tuning?.let { canvas.penTuning = it }
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
        const val MB = 1024.0 * 1024.0
        const val ACTION = "app.bestnote.debug.REPLAY"
        const val PINCH = "app.bestnote.debug.PINCH"
        const val STATE = "app.bestnote.debug.STATE"
        const val SEED = "app.bestnote.debug.SEED"
        const val TEXTS = "app.bestnote.debug.TEXTS"
        const val PERF = "app.bestnote.debug.PERF"
        const val ONBOARDING = "app.bestnote.debug.ONBOARDING"

        /** The scripted PERF pinch sequence: (span0, span1, dx, dy) per gesture, zooming out and in. */
        val PERF_SCRIPT = listOf(
            floatArrayOf(700f, 260f, 0f, 0f), // out: more pages enter the view
            floatArrayOf(260f, 700f, 0f, 0f), // back in
            floatArrayOf(300f, 900f, 60f, 40f), // in past fit-to-width
            floatArrayOf(900f, 300f, -60f, -40f), // out again
            floatArrayOf(600f, 200f, 0f, 300f), // out while panning down
            floatArrayOf(200f, 600f, 0f, -300f), // in while panning up
        )
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
