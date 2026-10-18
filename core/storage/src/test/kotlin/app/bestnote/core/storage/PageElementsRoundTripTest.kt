package app.bestnote.core.storage

import app.bestnote.core.model.CropRect
import app.bestnote.core.model.ImageElement
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.TextAlignment
import app.bestnote.core.model.TextElement
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** TEXT-01 / MEDIA-01: text boxes and images survive the v1 format, the directory store and the autosaver. */
class PageElementsRoundTripTest {
    @get:Rule val tmp = TemporaryFolder()
    private val nid = NotebookId("el")
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
    private val asset = ImageAssets.pathFor(jpeg, "jpg")

    private val image = ImageElement(
        "el-1", 300f, 200f, 240f, 120f, asset, 1600, 800, CropRect(0.1f, 0.2f, 0.9f, 1f), rotation = 0.5f, locked = true,
    )
    private val arabic = TextElement(
        "el-2", 100f, 80f, 180f, 60f, "مرحبا بالعالم\nسطر ثان", sizePt = 24f, colorArgb = 0xFF2A4BD7.toInt(),
        bold = true, italic = true, align = TextAlignment.End, rotation = -0.25f,
    )
    private val english = TextElement("el-3", 100f, 300f, 200f, 30f, "Hello \"world\" ✓")

    private fun pkg(elements: List<PageElement> = listOf(image, arabic, english)): NotebookPackage {
        val pages = listOf(
            PageContent(Page(nid, 0), InkChunkCodec.encode(emptyList()), "p0", elements),
            PageContent(Page(nid, 1), InkChunkCodec.encode(emptyList()), "p1"),
        )
        return NotebookPackage(Notebook(nid, "Els", 1, 2, pageCount = 2), pages, mapOf(asset to jpeg))
    }

    @Test fun elementsRoundTripThroughTheFormat() {
        val entries = BnoteFormat.toEntries(pkg())
        val back = BnoteFormat.fromEntries { entries[it] }
        assertEquals(listOf(image, arabic, english), back.pages[0].elements)
        assertTrue(back.pages[1].elements.isEmpty())
        assertArrayEquals(jpeg, back.assets.getValue(asset))
    }

    /** TEXT-05 / CANVAS-20: the picked font id (bundled, downloaded "gf:", own "my:") is stored per text box. */
    @Test fun fontIdsRoundTrip() {
        val fonts = listOf("noto-naskh-arabic", "frank-ruhl-libre", "gf:amiri", "my:3", null)
        val boxes = fonts.mapIndexed { i, f -> english.copy(id = "font-$i", font = f) }
        val entries = BnoteFormat.toEntries(pkg(boxes))
        val back = BnoteFormat.fromEntries { entries[it] }
        assertEquals(fonts, back.pages[0].elements.map { (it as TextElement).font })
    }

    @Test fun styleRunsListsAndSpacingRoundTrip() {
        val rich = TextElement(
            "el-9", 50f, 60f, 200f, 80f, "سطر أول\nline two", underline = true, strike = true, align = app.bestnote.core.model.TextAlignment.Justify,
            runs = listOf(
                app.bestnote.core.model.TextRun(0, 3, app.bestnote.core.model.RunStyle(bold = true, underline = true, colorArgb = 0xFFAA0000.toInt())),
                app.bestnote.core.model.TextRun(8, 12, app.bestnote.core.model.RunStyle(italic = true, strike = true, font = "hand", sizePt = 30f)),
            ),
            list = app.bestnote.core.model.ListStyle.Numbered, numerals = app.bestnote.core.model.Numerals.ArabicIndic, lineSpacing = 1.6f,
        )
        val entries = BnoteFormat.toEntries(pkg(listOf(rich)))
        assertEquals(listOf<PageElement>(rich), BnoteFormat.fromEntries { entries[it] }.pages[0].elements)
    }

    @Test fun v7TextWithoutFormattingStillReads() {
        val entries = BnoteFormat.toEntries(pkg(listOf(english))).toMutableMap()
        val page = entries.getValue(BnoteFormat.pageMetaPath("p0")).decodeToString()
            .replace("\"format\": ${BnoteFormat.VERSION}", "\"format\": 7")
        entries[BnoteFormat.pageMetaPath("p0")] = page.encodeToByteArray()
        entries[BnoteFormat.MANIFEST] = entries.getValue(BnoteFormat.MANIFEST).decodeToString().replace("\"format\": ${BnoteFormat.VERSION}", "\"format\": 7").encodeToByteArray()
        assertEquals(listOf<PageElement>(english), BnoteFormat.fromEntries { entries[it] }.pages[0].elements)
    }

    @Test fun paragraphStylesListsAndIndentsRoundTrip() {
        val styled = TextElement(
            "el-10", 50f, 60f, 200f, 80f, "Title\nعنوان فرعي\nitem\nnested\ncode()",
            paragraphs = listOf(
                app.bestnote.core.model.ParagraphFormat(app.bestnote.core.model.ParagraphStyle.Title),
                app.bestnote.core.model.ParagraphFormat(app.bestnote.core.model.ParagraphStyle.Subheading),
                app.bestnote.core.model.ParagraphFormat(list = app.bestnote.core.model.ListStyle.Bullet),
                app.bestnote.core.model.ParagraphFormat(list = app.bestnote.core.model.ListStyle.Numbered, indent = 2),
                app.bestnote.core.model.ParagraphFormat(app.bestnote.core.model.ParagraphStyle.Code, list = app.bestnote.core.model.ListStyle.None),
            ),
        )
        val entries = BnoteFormat.toEntries(pkg(listOf(styled)))
        assertTrue(entries.getValue(BnoteFormat.pageMetaPath("p0")).decodeToString().contains("\"paras\""))
        assertEquals(listOf<PageElement>(styled), BnoteFormat.fromEntries { entries[it] }.pages[0].elements)
    }

    @Test fun plainParagraphsStoreNothing() {
        val plain = english.copy(paragraphs = listOf(app.bestnote.core.model.ParagraphFormat.PLAIN))
        val entries = BnoteFormat.toEntries(pkg(listOf(plain)))
        val back = BnoteFormat.fromEntries { entries[it] }.pages[0].elements.single() as TextElement
        assertTrue(back.paragraphs.isEmpty())
    }

    @Test fun v8TextWithoutParagraphFormatsStillReads() {
        val rich = english.copy(underline = true, list = app.bestnote.core.model.ListStyle.Bullet)
        val entries = BnoteFormat.toEntries(pkg(listOf(rich))).toMutableMap()
        for (path in listOf(BnoteFormat.pageMetaPath("p0"), BnoteFormat.pageMetaPath("p1"), BnoteFormat.MANIFEST)) {
            entries[path] = entries.getValue(path).decodeToString().replace("\"format\": ${BnoteFormat.VERSION}", "\"format\": 8").encodeToByteArray()
        }
        assertEquals(listOf<PageElement>(rich), BnoteFormat.fromEntries { entries[it] }.pages[0].elements)
    }

    @Test fun groupsAndOrderRoundTrip() {
        val grouped = listOf(english.withGroup("g-1"), image.withGroup("g-1"), arabic) // order is the z-order, bottom to top
        val entries = BnoteFormat.toEntries(pkg(grouped))
        val back = BnoteFormat.fromEntries { entries[it] }.pages[0].elements
        assertEquals(grouped, back)
        assertEquals(listOf("g-1", "g-1", null), back.map { it.group })
        assertEquals(listOf("el-3", "el-1", "el-2"), back.map { it.id })
    }

    @Test fun olderFilesWithoutGroupsStillRead() {
        val entries = BnoteFormat.toEntries(pkg())
        val back = BnoteFormat.fromEntries { entries[it] }
        assertTrue(back.pages[0].elements.all { it.group == null })
    }

    @Test fun lockStatePersists() {
        val entries = BnoteFormat.toEntries(pkg(listOf(image, english.withLocked(true))))
        val back = BnoteFormat.fromEntries { entries[it] }.pages[0].elements
        assertEquals(listOf(true, true), back.map { it.locked })
    }

    @Test fun survivesTheDirectoryStoreAndPartialSaves() = runTest {
        val store = FileNotebookStore(tmp.root)
        store.write(pkg())
        val back = store.read(nid)!!
        assertEquals(listOf(image, arabic, english), back.pages[0].elements)
        store.writePages(back, setOf("p1")) // another page saved: p0's elements and the picture stay
        val again = store.read(nid)!!
        assertEquals(listOf(image, arabic, english), again.pages[0].elements)
        assertArrayEquals(jpeg, again.assets.getValue(asset))
    }

    @Test fun filesWithoutElementsStillOpenAndUnknownKindsAreSkipped() {
        val entries = BnoteFormat.toEntries(pkg(emptyList()))
        val meta = BnoteFormat.pageMetaPath("p0")
        val legacy = entries.getValue(meta).decodeToString().replace(Regex("\"elements\":\\s*\\[\\s*\\]"), "\"x\": 0")
        entries[meta] = legacy.encodeToByteArray()
        assertTrue(BnoteFormat.fromEntries { entries[it] }.pages[0].elements.isEmpty())

        // A newer app's element type is dropped, the rest of the page is kept.
        val withFuture = BnoteFormat.toEntries(pkg(listOf(arabic)))
        withFuture[meta] = withFuture.getValue(meta).decodeToString()
            .replace("\"elements\": [", "\"elements\": [{\"type\":\"sticker\",\"id\":\"s\",\"cx\":1,\"cy\":1,\"w\":1,\"h\":1},")
            .encodeToByteArray()
        assertEquals(listOf(arabic), BnoteFormat.fromEntries { withFuture[it] }.pages[0].elements)
    }

    @Test fun sameBytesAreOneAssetName() {
        assertEquals(asset, ImageAssets.pathFor(jpeg.copyOf(), "jpg"))
        assertTrue(asset != ImageAssets.pathFor(byteArrayOf(9), "jpg"))
        assertTrue(asset.startsWith("assets/img-") && asset.endsWith(".jpg"))
    }

    @Test fun autosaverStoresElementsAndAssetsOnce() = runTest {
        val store = InMemoryNotebookStore()
        store.write(pkg(emptyList()).let { NotebookPackage(it.notebook, it.pages) })
        val saver = Autosaver(
            store.read(nid)!!, store, backgroundScope, cpu = StandardTestDispatcher(testScheduler),
            clock = { 5_000 + testScheduler.currentTime }, timeSource = testScheduler.timeSource,
        )
        saver.addAsset(asset, jpeg)
        saver.addAsset(asset, jpeg) // inserting the same picture again adds nothing
        saver.updateElements("p0", listOf(image, arabic))
        saver.flush()
        var back = store.read(nid)!!
        assertEquals(listOf(image, arabic), back.pages[0].elements)
        assertEquals(setOf(asset), back.assets.keys)

        // Ink on the other page, and a layout change, keep the elements.
        saver.updatePage("p1", emptyList())
        saver.updateLayout(listOf("p1" to back.pages[1].page, "p0" to back.pages[0].page))
        saver.flush()
        back = store.read(nid)!!
        assertEquals(listOf("p1", "p0"), back.pages.map { it.id })
        assertEquals(listOf(image, arabic), back.pages[1].elements)
        saver.close()
    }
}
