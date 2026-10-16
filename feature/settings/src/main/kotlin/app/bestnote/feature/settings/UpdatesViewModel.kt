package app.bestnote.feature.settings

import androidx.lifecycle.ViewModel
import app.bestnote.core.updater.BuildInfo
import app.bestnote.core.updater.UpdateManager
import app.bestnote.core.updater.UpdateState
import app.bestnote.core.updater.UpdateStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Settings → About → Updates (REL-01). Thin wrapper over the app-wide [UpdateManager]. */
@HiltViewModel
class UpdatesViewModel @Inject constructor(private val manager: UpdateManager) : ViewModel() {
    val build: BuildInfo = manager.build
    val status: StateFlow<UpdateStatus> = manager.status
    val settings: StateFlow<UpdateState> = manager.settings

    /** The row's action: install when a newer build is already known, otherwise look for one. */
    fun checkOrUpdate() {
        val s = status.value
        if (s is UpdateStatus.Available) manager.update(s.release) else manager.checkNow()
    }

    fun setAuto(enabled: Boolean) = manager.setAutoCheck(enabled)
}
