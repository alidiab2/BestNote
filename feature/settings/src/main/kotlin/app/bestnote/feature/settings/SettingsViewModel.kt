package app.bestnote.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bestnote.core.datastore.AppearancePreferences
import app.bestnote.core.datastore.GesturePreferences
import app.bestnote.core.datastore.StoragePreferences
import app.bestnote.core.datastore.StylusPreferences
import app.bestnote.core.datastore.UserPreferences
import app.bestnote.core.datastore.UserPreferencesRepository
import app.bestnote.core.datastore.WritingPreferences
import app.bestnote.feature.settings.data.AppLanguage
import app.bestnote.feature.settings.data.AppLocaleController
import app.bestnote.feature.settings.data.StorageInspector
import app.bestnote.feature.settings.data.StorageUsage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The categories of the Settings screen, in display order. */
enum class SettingsCategory { Stylus, Gestures, Writing, SmartBoard, Appearance, Storage, Requests, Debug, About }

/** Storage numbers; null while measuring. */
data class StorageUiState(val usage: StorageUsage? = null, val clearing: Boolean = false)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: UserPreferencesRepository,
    private val locale: AppLocaleController,
    private val storageInspector: StorageInspector,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> =
        repository.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UserPreferences())

    private val _language = MutableStateFlow(locale.current())
    val language: StateFlow<AppLanguage> = _language.asStateFlow()
    val languageSupported: Boolean get() = locale.supported

    private val _storage = MutableStateFlow(StorageUiState())
    val storage: StateFlow<StorageUiState> = _storage.asStateFlow()

    /** Selected category in the two-pane layout; null on a phone shows the list. */
    private val _selected = MutableStateFlow<SettingsCategory?>(null)
    val selected: StateFlow<SettingsCategory?> = _selected.asStateFlow()

    init {
        refreshStorage()
    }

    fun select(category: SettingsCategory?) = _selected.update { category }

    /** Top-level settings shared with the editor and theme (dock, left-handed, hover button, theme). */
    fun update(transform: (UserPreferences) -> UserPreferences) = launch { repository.update(transform) }
    fun updateStylus(transform: (StylusPreferences) -> StylusPreferences) = launch { repository.updateStylus(transform) }
    fun updateGestures(transform: (GesturePreferences) -> GesturePreferences) = launch { repository.updateGestures(transform) }
    fun updateWriting(transform: (WritingPreferences) -> WritingPreferences) = launch { repository.updateWriting(transform) }
    fun updateAppearance(transform: (AppearancePreferences) -> AppearancePreferences) = launch { repository.updateAppearance(transform) }
    fun updateStorage(transform: (StoragePreferences) -> StoragePreferences) = launch { repository.updateStorage(transform) }

    fun setLanguage(language: AppLanguage) {
        locale.set(language)
        _language.value = language
    }

    fun refreshStorage() = launch { _storage.update { it.copy(usage = storageInspector.usage()) } }

    fun clearThumbnails() = launch {
        _storage.update { it.copy(clearing = true) }
        storageInspector.clearThumbnails()
        _storage.value = StorageUiState(usage = storageInspector.usage())
    }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
