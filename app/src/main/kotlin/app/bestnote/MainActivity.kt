package app.bestnote

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.bestnote.feature.library.LibraryRepository
import app.bestnote.feature.library.PaperFormat
import app.bestnote.feature.library.PaperSize
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import app.bestnote.core.datastore.ThemeMode
import app.bestnote.core.datastore.UserPreferences
import app.bestnote.core.datastore.UserPreferencesRepository
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.systemReducedMotion
import app.bestnote.core.ui.keys.LocalShortcutHost
import app.bestnote.core.ui.keys.ShortcutHost
import app.bestnote.core.ui.keys.ShortcutOverlay
import app.bestnote.core.ui.keys.keyboardShortcutGroups
import androidx.compose.runtime.CompositionLocalProvider
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import app.bestnote.core.updater.UpdateHost
import app.bestnote.core.updater.UpdateManager
import app.bestnote.feature.editor.InkInsert
import app.bestnote.feature.editor.editorScreen
import app.bestnote.feature.editor.navigateToEditor
import app.bestnote.feature.library.LibraryRoute
import app.bestnote.feature.library.PdfOpenRequests
import app.bestnote.feature.library.QuicknoteRequests
import app.bestnote.feature.library.libraryScreen
import app.bestnote.feature.onboarding.OnboardingFlow
import app.bestnote.feature.settings.SettingsRoute
import app.bestnote.feature.settings.settingsScreen
import app.bestnote.feature.smartboard.navigateToSmartBoard
import app.bestnote.feature.smartboard.smartBoardScreen
import app.bestnote.feature.templates.navigateToTemplates
import app.bestnote.feature.templates.templatesScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
// FragmentActivity (not ComponentActivity): the biometric / screen-lock prompt of locked notebooks (LIB-11) needs it.
class MainActivity : FragmentActivity() {
    @Inject lateinit var preferences: UserPreferencesRepository

    @Inject lateinit var updates: UpdateManager

    @Inject lateinit var quicknotes: QuicknoteRequests

    @Inject lateinit var pdfRequests: PdfOpenRequests

    @Inject lateinit var library: LibraryRepository

    /** SPEN-10: hardware-keyboard shortcuts and their help overlay. */
    private val shortcuts = ShortcutHost()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch, so a rotation does not create another Quicknote.
        if (savedInstanceState == null) handleIntent(intent)
        publishShortcuts(this)
        setContent {
            val loaded by preferences.preferences.collectAsStateWithLifecycle(null)
            val prefs = loaded ?: UserPreferences()
            val dark = when (prefs.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            BestNoteTheme(
                darkTheme = dark,
                dynamicColor = prefs.dynamicColor,
                reducedMotion = prefs.appearance.reduceMotion || systemReducedMotion(),
                chromeWarmth = prefs.appearance.chromeWarmth.amount,
            ) {
                CompositionLocalProvider(LocalShortcutHost provides shortcuts) {
                    // ONB-01: first run (and Settings → About → Show welcome again). Not before the settings have loaded.
                    BestNoteNavHost(quicknotes, pdfRequests, library, showOnboarding = loaded?.onboardingDone == false)
                    UpdateHost(updates)
                    ShortcutOverlay(shortcuts)
                }
            }
        }
    }

    // ComponentActivity (our base) marks dispatchKeyEvent restricted; overriding it is the documented way to see every key first.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        shortcuts.onKeyEvent(event, focusIsTextEditor = currentFocus?.onCheckIsTextEditor() == true) || super.dispatchKeyEvent(event)

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        data.addAll(keyboardShortcutGroups(this))
    }

    override fun onPause() {
        super.onPause()
        shortcuts.reset()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == QuicknoteRequests.ACTION) quicknotes.request()
        pdfUri(intent)?.let { pdfRequests.request(it.toString()) }
    }
}

/** The PDF an "Open with" (VIEW) or share (SEND) intent carries, if any. */
@Suppress("DEPRECATION")
private fun pdfUri(intent: Intent?): Uri? = when (intent?.action) {
    Intent.ACTION_VIEW -> intent.data
    Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }
    else -> null
}

/** Launcher long-press shortcut "Quicknote" (LIB-05). Re-published on launch so its label follows the locale. */
private fun publishShortcuts(context: Context) {
    val quicknote = ShortcutInfoCompat.Builder(context, "quicknote")
        .setShortLabel(context.getString(R.string.shortcut_quicknote_short))
        .setLongLabel(context.getString(R.string.shortcut_quicknote_long))
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_quicknote))
        .setIntent(
            Intent(QuicknoteRequests.ACTION)
                .setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        .build()
    ShortcutManagerCompat.pushDynamicShortcut(context, quicknote)
}

@Composable
private fun BestNoteNavHost(
    quicknotes: QuicknoteRequests,
    pdfRequests: PdfOpenRequests,
    library: LibraryRepository,
    showOnboarding: Boolean,
) {
    val nav = rememberNavController()
    // A double tap on an in-app back arrow must not pop the library too (that leaves an empty, blank screen).
    val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
    val quicknoteRequested by quicknotes.requested.collectAsStateWithLifecycle()
    // A Quicknote request while the editor is open: go back to the library, which opens the note.
    LaunchedEffect(quicknoteRequested) {
        if (quicknoteRequested) nav.popBackStack(LibraryRoute, inclusive = false)
    }
    // A PDF opened with BestNote while the editor is up: back to the library, which imports it.
    val pdfRequested by pdfRequests.requested.collectAsStateWithLifecycle()
    LaunchedEffect(pdfRequested) {
        if (pdfRequested != null) nav.popBackStack(LibraryRoute, inclusive = false)
    }
    // Smart Board → editor hand-off: the handwritten pages wait here until the editor takes them.
    val inbox = remember { InkInbox() }
    val scope = rememberCoroutineScope()
    NavHost(nav, startDestination = LibraryRoute) {
        libraryScreen(
            onOpenNotebook = { nav.navigateToEditor(it) },
            onOpenSettings = { nav.navigate(SettingsRoute) },
            onOpenPage = { id, pageId, query -> nav.navigateToEditor(id, pageId, query) },
        )
        editorScreen(
            onBack = back,
            onOpenSettings = { nav.navigate(SettingsRoute) },
            onSmartBoard = { notebook, page, continuePageId, reserved -> nav.navigateToSmartBoard(notebook, page, continuePageId, reserved) },
            takeInsert = inbox::take,
        )
        smartBoardScreen(
            onClose = back,
            onManageTemplates = { nav.navigateToTemplates() },
            onInsert = { result ->
                if (!result.newNotebook) {
                    inbox.pending = InkInsert(
                        result.template, result.pages, afterIndex = result.afterIndex, elements = result.elements,
                        notes = result.notes, templateId = result.templateId, followUpPageIds = result.followUpPageIds,
                        photos = result.photos, assets = result.assets,
                    )
                    nav.popBackStack()
                } else {
                    // "New notebook": created on the Smart Board paper, then opened with the pages in it.
                    scope.launch {
                        val format = PaperSize.entries.firstNotNullOfOrNull { size ->
                            listOf(false, true).map { PaperFormat(size, it) }.firstOrNull { f ->
                                kotlin.math.abs(f.widthPt - result.widthPt) < 1f && kotlin.math.abs(f.heightPt - result.heightPt) < 1f
                            }
                        } ?: PaperFormat()
                        val notebook = library.create(result.title ?: "Smart Board", result.template, format)
                        inbox.pending = InkInsert(result.template, result.pages, freshNotebook = true, elements = result.elements, notes = result.notes, templateId = result.templateId, photos = result.photos, assets = result.assets)
                        nav.popBackStack()
                        nav.navigateToEditor(notebook.id)
                    }
                }
            },
        )
        settingsScreen(onBack = back, onOpenTemplates = { nav.navigateToTemplates() })
        templatesScreen(onBack = back)
    }
    if (showOnboarding) {
        val firstTitle = stringResource(R.string.onboarding_first_notebook)
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
            OnboardingFlow(
                onFinished = { createNotebook ->
                    if (createNotebook) {
                        scope.launch { nav.navigateToEditor(library.create(firstTitle).id) }
                    }
                },
            )
        }
    }
}

private class InkInbox {
    var pending: InkInsert? = null

    fun take(): InkInsert? = pending.also { pending = null }
}
