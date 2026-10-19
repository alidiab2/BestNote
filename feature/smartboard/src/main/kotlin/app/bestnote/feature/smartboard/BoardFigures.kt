package app.bestnote.feature.smartboard

import android.graphics.Bitmap
import app.bestnote.ai.scan.Quad
import app.bestnote.ai.scan.Raster
import app.bestnote.ai.scan.figure.BoardPixels
import app.bestnote.ai.scan.figure.FigureRedraw
import app.bestnote.ai.vision.Canary
import app.bestnote.core.storage.ImageAssets
import app.bestnote.engine.canvas.document.AssetImageCache
import app.bestnote.engine.canvas.document.ElementImages
import app.bestnote.engine.layout.FigureRaster
import app.bestnote.engine.layout.NoteDocument
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * AI-12 on Android: the board drawings of a reading, redrawn from the flattened board the AI saw
 * ([FigureRedraw], pure JVM). A drawing that could not be redrawn is kept as a cleaned PNG: its bytes
 * wait in [assets] (path = the notebook asset path) until the pages are inserted, and [images] shows
 * them in the review preview.
 */
internal class BoardFigures {
    val assets = ConcurrentHashMap<String, ByteArray>()

    /**
     * Redraws the new figures of [doc] from [board], the bitmap sent (before the canary strip). With the
     * [photo] it was flattened from (REQ-001), each drawing's pixels come from the photo's full resolution.
     */
    fun resolve(doc: NoteDocument, board: Bitmap, photo: BoardPhoto? = null, enhance: Boolean = true): NoteDocument {
        if (doc.flatten().none { it.figure != null }) return doc
        val strip = Canary.stripHeight(board.width, board.height)
        val yScale = (board.height + strip).toFloat() / board.height
        val raster = BoardScan.raster(board)
        val original = photo?.original ?: return resolve(doc, raster, yScale, null)
        // The same outline the board was flattened with (BoardScan.flatten).
        val quad = photo.quad.takeIf { it.isConvex } ?: Quad.full(0f)
        return PhotoRegions.withPixels(original, quad, enhance, raster) { pixels -> resolve(doc, raster, yScale, pixels) }
    }

    private fun resolve(doc: NoteDocument, raster: Raster, yScale: Float, pixels: BoardPixels?): NoteDocument =
        FigureRedraw.resolve(doc, raster, yScale, pixels = pixels, store = { cleaned ->
            val png = ByteArrayOutputStream().use { out ->
                BoardScan.bitmap(cleaned).compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                out.toByteArray()
            }
            val path = ImageAssets.pathFor(png, "png")
            assets[path] = png
            FigureRaster(path, cleaned.width, cleaned.height)
        })

    /** The pictures for the review preview's [app.bestnote.core.model.ImageElement]s. */
    val images: ElementImages = AssetImageCache({ assets[it] })

    private companion object {
        const val PNG_QUALITY = 100
    }
}
