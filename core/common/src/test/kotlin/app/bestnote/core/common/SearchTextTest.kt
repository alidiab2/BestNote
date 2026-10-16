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

    @Test fun tokensAreDistinctAndLongestFirst() {
        assertEquals(listOf("newton", "law", "of"), SearchText.tokens("Law of NEWTON law"))
        assertTrue(SearchText.tokens("  ").isEmpty())
    }

    @Test fun detectsArabic() {
        assertTrue(SearchText.isArabic("قانون Newton"))
        assertFalse(SearchText.isArabic("Newton"))
    }
}
