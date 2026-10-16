package app.bestnote.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bestnote.core.model.NotebookId
import app.bestnote.core.security.LockDialogs
import app.bestnote.core.security.rememberLockController
import kotlinx.coroutines.launch

/** LIB-11 in the editor's More menu: whether the notebook is locked, and the lock actions. */
internal class EditorLock(
    val sealed: Boolean,
    val onLock: () -> Unit,
    val onLockNow: () -> Unit,
    val onRemoveLock: () -> Unit,
    /** Runs [then] (the export dialog) directly, or after warning that a locked notebook exports unencrypted. */
    val beforeExport: (then: () -> Unit) -> Unit,
)

/**
 * Lock support for the open notebook [id]: saves before the session relocks it, locks it in place (it stays open,
 * now encrypted on disk), "Lock now" (saves, drops the key, goes back) and "Remove lock". Draws the lock
 * dialogs. The lock screen itself and FLAG_SECURE come from `LockGate` around the editor.
 */
@Composable
internal fun rememberEditorLock(id: NotebookId, session: DocumentSession, onBack: () -> Unit): EditorLock {
    val controller = rememberLockController()
    val manager = controller.manager
    val scope = rememberCoroutineScope()
    val sealedIds by manager.sealed.collectAsStateWithLifecycle()
    DisposableEffect(id, session) {
        val unregister = manager.registerOpen(id) { session.flush() }
        onDispose { unregister() }
    }
    LockDialogs(controller)
    val sealed = id in sealedIds
    return EditorLock(
        sealed = sealed,
        onLock = {
            scope.launch {
                session.flush() // the latest strokes are sealed with the rest
                controller.lock(listOf(id), keepUnlocked = true)
            }
        },
        // Back to the library right away, so the lock screen does not offer to unlock what was just locked.
        onLockNow = {
            scope.launch {
                manager.relock(listOf(id))
                onBack()
            }
        },
        onRemoveLock = { scope.launch { controller.removeLock(listOf(id)) } },
        beforeExport = { then ->
            if (!sealed) then() else scope.launch { if (controller.confirmPlainExport()) then() }
        },
    )
}
