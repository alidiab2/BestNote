package app.bestnote.engine.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.bestnote.core.database.NotebookRepository
import app.bestnote.core.storage.BackupException
import app.bestnote.core.storage.BackupProgress
import app.bestnote.core.storage.FileNotebookStore
import app.bestnote.core.storage.LibraryBackup
import app.bestnote.core.storage.LibraryMetadataMerge
import app.bestnote.core.storage.NotebookStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** How a backup or restore run ended. */
sealed interface BackupOutcome {
    data class BackedUp(val notebooks: Int, val trashed: Int, val written: Int) : BackupOutcome
    data class Restored(val restored: Int, val skippedNewerLocal: Int, val failed: List<String>) : BackupOutcome
    data object NoBackupFound : BackupOutcome
    data class Failed(val message: String) : BackupOutcome
}

sealed interface BackupJob {
    data object Idle : BackupJob
    data class Running(val restoring: Boolean, val done: Int, val total: Int) : BackupJob
    data class Finished(val outcome: BackupOutcome) : BackupJob
}

data class BackupState(
    /** Readable name of the chosen backup folder, null until one is chosen. */
    val folderName: String? = null,
    val folder: Uri? = null,
    val lastBackupMillis: Long? = null,
    val job: BackupJob = BackupJob.Idle,
)

/**
 * Full backup and restore of the library to a folder the user picks (DOC-08). Runs on its own scope,
 * off the main thread, so closing the dialog does not stop a backup; [cancel] does. Library-side
 * state (folders, covers, favorites in `library/metadata.json`) travels with the notebooks and is
 * merged back in on restore. After a restore the library screen re-reads its store when shown.
 */
@Singleton
class BackupController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: NotebookStore,
    private val repository: NotebookRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val trash = FileNotebookStore(File(context.filesDir, TRASH_DIR))
    private val backup = LibraryBackup(store, trash)
    private var running: Job? = null

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private fun readState(): BackupState {
        val folder = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)
        val last = prefs.getLong(KEY_LAST, 0L).takeIf { it > 0L }
        return BackupState(folder?.let(::displayName), folder, last)
    }

    /** The user picked [tree] for backups. Keeps access across restarts. */
    fun chooseFolder(tree: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        prefs.edit().putString(KEY_FOLDER, tree.toString()).apply()
        _state.update { it.copy(folder = tree, folderName = displayName(tree)) }
    }

    fun backUp() {
        val folder = _state.value.folder ?: return
        if (!begin(restoring = false)) return
        running = scope.launch {
            val outcome = try {
                val extras = readExtras()
                val result = backup.backup(SafBackupTarget(context, folder), extras, progress = progress(false))
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST, now).apply()
                _state.update { it.copy(lastBackupMillis = now) }
                BackupOutcome.BackedUp(result.notebooks, result.trashed, result.written)
            } catch (e: CancellationException) {
                _state.update { it.copy(job = BackupJob.Idle) }
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                BackupOutcome.Failed(e.message ?: e.javaClass.simpleName)
            }
            finish(outcome)
        }
    }

    /** Restores from the backup in [tree] (the folder itself or its `BestNote Backup` subfolder). */
    fun restoreFrom(tree: Uri) {
        if (!begin(restoring = true)) return
        running = scope.launch {
            val outcome = try {
                val target = SafBackupTarget.forRestore(context, tree)
                if (target == null) {
                    BackupOutcome.NoBackupFound
                } else {
                    val result = LibraryBackup(store, trash).restore(target, progress(true))
                    applyExtras(result.extras, result.restoredIds)
                    repository.reconcileIndex()
                    BackupOutcome.Restored(result.restored, result.skippedNewerLocal, result.failed)
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(job = BackupJob.Idle) }
                throw e
            } catch (e: BackupException) {
                BackupOutcome.Failed(e.message ?: "Not a BestNote backup")
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                BackupOutcome.Failed(e.message ?: e.javaClass.simpleName)
            }
            finish(outcome)
        }
    }

    fun cancel() {
        running?.cancel()
    }

    fun dismissResult() = _state.update { if (it.job is BackupJob.Finished) it.copy(job = BackupJob.Idle) else it }

    private fun begin(restoring: Boolean): Boolean {
        var started = false
        _state.update {
            if (it.job is BackupJob.Running) it else {
                started = true
                it.copy(job = BackupJob.Running(restoring, 0, 0))
            }
        }
        return started
    }

    private fun progress(restoring: Boolean) = BackupProgress { done, total ->
        _state.update { it.copy(job = BackupJob.Running(restoring, done, total)) }
    }

    private fun finish(outcome: BackupOutcome) = _state.update { it.copy(job = BackupJob.Finished(outcome)) }

    private fun readExtras(): Map<String, ByteArray> =
        EXTRAS.mapNotNull { name -> File(context.filesDir, name).takeIf { it.isFile }?.let { name to it.readBytes() } }.toMap()

    private fun applyExtras(extras: Map<String, ByteArray>, restoredIds: Set<String>) {
        for ((name, bytes) in extras) {
            val target = File(context.filesDir, name)
            val merged = if (name == METADATA) {
                LibraryMetadataMerge.merge(target.takeIf { it.isFile }?.readText(), bytes.decodeToString(), restoredIds).encodeToByteArray()
            } else {
                bytes
            }
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".tmp")
            tmp.writeBytes(merged)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Could not write $name")
            }
        }
    }

    private fun displayName(tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
        return id.substringAfter(':', id).ifEmpty { id.substringBefore(':') }
    }

    private companion object {
        const val PREFS = "backup"
        const val KEY_FOLDER = "folder"
        const val KEY_LAST = "last"
        const val TRASH_DIR = "trash"
        const val METADATA = "library/metadata.json"
        val EXTRAS = listOf(METADATA)
    }
}
