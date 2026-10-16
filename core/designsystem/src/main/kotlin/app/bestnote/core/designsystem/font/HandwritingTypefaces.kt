package app.bestnote.core.designsystem.font

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import app.bestnote.core.designsystem.R
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The handwriting fonts (SIL OFL): Caveat for Latin, Aref Ruqaa for Arabic (Ruqaa is the everyday
 * Arabic hand), Gveret Levin for Hebrew are bundled. More fonts are registered at run time by
 * `:core:fonts` (downloaded OFL fonts, the user's own files) with [register].
 *
 * Every typeface is a per-glyph fallback chain: the font itself, then the other bundled hands, then
 * the system font (maths symbols, anything left), so a mixed line renders in one pass and a glyph is
 * never drawn as "?".
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
    @Volatile private var bundled: List<FontFamily> = emptyList()
    private val extra = ConcurrentHashMap<String, Typeface>()

    /** Called whenever a font is added or removed, so painters can drop cached measurements. */
    @Volatile var onChanged: (() -> Unit)? = null

    fun init(context: Context) {
        if (latin != null) return
        val res = context.applicationContext.resources
        val caveat = FontFamily.Builder(Font.Builder(res, R.font.caveat).build()).build()
        val ruqaa = FontFamily.Builder(Font.Builder(res, R.font.aref_ruqaa).build()).build()
        // Gveret Levin (SIL OFL): Hebrew handwriting, so Hebrew in any run is written by hand too.
        val levin = FontFamily.Builder(Font.Builder(res, R.font.gveret_levin).build()).build()
        bundled = listOf(caveat, ruqaa, levin)
        latin = Typeface.CustomFallbackBuilder(caveat).addCustomFallback(ruqaa).addCustomFallback(levin).setSystemFallback("sans-serif").build()
        arabic = Typeface.CustomFallbackBuilder(ruqaa).addCustomFallback(caveat).addCustomFallback(levin).setSystemFallback("sans-serif").build()
        hebrew = Typeface.CustomFallbackBuilder(levin).addCustomFallback(caveat).addCustomFallback(ruqaa).setSystemFallback("sans-serif").build()
    }

    /**
     * Loads the font file [file] as a typeface with the fallback chain (it first, then the bundled hands,
     * then the system). Throws if the file is not a usable font. Does not register it.
     */
    fun load(file: File): Typeface {
        val family = FontFamily.Builder(Font.Builder(file).build()).build()
        return chain(family)
    }

    private fun chain(first: FontFamily): Typeface {
        val b = Typeface.CustomFallbackBuilder(first)
        bundled.forEach { b.addCustomFallback(it) }
        return b.setSystemFallback("sans-serif").build()
    }

    /** Makes [id] resolve to [typeface] (a downloaded or imported font). */
    fun register(id: String, typeface: Typeface) {
        extra[id] = typeface
        onChanged?.invoke()
    }

    fun unregister(id: String) {
        extra.remove(id)
        onChanged?.invoke()
    }

    fun isRegistered(id: String) = extra.containsKey(id)

    /** The typeface for a [app.bestnote.core.model.TextElement.font] or a registry font id, or null for unknown ids. */
    fun of(id: String?): Typeface? = when (id) {
        null -> null
        LATIN_FIRST, "caveat" -> latin
        ARABIC_FIRST, "aref-ruqaa" -> arabic
        HEBREW_FIRST, "gveret-levin" -> hebrew
        SYSTEM -> Typeface.SANS_SERIF
        else -> extra[id]
    }
}
