package app.bestnote.core.fonts

import java.io.File

/** The writing systems templates pick a font for. */
enum class FontScript(val sample: String, internal val probe: IntArray) {
    Latin("The quick brown fox 0123", intArrayOf('a'.code, 'e'.code, 'k'.code, 'A'.code, 'E'.code, 'Q'.code, '0'.code, '5'.code)),
    Arabic("أبجد هوز حطي كلمن", intArrayOf(0x627, 0x628, 0x62A, 0x62F, 0x631, 0x645, 0x646, 0x647, 0x64A)),
    Hebrew("אבגד הוז חטי כלמנ", intArrayOf(0x5D0, 0x5D1, 0x5D2, 0x5D3, 0x5D4, 0x5E9, 0x5EA)),
}

/** Where a font in the registry comes from. */
enum class FontOrigin { Bundled, Downloaded, Mine }

/**
 * One font a template or a text box can write with. [id] is what documents and templates store:
 * "caveat" / "aref-ruqaa" / "gveret-levin" (bundled), "gf:<family dir>" (downloaded), "my:<n>" (the user's own).
 * [file] is null for bundled fonts (they live in resources).
 */
data class AppFont(
    val id: String,
    val name: String,
    val origin: FontOrigin,
    val scripts: Set<FontScript>,
    val file: File? = null,
)

/** Fonts that ship inside the APK (SIL OFL). */
object BundledFonts {
    val all: List<AppFont> = listOf(
        AppFont("caveat", "Caveat", FontOrigin.Bundled, setOf(FontScript.Latin)),
        AppFont("aref-ruqaa", "Aref Ruqaa", FontOrigin.Bundled, setOf(FontScript.Arabic, FontScript.Latin)),
        AppFont("gveret-levin", "Gveret Levin", FontOrigin.Bundled, setOf(FontScript.Hebrew)),
    )

    /** What a template uses when it names no font for a script. */
    fun defaultFor(script: FontScript): String = when (script) {
        FontScript.Latin -> "caveat"
        FontScript.Arabic -> "aref-ruqaa"
        FontScript.Hebrew -> "gveret-levin"
    }
}
