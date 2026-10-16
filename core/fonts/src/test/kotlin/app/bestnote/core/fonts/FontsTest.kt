package app.bestnote.core.fonts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

private fun repoFont(name: String): ByteArray {
    val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    return File(root, "core/designsystem/src/main/res/font/$name").readBytes()
}

/** A minimal sfnt with a format-4 cmap covering exactly [ranges] and a glyf table (enough for [FontValidator]). */
private fun syntheticFont(ranges: List<IntRange>, family: String? = "Synthetic"): ByteArray {
    fun u16(o: DataOutputStream, v: Int) = o.writeShort(v)
    val cmap = ByteArrayOutputStream().also { b ->
        val o = DataOutputStream(b)
        val segs = ranges.sortedBy { it.first } + listOf(0xFFFF..0xFFFF)
        val n = segs.size
        u16(o, 0); u16(o, 1) // version, one record
        u16(o, 3); u16(o, 1); o.writeInt(12) // platform 3, encoding 1, offset
        u16(o, 4); u16(o, 16 + n * 8); u16(o, 0); u16(o, n * 2) // format, length, language, segCountX2
        u16(o, 0); u16(o, 0); u16(o, 0) // searchRange, entrySelector, rangeShift (unused)
        segs.forEach { u16(o, it.last) }
        u16(o, 0)
        segs.forEach { u16(o, it.first) }
        // idDelta: map first char of every segment to glyph 1+ (non-zero) by delta = 1 - first
        segs.forEach { u16(o, if (it.first == 0xFFFF) 1 else (1 - it.first) and 0xFFFF) }
        segs.forEach { u16(o, 0) } // idRangeOffset
    }.toByteArray()
    val name = ByteArrayOutputStream().also { b ->
        val o = DataOutputStream(b)
        val s = (family ?: "").toByteArray(Charsets.UTF_16BE)
        u16(o, 0); u16(o, if (family == null) 0 else 1); u16(o, 6 + if (family == null) 0 else 12)
        if (family != null) { u16(o, 3); u16(o, 1); u16(o, 0x409); u16(o, 1); u16(o, s.size); u16(o, 0); o.write(s) }
    }.toByteArray()
    val maxp = ByteArray(6).also { it[5] = 7 }
    val glyf = ByteArray(16)
    val tables = listOf("cmap" to cmap, "glyf" to glyf, "maxp" to maxp, "name" to name)
    val out = ByteArrayOutputStream()
    val o = DataOutputStream(out)
    o.writeInt(0x00010000); u16(o, tables.size); u16(o, 0); u16(o, 0); u16(o, 0)
    var offset = 12 + tables.size * 16
    tables.forEach { (tag, data) ->
        o.write(tag.padEnd(4).toByteArray(Charsets.ISO_8859_1)); o.writeInt(0); o.writeInt(offset); o.writeInt(data.size)
        offset += (data.size + 3) / 4 * 4
    }
    tables.forEach { (_, data) -> o.write(data); repeat((4 - data.size % 4) % 4) { o.write(0) } }
    return out.toByteArray()
}

private val LATIN = listOf(0x20..0x7E)
private val ARABIC = listOf(0x20..0x7E, 0x600..0x6FF)
private val HEBREW = listOf(0x20..0x7E, 0x5D0..0x5EA)

class FontValidatorTest {
    @Test fun bundledFontsReportTheirScripts() {
        val caveat = FontValidator.inspect(repoFont("caveat.ttf"))
        assertTrue(FontScript.Latin in caveat.scripts)
        assertFalse(FontScript.Arabic in caveat.scripts)
        assertEquals("Caveat", caveat.familyName)
        val ruqaa = FontValidator.inspect(repoFont("aref_ruqaa.ttf"))
        assertTrue(FontScript.Arabic in ruqaa.scripts && FontScript.Latin in ruqaa.scripts)
        val levin = FontValidator.inspect(repoFont("gveret_levin.ttf"))
        assertTrue(FontScript.Hebrew in levin.scripts)
    }

    @Test fun syntheticFontsAreSeenPerScript() {
        assertEquals(setOf(FontScript.Latin), FontValidator.inspect(syntheticFont(LATIN)).scripts)
        assertEquals(setOf(FontScript.Latin, FontScript.Arabic), FontValidator.inspect(syntheticFont(ARABIC)).scripts)
        assertEquals(setOf(FontScript.Latin, FontScript.Hebrew), FontValidator.inspect(syntheticFont(HEBREW)).scripts)
        assertEquals("Synthetic", FontValidator.inspect(syntheticFont(LATIN)).familyName)
        assertNull(FontValidator.inspect(syntheticFont(LATIN, family = null)).familyName)
    }

    @Test fun refusesFilesThatAreNotUsableFonts() {
        fun kind(bytes: ByteArray) = assertThrows(FontRejected::class.java) { FontValidator.inspect(bytes) }.kind
        assertEquals(FontRejected.Kind.NotAFont, kind("hello, this is text".toByteArray()))
        assertEquals(FontRejected.Kind.NotAFont, kind(ByteArray(3)))
        assertEquals(FontRejected.Kind.Collection, kind("ttcf".toByteArray() + ByteArray(40)))
        assertEquals(FontRejected.Kind.Damaged, kind(repoFont("caveat.ttf").copyOf(300)))
        // Digits and punctuation only: no letters of any script.
        assertEquals(FontRejected.Kind.NoLetters, kind(syntheticFont(listOf(0x30..0x39, 0x2B..0x3D))))
        assertEquals(FontRejected.Kind.TooLarge, kind(ByteArray(FontValidator.MAX_BYTES + 1)))
    }
}

class FontCatalogueTest {
    @Test fun entriesAreUniqueAndTrusted() {
        assertEquals(FontCatalogue.all.size, FontCatalogue.all.map { it.id }.toSet().size)
        FontCatalogue.all.forEach { f ->
            assertTrue(f.fontUrl, f.fontUrl.startsWith("https://raw.githubusercontent.com/google/fonts/main/ofl/${f.dir}/"))
            assertTrue(f.licenseUrl.endsWith("/OFL.txt"))
            assertFalse(f.fontUrl.contains('[') || f.fontUrl.contains(' '))
            assertTrue(f.bytes in 1..FontCatalogue.HARD_LIMIT)
        }
        assertTrue(FontCatalogue.all.any { it.id == "gf:playpensansarabic" && it.fontUrl.endsWith("PlaypenSansArabic%5Bwght%5D.ttf") })
    }

    @Test fun everyScriptHasSeveralChoices() {
        assertTrue(FontCatalogue.forScript(FontScript.Latin).size >= 5)
        assertTrue(FontCatalogue.forScript(FontScript.Arabic).size >= 3)
        assertTrue(FontCatalogue.forScript(FontScript.Hebrew).size >= 3)
    }
}

class FontLibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun library() = FontLibrary(File(tmp.root, "fonts"))

    @Test fun downloadedFontIsValidatedStoredAndListed() {
        val lib = library()
        val entry = FontCatalogue.byId("gf:katibeh")!! // Arabic + Latin
        val added = lib.installDownloaded(entry, repoFont("aref_ruqaa.ttf"), "OFL text")
        assertEquals(FontOrigin.Downloaded, added.origin)
        assertEquals(listOf("gf:katibeh"), lib.list().map { it.id })
        assertEquals("OFL text", lib.licenseText("gf:katibeh"))
        // A file that lacks the promised script is refused and nothing is kept.
        val bad = assertThrows(FontRejected::class.java) { lib.installDownloaded(FontCatalogue.byId("gf:solitreo")!!, repoFont("caveat.ttf"), null) }
        assertEquals(FontRejected.Kind.NoLetters, bad.kind)
        assertEquals(1, lib.list().size)
        assertTrue(lib.delete("gf:katibeh"))
        assertTrue(lib.list().isEmpty())
        assertNull(lib.licenseText("gf:katibeh"))
    }

    @Test fun ownFontsGetIdsNamesAndSurviveRestart() {
        val lib = library()
        val a = lib.importMine("MyHand.ttf", syntheticFont(ARABIC, family = null))
        val b = lib.importMine("Other.otf", syntheticFont(HEBREW, family = "Ink Pen"))
        assertEquals(listOf("my:1", "my:2"), listOf(a.id, b.id))
        assertEquals("MyHand", a.name)
        assertEquals("Ink Pen", b.name)
        val again = library().list() // a new instance reads the same folder
        assertEquals(listOf("my:1", "my:2"), again.map { it.id })
        assertEquals(setOf(FontScript.Latin, FontScript.Hebrew), again[1].scripts)
        assertTrue(library().rename("my:2", "Pen two"))
        assertEquals("Pen two", library().list()[1].name)
        assertTrue(library().delete("my:1"))
        assertEquals(listOf("my:2"), library().list().map { it.id })
        // ids are never reused for a different file while a later one exists
        assertEquals("my:3", library().importMine("c.ttf", syntheticFont(LATIN)).id)
        // Not a font: refused, nothing written.
        assertThrows(FontRejected::class.java) { library().importMine("notes.txt", "hi there, i am text".toByteArray()) }
        assertEquals(2, library().list().size)
        // Unsafe ids never touch the disk.
        assertFalse(library().delete("my:../../x"))
        assertFalse(library().delete("gf:../../x"))
    }

    @Test fun installerDownloadsFromTheCatalogueUrlAndFetchesTheLicense() {
        val lib = library()
        val asked = ArrayList<String>()
        val installer = FontInstaller(lib) { url, max ->
            asked += url
            assertTrue(max > 0)
            if (url.endsWith("OFL.txt")) "licence".toByteArray() else repoFont("caveat.ttf")
        }
        // caveat has no Hebrew; Solitreo promises Hebrew + Latin, so it is refused and nothing is stored.
        assertThrows(FontRejected::class.java) { installer.install(FontCatalogue.byId("gf:solitreo")!!) }
        assertTrue(lib.list().isEmpty())
        assertTrue(asked.first().startsWith("https://raw.githubusercontent.com/google/fonts/main/ofl/solitreo/"))
    }

    @Test fun onlyTheTrustedHostIsFetched() {
        assertThrows(IOException::class.java) { TrustedFontFetcher.fetch("http://raw.githubusercontent.com/google/fonts/main/ofl/x/y.ttf", 10) }
        assertThrows(IOException::class.java) { TrustedFontFetcher.fetch("https://example.com/google/fonts/x.ttf", 10) }
        assertThrows(IOException::class.java) { TrustedFontFetcher.fetch("https://raw.githubusercontent.com/someone/else/main/x.ttf", 10) }
    }
}
