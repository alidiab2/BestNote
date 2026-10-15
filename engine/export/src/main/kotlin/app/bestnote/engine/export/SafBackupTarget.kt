package app.bestnote.engine.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.bestnote.core.storage.BackupTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [BackupTarget] on a folder the user picked with the Storage Access Framework
 * (`ACTION_OPEN_DOCUMENT_TREE`), so backups can go to a memory card, USB drive or a cloud provider's
 * folder without any storage permission. The backup lives in [subfolder] of [tree], created on first write.
 */
class SafBackupTarget(
    private val context: Context,
    private val tree: Uri,
    private val subfolder: String? = DEFAULT_SUBFOLDER,
) : BackupTarget {
    private val resolver: ContentResolver get() = context.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(tree)

    /** Document ids of directories already looked up, keyed by relative path ("" = backup root). */
    private val dirs = HashMap<String, String>()

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    private fun childrenUri(docId: String): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)

    private class Child(val docId: String, val name: String, val isDir: Boolean)

    private fun children(docId: String): List<Child> {
        val out = ArrayList<Child>()
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        try {
            resolver.query(childrenUri(docId), projection, null, null, null)?.use { c ->
                while (c.moveToNext()) out += Child(c.getString(0), c.getString(1), c.getString(2) == Document.MIME_TYPE_DIR)
            }
        } catch (e: IllegalArgumentException) {
            throw IOException("Cannot list the backup folder", e)
        }
        return out
    }

    /** The document id of directory [path] ("" = backup root), creating it when [create]. Null if absent. */
    private fun dirId(path: String, create: Boolean): String? {
        dirs[path]?.let { return it }
        var current = rootDocId
        var walked = ""
        val parts = listOfNotNull(subfolder?.takeIf { it.isNotEmpty() }) + path.split('/').filter { it.isNotEmpty() }
        for (name in parts) {
            walked = if (walked.isEmpty()) name else "$walked/$name"
            val cached = dirs["#$walked"]
            current = cached ?: (children(current).firstOrNull { it.name == name && it.isDir }?.docId
                ?: if (create) createDocument(current, Document.MIME_TYPE_DIR, name) else return null)
            dirs["#$walked"] = current
        }
        dirs[path] = current
        return current
    }

    private fun createDocument(parentDocId: String, mime: String, name: String): String {
        val uri = DocumentsContract.createDocument(resolver, docUri(parentDocId), mime, name)
            ?: throw IOException("Cannot create $name in the backup folder")
        return DocumentsContract.getDocumentId(uri)
    }

    private fun split(path: String): Pair<String, String> =
        path.substringBeforeLast('/', "") to path.substringAfterLast('/')

    private fun fileId(path: String): String? {
        val (dir, name) = split(path)
        val parent = dirId(dir, create = false) ?: return null
        return children(parent).firstOrNull { it.name == name && !it.isDir }?.docId
    }

    override suspend fun exists(path: String) = withContext(Dispatchers.IO) { fileId(path) != null }

    override suspend fun list(dir: String): List<String> = withContext(Dispatchers.IO) {
        val id = dirId(dir.trimEnd('/'), create = false) ?: return@withContext emptyList()
        children(id).filter { !it.isDir }.map { it.name }
    }

    override suspend fun <T> read(path: String, block: (InputStream) -> T): T? = withContext(Dispatchers.IO) {
        val id = fileId(path) ?: return@withContext null
        val stream = try {
            resolver.openInputStream(docUri(id))
        } catch (e: FileNotFoundException) {
            null
        } ?: return@withContext null
        stream.buffered().use(block)
    }

    override suspend fun write(path: String, block: (OutputStream) -> Unit) = withContext(Dispatchers.IO) {
        val (dir, name) = split(path)
        val parent = dirId(dir, create = true) ?: throw IOException("Cannot create $dir")
        val id = children(parent).firstOrNull { it.name == name && !it.isDir }?.docId ?: createDocument(parent, MIME_FILE, name)
        // "wt" truncates: an overwritten file never keeps a stale tail.
        val out = resolver.openOutputStream(docUri(id), "wt") ?: throw IOException("Cannot write $path")
        out.buffered().use(block)
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            val id = fileId(path) ?: return@withContext
            try {
                DocumentsContract.deleteDocument(resolver, docUri(id))
            } catch (e: FileNotFoundException) {
                // already gone
            }
        }
    }

    /** Whether [subfolder] (or the picked folder itself, when null) holds a `backup.json`. */
    suspend fun hasBackup(): Boolean = exists(app.bestnote.core.storage.LibraryBackup.MANIFEST)

    companion object {
        const val DEFAULT_SUBFOLDER = "BestNote Backup"
        private const val MIME_FILE = "application/octet-stream"

        /**
         * The target to restore from when the user picked [tree]: the folder itself if it holds a
         * backup, otherwise its [DEFAULT_SUBFOLDER]. Null when neither does.
         */
        suspend fun forRestore(context: Context, tree: Uri): SafBackupTarget? =
            listOf(SafBackupTarget(context, tree, null), SafBackupTarget(context, tree, DEFAULT_SUBFOLDER)).firstOrNull { it.hasBackup() }
    }
}
