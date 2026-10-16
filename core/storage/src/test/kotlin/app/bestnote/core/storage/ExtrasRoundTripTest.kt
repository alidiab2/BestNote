package app.bestnote.core.storage

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtrasRoundTripTest {
    @get:Rule val tmp = TemporaryFolder()

    private val id = NotebookId("nb-extras")
    private fun pkg(extras: Map<String, ByteArray> = emptyMap()) = NotebookPackage(
        Notebook(id, "Extras", 1L, 2L),
        listOf(PageContent(Page(id, 0), InkChunkCodec.encode(emptyList()), "page-a")),
        extras = extras,
    )

    @Test fun `extras survive the directory store and the zip archive`() = runTest {
        val store = FileNotebookStore(tmp.newFolder())
        val bytes = "{\"format\":1}".encodeToByteArray()
        store.write(pkg(mapOf("smartboard.json" to bytes)))
        assertArrayEquals(bytes, store.read(id)!!.extras["smartboard.json"])
        val out = java.io.ByteArrayOutputStream()
        BnoteArchive.export(store.read(id)!!, out)
        assertArrayEquals(bytes, BnoteArchive.import(out.toByteArray().inputStream()).extras["smartboard.json"])
    }

    @Test fun `an extra is rewritten in place by a partial save`() = runTest {
        val store = FileNotebookStore(tmp.newFolder())
        store.write(pkg(mapOf("smartboard.json" to "1".encodeToByteArray())))
        store.writePages(pkg(mapOf("smartboard.json" to "2".encodeToByteArray())), emptySet())
        assertEquals("2", store.read(id)!!.extras["smartboard.json"]!!.decodeToString())
    }

    @Test fun `the autosaver replaces an extra and reports the newest bytes`() = runTest {
        val store = InMemoryNotebookStore()
        store.write(pkg())
        val saver = Autosaver(pkg(), store, backgroundScope)
        assertNull(saver.extra("smartboard.json"))
        saver.updateExtra("smartboard.json", "a".encodeToByteArray())
        saver.updateExtra("smartboard.json", "b".encodeToByteArray())
        assertEquals("b", saver.extra("smartboard.json")!!.decodeToString())
        saver.flush()
        assertEquals("b", store.read(id)!!.extras["smartboard.json"]!!.decodeToString())
        saver.close()
    }

    @Test fun `a notebook without extras (format 4) still reads`() {
        val manifest = BnoteFormat.parseManifest(
            """{"format": 4, "notebook": {"id": "x", "title": "t", "createdAtMillis": 1, "updatedAtMillis": 2, "template": "Ruled", "pageCount": 1}, "pages": []}""".encodeToByteArray(),
        )
        assertTrue(manifest.extras.isEmpty())
        assertEquals(BnoteFormat.VERSION, manifest.format)
    }

    @Test fun `the v4 fixture reads with no extras and saves as v5`() {
        fun entry(path: String) = javaClass.getResourceAsStream("/fixtures/v4-notebook/$path")?.readBytes()
        val pkg = BnoteFormat.fromEntries(::entry)
        assertTrue(pkg.extras.isEmpty())
        assertEquals(2, pkg.pages.size)
        val again = BnoteFormat.fromEntries(BnoteFormat.toEntries(pkg.withExtras(mapOf("smartboard.json" to "{}".encodeToByteArray())))::get)
        assertEquals("{}", again.extras["smartboard.json"]!!.decodeToString())
    }
}
