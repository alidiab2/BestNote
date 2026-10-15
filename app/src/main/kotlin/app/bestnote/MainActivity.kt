package app.bestnote

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import app.bestnote.core.datastore.ThemeMode
import app.bestnote.core.datastore.UserPreferences
import app.bestnote.core.datastore.UserPreferencesRepository
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.systemReducedMotion
import app.bestnote.feature.editor.InkInsert
import app.bestnote.feature.editor.editorScreen
import app.bestnote.feature.editor.navigateToEditor
import app.bestnote.feature.library.LibraryRoute
import app.bestnote.feature.library.QuicknoteRequests
import app.bestnote.feature.library.libraryScreen
import app.bestnote.feature.settings.SettingsRoute
import app.bestnote.feature.settings.settingsScreen
import app.bestnote.feature.smartboard.navigateToSmartBoard
import app.bestnote.feature.smartboard.smartBoardScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var preferences: UserPreferencesRepository

    @Inject lateinit var quicknotes: QuicknoteRequests

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch, so a rotation does not create another Quicknote.
        if (savedInstanceState == null) handleIntent(intent)
        publishShortcuts(this)
        setContent {
            val prefs by preferences.preferences.collectAsStateWithLifecycle(UserPreferences())
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
            ) { BestNoteNavHost(quicknotes) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == QuicknoteRequests.ACTION) quicknotes.request()
    }
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
private fun BestNoteNavHost(quicknotes: QuicknoteRequests) {
    val nav = rememberNavController()
    val quicknoteRequested by quicknotes.requested.collectAsStateWithLifecycle()
    // A Quicknote request while the editor is open: go back to the library, which opens the note.
    LaunchedEffect(quicknoteRequested) {
        if (quicknoteRequested) nav.popBackStack(LibraryRoute, inclusive = false)
    }
    // Smart Board → editor hand-off: the handwritten pages wait here until the editor takes them.
    val inbox = remember { InkInbox() }
    NavHost(nav, startDestination = LibraryRoute) {
        libraryScreen(
            onOpenNotebook = { nav.navigateToEditor(it) },
            onOpenSettings = { nav.navigate(SettingsRoute) },
        )
        editorScreen(
            onBack = nav::popBackStack,
            onOpenSettings = { nav.navigate(SettingsRoute) },
            onSmartBoard = { notebook, page -> nav.navigateToSmartBoard(notebook, page) },
            takeInsert = inbox::take,
        )
        smartBoardScreen(
            onClose = nav::popBackStack,
            onInsert = { result ->
                inbox.pending = InkInsert(result.template, result.pages)
                nav.popBackStack()
            },
        )
        settingsScreen(onBack = nav::popBackStack)
    }
}

private class InkInbox {
    var pending: InkInsert? = null

    fun take(): InkInsert? = pending.also { pending = null }
}
