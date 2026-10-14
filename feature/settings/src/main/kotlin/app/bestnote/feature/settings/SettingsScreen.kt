/**
 * :feature:settings — user preferences UI, bound to :core:datastore's
 * [UserPreferencesRepository] (stylus-only drawing, theme, dynamic color).
 */
package app.bestnote.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.bestnote.core.datastore.ThemeMode
import app.bestnote.core.datastore.UserPreferences
import app.bestnote.core.datastore.UserPreferencesRepository
import app.bestnote.core.designsystem.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data object SettingsRoute

fun NavGraphBuilder.settingsScreen(onBack: () -> Unit) {
    composable<SettingsRoute> { SettingsScreen(onBack) }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repository: UserPreferencesRepository) : ViewModel() {
    val preferences: StateFlow<UserPreferences> =
        repository.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    fun setStylusOnly(enabled: Boolean) = viewModelScope.launch { repository.setStylusOnly(enabled) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { repository.setDynamicColor(enabled) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader("Stylus")
            ListItem(
                headlineContent = { Text("Stylus-only drawing") },
                supportingContent = { Text("Only the pen draws. Fingers and palms never leave marks.") },
                trailingContent = { Switch(checked = prefs.stylusOnly, onCheckedChange = { viewModel.setStylusOnly(it) }) },
            )
            SectionHeader("Appearance")
            ThemeMode.entries.forEach { mode ->
                ListItem(
                    headlineContent = { Text(mode.label) },
                    leadingContent = { RadioButton(selected = prefs.themeMode == mode, onClick = null) },
                    modifier = Modifier.selectable(selected = prefs.themeMode == mode, onClick = { viewModel.setThemeMode(mode) }),
                )
            }
            ListItem(
                headlineContent = { Text("Dynamic color") },
                supportingContent = { Text("Use colors from your wallpaper (Android 12+).") },
                trailingContent = { Switch(checked = prefs.dynamicColor, onCheckedChange = { viewModel.setDynamicColor(it) }) },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.L, top = Spacing.L, bottom = Spacing.XS),
    )
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.System -> "Follow system"
        ThemeMode.Light -> "Light"
        ThemeMode.Dark -> "Dark"
    }
