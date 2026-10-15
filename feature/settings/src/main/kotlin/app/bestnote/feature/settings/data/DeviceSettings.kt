package app.bestnote.feature.settings.data

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.ChecksSdkIntAtLeast
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** App languages offered in Settings. [tag] null = follow the system. */
enum class AppLanguage(val tag: String?) { System(null), English("en"), Arabic("ar") }

/** Per-app language (Android 13+ `LocaleManager`; the system then also lists BestNote under App languages). */
interface AppLocaleController {
    /** False below Android 13, where per-app languages need the system language instead. */
    val supported: Boolean
    fun current(): AppLanguage
    fun set(language: AppLanguage)
}

class SystemAppLocaleController @Inject constructor(@ApplicationContext private val context: Context) : AppLocaleController {
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    override val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    override fun current(): AppLanguage {
        if (!supported) return AppLanguage.System
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        if (locales.isEmpty) return AppLanguage.System
        return AppLanguage.entries.firstOrNull { it.tag == locales[0].language } ?: AppLanguage.System
    }

    override fun set(language: AppLanguage) {
        if (!supported) return
        val list = language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        context.getSystemService(LocaleManager::class.java).applicationLocales = list
    }
}

data class StorageUsage(
    val notebooksBytes: Long = 0,
    val thumbnailsBytes: Long = 0,
    val cacheBytes: Long = 0,
    /** Where notebooks live (app-private storage). */
    val notebooksPath: String = "",
) {
    val totalBytes: Long get() = notebooksBytes + thumbnailsBytes + cacheBytes
    val clearableBytes: Long get() = thumbnailsBytes + cacheBytes
}

interface StorageInspector {
    suspend fun usage(): StorageUsage

    /** Deletes cached thumbnails and the app cache; they are rebuilt when needed. */
    suspend fun clearThumbnails()
}

/**
 * Sizes of BestNote's private folders. Paths match the stores: notebooks in `files/notebooks`
 * (`FileNotebookStore`), cover thumbnails in `files/thumbnails` (`ThumbnailCache`, DOC-10).
 */
class FileStorageInspector @Inject constructor(@ApplicationContext private val context: Context) : StorageInspector {
    private val notebooks get() = File(context.filesDir, NOTEBOOKS_DIR)
    private val thumbnails get() = File(context.filesDir, THUMBNAILS_DIR)

    override suspend fun usage() = withContext(Dispatchers.IO) {
        StorageUsage(sizeOf(notebooks), sizeOf(thumbnails), sizeOf(context.cacheDir), notebooks.absolutePath)
    }

    override suspend fun clearThumbnails() = withContext(Dispatchers.IO) {
        thumbnails.listFiles()?.forEach { it.deleteRecursively() }
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        Unit
    }

    private fun sizeOf(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        const val NOTEBOOKS_DIR = "notebooks"
        const val THUMBNAILS_DIR = "thumbnails"
    }
}
