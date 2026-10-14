package app.bestnote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import app.bestnote.core.datastore.ThemeMode
import app.bestnote.core.datastore.UserPreferences
import app.bestnote.core.datastore.UserPreferencesRepository
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.feature.editor.editorScreen
import app.bestnote.feature.editor.navigateToEditor
import app.bestnote.feature.library.LibraryRoute
import app.bestnote.feature.library.libraryScreen
import app.bestnote.feature.settings.SettingsRoute
import app.bestnote.feature.settings.settingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var preferences: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs by preferences.preferences.collectAsStateWithLifecycle(UserPreferences())
            val dark = when (prefs.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            BestNoteTheme(darkTheme = dark, dynamicColor = prefs.dynamicColor) { BestNoteNavHost() }
        }
    }
}

@Composable
private fun BestNoteNavHost() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = LibraryRoute) {
        libraryScreen(
            onOpenNotebook = { nav.navigateToEditor(it) },
            onOpenSettings = { nav.navigate(SettingsRoute) },
        )
        editorScreen(onBack = nav::popBackStack)
        settingsScreen(onBack = nav::popBackStack)
    }
}
