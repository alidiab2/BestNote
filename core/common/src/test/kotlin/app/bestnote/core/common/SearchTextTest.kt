package app.bestnote.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {
    @Test fun foldsCaseAndAccents() = assertEquals("cafe", SearchText.fold("  Café "))

    @Test fun foldsArabicVariants() {
        assertEquals(SearchText.fold("أحمد"), SearchText.fold("احمد"))
        assertEquals(SearchText.fold("مكتبة"), SearchText.fold("مكتبه"))
        assertEquals(SearchText.fold("قَانُون"), SearchText.fold("قانون"))
    }

    @Test fun foldsMoreArabicForms() {
        assertEquals(SearchText.fold("إسلام"), SearchText.fold("اسلام"))
        assertEquals(SearchText.fold("آمن"), SearchText.fold("امن"))
        assertEquals(SearchText.fold("مستشفى"), SearchText.fold("مستشفي"))
        assertEquals(SearchText.fold("كـــتاب"), SearchText.fold("كتاب"))
        assertEquals(SearchText.fold("مُحَمَّد"), SearchText.fold("محمد"))
    }

    @Test fun mixedQueryKeepsBothScripts() {
        assertEquals(listOf("newton", "قانون"), SearchText.tokens("قانون NEWTON").sortedBy { it != "newton" })
    }

    @Test fun tokensAreDistinctAndLongestFirst() {
        assertEquals(listOf("newton", "law", "of"), SearchText.tokens("Law of NEWTON law"))
        assertTrue(SearchText.tokens("  ").isEmpty())
    }

    @Test fun detectsArabic() {
        assertTrue(SearchText.isArabic("قانون Newton"))
        assertFalse(SearchText.isArabic("Newton"))
    }
}
