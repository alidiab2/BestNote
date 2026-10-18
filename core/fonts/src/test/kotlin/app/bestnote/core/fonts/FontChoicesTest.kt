package app.bestnote.core.fonts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEXT-05: the picker's lists, the catalogue's text families and how a missing font falls back. */
class FontChoicesTest {
    @Test fun recentsAreNewestFirstWithoutDuplicatesAndCapped() {
        var r = emptyList<String>()
        listOf("a", "b", "c", "a").forEach { r = FontChoices.pushRecent(r, it, max = 3) }
        assertEquals(listOf("a", "c", "b"), r)
        r = FontChoices.pushRecent(r, "d", max = 3)
        assertEquals(listOf("d", "a", "c"), r)
    }

    @Test fun favouritesToggle() {
        val f = FontChoices.toggle(emptySet(), "lora")
        assertEquals(setOf("lora"), f)
        assertTrue(FontChoices.toggle(f, "lora").isEmpty())
    }

    @Test fun searchMatchesNamesWordsAndScripts() {
        val ar = setOf(FontScript.Arabic, FontScript.Latin)
        assertTrue(FontChoices.matches("Noto Naskh Arabic", ar, "naskh", null))
        assertTrue(FontChoices.matches("Noto Naskh Arabic", ar, "noto arabic", null))
        assertFalse(FontChoices.matches("Noto Naskh Arabic", ar, "hebrew", null))
        assertTrue(FontChoices.matches("Amiri", ar, "عربي", null))
        assertTrue(FontChoices.matches("Heebo", setOf(FontScript.Hebrew, FontScript.Latin), "עברית", null))
        assertFalse(FontChoices.matches("Lora", setOf(FontScript.Latin), "", FontScript.Arabic))
        assertTrue(FontChoices.matches("Lora", setOf(FontScript.Latin), "", null))
    }

    @Test fun manyFontsForEveryScriptAndKind() {
        val fonts = BundledFonts.all.map { it.name to it.scripts } + FontCatalogue.all.map { it.family to it.scripts }
        assertTrue(fonts.count { FontScript.Arabic in it.second } >= 15)
        assertTrue(fonts.count { FontScript.Hebrew in it.second } >= 12)
        assertTrue(fonts.count { FontScript.Latin in it.second } >= 40)
        val kinds = BundledFonts.text.map { it.category }.toSet()
        assertTrue(kinds.containsAll(listOf(FontCategory.Sans, FontCategory.Serif, FontCategory.Mono)))
        // Ids are unique across bundled and catalogue fonts; bundled fonts are never also offered for download.
        val ids = BundledFonts.all.map { it.id } + FontCatalogue.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(FontCatalogue.all.any { c -> BundledFonts.all.any { it.name == c.family } })
    }

    @Test fun missingFontsFallBackToTheirKind() {
        assertEquals(FontChoices.Fallback.Serif, FontChoices.fallbackFor("gf:amiri"))
        assertEquals(FontChoices.Fallback.Serif, FontChoices.fallbackFor("noto-naskh-arabic"))
        assertEquals(FontChoices.Fallback.Mono, FontChoices.fallbackFor("gf:firacode"))
        assertEquals(FontChoices.Fallback.Mono, FontChoices.fallbackFor("mono"))
        assertEquals(FontChoices.Fallback.Hand, FontChoices.fallbackFor("gf:patrickhand"))
        assertEquals(FontChoices.Fallback.Sans, FontChoices.fallbackFor("gf:rubik"))
        assertEquals(FontChoices.Fallback.Sans, FontChoices.fallbackFor("my:7"))
    }
}
