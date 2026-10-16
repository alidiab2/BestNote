package app.bestnote.core.storage

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class FileNotebookStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(root: File = tmp.root) = FileNotebookStore(root)

    private suspend inline fun <reified T : Throwable> expect(block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("Expected ${T::class.simpleName} but got $e", e)
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }

    private fun pkg(id: String = "nb-1", updated: Long = 5, pages: Int = 3, seed: Int = 1): NotebookPackage {
        val nid = NotebookId(id)
        return NotebookPackage(
            Notebook(nid, "Physics \"I\" ✏️", 1_700_000_000_000, updated, PaperTemplate.Grid, pageCount = pages),
            List(pages) { i ->
                PageContent(
                    Page(nid, i, PaperTemplate.entries[i % 4], 612f, 792.5f),
                    InkChunkCodec.encode(randomStrokes(50, seed = seed + i)),
                    id = "p$i-$seed",
                )
            },
            mapOf("assets/abc.pdf" to byteArrayOf(1, 2, 3), "assets/img/x.webp" to ByteArray(1000) { it.toByte() }),
        )
    }

    private fun assertSame(expected: NotebookPackage, actual: NotebookPackage) {
        assertEquals(expected.notebook, actual.notebook)
        assertEquals(expected.pages.map { it.page }, actual.pages.map { it.page })
        assertEquals(expected.pages.map { it.id }, actual.pages.map { it.id })
        expected.pages.zip(actual.pages).forEach { (a, b) ->
            assertArrayEquals(a.strokes, b.strokes)
            assertEquals(InkChunkCodec.decode(a.strokes), InkChunkCodec.decode(b.strokes))
        }
        assertEquals(expected.assets.keys, actual.assets.keys)
        expected.assets.forEach { (k, v) -> assertArrayEquals(v, actual.assets.getValue(k)) }
    }

    @Test fun writeReadListDelete() = runTest {
        val s = store()
        val a = pkg("a", updated = 1)
        s.write(a)
        s.write(pkg("b/../weird id", updated = 2))
        assertEquals(listOf("b/../weird id", "a"), s.list().map { it.id.value })
        assertSame(a, s.read(NotebookId("a"))!!)
        // Weird ids stay inside root as one directory.
        assertEquals(2, tmp.root.listFiles()!!.size)

        assertTrue(s.delete(NotebookId("a")))
        assertFalse(s.delete(NotebookId("a")))
        assertNull(s.read(NotebookId("a")))
        assertEquals(listOf("b/../weird id"), s.list().map { it.id.value })
    }

    @Test fun unpackedLayoutOnDisk() = runTest {
        store().write(pkg("nb", pages = 2))
        val dir = File(tmp.root, "nb")
        val files = dir.walk().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet()
        assertEquals(
            setOf("manifest.json", "pages/p0-1.json", "pages/p0-1.ink", "pages/p1-1.json", "pages/p1-1.ink", "assets/abc.pdf", "assets/img/x.webp"),
            files,
        )
        val manifest = File(dir, "manifest.json").readText()
        assertTrue(manifest.contains("\"format\": ${BnoteFormat.VERSION}"))
    }

    @Test fun rewriteRemovesDroppedPagesAndTempFiles() = runTest {
        val s = store()
        s.write(pkg("nb", pages = 3, seed = 1))
        File(tmp.root, "nb/pages/p0-1.ink.tmp").writeText("leftover from a crash")
        val smaller = pkg("nb", pages = 1, seed = 9)
        s.write(smaller)
        assertSame(smaller, s.read(NotebookId("nb"))!!)
        val pages = File(tmp.root, "nb/pages").list()!!.sorted()
        assertEquals(listOf("p0-9.ink", "p0-9.json"), pages)
    }

    @Test fun crashBeforeManifestKeepsPreviousVersionReadable() = runTest {
        val s = store()
        val old = pkg("nb", pages = 2, seed = 1)
        s.write(old)
        // Simulate a crash mid-write: new page files landed, manifest did not.
        val dir = File(tmp.root, "nb")
        File(dir, "pages/new.ink").writeBytes(InkChunkCodec.encode(randomStrokes(3)))
        File(dir, "manifest.json.tmp").writeText("{\"format\": 1, half")
        assertSame(old, s.read(NotebookId("nb"))!!)
    }

    @Test fun zipExportImportRoundTrip() = runTest {
        val s = store()
        val original = pkg("nb", pages = 4)
        s.write(original)
        val zip = ByteArrayOutputStream().also { assertTrue(s.exportTo(NotebookId("nb"), it)) }.toByteArray()
        assertFalse(s.exportTo(NotebookId("missing"), ByteArrayOutputStream()))

        // Manifest is last, .ink entries are STORED, JSON is DEFLATED.
        val entries = ZipInputStream(ByteArrayInputStream(zip)).use { z -> generateSequence { z.nextEntry }.toList() }
        assertEquals("manifest.json", entries.last().name)
        assertTrue(entries.filter { it.name.endsWith(".ink") }.all { it.method == ZipEntry.STORED })
        assertTrue(entries.filter { it.name.endsWith(".json") }.all { it.method == ZipEntry.DEFLATED })

        assertSame(original, BnoteArchive.import(ByteArrayInputStream(zip)))

        val other = store(tmp.newFolder("other"))
        assertSame(original, other.importFrom(ByteArrayInputStream(zip)))
        assertSame(original, other.read(NotebookId("nb"))!!)
    }

    @Test fun newerFormatVersionIsRefused() = runTest {
        val s = store()
        s.write(pkg("nb"))
        val manifest = File(tmp.root, "nb/manifest.json")
        manifest.writeText(manifest.readText().replace("\"format\": ${BnoteFormat.VERSION}", "\"format\": ${BnoteFormat.VERSION + 1}"))
        val e = expect<UnsupportedFormatVersionException> { s.read(NotebookId("nb")) }
        assertEquals(BnoteFormat.VERSION + 1, e.found)
        assertEquals(emptyList<Notebook>(), s.list())

        // A future manifest with an unknown schema is still reported as "newer", not "corrupt".
        val zip = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write("{\"format\": 7, \"everything\": \"changed\"}".toByteArray()); z.closeEntry()
        }
        assertThrows(UnsupportedFormatVersionException::class.java) { BnoteArchive.import(ByteArrayInputStream(zip.toByteArray())) }
    }

    @Test fun newerPageVersionIsRefused() = runTest {
        val s = store()
        val p = pkg("nb")
        s.write(p)
        val meta = File(tmp.root, "nb/pages/${p.pages[0].id}.json")
        meta.writeText(meta.readText().replace("\"format\": ${BnoteFormat.VERSION}", "\"format\": 99"))
        expect<UnsupportedFormatVersionException> { s.read(NotebookId("nb")) }
    }

    @Test fun corruptChunkOnDiskIsDetectedByCodec() = runTest {
        val s = store()
        val p = pkg("nb")
        s.write(p)
        val ink = File(tmp.root, "nb/pages/${p.pages[1].id}.ink")
        ink.writeBytes(ink.readBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte() })
        val read = s.read(NotebookId("nb"))!!
        InkChunkCodec.decode(read.pages[0].strokes) // other pages unaffected
        assertThrows(CorruptChunkException::class.java) { InkChunkCodec.decode(read.pages[1].strokes) }
    }

    @Test fun missingOrMalformedFilesAreCorrupt() = runTest {
        val s = store()
        val p = pkg("nb")
        s.write(p)
        File(tmp.root, "nb/pages/${p.pages[2].id}.ink").delete()
        expect<CorruptChunkException> { s.read(NotebookId("nb")) }
        File(tmp.root, "nb/manifest.json").writeText("not json")
        expect<CorruptChunkException> { s.read(NotebookId("nb")) }
    }

    @Test fun unsafeAssetPathsAndPageIdsAreRejected() = runTest {
        val nid = NotebookId("x")
        val nb = Notebook(nid, "x", 0, 0)
        val page = PageContent(Page(nid, 0), InkChunkCodec.encode(emptyList()))
        for (bad in listOf("../evil", "assets/../../evil", "assets/", "manifest.json", "/abs")) {
            expect<IllegalArgumentException> { store().write(NotebookPackage(nb, listOf(page), mapOf(bad to byteArrayOf()))) }
        }
        assertThrows(IllegalArgumentException::class.java) { PageContent(Page(nid, 0), byteArrayOf(), id = "../p") }
    }
}
