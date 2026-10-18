package app.bestnote.core.fonts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.bestnote.core.designsystem.font.HandwritingTypefaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Every font a template or a text box can use: the bundled hands, families downloaded on demand from the
 * trusted Google Fonts repository (OFL), and the user's own .ttf / .otf files. Registers each one's
 * typeface (with the per-glyph fallback chain) with [HandwritingTypefaces] so the page renderer, the
 * layout measure and the previews resolve it by id.
 */
class FontRegistry private constructor(context: Context) {
    private val app = context.applicationContext
    private val library = FontLibrary(File(app.filesDir, "fonts"))
    private val installer = FontInstaller(library)

    private val state = MutableStateFlow(BundledFonts.all)

    /** Bundled, then downloaded, then the user's own. */
    val fonts: StateFlow<List<AppFont>> = state.asStateFlow()

    private val working = MutableStateFlow<Set<String>>(emptySet())

    /** Catalogue ids being downloaded right now. */
    val downloading: StateFlow<Set<String>> = working.asStateFlow()

    @Volatile private var started = false

    /** Loads the stored fonts (cheap: files are opened lazily by the typeface) and registers them. Idempotent. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        runCatching { HandwritingTypefaces.init(app) }
        BundledTextFonts.register(app.resources)
        refresh()
    }

    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val recentState by lazy { MutableStateFlow(prefs.getString(KEY_RECENTS, null).orEmpty().split('\n').filter { it.isNotEmpty() }) }
    private val favouriteState by lazy { MutableStateFlow(prefs.getStringSet(KEY_FAVOURITES, emptySet()).orEmpty().toSet()) }

    /** TEXT-05: fonts picked lately, newest first. */
    val recents: StateFlow<List<String>> get() = recentState.asStateFlow()

    /** TEXT-05: fonts the user starred in the picker. */
    val favourites: StateFlow<Set<String>> get() = favouriteState.asStateFlow()

    /** Remembers that [id] was just picked (the picker's Recent row). */
    fun noteUsed(id: String?) {
        if (id.isNullOrEmpty()) return
        val next = FontChoices.pushRecent(recentState.value, id)
        recentState.value = next
        prefs.edit().putString(KEY_RECENTS, next.joinToString("\n")).apply()
    }

    fun toggleFavourite(id: String) {
        val next = FontChoices.toggle(favouriteState.value, id)
        favouriteState.value = next
        prefs.edit().putStringSet(KEY_FAVOURITES, next).apply()
    }

    private fun refresh() {
        val stored = library.list().filter { f ->
            val file = f.file ?: return@filter false
            runCatching { HandwritingTypefaces.register(f.id, HandwritingTypefaces.load(file)) }.isSuccess ||
                // A file the device cannot load is unusable: drop it so it cannot break a page.
                run { library.delete(f.id); false }
        }
        state.value = BundledFonts.all + stored
    }

    fun byId(id: String?): AppFont? = id?.let { i -> state.value.firstOrNull { it.id == i } }

    fun nameOf(id: String?): String = byId(id)?.name ?: when (id) {
        null, "", "system" -> "System"
        "mono" -> "Monospace"
        "hand", "hand-ar", "hand-he" -> "Handwriting"
        else -> FontCatalogue.byId(id)?.family ?: id
    }

    fun forScript(script: FontScript): List<AppFont> = state.value.filter { script in it.scripts }

    fun isInstalled(catalogueId: String) = state.value.any { it.id == catalogueId }

    /** Downloads [font] (OFL file and licence), validates and registers it. */
    suspend fun download(font: CatalogueFont): Result<AppFont> = withContext(Dispatchers.IO) {
        working.update { it + font.id }
        try {
            runCatching {
                val installed = installer.install(font)
                val tf = try {
                    HandwritingTypefaces.load(installed.file!!)
                } catch (e: Exception) {
                    library.delete(installed.id)
                    throw FontRejected(FontRejected.Kind.Damaged, "the device cannot load it")
                }
                HandwritingTypefaces.register(installed.id, tf)
                refresh()
                installed
            }
        } finally {
            working.update { it - font.id }
        }
    }

    /** Adds the user's own font from a picked file; refuses anything that is not a font with letters. */
    suspend fun import(uri: Uri): Result<AppFont> = withContext(Dispatchers.IO) {
        runCatching {
            val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "font.ttf"
            val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > FontValidator.MAX_BYTES) throw FontRejected(FontRejected.Kind.TooLarge, "over ${FontValidator.MAX_BYTES}")
                }
                out.toByteArray()
            } ?: throw IOException("cannot open the file")
            val added = library.importMine(name, bytes)
            val tf = try {
                HandwritingTypefaces.load(added.file!!)
            } catch (e: Exception) {
                library.delete(added.id)
                throw FontRejected(FontRejected.Kind.Damaged, "the device cannot load it")
            }
            HandwritingTypefaces.register(added.id, tf)
            refresh()
            added
        }
    }

    /** Removes a downloaded or own font. Pages already written keep working (they fall back to the bundled hands). */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        if (library.delete(id)) {
            HandwritingTypefaces.unregister(id)
            refresh()
        }
    }

    fun licenseText(id: String): String? = library.licenseText(id)

    companion object {
        private const val PREFS = "fonts"
        private const val KEY_RECENTS = "recents"
        private const val KEY_FAVOURITES = "favourites"

        @Volatile private var instance: FontRegistry? = null

        /**
         * The typeface for a stored font id: the font itself when it is on this device, else a fallback of the same
         * kind ([FontChoices.fallbackFor]), so a page from another device (or after a font was deleted) still reads well
         * on screen and in the PDF export. Null only for the default font (null id).
         */
        fun typefaceOrFallback(id: String?): android.graphics.Typeface? {
            if (id == null) return null
            HandwritingTypefaces.of(id)?.let { return it }
            return when (FontChoices.fallbackFor(id)) {
                FontChoices.Fallback.Serif -> android.graphics.Typeface.SERIF
                FontChoices.Fallback.Mono -> android.graphics.Typeface.MONOSPACE
                FontChoices.Fallback.Hand -> HandwritingTypefaces.of(HandwritingTypefaces.LATIN_FIRST)
                FontChoices.Fallback.Sans -> android.graphics.Typeface.SANS_SERIF
            }
        }

        fun get(context: Context): FontRegistry = instance ?: synchronized(this) {
            instance ?: FontRegistry(context).also { instance = it }
        }
    }
}
