/**
 * :app — BestNote's entry point: the Hilt application graph, the single activity and the
 * navigation graph that stitches the feature modules together. Holds no feature logic.
 */
package app.bestnote

import android.app.Application
import app.bestnote.core.designsystem.font.HandwritingTypefaces
import app.bestnote.engine.canvas.document.ElementPainter
import android.os.Build
import app.bestnote.ai.vision.AiExchange
import app.bestnote.ai.vision.VisionDiagnostics
import app.bestnote.ai.vision.providers.KeystoreAiSettings
import app.bestnote.core.common.CrashLog
import app.bestnote.core.security.LockManager
import app.bestnote.core.security.LockSession
import app.bestnote.core.storage.LockedScratch
import app.bestnote.core.storage.NotebookKeyring
import app.bestnote.engine.pdf.PdfNotebookFiles
import app.bestnote.core.updater.BuildInfo
import app.bestnote.core.updater.UpdateManager
import dagger.Provides
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import android.util.Log
import app.bestnote.core.common.DefaultDispatcherProvider
import app.bestnote.core.common.DispatcherProvider
import app.bestnote.core.database.NotebookRepository
import app.bestnote.engine.recognition.mlkit.IndexScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BestNoteApplication : Application() {
    @Inject lateinit var notebooks: NotebookRepository

    @Inject lateinit var searchIndexing: IndexScheduler

    @Inject lateinit var aiSettings: KeystoreAiSettings

    @Inject lateinit var updates: UpdateManager

    @Inject lateinit var locks: LockManager

    @Inject lateinit var keyring: NotebookKeyring

    @Inject lateinit var lockedScratch: LockedScratch

    override fun onCreate() {
        // First, so even a crash during start-up lands in Settings → Debug → Share debug log.
        CrashLog.install(CrashLog(File(filesDir, CRASH_DIR))) {
            "BestNote ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE} ${BuildConfig.GIT_COMMIT} · " +
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        }
        super.onCreate()
        setUpAiDiagnostics()
        // Smart Board writes with the bundled handwriting fonts; the page renderer resolves them by id.
        runCatching { HandwritingTypefaces.init(this) }.onFailure { Log.w("BestNote", "Handwriting fonts unavailable", it) }
        ElementPainter.Fonts.resolve = HandwritingTypefaces::of
        // Equations (MATH-01): parse the math font off the main thread before the first one is drawn.
        app.bestnote.engine.canvas.document.EquationRendering.warmUp()
        // Downloaded and own fonts (TPL-02): registered with the typefaces so pages and previews resolve them by id.
        runCatching { app.bestnote.core.fonts.FontRegistry.get(this).start() }.onFailure { Log.w("BestNote", "Font registry unavailable", it) }
        // The Room index is a cache of the notebook packages: refresh it in the background (ADR-0004).
        notebooks.backgroundScope.launch {
            runCatching { notebooks.reconcileIndex() }.onFailure { Log.w("BestNote", "Index reconcile failed", it) }
        }
        // Locked notebooks (LIB-11): which are sealed, and relock after the app has been in the background.
        LockSession.install(this, locks)
        notebooks.backgroundScope.launch {
            runCatching { locks.refresh() }.onFailure { Log.w("BestNote", "Lock state unavailable", it) }
            keepLockedFilesPrivate()
        }
        // Handwriting and text search (REC-02): index pages in the background, battery-aware.
        searchIndexing.start(notebooks.backgroundScope)
        // In-app updates (REL-01): at most one check per 24 h, if "Check automatically" is on.
        updates.checkOnStart()
    }

    /**
     * LIB-11b: no plaintext of a locked notebook stays on disk outside its package. At start every scratch file is an
     * orphan of a crash, and older builds' plain caches go; a dropped key deletes that notebook's scratch; a newly
     * locked notebook loses its plain PDF cache; raw AI answers are not kept while one is open.
     */
    private suspend fun keepLockedFilesPrivate() = coroutineScope {
        val pdfCache = File(cacheDir, "pdf")
        runCatching {
            val legacy = listOf("export-pdf-sources", "search-pdf", "exports/.sources", "exports/.annotations.pdf").map { File(cacheDir, it) } +
                locks.sealed.value.map { PdfNotebookFiles.cacheDirOf(it, pdfCache) }
            lockedScratch.sweepAtStart(locks.sealed.value, legacy)
        }.onFailure { Log.w("BestNote", "Locked files sweep failed", it) }
        launch { lockedScratch.follow(keyring) }
        launch { locks.sealed.collect { ids -> ids.forEach { runCatching { PdfNotebookFiles.forget(it, pdfCache) } } } }
        launch { keyring.unlocked.collect { VisionDiagnostics.privateSession = it.isNotEmpty() } }
    }

    companion object {
        const val CRASH_DIR = "crashes"
    }
}

/**
 * Raw AI answers are kept in debug builds, and in release only with "Verbose AI logging". The last
 * few calls are saved so a debug log shared after a restart still has them.
 */
private fun BestNoteApplication.setUpAiDiagnostics() {
    VisionDiagnostics.captureRaw = BuildConfig.DEBUG || aiSettings.verboseLogging
    val file = File(filesDir, "diagnostics/ai-exchanges.json")
    val json = Json { ignoreUnknownKeys = true }
    val serializer = ListSerializer(AiExchange.serializer())
    notebooks.backgroundScope.launch {
        runCatching { if (file.exists()) VisionDiagnostics.restore(json.decodeFromString(serializer, file.readText())) }
        VisionDiagnostics.listener = { entries ->
            notebooks.backgroundScope.launch {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(json.encodeToString(serializer, entries))
                }
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface AppModule {
    @Binds
    fun dispatchers(impl: DefaultDispatcherProvider): DispatcherProvider

    companion object {
        @Provides
        fun buildInfo(): BuildInfo = BuildInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), BuildConfig.GIT_COMMIT)
    }
}
