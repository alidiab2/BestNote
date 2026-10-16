package app.bestnote.core.common

import java.text.Normalizer
import java.util.Locale

private val ARABIC_MARKS = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]")
private val LATIN_MARKS = Regex("\\p{Mn}+")
private val SPACES = Regex("\\s+")

/** Text folding shared by title search, the handwriting index and the global search box. */
object SearchText {
    /**
     * Folds a title, recognized word or query for matching: case, Latin accents, Arabic diacritics and
     * tatweel are ignored; أ/إ/آ/ٱ → ا, ة → ه and ى → ي, so "مكتبة" finds "مكتبه" and "أحمد" finds "احمد".
     */
    fun fold(text: String): String {
        val arabic = ARABIC_MARKS.replace(text, "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
            .replace('ة', 'ه').replace('ى', 'ي')
        val latin = LATIN_MARKS.replace(Normalizer.normalize(arabic, Normalizer.Form.NFD), "")
        // NFD splits آ into ا + madda, which LATIN_MARKS removes; recompose the rest.
        return SPACES.replace(Normalizer.normalize(latin, Normalizer.Form.NFC).lowercase(Locale.ROOT), " ").trim()
    }

    /** The words of a query after [fold], without duplicates, longest first (they narrow the most). */
    fun tokens(query: String): List<String> =
        fold(query).split(' ').filter { it.isNotEmpty() }.distinct().sortedByDescending { it.codePointCount(0, it.length) }

    /** True when [text] has an Arabic letter, so it reads right to left. */
    fun isArabic(text: String): Boolean = text.any { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' }
}
