package app.bestnote.core.fonts

/**
 * TEXT-05: the font picker's lists (search, script filter, recents, favourites), and how a font id that is not on
 * this device falls back. Pure so it is tested without a device.
 */
object FontChoices {
    const val MAX_RECENTS = 6

    /** [recents] with [id] moved to the front, at most [max] long. */
    fun pushRecent(recents: List<String>, id: String, max: Int = MAX_RECENTS): List<String> =
        (listOf(id) + recents.filter { it != id }).take(max)

    fun toggle(favourites: Set<String>, id: String): Set<String> = if (id in favourites) favourites - id else favourites + id

    /** Does a font named [name] in [scripts] pass the search [query] and the [script] filter (null = every script)? */
    fun matches(name: String, scripts: Set<FontScript>, query: String, script: FontScript?): Boolean {
        if (script != null && script !in scripts) return false
        val q = query.trim()
        if (q.isEmpty()) return true
        val folded = name.lowercase()
        return q.lowercase().split(' ').filter { it.isNotEmpty() }.all { folded.contains(it) } ||
            // "arabic" / "عربي" find every Arabic font, and so on.
            scripts.any { s -> s.name.lowercase().startsWith(q.lowercase()) || q in s.localNames }
    }

    fun filter(fonts: List<AppFont>, query: String, script: FontScript?): List<AppFont> =
        fonts.filter { matches(it.name, it.scripts, query, script) }

    fun filterCatalogue(fonts: List<CatalogueFont>, query: String, script: FontScript?): List<CatalogueFont> =
        fonts.filter { matches(it.family, it.scripts, query, script) }

    /**
     * The generic family a missing font [id] falls back to (another device without that download, a deleted own
     * font): serif for serif fonts, monospace for mono, the bundled hand for handwriting, sans otherwise.
     */
    fun fallbackFor(id: String?): Fallback = when (FontCatalogue.categoryOf(id)) {
        FontCategory.Serif -> Fallback.Serif
        FontCategory.Mono -> Fallback.Mono
        FontCategory.Handwriting -> Fallback.Hand
        else -> Fallback.Sans
    }

    enum class Fallback { Sans, Serif, Mono, Hand }

    private val FontScript.localNames: List<String>
        get() = when (this) {
            FontScript.Latin -> listOf("لاتيني", "לטיני")
            FontScript.Arabic -> listOf("عربي", "عربية", "ערבית")
            FontScript.Hebrew -> listOf("عبري", "عبرية", "עברית")
        }
}
