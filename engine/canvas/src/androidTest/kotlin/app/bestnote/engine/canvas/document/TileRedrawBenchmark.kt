package app.bestnote.engine.canvas.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import app.bestnote.engine.ink.BrushCatalog
import app.bestnote.engine.layout.document.DocumentMetrics
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CANVAS-06 benchmark: a page with 5k+ strokes of real handwriting (the lecture_en corpus sample,
 * repeated) redrawn through the real page slot, with the dry-ink tile cache and without it.
 *
 * "Redraw" is the main-thread cost of a page invalidation: recording the slot's display list
 * (RenderNode, what the UI thread does each frame the page changes). The software-raster numbers
 * draw the same frame into a Bitmap on the CPU, which bounds the pixel work too.
 *
 * Run: ./gradlew :engine:canvas:assembleDebugAndroidTest, install on the emulator only, then
 * `adb -s emulator-5554 shell am instrument -w -e class app.bestnote.engine.canvas.document.TileRedrawBenchmark
 *   app.bestnote.engine.canvas.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class TileRedrawBenchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun fiveThousandStrokePageRedrawsUnder8ms() {
        val strokes = handwriting(copiesX = 4, copiesY = 11)
        assertTrue("need 5k strokes, have ${strokes.size}", strokes.size >= 5000)

        lateinit var layer: PagesLayer
        val width = 1600
        val height = 2560
        instrumentation.runOnMainSync {
            layer = PagesLayer(context, SheetStyle(10f, 3f, 0x33503C28), DocumentMetrics(0, 0, 0, 24, 0))
            layer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            layer.layout(0, 0, width, height)
            val doc = PagedDocument()
            doc.reset(listOf(Page(NotebookId("bench"), 0)), strokes = { strokes })
            layer.document = doc
            layer.layout(0, 0, width, height)
        }
        val slot = slotOf(layer)

        // Without the cache: every redraw replays every stroke.
        layer.tiles.enabled = false
        val legacyRecord = recordMs(slot, 7)
        val legacySoftware = softwareMs(slot, 3)

        // With the cache: the first draw queues the tiles; wait for the workers, then redraw.
        layer.tiles.enabled = true
        val t0 = System.nanoTime()
        recordMs(slot, 1)
        settle(layer)
        val fillMs = (System.nanoTime() - t0) / 1e6
        val tiledRecord = recordMs(slot, 15)
        val tiledSoftware = softwareMs(slot, 5)
        val fresh = layer.tiles.lastFresh

        // One more stroke: the touched tiles keep their bitmap and draw the new ink over it.
        // Measured over several strokes (the first one also pays for JIT warm-up).
        val adds = DoubleArray(9)
        for (i in adds.indices) {
            instrumentation.runOnMainSync { layer.document!!.addStrokes(0, listOf(strokes[17 + i * 131].let { Stroke(it.brush, it.inputs) })) }
            adds[i] = recordMs(slot, 1)
        }
        adds.sort()
        val addRecord = adds[adds.size / 2]
        val addWorst = adds.last()
        settle(layer)
        val afterAdd = recordMs(slot, 7)

        val report = "strokes=${strokes.size} tiles=$fresh fill=${"%.0f".format(fillMs)}ms | record median: legacy=${f(legacyRecord)}ms tiled=${f(tiledRecord)}ms " +
            "afterStroke=${f(addRecord)}ms (worst ${f(addWorst)}) settled=${f(afterAdd)}ms | software raster: legacy=${f(legacySoftware)}ms tiled=${f(tiledSoftware)}ms"
        Log.i(TAG, report)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$TAG: $report\n") })
        assertTrue("tiled redraw too slow: $report", tiledRecord < 8.0)
        assertTrue("redraw after a new stroke too slow: $report", addRecord < 8.0)
    }

    private fun f(v: Double) = "%.2f".format(v)

    private fun settle(layer: PagesLayer) {
        repeat(3) {
            layer.tiles.awaitIdle()
            instrumentation.waitForIdleSync()
            recordMs(slotOf(layer), 1) // a redraw requests any tile that is still missing
        }
        layer.tiles.awaitIdle()
        instrumentation.waitForIdleSync()
    }

    private fun slotOf(layer: PagesLayer): PageSlotView {
        var slot: PageSlotView? = null
        instrumentation.runOnMainSync {
            for (i in 0 until layer.childCount) (layer.getChildAt(i) as? PageSlotView)?.takeIf { it.index == 0 }?.let { slot = it }
        }
        return checkNotNull(slot) { "page 0 not bound" }
    }

    /** Median ms of recording the slot's display list [n] times on the main thread. */
    private fun recordMs(slot: PageSlotView, n: Int): Double {
        val times = DoubleArray(n)
        instrumentation.runOnMainSync {
            val node = RenderNode("bench")
            node.setPosition(0, 0, slot.width, slot.height)
            for (i in 0 until n) {
                val canvas = node.beginRecording(slot.width, slot.height)
                val t = System.nanoTime()
                slot.draw(canvas)
                times[i] = (System.nanoTime() - t) / 1e6
                node.endRecording()
            }
        }
        times.sort()
        return times[n / 2]
    }

    /** Median ms of drawing the slot into a CPU bitmap. */
    private fun softwareMs(slot: PageSlotView, n: Int): Double {
        val times = DoubleArray(n)
        instrumentation.runOnMainSync {
            val bitmap = Bitmap.createBitmap(slot.width, slot.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            for (i in 0 until n) {
                val t = System.nanoTime()
                slot.draw(canvas)
                times[i] = (System.nanoTime() - t) / 1e6
            }
            bitmap.recycle()
        }
        times.sort()
        return times[n / 2]
    }

    /** The corpus sample repeated over an A4 page, in page points, with the default fountain pen. */
    private fun handwriting(copiesX: Int, copiesY: Int): List<Stroke> {
        val json = JSONObject(instrumentation.context.assets.open("lecture_en.json").bufferedReader().readText())
        val wMm = json.getDouble("width").toFloat()
        val hMm = json.getDouble("height").toFloat()
        val pageW = 595f
        val pageH = 842f
        val ptPerMm = minOf(pageW / copiesX / wMm, pageH / copiesY / hMm)
        val brush = checkNotNull(BrushCatalog.brush(Tool.Pen, PenStyle.Fountain, 0xFF1E2433.toInt(), BrushCatalog.DEFAULT_SIZE * 0.3f * 0.5f))
        val raw = json.getJSONArray("strokes")
        val out = ArrayList<Stroke>()
        for (cy in 0 until copiesY) for (cx in 0 until copiesX) {
            val ox = cx * wMm * ptPerMm
            val oy = cy * hMm * ptPerMm
            for (i in 0 until raw.length()) {
                val s = raw.getJSONObject(i)
                if (s.optString("type", "ink") != "ink") continue
                val samples = s.getJSONArray("samples")
                if (samples.length() == 0) continue
                val batch = MutableStrokeInputBatch()
                val t0 = samples.getJSONObject(0).getDouble("t_ms")
                for (j in 0 until samples.length()) {
                    val p = samples.getJSONObject(j)
                    batch.add(
                        InputToolType.STYLUS,
                        ox + p.getDouble("x").toFloat() * ptPerMm,
                        oy + p.getDouble("y").toFloat() * ptPerMm,
                        (p.getDouble("t_ms") - t0).toLong(),
                        CM_PER_PT,
                        p.optDouble("pressure", 0.5).toFloat(),
                        p.optDouble("tiltRad", 0.0).toFloat(),
                        p.optDouble("orientationRad", 0.0).toFloat(),
                    )
                }
                out += Stroke(brush, batch)
            }
        }
        return out
    }

    private companion object {
        const val TAG = "TileRedrawBenchmark"
        const val CM_PER_PT = 2.54f / 72f
    }
}
