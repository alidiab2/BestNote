package app.bestnote.engine.pdf

import app.bestnote.core.model.NotebookId
import app.bestnote.core.storage.NotebookPackage
import java.io.File

/** Opens the PDFs of a notebook for rendering (PDF-01). */
object PdfNotebookFiles {
    /**
     * The renderer-ready backgrounds for [pkg], or null if no page has a PDF. PdfRenderer needs a
     * file, so each PDF asset is copied once to [cacheDir] (reused while its size matches).
     * Does file I/O: call off the main thread. A PDF that cannot be opened leaves its pages on white paper.
     */
    fun open(pkg: NotebookPackage, cacheDir: File): PdfPageBackgrounds? {
        val assets = pkg.pages.mapNotNull { it.page.pdf?.asset }.distinct().filter { it in pkg.assets }
        if (assets.isEmpty()) return null
        val dir = File(cacheDir, dirName(pkg.notebook.id)).apply { mkdirs() }
        val sources = HashMap<String, PdfDocumentSource>()
        for (asset in assets) {
            val bytes = pkg.assets.getValue(asset)
            val file = File(dir, asset.substringAfterLast('/'))
            try {
                if (!file.isFile || file.length() != bytes.size.toLong()) {
                    val tmp = File(dir, file.name + ".tmp")
                    tmp.writeBytes(bytes)
                    check(tmp.renameTo(file)) { "Cannot move $tmp" }
                }
                sources[asset] = PdfRendererDocumentSource.open(file)
            } catch (_: Exception) {
                // IOException, SecurityException (password) or a damaged file: skip this PDF.
            }
        }
        return if (sources.isEmpty()) null else PdfPageBackgrounds(sources)
    }

    internal fun dirName(id: NotebookId): String =
        id.value.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").take(48) +
            "-" + Integer.toHexString(id.value.hashCode())
}
