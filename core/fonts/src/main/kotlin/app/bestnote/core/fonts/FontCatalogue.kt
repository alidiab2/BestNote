package app.bestnote.core.fonts

import java.net.URLEncoder

/**
 * A family of the curated "More fonts" list. Every entry was checked against its `METADATA.pb` in the
 * trusted `google/fonts` GitHub repository: `license: "OFL"`, a handwriting-like category
 * (HANDWRITING or DISPLAY with a hand-drawn design), and the needed subset (`arabic`, `hebrew`,
 * `latin`); the downloaded file's cmap is re-checked on the device ([FontValidator]) before it is kept.
 *
 * @property dir folder under `ofl/`; the font's registry id is "gf:$dir".
 * @property file the regular file in that folder.
 * @property bytes size of [file] (shown before downloading; the download is capped at a few times this).
 */
data class CatalogueFont(
    val dir: String,
    val family: String,
    val file: String,
    val scripts: Set<FontScript>,
    val bytes: Int,
) {
    val id: String get() = ID_PREFIX + dir
    val fontUrl: String get() = "$BASE/ofl/$dir/${URLEncoder.encode(file, "UTF-8").replace("+", "%20")}"
    val licenseUrl: String get() = "$BASE/ofl/$dir/OFL.txt"
    val pageUrl: String get() = "https://fonts.google.com/specimen/${family.replace(' ', '+')}"

    companion object {
        const val ID_PREFIX = "gf:"
        const val BASE = "https://raw.githubusercontent.com/google/fonts/main"
        const val HOST = "raw.githubusercontent.com"
    }
}

/** The curated, verified list (OFL, handwriting-like). To add a family: read its METADATA.pb, then add it here. */
object FontCatalogue {
    private val L = setOf(FontScript.Latin)
    private val A = setOf(FontScript.Arabic, FontScript.Latin)
    private val H = setOf(FontScript.Hebrew, FontScript.Latin)

    val all: List<CatalogueFont> = listOf(
        // Arabic (all also cover Latin)
        CatalogueFont("playpensansarabic", "Playpen Sans Arabic", "PlaypenSansArabic[wght].ttf", A, 758_132),
        CatalogueFont("katibeh", "Katibeh", "Katibeh-Regular.ttf", A, 189_124),
        CatalogueFont("lemonada", "Lemonada", "Lemonada[wght].ttf", A, 189_204),
        CatalogueFont("marhey", "Marhey", "Marhey[wght].ttf", A, 198_704),
        // Hebrew (also Latin)
        CatalogueFont("playpensanshebrew", "Playpen Sans Hebrew", "PlaypenSansHebrew[wght].ttf", H, 648_604),
        CatalogueFont("amaticsc", "Amatic SC", "AmaticSC-Regular.ttf", H, 151_624),
        CatalogueFont("solitreo", "Solitreo", "Solitreo-Regular.ttf", H, 96_968),
        // Latin
        CatalogueFont("patrickhand", "Patrick Hand", "PatrickHand-Regular.ttf", L, 214_772),
        CatalogueFont("kalam", "Kalam", "Kalam-Regular.ttf", L, 427_360),
        CatalogueFont("indieflower", "Indie Flower", "IndieFlower-Regular.ttf", L, 108_196),
        CatalogueFont("shadowsintolight", "Shadows Into Light", "ShadowsIntoLight.ttf", L, 54_304),
        CatalogueFont("gochihand", "Gochi Hand", "GochiHand-Regular.ttf", L, 37_352),
        CatalogueFont("architectsdaughter", "Architects Daughter", "ArchitectsDaughter-Regular.ttf", L, 43_352),
        CatalogueFont("handlee", "Handlee", "Handlee-Regular.ttf", L, 39_132),
        CatalogueFont("reeniebeanie", "Reenie Beanie", "ReenieBeanie.ttf", L, 147_036),
        CatalogueFont("caveatbrush", "Caveat Brush", "CaveatBrush-Regular.ttf", L, 295_568),
    )

    fun byId(id: String): CatalogueFont? = all.firstOrNull { it.id == id }

    fun forScript(script: FontScript): List<CatalogueFont> = all.filter { script in it.scripts }

    /** The download's size limit: a little more than the listed file, never more than [HARD_LIMIT]. */
    fun limitFor(font: CatalogueFont): Int = (font.bytes * 2).coerceAtMost(HARD_LIMIT)

    const val HARD_LIMIT = 4 * 1024 * 1024
}
