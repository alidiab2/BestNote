package app.bestnote.core.designsystem.font

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import app.bestnote.core.designsystem.R

/**
 * The bundled handwriting fonts (SIL OFL): Caveat for Latin, Aref Ruqaa for Arabic (Ruqaa is the
 * everyday Arabic hand). Each typeface falls back to the other for letters it lacks, then to the
 * system font for maths symbols, so a mixed line renders in one pass.
 */
object HandwritingTypefaces {
    const val LATIN_FIRST = "hand"
    const val ARABIC_FIRST = "hand-ar"

    const val HEBREW_FIRST = "hand-he"

    /** The device font, for scripts without a bundled hand. */
    const val SYSTEM = "system"

    @Volatile private var latin: Typeface? = null
    @Volatile private var arabic: Typeface? = null
    @Volatile private var hebrew: Typeface? = null

    fun init(context: Context) {
        if (latin != null) return
        val res = context.applicationContext.resources
        val caveat = FontFamily.Builder(Font.Builder(res, R.font.caveat).build()).build()
        val ruqaa = FontFamily.Builder(Font.Builder(res, R.font.aref_ruqaa).build()).build()
        // Gveret Levin (SIL OFL): Hebrew handwriting, so Hebrew in any run is written by hand too.
        val levin = FontFamily.Builder(Font.Builder(res, R.font.gveret_levin).build()).build()
        latin = Typeface.CustomFallbackBuilder(caveat).addCustomFallback(ruqaa).addCustomFallback(levin).setSystemFallback("sans-serif").build()
        arabic = Typeface.CustomFallbackBuilder(ruqaa).addCustomFallback(caveat).addCustomFallback(levin).setSystemFallback("sans-serif").build()
        hebrew = Typeface.CustomFallbackBuilder(levin).addCustomFallback(caveat).addCustomFallback(ruqaa).setSystemFallback("sans-serif").build()
    }

    /** The typeface for a [app.bestnote.core.model.TextElement.font] id, or null for unknown ids. */
    fun of(id: String?): Typeface? = when (id) {
        LATIN_FIRST -> latin
        ARABIC_FIRST -> arabic
        HEBREW_FIRST -> hebrew
        SYSTEM -> Typeface.SANS_SERIF
        else -> null
    }
}
