package app.bestnote.feature.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.bestnote.core.model.Notebook
import app.bestnote.core.storage.NotebookStore
import app.bestnote.engine.pdf.PdfImportException
import app.bestnote.engine.pdf.PdfImporter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Why a PDF could not be imported. */
class PdfImportFailed(cause: Throwable) : Exception(cause.message, cause)

/** Creates a notebook from the PDF at [uri] (a content:// or file:// URI string). */
fun interface PdfImporting {
    /** @throws PdfImportFailed if the file cannot be read or is not a usable PDF. */
    suspend fun import(uri: String): Notebook
}

/** [PdfImporting] over the system content resolver and :engine:pdf (PDF-01). */
class PdfImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    store: NotebookStore,
    private val mirror: RoomIndexMirror,
) : PdfImporting {
    private val importer = PdfImporter(store)

    override suspend fun import(uri: String): Notebook {
        val parsed = Uri.parse(uri)
        val notebook = try {
            val title = withContext(Dispatchers.IO) { displayName(parsed) }
            val input = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(parsed) } ?: throw FileNotFoundException(uri)
            importer.import(input, title, File(context.cacheDir, "pdf-import"))
        } catch (e: PdfImportException) {
            throw PdfImportFailed(e)
        } catch (e: IOException) {
            throw PdfImportFailed(e)
        } catch (e: SecurityException) {
            throw PdfImportFailed(e)
        }
        mirror.upsert(notebook)
        return notebook
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }
}

/**
 * App-wide "import this PDF" requests: an "Open with BestNote" / share intent calls [request]; the
 * library screen performs it, like [QuicknoteRequests].
 */
@Singleton
class PdfOpenRequests @Inject constructor() {
    private val pending = MutableStateFlow<String?>(null)
    val requested: StateFlow<String?> = pending

    fun request(uri: String) {
        pending.value = uri
    }

    /** @return the pending PDF URI (now taken), or null. */
    fun take(): String? {
        while (true) {
            val current = pending.value ?: return null
            if (pending.compareAndSet(current, null)) return current
        }
    }
}
