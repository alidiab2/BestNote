package app.bestnote.engine.canvas.document

import android.graphics.RenderNode
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.engine.layout.document.DocumentMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CANVAS-05 smooth zoom: a 5-page notebook of real handwriting (~1.5k strokes a page) zoomed out
 * (every page enters the view) and in through the real pages layer, the way a pinch drives it.
 * Each step re-records every bound page slot, as the UI thread does in the frame after a zoom
 * commit. No step may draw ink as vectors or measure strokes on the main thread: pages show
 * stand-ins (other scales stretched, or paper) until the workers have rendered the settled scale.
 *
 * Run: ./gradlew :engine:canvas:assembleDebugAndroidTest, install on the emulator only, then
 * `adb -s emulator-5554 shell am instrument -w -e class app.bestnote.engine.canvas.document.ZoomStallBenchmark
 *   app.bestnote.engine.canvas.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class ZoomStallBenchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun pinchingADenseNotebookNeverRastersOnTheMainThread() {
        // Fresh stroke objects per page, so every page is measured for the first time when it appears.
        val perPage = List(PAGES) { BenchStrokes.handwriting(instrumentation.context, copiesX = 3, copiesY = 4) }
        lateinit var layer: PagesLayer
        instrumentation.runOnMainSync {
            layer = PagesLayer(context, SheetStyle(10f, 3f, 0x33503C28), DocumentMetrics(0, 0, 0, 24, 0))
            layer.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY))
            layer.layout(0, 0, WIDTH, HEIGHT)
            val doc = PagedDocument()
            doc.reset(List(PAGES) { Page(NotebookId("bench"), it) }, strokes = { perPage[it] })
            layer.document = doc
            layer.layout(0, 0, WIDTH, HEIGHT)
        }

        // First sight of pages 0-1 (includes one-time class loading and JIT, so only reported). Pages 2-4
        // are first seen during the pinch below, where they count towards the worst step.
        val firstDraw = recordAll(layer).max
        settle(layer)
        val settled = recordAll(layer)

        // The pinch: out until all five pages show, back in past fit-to-width.
        instrumentation.runOnMainSync { layer.zoomGesture = true }
        var worst = 0.0
        var live = 0
        var slots = 0
        val steps = floatArrayOf(0.8f, 0.6f, 0.45f, 0.3f, 0.45f, 0.7f, 1f, 1.4f, 2f, 2.8f)
        for (z in steps) {
            instrumentation.runOnMainSync { layer.applyZoom(z, WIDTH / 2f, HEIGHT / 2f, WIDTH / 2f, HEIGHT / 2f) }
            val r = recordAll(layer)
            worst = maxOf(worst, r.max)
            live += r.live
            slots = maxOf(slots, r.slots)
        }
        val boundAtSmallest = slots
        instrumentation.runOnMainSync { layer.zoomGesture = false }
        val t0 = System.nanoTime()
        settle(layer)
        val sharpMs = (System.nanoTime() - t0) / 1e6
        val after = recordAll(layer)

        val report = "pages=$PAGES strokes/page=${perPage[0].size} firstDraw=${f(firstDraw)}ms settledRecord=${f(settled.max)}ms | " +
            "pinch steps=${steps.size} worstRecord=${f(worst)}ms liveTiles=$live maxSlots=$boundAtSmallest | sharp after ${"%.0f".format(sharpMs)}ms, " +
            "record=${f(after.max)}ms fresh=${after.fresh}"
        Log.i(TAG, report)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$TAG: $report\n") })
        assertEquals("ink drawn as vectors during the pinch: $report", 0, live)
        assertTrue("a pinch step stalled the main thread: $report", worst < 8.0)
        assertTrue("settled zoom didn't render: $report", after.fresh > 0)
    }

    private class Recorded(val max: Double, val live: Int, val fresh: Int, val slots: Int)

    private fun f(v: Double) = "%.2f".format(v)

    private fun settle(layer: PagesLayer) {
        repeat(4) {
            layer.tiles.awaitIdle()
            instrumentation.waitForIdleSync()
            recordAll(layer) // a redraw requests any tile that is still missing
        }
        layer.tiles.awaitIdle()
        instrumentation.waitForIdleSync()
    }

    /** Records every bound page slot's display list once on the main thread; the slowest one and tile counts. */
    private fun recordAll(layer: PagesLayer): Recorded {
        var max = 0.0
        var live = 0
        var fresh = 0
        var slots = 0
        instrumentation.runOnMainSync {
            for (i in 0 until layer.childCount) {
                val slot = layer.getChildAt(i) as? PageSlotView ?: continue
                if (slot.index < 0 || slot.width <= 0) continue
                slots++
                val node = RenderNode("bench")
                node.setPosition(0, 0, slot.width, slot.height)
                val canvas = node.beginRecording(slot.width, slot.height)
                val t = System.nanoTime()
                slot.draw(canvas)
                max = maxOf(max, (System.nanoTime() - t) / 1e6)
                node.endRecording()
                live += layer.tiles.lastLive
                fresh += layer.tiles.lastFresh
            }
        }
        return Recorded(max, live, fresh, slots)
    }

    private companion object {
        const val TAG = "ZoomStallBenchmark"
        const val PAGES = 5
        const val WIDTH = 1600
        const val HEIGHT = 2560
    }
}
