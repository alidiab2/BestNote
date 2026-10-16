package app.bestnote.engine.layout

/** The writing systems Smart Board tells apart: for the font, and for which way a line runs. */
enum class WritingScript(val rtl: Boolean) {
    Latin(false), Cyrillic(false), Greek(false), Hebrew(true), Arabic(true), Other(false);

    companion object {
        fun of(c: Char): WritingScript? = when {
            c in '֐'..'׿' || c in 'יִ'..'ﭏ' -> Hebrew
            c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ࢠ'..'ࣿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿' -> Arabic
            c in 'Ѐ'..'ӿ' -> Cyrillic
            c in 'Ͱ'..'Ͽ' -> Greek
            c in 'a'..'z' || c in 'A'..'Z' || c in 'À'..'ɏ' -> Latin
            c.isLetter() -> Other
            else -> null
        }

        /** The script most letters of [text] are in (Latin when there are none). */
        fun dominant(text: CharSequence): WritingScript {
            val counts = IntArray(entries.size)
            for (c in text) of(c)?.let { counts[it.ordinal]++ }
            val best = counts.indices.maxBy { counts[it] }
            return if (counts[best] == 0) Latin else entries[best]
        }

        /** True when [text] has any right-to-left letters (Hebrew, Arabic). */
        fun hasRtl(text: CharSequence): Boolean = text.any { of(it)?.rtl == true }

        /** Direction of the first strong letter (Unicode's paragraph rule), or null without letters. */
        fun firstStrongRtl(text: CharSequence): Boolean? {
            for (c in text) of(c)?.let { return it.rtl }
            return null
        }

        /** More right-to-left letters than left-to-right ones. */
        fun mostlyRtl(text: CharSequence): Boolean {
            var r = 0
            var l = 0
            for (c in text) when (of(c)?.rtl) {
                true -> r++
                false -> l++
                null -> Unit
            }
            return r > l
        }
    }
}
