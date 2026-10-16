package app.bestnote.engine.export

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** Minimal in-test folder provider over `cacheDir/saf-root`; document ids are paths relative to it ("" is the root). */
class TestDocumentsProvider : DocumentsProvider() {
    private val base: File get() = File(context!!.cacheDir, "saf-root").also { it.mkdirs() }

    private fun file(id: String) = if (id == ROOT) base else File(base, id.removePrefix("$ROOT/"))
    private fun idOf(f: File) = if (f == base) ROOT else "$ROOT/" + f.relativeTo(base).invariantSeparatorsPath

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID)).apply {
        newRow().add(Root.COLUMN_ROOT_ID, "root").add(Root.COLUMN_DOCUMENT_ID, ROOT).add(Root.COLUMN_TITLE, "Test")
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE)
    }

    private fun MatrixCursor.addFile(f: File) {
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idOf(f))
            .add(Document.COLUMN_DISPLAY_NAME, if (f == base) "Test" else f.name)
            .add(Document.COLUMN_MIME_TYPE, if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
            .add(Document.COLUMN_SIZE, f.length())
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_DOC).apply { addFile(file(documentId).also { if (!it.exists()) throw FileNotFoundException(documentId) }) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DEFAULT_DOC).apply { file(parentDocumentId).listFiles().orEmpty().sortedBy { it.name }.forEach { addFile(it) } }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val f = File(file(parentDocumentId), displayName)
        if (mimeType == Document.MIME_TYPE_DIR) f.mkdirs() else f.createNewFile()
        return idOf(f)
    }

    override fun deleteDocument(documentId: String) {
        file(documentId).deleteRecursively()
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    private companion object {
        const val ROOT = "root"
        val DEFAULT_DOC = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
