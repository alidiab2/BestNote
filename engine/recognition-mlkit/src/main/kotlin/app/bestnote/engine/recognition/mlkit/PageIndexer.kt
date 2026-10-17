package app.bestnote.engine.recognition.mlkit

import android.content.Context
import android.util.Log
import app.bestnote.core.common.SearchText
import app.bestnote.core.database.SearchDao
import app.bestnote.core.database.SearchNotebookEntity
import app.bestnote.core.database.SearchPageEntity
import app.bestnote.core.database.SearchSegmentEntity
import app.bestnote.core.model.Notebook
import app.bestnote.core.storage.InkChunkCodec
import app.bestnote.core.storage.NotebookLockedException
import app.bestnote.core.storage.NotebookStore
import app.bestnote.core.storage.PageContent
import app.bestnote.core.model.PageRect
import app.bestnote.core.model.SegmentKind
import app.bestnote.engine.export.PageThumbnails
import app.bestnote.engine.pdf.PdfTextExtractor
import app.bestnote.engine.recognition.PageTextExtractor
import app.bestnote.engine.recognition.SegmentDraft
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the search index current (REC-02). Incremental twice over: a notebook is only looked at when it was
 * saved since the last run, and inside it only pages whose ink or text changed are read again. Typed text is
 * indexed always; handwriting only once the models are on the device (those pages are marked and revisited).
 * Every page is committed on its own, so being stopped halfway loses nothing.
 */
@Singleton
class PageIndexer @Inject constructor(
    private val store: NotebookStore,
    private val dao: SearchDao,
    private val models: MlKitModels,
    private val recognizer: MlKitHandwritingRecognizer,
    @ApplicationContext private val context: Context,
) {
    private val thumbnailDir get() = File(context.filesDir, THUMBNAILS)

    /** Brings the index up to date. @return how many pages were (re)read. */
    suspend fun run(): Int = withContext(Dispatchers.Default) {
        models.refresh()
        val ink = models.state.value.isReady
        val extractor = PageTextExtractor(recognizer, MlKitModels.LANGUAGES)
        val known = dao.notebooks().associateBy { it.notebookId }
        // Locked notebooks (LIB-11) are never indexed: the index and page pictures are plaintext on disk.
        val sealed = store.sealedIds()
        val notebooks = store.list().filter { it.id !in sealed }.sortedByDescending { it.updatedAtMillis }
        var pages = 0
        for (nb in notebooks) {
            currentCoroutineContext().ensureActive()
            val state = known[nb.id.value]
            if (state != null && state.indexedUpdatedAtMillis == nb.updatedAtMillis && (state.inkComplete || !ink)) continue
            pages += try {
                indexNotebook(nb, extractor, ink)
            } catch (@Suppress("SwallowedException") e: NotebookLockedException) {
                0 // locked while we were indexing it
            }
        }
        val present = notebooks.mapTo(HashSet()) { it.id.value }
        known.keys.filter { it !in present }.forEach { dao.removeNotebook(it) }
        for (id in sealed) thumbnailDir.listFiles { f -> f.name.startsWith(id.value + "-") }?.forEach { it.delete() }
        pages
    }

    private suspend fun indexNotebook(nb: Notebook, extractor: PageTextExtractor, ink: Boolean): Int {
        val pkg = store.read(nb.id) ?: return 0
        val before = dao.pages(nb.id.value).associateBy { it.pageId }
        var read = 0
        var inkComplete = ink
        val pdfs = PdfTexts(pkg.assets, File(context.cacheDir, "search-pdf"))
        for ((i, content) in pkg.pages.withIndex()) {
            currentCoroutineContext().ensureActive()
            val signature = signatureOf(content)
            val prev = before[content.id]
            if (prev != null && prev.signature == signature && (prev.inkIndexed || !ink)) {
                if (prev.pageIndex != i) dao.upsertPage(prev.copy(pageIndex = i))
                continue
            }
            try {
                if (!indexPage(nb, content, i, signature, extractor, ink, pdfs)) inkComplete = false
                read++
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(MlKitModels.TAG, "Indexing page ${content.id} of ${nb.id} failed", e)
                return read
            }
        }
        pdfs.close()
        dao.retainPages(nb.id.value, pkg.pages.mapTo(HashSet()) { it.id })
        dao.upsertNotebook(SearchNotebookEntity(nb.id.value, nb.updatedAtMillis, inkComplete = inkComplete))
        return read
    }

    private suspend fun indexPage(nb: Notebook, content: PageContent, index: Int, signature: String, extractor: PageTextExtractor, ink: Boolean, pdfs: PdfTexts): Boolean {
        val strokes = InkChunkCodec.decode(content.strokes)
        val failuresBefore = recognizer.failures
        val drafts = ArrayList<SegmentDraft>(extractor.elements(content.elements))
        content.page.pdf?.let { ref -> drafts += pdfs.drafts(ref.asset, ref.pageIndex) }
        if (ink && strokes.isNotEmpty()) drafts += extractor.ink(strokes.map { it.samples })
        val inkRead = ink && recognizer.failures == failuresBefore
        val thumbnail = if (drafts.isEmpty()) null else renderThumbnail(nb, content, strokes)
        val page = SearchPageEntity(
            notebookId = nb.id.value,
            pageId = content.id,
            pageIndex = index,
            signature = signature,
            inkIndexed = inkRead,
            indexedAtMillis = System.currentTimeMillis(),
            widthPt = content.page.widthPt,
            heightPt = content.page.heightPt,
            thumbnailPath = thumbnail?.path,
        )
        dao.replacePage(page, drafts.map { it.toEntity(nb.id.value, content.id) })
        return inkRead || !ink
    }

    private suspend fun renderThumbnail(nb: Notebook, content: PageContent, strokes: List<app.bestnote.core.model.InkStroke>): File? = try {
        val bytes = PageThumbnails.webp(content.page, strokes, content.elements, THUMB_WIDTH)
        File(thumbnailDir, "${nb.id.value}-${content.id}.webp").also { f ->
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Log.w(MlKitModels.TAG, "Page picture failed", e)
        null
    }

    /** Printed text of a notebook's imported PDFs; each PDF is opened once and only when a page of it is read. */
    private class PdfTexts(private val assets: Map<String, ByteArray>, private val dir: File) {
        private val readers = HashMap<String, PdfTextExtractor.Reader?>()

        fun drafts(asset: String, pageIndex: Int): List<SegmentDraft> {
            val reader = readers.getOrPut(asset) { open(asset) } ?: return emptyList()
            return reader.text(pageIndex).map { run ->
                SegmentDraft(SegmentKind.Pdf, run.text, SearchText.fold(run.text), PageRect(run.left, run.top, run.right, run.bottom))
            }.filter { it.folded.isNotEmpty() }
        }

        private fun open(asset: String): PdfTextExtractor.Reader? {
            if (!PdfTextExtractor.isSupported) return null
            val bytes = assets[asset] ?: return null
            val file = File(dir.apply { mkdirs() }, "${bytes.contentHashCode().toUInt().toString(16)}-${bytes.size}.pdf")
            if (!file.isFile || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
            return PdfTextExtractor.open(file)
        }

        fun close() = readers.values.forEach { it?.close() }
    }

    internal companion object {
        const val THUMBNAILS = "search-thumbs"
        const val THUMB_WIDTH = 360

        /** Changes whenever the page's ink or text boxes change. */
        fun signatureOf(content: PageContent): String {
            val sha = MessageDigest.getInstance("SHA-1")
            sha.update(content.strokes)
            sha.update(content.elements.joinToString("|") { it.toString() }.toByteArray())
            return sha.digest().joinToString("") { "%02x".format(it) }
        }

        fun SegmentDraft.toEntity(notebookId: String, pageId: String) = SearchSegmentEntity(
            notebookId = notebookId,
            pageId = pageId,
            kind = kind.name,
            text = text,
            folded = folded,
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
        )
    }
}
