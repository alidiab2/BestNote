package app.bestnote.core.storage

import app.bestnote.core.model.PaperColor
import app.bestnote.core.model.PaperTemplate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatMigrationTest {
    private fun fixture(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/v1-notebook/$path")) { "missing fixture $path" }.readBytes()

    private fun readV1() = BnoteFormat.fromEntries { runCatching { fixture(it) }.getOrNull() }

    @Test fun `v1 fixture migrates to the current model`() {
        val pkg = readV1()
        assertEquals("Old notebook", pkg.notebook.title)
        assertEquals(PaperTemplate.Grid, pkg.notebook.template)
        assertEquals(listOf("page-0", "page-1"), pkg.pages.map { it.id })
        assertEquals(PaperColor.White, pkg.pages[0].page.paperColor)
        assertEquals(595f, pkg.pages[0].page.widthPt)
        assertEquals(420f, pkg.pages[1].page.widthPt)
        val strokes = InkChunkCodec.decode(pkg.pages[0].strokes)
        assertEquals(1, strokes.size)
        assertEquals(3, strokes[0].samples.size)
        assertEquals(null, strokes[0].brush.family) // v1 ink chunk: no family key
    }

    @Test fun `rewriting a migrated notebook writes the current version`() {
        val entries = BnoteFormat.toEntries(readV1())
        val manifest = Json.parseToJsonElement(entries.getValue(BnoteFormat.MANIFEST).decodeToString()).jsonObject
        assertEquals(BnoteFormat.VERSION.toString(), manifest["format"]!!.jsonPrimitive.content)
        val page = Json.parseToJsonElement(entries.getValue("pages/page-0.json").decodeToString()).jsonObject
        assertEquals(BnoteFormat.VERSION.toString(), page["format"]!!.jsonPrimitive.content)
        assertTrue("paper" in page)
        // And the rewritten form reads back identically.
        val again = BnoteFormat.fromEntries { entries[it] }
        assertEquals(readV1().notebook, again.notebook)
    }

    @Test fun `v2 fixture migrates and gets an empty element list`() {
        val pkg = BnoteFormat.fromEntries { runCatching { checkNotNull(javaClass.getResourceAsStream("/fixtures/v2-notebook/$it")).readBytes() }.getOrNull() }
        assertEquals(listOf("page-0", "page-1"), pkg.pages.map { it.id })
        assertTrue(pkg.pages.all { it.elements.isEmpty() })
        val page = Json.parseToJsonElement(BnoteFormat.toEntries(pkg).getValue("pages/page-0.json").decodeToString()).jsonObject
        assertEquals("3", page["format"]!!.jsonPrimitive.content)
    }

    @Test fun `a newer format is refused not migrated`() {
        val future = fixture("manifest.json").decodeToString().replace("\"format\": 1", "\"format\": 99")
        assertThrows(UnsupportedFormatVersionException::class.java) {
            BnoteFormat.fromEntries { if (it == BnoteFormat.MANIFEST) future.encodeToByteArray() else runCatching { fixture(it) }.getOrNull() }
        }
    }

    @Test fun `every version below current has a migration step`() {
        assertEquals((1 until BnoteFormat.VERSION).toList(), FormatMigrations.steps.map { it.from })
    }
}
