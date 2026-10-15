package app.bestnote.engine.export

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperColor
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.StylusSample
import app.bestnote.core.model.Tool
import app.bestnote.core.storage.FileNotebookStore
import app.bestnote.core.storage.InkChunkCodec
import app.bestnote.core.storage.LibraryBackup
import app.bestnote.core.storage.NotebookPackage
import app.bestnote.core.storage.PageContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The backup against real Storage Access Framework calls (a test provider stands in for the user's folder). */
@RunWith(AndroidJUnit4::class)
class SafBackupInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri("app.bestnote.engine.export.test.docs", "root")

    private fun pkg(id: String, updated: Long, pages: Int = 2): NotebookPackage {
        val nid = NotebookId(id)
        val strokes = listOf(InkStroke(InkBrush(Tool.Pen, PenStyle.Fountain, 0xFF000000.toInt(), 4f), List(40) { StylusSample(it * 3f, it * 2f, 0.5f, 0f, 0f, it * 8L) }))
        return NotebookPackage(
            Notebook(nid, "Notes \"$id\" ✏️", 1000, updated, PaperTemplate.Grid, pageCount = pages),
            List(pages) { i -> PageContent(Page(nid, i, PaperTemplate.Dotted, 612f, 792f, PaperColor.Cream), InkChunkCodec.encode(strokes), "p$i") },
            mapOf("assets/doc.pdf" to ByteArray(5000) { (it % 251).toByte() }),
        )
    }

    private fun stores(name: String): Pair<FileNotebookStore, FileNotebookStore> {
        val root = File(context.cacheDir, "saf-test/$name").also { it.deleteRecursively() }
        return FileNotebookStore(File(root, "notebooks")) to FileNotebookStore(File(root, "trash"))
    }

    @Test fun backupAndRestoreThroughSaf(): Unit = runBlocking<Unit> {
        File(context.cacheDir, "saf-root").deleteRecursively()
        val (nbA, trashA) = stores("a")
        nbA.write(pkg("one", 10))
        nbA.write(pkg("two/odd name", 20, pages = 4))
        trashA.write(pkg("gone", 30))
        val metadata = """{"version":1,"folders":[{"id":"f","name":"Uni","colorIndex":2}]}""".encodeToByteArray()

        val target = SafBackupTarget(context, tree)
        val first = LibraryBackup(nbA, trashA).backup(target, mapOf("library/metadata.json" to metadata))
        assertEquals(3, first.written)
        assertTrue(target.hasBackup())

        // Edit one notebook, back up again: that one and the (always rewritten) Trash entry; "two" is reused.
        nbA.write(pkg("one", 99))
        val second = LibraryBackup(nbA, trashA).backup(SafBackupTarget(context, tree), mapOf("library/metadata.json" to metadata))
        assertEquals(2, second.written)
        assertEquals(1, second.reused)

        // A brand new install restores everything from the picked folder.
        val (nbB, trashB) = stores("b")
        val restoreTarget = SafBackupTarget.forRestore(context, tree)
        assertNotNull(restoreTarget)
        val restored = LibraryBackup(nbB, trashB).restore(restoreTarget!!)
        assertEquals(3, restored.restored)
        assertTrue(restored.failed.isEmpty())
        assertArrayEquals(metadata, restored.extras.getValue("library/metadata.json"))
        for ((a, b) in listOf(nbA to nbB, trashA to trashB)) {
            assertEquals(a.list().sortedBy { it.id.value }, b.list().sortedBy { it.id.value })
            for (n in a.list()) {
                val pa = a.read(n.id)!!
                val pb = b.read(n.id)!!
                assertEquals(pa.notebook, pb.notebook)
                pa.pages.zip(pb.pages).forEach { (x, y) -> assertEquals(x.page, y.page); assertArrayEquals(x.strokes, y.strokes) }
                pa.assets.forEach { (k, v) -> assertArrayEquals(v, pb.assets.getValue(k)) }
            }
        }
    }
}
