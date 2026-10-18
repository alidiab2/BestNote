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
    val category: FontCategory = FontCategory.Handwriting,
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

/**
 * The curated, verified list (OFL only). To add a family: read its METADATA.pb (license, category, subsets), then add it
 * here. TEXT-05 added text families (sans, serif, mono, display) for text boxes next to the hands.
 */
object FontCatalogue {
    private val L = setOf(FontScript.Latin)
    private val A = setOf(FontScript.Arabic, FontScript.Latin)
    private val H = setOf(FontScript.Hebrew, FontScript.Latin)
    private val AH = setOf(FontScript.Arabic, FontScript.Hebrew, FontScript.Latin)

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
        CatalogueFont("comicneue", "Comic Neue", "ComicNeue-Regular.ttf", L, 57_248),
        // TEXT-05 text families. Arabic (all also cover Latin)
        CatalogueFont("amiri", "Amiri", "Amiri-Regular.ttf", A, 431_116, FontCategory.Serif),
        CatalogueFont("cairo", "Cairo", "Cairo[slnt,wght].ttf", A, 599_548, FontCategory.Sans),
        CatalogueFont("tajawal", "Tajawal", "Tajawal-Regular.ttf", A, 60_364, FontCategory.Sans),
        CatalogueFont("almarai", "Almarai", "Almarai-Regular.ttf", A, 152_824, FontCategory.Sans),
        CatalogueFont("notosansarabic", "Noto Sans Arabic", "NotoSansArabic[wdth,wght].ttf", A, 844_676, FontCategory.Sans),
        CatalogueFont("notokufiarabic", "Noto Kufi Arabic", "NotoKufiArabic[wght].ttf", A, 434_204, FontCategory.Sans),
        CatalogueFont("changa", "Changa", "Changa[wght].ttf", A, 132_140, FontCategory.Sans),
        CatalogueFont("elmessiri", "El Messiri", "ElMessiri[wght].ttf", A, 138_664, FontCategory.Sans),
        CatalogueFont("readexpro", "Readex Pro", "ReadexPro[HEXP,wght].ttf", A, 278_544, FontCategory.Sans),
        CatalogueFont("reemkufi", "Reem Kufi", "ReemKufi[wght].ttf", A, 127_152, FontCategory.Display),
        CatalogueFont("vazirmatn", "Vazirmatn", "Vazirmatn[wght].ttf", A, 241_328, FontCategory.Sans),
        CatalogueFont("lateef", "Lateef", "Lateef-Regular.ttf", A, 241_092, FontCategory.Serif),
        CatalogueFont("scheherazadenew", "Scheherazade New", "ScheherazadeNew-Regular.ttf", A, 331_504, FontCategory.Serif),
        // Arabic + Hebrew + Latin
        CatalogueFont("rubik", "Rubik", "Rubik[wght].ttf", AH, 359_804, FontCategory.Sans),
        // Hebrew (also Latin)
        CatalogueFont("assistant", "Assistant", "Assistant[wght].ttf", H, 99_496, FontCategory.Sans),
        CatalogueFont("opensans", "Open Sans", "OpenSans[wdth,wght].ttf", H, 532_636, FontCategory.Sans),
        CatalogueFont("varelaround", "Varela Round", "VarelaRound-Regular.ttf", H, 132_748, FontCategory.Sans),
        CatalogueFont("alef", "Alef", "Alef-Regular.ttf", H, 90_996, FontCategory.Sans),
        CatalogueFont("notosanshebrew", "Noto Sans Hebrew", "NotoSansHebrew[wdth,wght].ttf", H, 112_640, FontCategory.Sans),
        CatalogueFont("secularone", "Secular One", "SecularOne-Regular.ttf", H, 83_916, FontCategory.Display),
        CatalogueFont("notoserifhebrew", "Noto Serif Hebrew", "NotoSerifHebrew[wdth,wght].ttf", H, 183_600, FontCategory.Serif),
        CatalogueFont("davidlibre", "David Libre", "DavidLibre-Regular.ttf", H, 127_740, FontCategory.Serif),
        CatalogueFont("suezone", "Suez One", "SuezOne-Regular.ttf", H, 70_856, FontCategory.Serif),
        CatalogueFont("bellefair", "Bellefair", "Bellefair-Regular.ttf", H, 65_432, FontCategory.Serif),
        CatalogueFont("karantina", "Karantina", "Karantina-Regular.ttf", H, 51_480, FontCategory.Display),
        // Latin
        CatalogueFont("inter", "Inter", "Inter[opsz,wght].ttf", L, 876_576, FontCategory.Sans),
        CatalogueFont("dmsans", "DM Sans", "DMSans[opsz,wght].ttf", L, 240_164, FontCategory.Sans),
        CatalogueFont("nunito", "Nunito", "Nunito[wght].ttf", L, 276_932, FontCategory.Sans),
        CatalogueFont("worksans", "Work Sans", "WorkSans[wght].ttf", L, 361_072, FontCategory.Sans),
        CatalogueFont("montserrat", "Montserrat", "Montserrat[wght].ttf", L, 744_936, FontCategory.Sans),
        CatalogueFont("poppins", "Poppins", "Poppins-Regular.ttf", L, 160_316, FontCategory.Sans),
        CatalogueFont("lato", "Lato", "Lato-Regular.ttf", L, 656_568, FontCategory.Sans),
        CatalogueFont("librebaskerville", "Libre Baskerville", "LibreBaskerville[wght].ttf", L, 171_900, FontCategory.Serif),
        CatalogueFont("playfairdisplay", "Playfair Display", "PlayfairDisplay[wght].ttf", L, 300_724, FontCategory.Serif),
        CatalogueFont("ebgaramond", "EB Garamond", "EBGaramond[wght].ttf", L, 851_176, FontCategory.Serif),
        CatalogueFont("sourceserif4", "Source Serif 4", "SourceSerif4[opsz,wght].ttf", L, 1_209_508, FontCategory.Serif),
        CatalogueFont("firacode", "Fira Code", "FiraCode[wght].ttf", L, 260_364, FontCategory.Mono),
        CatalogueFont("ibmplexmono", "IBM Plex Mono", "IBMPlexMono-Regular.ttf", L, 135_580, FontCategory.Mono),
        CatalogueFont("spacemono", "Space Mono", "SpaceMono-Regular.ttf", L, 99_356, FontCategory.Mono),
    )

    /** What kind of font [id] is (bundled or catalogue), or null for an unknown id (the user's own fonts). */
    fun categoryOf(id: String?): FontCategory? = when (id) {
        null -> null
        "hand", "hand-ar", "hand-he" -> FontCategory.Handwriting
        "mono" -> FontCategory.Mono
        else -> BundledFonts.all.firstOrNull { it.id == id }?.category ?: byId(id)?.category
    }

    fun byId(id: String): CatalogueFont? = all.firstOrNull { it.id == id }

    fun forScript(script: FontScript): List<CatalogueFont> = all.filter { script in it.scripts }

    /** The download's size limit: a little more than the listed file, never more than [HARD_LIMIT]. */
    fun limitFor(font: CatalogueFont): Int = (font.bytes * 2).coerceAtMost(HARD_LIMIT)

    const val HARD_LIMIT = 4 * 1024 * 1024
}
