package app.bestnote.core.storage

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperColor
import app.bestnote.core.model.PaperTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String) = File(tmp.root, name).also { it.mkdirs() }
    private fun stores(name: String) = FileNotebookStore(dir("$name/notebooks")) to FileNotebookStore(dir("$name/trash"))

    private fun pkg(id: String, updated: Long, pages: Int = 2, seed: Int = 1): NotebookPackage {
        val nid = NotebookId(id)
        return NotebookPackage(
            Notebook(nid, "Notes \"$id\" ✏️", 1_700_000_000_000, updated, PaperTemplate.Cornell, pageCount = pages),
            List(pages) { i ->
                PageContent(
                    Page(nid, i, PaperTemplate.entries[(i + seed) % 8], 612f, 792.5f, if (i == 1) PaperColor.Dark else PaperColor.Cream),
                    InkChunkCodec.encode(randomStrokes(30, seed = seed + i)),
                    id = "p$i-$seed",
                )
            },
            mapOf("assets/doc.pdf" to ByteArray(3000) { (it * 7).toByte() }, "assets/library/deleted-at" to "123".encodeToByteArray()),
        )
    }

    private suspend fun assertSame(a: NotebookStore, b: NotebookStore) {
        val listA = a.list().sortedBy { it.id.value }
        assertEquals(listA, b.list().sortedBy { it.id.value })
        for (nb in listA) {
            val pa = a.read(nb.id)!!
            val pb = b.read(nb.id)!!
            assertEquals(pa.notebook, pb.notebook)
            assertEquals(pa.pages.map { it.id }, pb.pages.map { it.id })
            pa.pages.zip(pb.pages).forEach { (x, y) ->
                assertEquals(x.page, y.page)
                assertArrayEquals(x.strokes, y.strokes)
            }
            assertEquals(pa.assets.keys, pb.assets.keys)
            pa.assets.forEach { (k, v) -> assertArrayEquals(k, v, pb.assets.getValue(k)) }
        }
    }

    @Test fun `restore on a clean install reproduces the library exactly`() = runTest {
        val (nbA, trashA) = stores("a")
        nbA.write(pkg("one", 10, seed = 1))
        nbA.write(pkg("two/with:odd chars", 20, pages = 5, seed = 2))
        trashA.write(pkg("gone", 30, seed = 3))
        val folder = DirectoryBackupTarget(dir("sd"))
        val metadata = """{"version":1,"folders":[{"id":"f","name":"Uni","colorIndex":2}]}""".encodeToByteArray()

        val made = LibraryBackup(nbA, trashA).backup(folder, mapOf("library/metadata.json" to metadata), nowMillis = 99)
        assertEquals(2, made.notebooks)
        assertEquals(1, made.trashed)

        val (nbB, trashB) = stores("b") // a brand new install
        val result = LibraryBackup(nbB, trashB).restore(folder)
        assertEquals(3, result.restored)
        assertTrue(result.failed.isEmpty())
        assertArrayEquals(metadata, result.extras.getValue("library/metadata.json"))
        assertSame(nbA, nbB)
        assertSame(trashA, trashB)
    }

    @Test fun `second backup only writes what changed and drops stale files`() = runTest {
        val (nb, trash) = stores("a")
        nb.write(pkg("one", 10))
        nb.write(pkg("two", 20))
        val folder = DirectoryBackupTarget(dir("sd"))
        val backup = LibraryBackup(nb, trash)
        assertEquals(2, backup.backup(folder).written)

        nb.write(pkg("two", 25, seed = 5)) // edited
        nb.delete(NotebookId("one"))
        val again = backup.backup(folder)
        assertEquals(1, again.written)
        assertEquals(0, again.reused)
        val files = folder.list("notebooks")
        assertEquals(1, files.size)
        assertTrue(files.single().endsWith("-25.bnote"))

        nb.write(pkg("three", 40))
        val third = backup.backup(folder)
        assertEquals(1, third.written)
        assertEquals(1, third.reused)
    }

    @Test fun `restore keeps a newer local notebook and replaces an older one`() = runTest {
        val (nbA, trashA) = stores("a")
        nbA.write(pkg("one", 10, seed = 1))
        nbA.write(pkg("two", 20, seed = 2))
        val folder = DirectoryBackupTarget(dir("sd"))
        LibraryBackup(nbA, trashA).backup(folder)

        val (nbB, trashB) = stores("b")
        nbB.write(pkg("one", 50, seed = 9)) // edited after the backup
        nbB.write(pkg("two", 5, seed = 9)) // older than the backup
        nbB.write(pkg("local-only", 1))
        val result = LibraryBackup(nbB, trashB).restore(folder)
        assertEquals(1, result.restored)
        assertEquals(1, result.skippedNewerLocal)
        assertEquals(50, nbB.read(NotebookId("one"))!!.notebook.updatedAtMillis)
        assertEquals(20, nbB.read(NotebookId("two"))!!.notebook.updatedAtMillis)
        assertNotNull(nbB.read(NotebookId("local-only")))
    }

    @Test fun `a damaged notebook file is reported and the rest still restore`() = runTest {
        val (nbA, trashA) = stores("a")
        nbA.write(pkg("good", 10))
        nbA.write(pkg("bad", 20))
        val root = dir("sd")
        val folder = DirectoryBackupTarget(root)
        LibraryBackup(nbA, trashA).backup(folder)
        File(root, "notebooks").listFiles()!!.first { it.name.startsWith("bad") }.writeBytes(byteArrayOf(1, 2, 3))

        val (nbB, trashB) = stores("b")
        val result = LibraryBackup(nbB, trashB).restore(folder)
        assertEquals(1, result.restored)
        assertEquals(listOf("Notes \"bad\" ✏️"), result.failed)
        assertFalse(nbB.list().any { it.id.value == "bad" })
    }

    @Test fun `folder without a manifest is not a backup`() = runTest {
        val (nb, trash) = stores("a")
        assertThrows(BackupException::class.java) { kotlinx.coroutines.runBlocking { LibraryBackup(nb, trash).restore(DirectoryBackupTarget(dir("empty"))) } }
    }

    @Test fun `a crash before the manifest leaves the previous backup restorable`() = runTest {
        val (nb, trash) = stores("a")
        nb.write(pkg("one", 10))
        val root = dir("sd")
        val folder = DirectoryBackupTarget(root)
        val backup = LibraryBackup(nb, trash)
        backup.backup(folder)

        nb.write(pkg("one", 99, seed = 4))
        val failing = object : BackupTarget by folder {
            override suspend fun write(path: String, block: (java.io.OutputStream) -> Unit) {
                if (path == LibraryBackup.MANIFEST) throw java.io.IOException("disk full")
                folder.write(path, block)
            }
        }
        assertThrows(java.io.IOException::class.java) { kotlinx.coroutines.runBlocking { backup.backup(failing) } }

        val (nbB, trashB) = stores("b")
        assertEquals(1, LibraryBackup(nbB, trashB).restore(folder).restored)
        assertEquals(10, nbB.read(NotebookId("one"))!!.notebook.updatedAtMillis)
    }

    @Test fun `progress reaches the total`() = runTest {
        val (nb, trash) = stores("a")
        repeat(3) { nb.write(pkg("n$it", 10L + it)) }
        val seen = ArrayList<Pair<Int, Int>>()
        LibraryBackup(nb, trash).backup(DirectoryBackupTarget(dir("sd"))) { d, t -> seen += d to t }
        assertEquals(3 to 3, seen.last())
    }
}
