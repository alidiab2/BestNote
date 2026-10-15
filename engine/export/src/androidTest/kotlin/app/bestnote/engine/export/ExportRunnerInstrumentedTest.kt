package app.bestnote.engine.export

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperColor
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.StylusSample
import app.bestnote.core.model.Tool
import app.bestnote.core.storage.FileNotebookStore
import app.bestnote.core.storage.InkChunkCodec
import app.bestnote.core.storage.NotebookPackage
import app.bestnote.core.storage.PageContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the real exporters on a device: PNG pixels and PDF structure of a small notebook. */
@RunWith(AndroidJUnit4::class)
class ExportRunnerInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun stroke(tool: Tool, style: PenStyle, color: Int, y: Float) = InkStroke(
        InkBrush(tool, style, color, 4f),
        List(60) { i -> StylusSample(60f + i * 6f, y + (i % 7), 0.2f + 0.8f * (i / 59f), 0f, 0f, i * 8L) },
    )

    private fun notebook(): NotebookPackage {
        val id = NotebookId("export-test")
        val strokes = listOf(
            stroke(Tool.Pen, PenStyle.Fountain, 0xFF1E2433.toInt(), 120f),
            stroke(Tool.Pen, PenStyle.Brush, 0xFFD9462B.toInt(), 180f),
            stroke(Tool.Highlighter, PenStyle.Fountain, 0xFFF7D84A.toInt(), 240f),
        )
        return NotebookPackage(
            Notebook(id, "Export: test/notebook", 1, 2, PaperTemplate.Ruled, pageCount = 2),
            listOf(
                PageContent(Page(id, 0, PaperTemplate.Ruled), InkChunkCodec.encode(strokes), "page-0"),
                PageContent(Page(id, 1, PaperTemplate.Cornell, paperColor = PaperColor.Dark), InkChunkCodec.encode(strokes), "page-1"),
            ),
        )
    }

    private fun runner(): ExportRunner {
        val root = File(context.cacheDir, "export-test-store").also { it.deleteRecursively() }
        val store = FileNotebookStore(root)
        runBlocking { store.write(notebook()) }
        return ExportRunner(context, store)
    }

    @Test fun pngPerPageHasInk(): Unit = runBlocking<Unit> {
        val out = runner().export(NotebookId("export-test"), ExportFormat.Png)
        assertEquals(2, out.files.size)
        assertEquals("Export_ test_notebook-1.png", out.files[0].name)
        val bmp = BitmapFactory.decodeFile(out.files[0].path)
        assertEquals((595 * 2), bmp.width)
        // The first stroke runs along y=120pt: some pixel near it must be dark ink on the light page.
        var dark = 0
        for (x in 0 until bmp.width step 4) for (y in 230..270) if (Color.red(bmp.getPixel(x, y)) < 100) dark++
        assertTrue("ink pixels: $dark", dark > 50)
        // Keep copies where the host can pull them.
        File("/sdcard/Download").takeIf { it.canWrite() }?.let { d -> out.files.forEach { it.copyTo(File(d, "doc06-${it.name}"), overwrite = true) } }
    }

    @Test fun singlePagePng(): Unit = runBlocking<Unit> {
        val out = runner().export(NotebookId("export-test"), ExportFormat.Png, PageRange.Single(1))
        assertEquals(1, out.files.size)
        assertTrue(out.files[0].name.endsWith("-2.png"))
    }

    @Test fun pdfHasOnePagePerPage(): Unit = runBlocking<Unit> {
        val progress = ArrayList<Pair<Int, Int>>()
        val out = runner().export(NotebookId("export-test"), ExportFormat.Pdf) { d, t -> progress += d to t }
        val file = out.files.single()
        val head = file.inputStream().use { s -> ByteArray(5).also { s.read(it) } }.decodeToString()
        assertEquals("%PDF-", head)
        assertEquals(2 to 2, progress.last())
        val text = file.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertEquals(2, Regex("/Type /Page\\b").findAll(text).count())
        File("/sdcard/Download").takeIf { it.canWrite() }?.let { file.copyTo(File(it, "doc06-test.pdf"), overwrite = true) }
    }
}
