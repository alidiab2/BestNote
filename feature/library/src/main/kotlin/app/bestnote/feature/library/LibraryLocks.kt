package app.bestnote.feature.library

import app.bestnote.core.model.NotebookId
import app.bestnote.core.security.LockManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Which notebooks are locked (LIB-11), as the library needs it: [sealed] are encrypted on disk, [unlocked] are
 * open for this session. A notebook is drawn locked when it is sealed and not unlocked.
 */
interface LibraryLocks {
    val sealed: StateFlow<Set<NotebookId>>
    val unlocked: StateFlow<Set<NotebookId>>

    /** Re-reads [sealed] from the store. */
    suspend fun refresh()

    /** No locking (tests, previews). */
    object None : LibraryLocks {
        override val sealed: StateFlow<Set<NotebookId>> = MutableStateFlow(emptySet())
        override val unlocked: StateFlow<Set<NotebookId>> = MutableStateFlow(emptySet())
        override suspend fun refresh() = Unit
    }
}

class ManagerLibraryLocks @Inject constructor(private val manager: LockManager) : LibraryLocks {
    override val sealed: StateFlow<Set<NotebookId>> get() = manager.sealed
    override val unlocked: StateFlow<Set<NotebookId>> get() = manager.unlocked

    override suspend fun refresh() {
        manager.refresh()
    }
}
